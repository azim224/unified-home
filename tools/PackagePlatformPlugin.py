#!/usr/bin/env python3
"""Package a precompiled single DEX and sign its inventory using host-independent OpenSSL."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dex', required=True, type=Path)
    parser.add_argument('--package-id', required=True)
    parser.add_argument('--publisher', required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--entrypoint', required=True)
    parser.add_argument('--key-id', required=True)
    parser.add_argument('--offers', required=True, type=Path, help='JSON array of exact provider/contract offers')
    parser.add_argument('--capabilities', type=Path, help='JSON array of signed capability tokens')
    parser.add_argument('--abi', default='amalia-sdk-0.2-kotlin-2.2.10-coroutines-1.10.2-json-1.9.0-dex1')
    parser.add_argument('--private-key', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    for value in (args.package_id, args.publisher):
        if len(value) > 200 or not re.fullmatch(r'[a-z0-9]+(?:[.-][a-z0-9]+)+', value):
            parser.error('Invalid package or publisher ID')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+\-]{0,127}', args.version):
        parser.error('Invalid version')
    if not re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+', args.entrypoint):
        parser.error('Invalid Java entrypoint')
    if not re.fullmatch(r'[A-Za-z0-9._-]{1,128}', args.key_id):
        parser.error('Invalid key ID')
    if args.output.exists():
        parser.error('Output already exists')
    if not 112 <= args.dex.stat().st_size <= 32 * 1024 * 1024:
        parser.error('DEX must be between 112 bytes and 32 MiB')
    dex = args.dex.read_bytes()
    if dex[:8] not in (b'dex\n035\0', b'dex\n037\0'):
        parser.error('Expected standard DEX 035 or 037')
    if args.offers.stat().st_size > 64 * 1024:
        parser.error('Offers metadata too large')
    offers = json.loads(args.offers.read_text())
    if not isinstance(offers, list) or not 0 <= len(offers) <= 128:
        parser.error('Offers must be a bounded JSON array')
    capabilities = []
    if args.capabilities:
        if args.capabilities.stat().st_size > 32 * 1024:
            parser.error('Capabilities metadata too large')
        capabilities = json.loads(args.capabilities.read_text())
        simple = {'background.media', 'background.short', 'notifications', 'scheduler', 'secrets',
                  'browser', 'device.read', 'device.volume', 'device.vibrate', 'ui', 'android.native'}
        if not isinstance(capabilities, list) or len(capabilities) > 128 or any(not isinstance(t, str) for t in capabilities):
            parser.error('Invalid capability tokens')
        if len(set(capabilities)) != len(capabilities):
            parser.error('Duplicate capability tokens')
        for token in capabilities:
            if token not in simple and not (token.startswith('network:') and re.fullmatch(
                r'(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?', token[8:]) and len(token) <= 199):
                parser.error('Unknown capability token')
    version_range = dict(minimum='1.0.0', maximumExclusive='2.0.0')
    manifest = json.dumps(dict(packageId=args.package_id, publisher=args.publisher,
        version=args.version, runtime='android-dex', entryPoint=args.entrypoint,
        compatibility=dict(protocol=version_range, runtimeVersion=version_range, abi=args.abi),
        offers=offers, capabilities=capabilities, sha256={'classes.dex': hashlib.sha256(dex).hexdigest()}),
        ensure_ascii=True, separators=(',', ':')).encode() + b'\n'
    with tempfile.TemporaryDirectory(prefix='amalia-package-', dir=args.output.parent) as directory:
        root = Path(directory)
        metadata = root / 'platform.json'
        metadata.write_bytes(manifest)
        signature = root / 'signature.der'
        # The verifier additionally requires a pinned P-256 key; OpenSSL errors propagate.
        subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(args.private_key.resolve()),
                        '-out', str(signature), str(metadata)], check=True, capture_output=True)
        envelope = json.dumps(dict(algorithm='SHA256withECDSA', keyId=args.key_id,
            signature=base64.b64encode(signature.read_bytes()).decode()), separators=(',', ':')).encode()
        staged = root / 'package.zip'
        with zipfile.ZipFile(staged, 'w', compression=zipfile.ZIP_STORED) as archive:
            for name, payload in [('platform.json', manifest), ('package.sig', envelope), ('classes.dex', dex)]:
                archive.writestr(name, payload)
        # Exclusive creation cannot overwrite an archive created after the initial preflight.
        with args.output.open('xb') as output, staged.open('rb') as source:
            import shutil
            shutil.copyfileobj(source, output)
    print(f'Created {args.output} (publisher pin and activation consent are separate host actions)')


if __name__ == '__main__':
    main()
