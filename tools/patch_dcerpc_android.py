"""Port dcerpc 0.11.0's exception reference to Android without altering RPC logic.

Usage: python tools/patch_dcerpc_android.py original.jar android.jar
Only UTF-8 constant-pool entries naming java/rmi/UnmarshalException are changed.
Legacy SMBJ 0.10 helpers and their two factory/transport callers are excluded;
the application supplies SmbRpcTransport using SMBJ 0.13's public API.
The replacement exception supplies both original constructor signatures.
"""
import struct
import sys
import zipfile

OLD = b"java/rmi/UnmarshalException"
NEW = b"com/rapid7/client/dcerpc/AndroidUnmarshalException"

def patch(data):
    count = struct.unpack_from('>H', data, 8)[0]
    result = bytearray(data[:10])
    pos, index, changed = 10, 1, False
    sizes = {3:4, 4:4, 5:8, 6:8, 7:2, 8:2, 9:4, 10:4, 11:4,
             12:4, 15:3, 16:2, 17:4, 18:4, 19:2, 20:2}
    while index < count:
        tag = data[pos]
        pos += 1
        result.append(tag)
        if tag == 1:
            size = struct.unpack_from('>H', data, pos)[0]
            value = data[pos+2:pos+2+size]
            pos += 2 + size
            if value == OLD:
                value, changed = NEW, True
            result += struct.pack('>H', len(value)) + value
        else:
            size = sizes[tag]
            result += data[pos:pos+size]
            pos += size
            if tag in (5, 6):
                index += 1
        index += 1
    result += data[pos:]
    return bytes(result), changed

if __name__ == '__main__':
    changes = 0
    with zipfile.ZipFile(sys.argv[1]) as source, zipfile.ZipFile(sys.argv[2], 'w') as target:
        for entry in source.infolist():
            if entry.filename.startswith('com/rapid7/helper/') or entry.filename in (
                    'com/rapid7/client/dcerpc/transport/SMBTransport.class',
                    'com/rapid7/client/dcerpc/transport/SMBTransportFactories.class'):
                continue
            data = source.read(entry.filename)
            if entry.filename.endswith('.class'):
                data, changed = patch(data)
                changes += changed
            target.writestr(entry, data)
    if changes != 28:
        raise RuntimeError(f'Unexpected dcerpc version: changed {changes} classes, expected 28')
    print(f'Ported {changes} exception references to Android')
