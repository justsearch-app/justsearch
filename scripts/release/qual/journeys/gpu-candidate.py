#!/usr/bin/env python3
"""Prepare an explicitly development-only .deb with loopback Linux GPU pins.

Never used by the default qualification command. Does not modify source, the
input .deb, model downloads, or an extracted candidate during a journey.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

from runner import HERE, Infrastructure, digest


def prepare(package, packs, output, port=8769):
    output.mkdir(parents=True, exist_ok=False)
    spec = importlib.util.spec_from_file_location('linux_gpu_registry', HERE.parents[2] / 'ai/update-linux-gpu-registry.py')
    updater = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(updater)
    names = updater.FILENAMES
    paths = [packs / name for name in names]
    if not all(p.is_file() and not p.is_symlink() for p in paths):
        raise Infrastructure('Provide both hardlinked real Linux packs with their canonical filenames')
    checksums = ''.join(digest(p) + '  ' + p.name + '\n' for p in paths)
    sizes = ''.join(str(p.stat().st_size) + '  ' + p.name + '\n' for p in paths)
    with tempfile.TemporaryDirectory(prefix='w4c-test-candidate-') as temp:
        root = Path(temp)
        subprocess.run(['dpkg-deb', '-R', str(package), str(root / 'package')], check=True)
        roots = list((root / 'package/usr/lib').glob('*/resources/headless'))
        jars = list(roots[0].rglob('configuration-*.jar')) if len(roots) == 1 else []
        if len(jars) != 1:
            raise Infrastructure('No unique candidate configuration JAR')
        jar = jars[0]
        replacement = jar.with_suffix('.new')
        with zipfile.ZipFile(jar) as source, zipfile.ZipFile(replacement, 'w') as target:
            original = json.loads(source.read('ai/model-registry.v2.json'))
            registry = updater.update(original, 'linux-cuda12-1', checksums, sizes)
            package_entry = next(p for p in registry['packages'] if p['id'] == 'cuda-runtime-linux')
            for artifact in package_entry['supportingFiles']:
                artifact['downloadUrl'] = f'http://127.0.0.1:{port}/{artifact["filename"]}'
            for entry in source.infolist():
                target.writestr(entry, json.dumps(registry, indent=2).encode() if entry.filename == 'ai/model-registry.v2.json' else source.read(entry.filename))
        replacement.replace(jar)
        # The modified payload has its own Debian md5sums as well as its own
        # release SHA256SUMS; the immutable input is retained separately.
        import hashlib
        payload = root / 'package'
        hashes = []
        for path in sorted(payload.rglob('*')):
            if path.is_file() and not path.is_symlink() and 'DEBIAN' not in path.relative_to(payload).parts:
                with path.open('rb') as stream:
                    hashes.append(hashlib.file_digest(stream, 'md5').hexdigest() + '  ' + str(path.relative_to(payload)) + '\n')
        (payload / 'DEBIAN/md5sums').write_text(''.join(hashes))
        candidate = output / package.name
        subprocess.run(['dpkg-deb', '--root-owner-group', '-Zzstd', '-z3', '-b', str(payload), str(candidate)], check=True)
    (output / 'SHA256SUMS').write_text(digest(candidate) + '  ' + candidate.name + '\n')
    provenance = {'releaseQualification': False, 'reason': 'DEVELOPMENT ONLY: test registry uses loopback pack mirror',
                  'inputPackage': str(package), 'inputSha256': digest(package),
                  'candidatePackage': candidate.name, 'candidateSha256': digest(candidate),
                  'mirrorPort': port, 'aiRegistry': registry}
    (output / 'development-candidate.json').write_text(json.dumps(provenance, indent=2) + '\n')
    return provenance


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package', type=Path, required=True)
    parser.add_argument('--packs', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mirror-port', type=int, default=8769)
    args = parser.parse_args()
    result = prepare(args.package.resolve(), args.packs.resolve(), args.output.resolve(), args.mirror_port)
    print(result['candidatePackage'], result['candidateSha256'])
