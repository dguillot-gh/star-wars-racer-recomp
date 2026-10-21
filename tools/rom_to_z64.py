#!/usr/bin/env python3
"""
rom_to_z64.py - copy an N64 ROM to .z64 (big-endian) byte order.

    python tools/rom_to_z64.py <input .z64/.v64/.n64> [output, default baserom.z64]

.v64 dumps swap every 2 bytes, .n64 dumps reverse every 4; the first word tells which. The input is only read.
Prints the SHA-1 of the result (the supported USA dump is 3542d5597c8a56ea8f5c63bceae97a24c4c08d58).
"""
import hashlib
import sys

MAGIC_Z64 = bytes.fromhex("80371240")
MAGIC_V64 = bytes.fromhex("37804012")
MAGIC_N64 = bytes.fromhex("40123780")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__.strip())
        return 2
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else "baserom.z64"
    data = bytearray(open(src, "rb").read())
    head = bytes(data[:4])
    if head == MAGIC_Z64:
        kind = ".z64 (already big-endian)"
    elif head == MAGIC_V64:
        kind = ".v64 (byte-swapped)"
        data[0::2], data[1::2] = data[1::2], data[0::2]
    elif head == MAGIC_N64:
        kind = ".n64 (little-endian)"
        for i in range(0, len(data) - 3, 4):
            data[i:i + 4] = data[i:i + 4][::-1]
    else:
        print(f"{src}: not an N64 ROM (first bytes {head.hex()})")
        return 1
    open(dst, "wb").write(data)
    print(f"{src}: {kind} -> {dst}")
    print(f"SHA-1 {hashlib.sha1(data).hexdigest()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
