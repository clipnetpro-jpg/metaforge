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

# One tarball, not ten thousand adb pushes: the Perl tree is 30 MB of tiny
# files and pushing it file by file takes longer than the tests do.
( cd /tmp/mfprobe && tar -czf /tmp/mfprobe.tgz perl perl5 exiftool )
adb shell "rm -rf $PROBE; mkdir -p $PROBE"
timeout 300 adb push /tmp/mfprobe.tgz "$PROBE/probe.tgz" >/dev/null 2>&1
timeout 300 adb shell "cd $PROBE && tar -xzf probe.tgz && rm probe.tgz && chmod 0755 perl"

echo "--- interpreter ---"
timeout 120 adb shell "$PROBE/perl -e 'print qq{PERL \$] on \$^O\n}'" 2>&1 | head -20
echo "--- core modules ---"
timeout 120 adb shell "$PROBE/perl -I$PROBE/perl5 -MPOSIX -MFcntl -MIO::File -MEncode -e 'print qq{MODULES OK\n}'" 2>&1 | head -30
echo "--- exiftool one-shot ---"
timeout 180 adb shell "$PROBE/perl -I$PROBE/perl5 -I$PROBE/exiftool/lib $PROBE/exiftool/exiftool -ver" 2>&1 | head -40
echo "--- exiftool stay_open ---"
timeout 180 adb shell "printf '%s\n' -ver -execute1 -stay_open False -execute2 | $PROBE/perl -I$PROBE/perl5 -I$PROBE/exiftool/lib $PROBE/exiftool/exiftool -stay_open True -@ -" 2>&1 | head -20
echo "::endgroup::"

adb logcat -c >/dev/null 2>&1

# Never let a wedged device hold the whole workflow open.
timeout 1500 ./gradlew --no-daemon connectedDebugAndroidTest
rc=$?
[ "$rc" = "124" ] && echo "::error::instrumentation timed out after 25 minutes"

echo "::group::instrumentation results"
# -print0: the report file is named "TEST-emulator-5554 - 11.xml". Word
# splitting on that turned the filename into "-", so sed sat waiting on stdin
# and the whole workflow hung until it was cancelled.
find app/build/outputs/androidTest-results -name '*.xml' -print0 2>/dev/null |
  while IFS= read -r -d '' f; do
    echo "----- $f -----"
    sed -e 's/&#10;/\n/g' "$f" | head -c 30000
  done
echo "::endgroup::"

echo "::group::logcat"
adb logcat -d -v brief PerlRuntime:V ExifTool:V InpaintEngine:V AndroidRuntime:E System.err:V '*:S' 2>&1 | tail -n 200
echo "::endgroup::"

exit $rc
