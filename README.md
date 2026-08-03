# MetaForge

Fully native Android app (Kotlin + Jetpack Compose). No WebView, no Capacitor, no hybrid layer.

## What it does
- **Inspect and edit** every tag in an image or video: EXIF, IPTC, XMP, ICC, maker notes, QuickTime
- **Transplant** the whole metadata identity of one file onto another, then verify tag by tag that nothing was lost
- **Remove everything** before sharing, including the C2PA blocks ExifTool cannot delete, and report what actually survived
- **Detect AI images**: provenance first (C2PA, generator tags, prompt data), pixel forensics second, and it always says which of the two the verdict rests on

## Engine
MetaForge does not reimplement ExifTool. It ships the real thing:
Perl is cross-compiled for Android with perl-cross and bundled as `libperl.so` in jniLibs,
so it lands in `nativeLibraryDir` and stays executable on Android 10+.
ExifTool runs as a persistent `-stay_open` daemon for ~30 ms per command.

A cross build links every XS extension into the interpreter but never copies the `.pm`
half of it anywhere, so `ci/harvest-perl-modules.py` lays POSIX, Fcntl, IO, Encode and
the rest out at the paths `@INC` expects. Without that step ExifTool cannot load a
single module, and the failure looks like a version string, which is how it went
unnoticed for a while.

## Build
Everything builds in GitHub Actions. No Android Studio, no local Gradle.

1. Actions tab, **02 - Build APK**, Run workflow.
2. Wait about ten minutes.
3. Install `MetaForge-<run>.apk` from the Release, or from the run's artifacts.

The universal APK works on any phone. The per-ABI files are smaller if you know
your CPU; almost every modern phone is `arm64-v8a`.

Release APKs are signed with the key held in the `MF_KEYSTORE_B64`,
`MF_KEYSTORE_PASSWORD` and `MF_KEY_ALIAS` repository secrets, so updates install
over the top of an existing copy.

## Verification
`ci/emulator-tests.sh` runs on a real Android emulator in CI. Before the
instrumentation suite it pushes the interpreter and the Perl tree to the device
and runs ExifTool directly, so a broken engine reports the actual Perl error
rather than a silent failure.

## License
GPLv3 (required for bundling ExifTool and Perl).
