#!/usr/bin/env python3
"""Overwrite PT_INTERP on an ELF64 binary. New path must fit in the existing slot."""

from __future__ import annotations

import struct
import sys


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: patch_elf_interp.py <elf> <interpreter>", file=sys.stderr)
        return 2
    path, interp = sys.argv[1], sys.argv[2]
    data = bytearray(open(path, "rb").read())
    if data[:4] != b"\x7fELF":
        print("not ELF", file=sys.stderr)
        return 1
    if data[4] != 2:
        print("not ELF64", file=sys.stderr)
        return 1
    little = data[5] == 1
    endian = "<" if little else ">"
    e_phoff = struct.unpack_from(endian + "Q", data, 32)[0]
    e_phentsize = struct.unpack_from(endian + "H", data, 54)[0]
    e_phnum = struct.unpack_from(endian + "H", data, 56)[0]
    wanted = interp.encode("ascii") + b"\x00"
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from(endian + "I", data, off)[0]
        if p_type != 3:  # PT_INTERP
            continue
        p_offset = struct.unpack_from(endian + "Q", data, off + 8)[0]
        p_filesz = struct.unpack_from(endian + "Q", data, off + 32)[0]
        if len(wanted) > p_filesz:
            print(
                f"interpreter too long ({len(wanted)} > {p_filesz})",
                file=sys.stderr,
            )
            return 1
        data[p_offset : p_offset + p_filesz] = wanted.ljust(p_filesz, b"\x00")
        open(path, "wb").write(data)
        return 0
    print("no PT_INTERP", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
