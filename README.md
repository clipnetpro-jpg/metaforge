# MetaForge

Fully native Android app (Kotlin + Jetpack Compose). No WebView, no Capacitor, no hybrid layer.

## What it does
- Read, edit, and delete metadata of images and videos (EXIF, IPTC, XMP, ICC, maker notes, QuickTime)
- Transplant metadata from one file to another, automatically or field-by-field
- Detect AI-generated images with a layered engine (provenance, watermark, ML, forensics)

## Engine
MetaForge does not reimplement ExifTool. It ships the real thing:
Perl is cross-compiled for Android with perl-cross and bundled as `libperl.so` in jniLibs,
so it lands in `nativeLibraryDir` and stays executable on Android 10+.
ExifTool runs as a persistent `-stay_open` daemon for ~30 ms per command.

## Build
Everything builds in GitHub Actions. No Android Studio required.

## License
GPLv3 (required for bundling ExifTool and Perl).
