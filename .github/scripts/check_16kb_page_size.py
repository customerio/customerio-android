#!/usr/bin/env python3
"""Checks that every native library in an APK or AAB loads on 16 KB page-size devices.

The SDK ships no `.so` of its own, but host apps get some transitively (for example
`androidx.graphics:graphics-path` through Compose). A library loads on a 16 KB device only when
every ELF `PT_LOAD` segment is aligned to at least 16 KB (`p_align >= 0x4000`). Zip alignment of
uncompressed libraries is a separate requirement, checked with `zipalign -c -P 16`.

Only 64-bit ABIs are enforced: 16 KB pages exist only on arm64-v8a and x86_64 devices, and 32-bit
libraries are still commonly built for 4 KB pages. Their results are printed for information.

Usage: check_16kb_page_size.py <artifact.apk|artifact.aab>...
Exit: 0 every 64-bit library is aligned; 1 a library is misaligned or unreadable; 2 bad arguments.
"""
import pathlib
import struct
import sys
import zipfile

PAGE_16_KB = 0x4000
PT_LOAD = 1
ENFORCED_ABIS = {"arm64-v8a", "x86_64"}


def load_alignments(data):
    """Returns the p_align of every PT_LOAD segment, or raises ValueError for a malformed file."""
    if len(data) < 6 or data[:4] != b"ELF":
        raise ValueError("not an ELF file")
    elf_class, encoding = data[4], data[5]
    if elf_class not in (1, 2) or encoding not in (1, 2):
        raise ValueError(f"unsupported ELF class {elf_class} / data encoding {encoding}")
    order = "<" if encoding == 1 else ">"
    if elf_class == 2:  # ELF64
        header_size, entry_size, word = 64, 56, "Q"
        phoff_at, phentsize_at, align_at = 0x20, 0x36, 0x30
    else:  # ELF32
        header_size, entry_size, word = 52, 32, "I"
        phoff_at, phentsize_at, align_at = 0x1C, 0x2A, 0x1C
    if len(data) < header_size:
        raise ValueError("truncated ELF header")
    phoff, = struct.unpack_from(order + word, data, phoff_at)
    phentsize, phnum = struct.unpack_from(order + "HH", data, phentsize_at)
    # The loader reads fixed-size entries; a different size or a table past the end of the file
    # would make this check read the wrong bytes rather than the segments the loader maps.
    if phentsize != entry_size:
        raise ValueError(f"unexpected program header size {phentsize}")
    if phoff + phnum * phentsize > len(data):
        raise ValueError("program header table extends past the end of the file")
    alignments = []
    for i in range(phnum):
        header = phoff + i * phentsize
        p_type, = struct.unpack_from(order + "I", data, header)
        if p_type == PT_LOAD:
            p_align, = struct.unpack_from(order + word, data, header + align_at)
            alignments.append(p_align)
    if not alignments:
        raise ValueError("no PT_LOAD segments")
    return alignments


def abi_of(entry_name, is_bundle):
    """The ABI directory of a native library entry, or None for anything else."""
    # APK: lib/<abi>/libx.so. AAB: <module>/lib/<abi>/libx.so.
    parts = entry_name.split("/")
    lib_at = 1 if is_bundle else 0
    if len(parts) == lib_at + 3 and parts[lib_at] == "lib" and parts[-1].endswith(".so"):
        return parts[lib_at + 1]
    return None


def check(artifact):
    failures = 0
    checked = 0
    is_bundle = artifact.endswith(".aab")
    with zipfile.ZipFile(artifact) as archive:
        for info in archive.infolist():
            abi = abi_of(info.filename, is_bundle)
            if abi is None:
                continue
            enforced = abi in ENFORCED_ABIS
            try:
                alignments = load_alignments(archive.read(info))
            except (ValueError, struct.error) as error:
                status, ok = f"UNREADABLE ({error})", False
            else:
                smallest = min(alignments)
                ok = smallest >= PAGE_16_KB
                status = f"{'ALIGNED' if ok else 'UNALIGNED'} (smallest LOAD p_align {smallest:#x})"
            checked += enforced
            if enforced and not ok:
                failures += 1
                print(f"::error file={artifact}::{info.filename}: {status}")
            else:
                note = "" if enforced else f" [{abi}: not enforced]"
                print(f"{artifact}: {info.filename}: {status}{note}")
    if checked == 0:
        print(f"::warning file={artifact}::No 64-bit native libraries found; nothing to check.")
    return failures


def main(paths):
    if not paths:
        print(__doc__.strip().splitlines()[-2], file=sys.stderr)
        return 2
    failures = 0
    for path in paths:
        if not pathlib.Path(path).is_file():
            print(f"::error::{path} does not exist.")
            return 2
        failures += check(path)
    if failures:
        print(f"::error::{failures} 64-bit native librar{'y' if failures == 1 else 'ies'} failed the 16 KB check.")
        return 1
    print("All 64-bit native libraries are 16 KB aligned.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
