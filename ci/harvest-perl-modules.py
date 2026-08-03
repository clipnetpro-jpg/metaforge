#!/usr/bin/env python3
"""Lay out the Perl modules a cross build leaves behind.

Cross-compiling perl links every extension into the interpreter but never
copies its .pm half into lib/: POSIX.pm, Fcntl.pm, IO.pm, Encode.pm and friends
stay in ext/, dist/ and cpan/. An APK built from lib/ alone therefore ships the
object code for those modules and none of the Perl that loads it, which is
exactly how ExifTool ended up unable to load a single module on device.

Run from the perl source tree; pass the destination @INC directory. Existing
files are never overwritten, so generated files already staged from lib/
(Config.pm, Errno.pm) win over their source-tree counterparts.
"""
import os
import shutil
import sys

WANTED = ('.pm', '.pl', '.al', '.ix')
SKIP_DIRS = {'t', 'blib', 'testdir'}


def main(dest: str) -> int:
    copied = 0

    def place(src: str, rel: str) -> None:
        nonlocal copied
        out = os.path.join(dest, rel)
        if os.path.exists(out):
            return
        os.makedirs(os.path.dirname(out) or dest, exist_ok=True)
        shutil.copy2(src, out)
        copied += 1

    def harvest_lib(libdir: str) -> None:
        for root, dirs, files in os.walk(libdir):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
            for f in files:
                if f.endswith(WANTED):
                    full = os.path.join(root, f)
                    place(full, os.path.relpath(full, libdir))

    for area in ('ext', 'dist', 'cpan'):
        if not os.path.isdir(area):
            continue
        for name in sorted(os.listdir(area)):
            moddir = os.path.join(area, name)
            if not os.path.isdir(moddir):
                continue
            libdir = os.path.join(moddir, 'lib')
            if os.path.isdir(libdir):
                harvest_lib(libdir)
            # Modules with no lib/ keep their .pm at the top level and encode
            # the namespace in the directory name: cpan/Digest-MD5/MD5.pm is
            # Digest/MD5.pm, while dist/PathTools/Cwd.pm is plain Cwd.pm.
            mod = name.replace('-', '/')
            for f in sorted(os.listdir(moddir)):
                if not f.endswith('.pm'):
                    continue
                rel = mod + '.pm' if f == os.path.basename(mod) + '.pm' else f
                place(os.path.join(moddir, f), rel)

    print('harvested %d module files into %s' % (copied, dest))
    return 0


if __name__ == '__main__':
    if len(sys.argv) != 2:
        print('usage: harvest-perl-modules.py <dest-inc-dir>', file=sys.stderr)
        raise SystemExit(2)
    raise SystemExit(main(sys.argv[1]))
