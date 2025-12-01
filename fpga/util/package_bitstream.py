#!/usr/bin/env python3

from pathlib import Path
import sys
import gzip
import subprocess
import struct

"""
FuseSoc post_build hook to package up the bitstream:

Inserts Git revision information to the header and
gzip-compresses it.
"""

def get_git_revision() -> str:
    try:
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True)
        revision = revision.strip()
    except:
        print("Failed to find Git revision")
        return ""
    
    return revision[:12]

def insert_metadata(bitstream: bytearray, extra: str):
    # Very crude way of not parsing the Xilinx header
    # Assumes the first tag is "a" (design name)
    HEADER = [0x00, 0x09, 0x0F, 0xF0, 0x0F, 0xF0, 0x0F, 0xF0, 0x0F, 0xF0, 0x00, 0x00, 0x01, 0x61]
    if bitstream[:len(HEADER)] != bytearray(HEADER):
        raise SystemExit("Unexpected bitstream header")

    # Find the length of the tag
    index = len(HEADER)
    length, = struct.unpack(">H", bitstream[index : (index + 2)])
    if length < 1:
        return
    index += 2
    # Extract the tag
    tag = bitstream[index : (index + length)]
    # Modify the tag
    tag = tag[:-1] + extra.encode("utf-8") + bytearray([0])
    bitstream[index : (index + length)] = tag
    # Modify the length
    length = struct.pack(">H", len(tag))
    index = len(HEADER)
    bitstream[index : (index + 2)] = length


# Find the bitstream
bitstream_paths = list(Path(".").glob("*.bit"))
if len(bitstream_paths) != 1:
    raise SystemExit(f"Expected 1 .bit file, found {len(bitstream_paths)}")

# Read the bitstream
bitstream_path = bitstream_paths[0]
print("Found bitstream at", bitstream_path)
bitstream = bytearray(open(bitstream_path, "rb").read())

# Determine Git revision
git_revision = get_git_revision()
if git_revision:
    print("Git revision:", git_revision)

# Insert Git information into bitstream
insert_metadata(bitstream, ";GitRev=" + git_revision)

# Bitstream output name
if len(sys.argv) <= 1:
    output_name = "bitstream"
else:
    output_name = sys.argv[1]
output_path = Path(output_name + ".bit.gz")

# Compress bitstream
compressed = gzip.compress(bitstream)
with open(output_path, "wb") as f:
    f.write(compressed)

print("*" * 80)
print("Bitstream output path:")
print(output_path.resolve())
print("*" * 80)
