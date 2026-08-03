#!/usr/bin/env bash
# On-device verification. Runs inside the emulator-runner.
#
# Everything here is best-effort and always prints: a failing engine must leave
# the real Perl error in the log, not just "daemon did not start".
set +e

PROBE=/data/local/tmp/mfprobe
ASSETS=app/src/main/assets

echo "::group::raw device probe (no app involved)"
rm -rf /tmp/mfprobe && mkdir -p /tmp/mfprobe
unzip -q  "$ASSETS/perl5.zip"        -d /tmp/mfprobe
unzip -qo "$ASSETS/perl5-x86_64.zip" -d /tmp/mfprobe
unzip -q  "$ASSETS/exiftool.zip"     -d /tmp/mfprobe
cp app/src/main/jniLibs/x86_64/libperl.so /tmp/mfprobe/perl

adb shell "rm -rf $PROBE; mkdir -p $PROBE"
( cd /tmp/mfprobe && adb push perl perl5 exiftool "$PROBE/" >/dev/null 2>&1 )
adb shell "chmod 0755 $PROBE/perl"

echo "--- interpreter ---"
adb shell "$PROBE/perl -e 'print qq{PERL \$] on \$^O\n}'" 2>&1 | head -20
echo "--- core modules ---"
adb shell "$PROBE/perl -I$PROBE/perl5 -MPOSIX -MFcntl -MIO::File -MEncode -e 'print qq{MODULES OK\n}'" 2>&1 | head -30
echo "--- exiftool one-shot ---"
adb shell "$PROBE/perl -I$PROBE/perl5 -I$PROBE/exiftool/lib $PROBE/exiftool/exiftool -ver" 2>&1 | head -40
echo "--- exiftool stay_open ---"
adb shell "printf '%s\n' -ver -execute1 -stay_open False -execute2 | $PROBE/perl -I$PROBE/perl5 -I$PROBE/exiftool/lib $PROBE/exiftool/exiftool -stay_open True -@ -" 2>&1 | head -20
echo "::endgroup::"

adb logcat -c >/dev/null 2>&1

gradle --no-daemon connectedDebugAndroidTest
rc=$?

echo "::group::instrumentation results"
for f in $(find app/build/outputs/androidTest-results -name '*.xml' 2>/dev/null); do
  echo "----- $f -----"
  sed -e 's/&#10;/\n/g' "$f" | head -c 30000
done
echo "::endgroup::"

echo "::group::logcat"
adb logcat -d -v brief PerlRuntime:V ExifTool:V InpaintEngine:V AndroidRuntime:E System.err:V '*:S' 2>&1 | tail -n 200
echo "::endgroup::"

exit $rc
