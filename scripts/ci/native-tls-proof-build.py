#!/usr/bin/env python3
"""One-off hosted package carrier; no runtime, trust-store or release operations."""
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import subprocess
import sys
import time

BEFORE = '379f5936c8b9c0c23c59765f6d970bd33eeab8a8'
CANDIDATE = '1f152599a1bd715e34e95c3bbcd48ff03bcb246b'
BUILDS = {'before': ('proof-before', BEFORE, '0.3.80'),
          'probe': ('proof-candidate', CANDIDATE, '0.3.81'),
          'target': ('proof-candidate', CANDIDATE, '0.3.82')}
VERSION_FILES = {
    'gradle.properties', 'THIRD_PARTY_NOTICES',
    'modules/shell/package.json', 'modules/shell/package-lock.json',
    'modules/shell/src-tauri/Cargo.toml', 'modules/shell/src-tauri/Cargo.lock',
    'modules/shell/src-tauri/tauri.conf.json',
    'packaging/mcpb/manifest.json', 'packaging/mcpb/server.json',
}


def admit(env):
    if (env.get('GITHUB_ACTIONS') != 'true'
            or env.get('RUNNER_ENVIRONMENT') != 'github-hosted'
            or env.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
            or not env.get('GITHUB_REF', '').startswith('refs/heads/')):
        raise RuntimeError('This carrier requires a branch dispatch on a disposable GitHub-hosted runner')
    for name, expected in (('BEFORE_SHA', BEFORE), ('CANDIDATE_SHA', CANDIDATE)):
        if not re.fullmatch('[0-9a-f]{40}', env.get(name, '')) or env[name] != expected:
            raise RuntimeError(f'{name} must name the exact settled full source SHA')
    if env.get('PROOF_PLATFORM') not in ('linux', 'windows'):
        raise RuntimeError('Unsupported proof platform')
    if env.get('RUNNER_OS') != {'linux': 'Linux', 'windows': 'Windows'}[env['PROOF_PLATFORM']]:
        raise RuntimeError('Declared platform does not match the hosted runner OS')
    if env.get('JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE'):
        raise RuntimeError('Normal platform TLS verification requires sandbox test mode unset')
    # Never admit an accidentally inherited signing/release environment.
    for name, value in env.items():
        if value and (name.startswith(('TAURI_SIGNING_', 'JUSTSEARCH_CODESIGN_'))
                      and name != 'JUSTSEARCH_CODESIGN_LEDGER_PATH'):
            raise RuntimeError(f'Unexpected signing input: {name}')
    if env.get('RELEASE_BUILD') != 'false':
        raise RuntimeError('This carrier builds unsigned development packages only')


def digest(file):
    with Path(file).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write_json(file, value):
    Path(file).write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def capture(args, source):
    return subprocess.check_output(args, cwd=source, text=True, encoding='utf-8', timeout=60).strip()


def changed_paths(source):
    return set(capture(['git', 'diff', '--name-only', 'HEAD'], source).splitlines())


def generated_diff(source):
    return subprocess.check_output(['git', 'diff', '--binary', 'HEAD'],
                                   cwd=source, timeout=60)


def child_environment(pins=None):
    # Producers get no action/session token or production trust/signing override.
    env = {k: v for k, v in os.environ.items()
           if not re.search(r'TOKEN|SECRET|PASSWORD|CREDENTIAL', k, re.I)
           and not k.startswith(('JUSTSEARCH_RELEASE_', 'JUSTSEARCH_UPDATE_', 'TAURI_SIGNING_'))}
    env.pop('CARGO_TARGET_DIR', None)  # Producers use their own source-local targets.
    env['RELEASE_BUILD'] = 'false'
    env['ORG_GRADLE_PROJECT_includeCuda'] = 'false'
    env['ORG_GRADLE_PROJECT_skipOnnxModels'] = 'true'
    if pins:
        env.update({k: v for k, v in pins.items() if k.startswith('JUSTSEARCH_')})
    return env


def run(args, source, log, seconds, env=None):
    """Bound each child, retaining output while remaining visible in Actions."""
    started = time.monotonic()
    with Path(log).open('ab') as output:
        process = subprocess.Popen(args, cwd=source, env=env or child_environment(),
                                   stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=os.name != 'nt')
        try:
            code = process.wait(timeout=seconds)
        except subprocess.TimeoutExpired:
            if process.poll() is None:
                if os.name == 'nt':
                    # Only the still-live child PID belongs to this invocation.
                    subprocess.run(['taskkill.exe', '/PID', str(process.pid), '/T', '/F'],
                                   capture_output=True, timeout=30, check=True)
                else:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=30)
            raise
    print(f'{Path(log).name}: exit={code}, seconds={time.monotonic() - started:.1f}', flush=True)
    if code:
        # The complete log remains a public failure artifact; surface its tail too.
        print(Path(log).read_text(encoding='utf-8', errors='replace')[-12000:], flush=True)
        raise RuntimeError(f'{args[0]} exited {code}; see {Path(log).name}')


def roots():
    return Path(os.environ['GITHUB_WORKSPACE']).resolve(), Path(os.environ['RUNNER_TEMP']).resolve() / 'native-tls-proof'


def stage(workspace, root):
    # Admission happens before writing anything, even runner-local output.
    admit(os.environ)
    if capture(['git', 'status', '--porcelain', '--untracked-files=no'], workspace):
        raise RuntimeError('Carrier must be committed and clean before dispatch')
    root.mkdir(exist_ok=False)
    public = root / 'public'
    public.mkdir()
    for relative in ('scripts/ci/native-tls-proof-build.py', 'scripts/release/native-tls-proof-fixtures.mjs'):
        shutil.copy2(workspace / relative, root / Path(relative).name)
    write_json(public / 'carrier.json', {
        'scope': 'Build-only development TEST packages; native P1–P8 not executed by this workflow.',
        'carrierSha': capture(['git', 'rev-parse', 'HEAD'], workspace),
        'beforeSourceSha': BEFORE, 'candidateSourceSha': CANDIDATE,
        'runId': os.environ['GITHUB_RUN_ID'], 'runAttempt': os.environ['GITHUB_RUN_ATTEMPT'],
        'runnerEnvironment': os.environ['RUNNER_ENVIRONMENT'],
        'runnerOS': os.environ.get('RUNNER_OS'), 'runnerImage': os.environ.get('ImageOS'),
        'runnerImageVersion': os.environ.get('ImageVersion'),
        'helpers': {p.name: digest(p) for p in root.glob('*') if p.is_file()},
    })


def validate_source(source, expected, *, clean=True):
    if capture(['git', 'rev-parse', 'HEAD'], source) != expected:
        raise RuntimeError('Checkout does not match the exact tested source SHA')
    status = capture(['git', 'status', '--porcelain', '--untracked-files=all'], source)
    if clean and status:
        raise RuntimeError('Source checkout has unexpected input changes: ' + status)
    return status


def initialize(workspace, root):
    for folder, sha, _ in BUILDS.values():
        validate_source(workspace / folder, sha)
    private = root / 'private'
    private.mkdir(mode=0o700)
    run(['node', str(root / 'native-tls-proof-fixtures.mjs'), 'initialize',
         str(workspace / 'proof-candidate'), str(private), str(root / 'public')],
        workspace / 'proof-candidate', root / 'public' / 'initialize.log', 120)
    if os.name != 'nt':
        for file in private.iterdir():
            file.chmod(0o600)
    shutil.copy2(workspace / 'proof-before/governance/store-recoverability.v1.json',
                 root / 'public' / 'predecessor-register.json')
    shutil.copy2(workspace / 'proof-candidate/governance/store-recoverability.v1.json',
                 root / 'public' / 'candidate-register.json')
    toolchain = {
        'platform': platform.platform(),
        **{name: capture(command, workspace) for name, command in {
            'rustc': ['rustc', '-Vv'], 'cargo': ['cargo', '-V'],
            'node': ['node', '--version'],
            'npm': ['node', str(workspace / 'proof-candidate/scripts/lib/tool-invoker.cjs'), 'npm', '--version'],
            'java': ['java', '--version'],
        }.items()},
    }
    expected_host = 'x86_64-pc-windows-msvc' if os.name == 'nt' else 'x86_64-unknown-linux-gnu'
    if f'host: {expected_host}' not in toolchain['rustc']:
        raise RuntimeError('Rust host does not match the supported native package target')
    write_json(root / 'public' / 'toolchain.json', toolchain)


def build(workspace, root, label):
    folder, sha, version = BUILDS[label]
    source, public = workspace / folder, root / 'public'
    output = public / label
    output.mkdir()
    began = time.monotonic()
    outcome = {'label': label, 'sourceSha': sha, 'buildVersion': version, 'status': 'failed'}
    try:
        if label == 'target':
            validate_source(source, sha, clean=False)
            previous = (public / 'probe/generated-input.diff').read_bytes()
            if generated_diff(source) != previous or changed_paths(source) - VERSION_FILES:
                raise RuntimeError('Candidate input changed after the probe build')
            if capture(['git', 'ls-files', '--others', '--exclude-standard'], source):
                raise RuntimeError('Candidate checkout acquired untracked inputs after the probe')
            # Retire only the recorded, checked version input overlay; retain warm outputs.
            subprocess.run(['git', 'restore', '--source=HEAD', '--', *sorted(changed_paths(source))],
                           cwd=source, check=True, timeout=60)
        status = validate_source(source, sha)
        (output / 'source-status-before.txt').write_text(status + '\n', encoding='utf-8')
        properties = source / 'gradle.properties'
        original = properties.read_bytes()
        if len(re.findall(rb'^version=[^\r\n]+', original, re.M)) != 1:
            raise RuntimeError('Expected one canonical Gradle version input')
        properties.write_bytes(re.sub(rb'^version=[^\r\n]+', ('version=' + version).encode(), original, flags=re.M))
        run(['node', 'scripts/ci/sync-version.mjs', '--require-release-semver'], source,
            output / 'version-sync.log', 120)
        if changed_paths(source) - VERSION_FILES:
            raise RuntimeError('Version sync changed inputs outside the admitted projections')
        before_diff = generated_diff(source)
        (output / 'generated-input.diff').write_bytes(before_diff)
        run(['node', 'scripts/ci/sync-version.mjs', '--check', '--require-release-semver'],
            source, output / 'version-sync.log', 120)
        if generated_diff(source) != before_diff:
            raise RuntimeError('Version sync is not deterministic')
        pins = json.loads((public / 'trust-pins.json').read_text(encoding='utf-8'))
        write_json(output / 'build-inputs.json', {
            'sourceSha': sha, 'version': version, 'publicPins': pins,
            'generatedInputDiffSha256': digest(output / 'generated-input.diff'),
            'producer': 'scripts/ci/package-installer-linux.sh' if os.name != 'nt' else 'scripts/ci/build-installer-windows.ps1',
            'releaseBuild': False, 'sandboxTestMode': 'unset', 'includeCuda': False,
            'skipOnnxModels': True, 'sourceTargetDirectory': f'{folder}/modules/shell/src-tauri/target',
            'cargoLockSha256': digest(source / 'modules/shell/src-tauri/Cargo.lock'),
            'signedMirrorsSha256': digest(source / 'packaging/signed-mirrors.v1.json'),
        })
        producer = (['pwsh', '-NoProfile', '-File', 'scripts/ci/build-installer-windows.ps1'] if os.name == 'nt'
                    else ['bash', 'scripts/ci/package-installer-linux.sh'])
        package_dir = source / ('dist/installer' if os.name == 'nt' else 'dist/installer-linux')
        # These are owned build outputs, already exported by the preceding stage.
        # Retain compiler intermediates, but never let an older bundle satisfy this build.
        if package_dir.exists():
            shutil.rmtree(package_dir)
        if os.name == 'nt':
            for relative in ('target/release/bundle/nsis', 'target/x86_64-pc-windows-msvc/release/bundle/nsis'):
                bundle = source / 'modules/shell/src-tauri' / relative
                if bundle.exists():
                    shutil.rmtree(bundle)
        # Windows producer expects npm dependencies to have been installed by CI.
        if os.name == 'nt':
            for module in ('shell', 'ui-web'):
                run(['node', 'scripts/lib/tool-invoker.cjs', 'npm', 'ci', '--prefix', f'modules/{module}', '--audit=false'],
                    source, output / 'npm-ci.log', 600, child_environment(pins))
        run(producer, source, output / 'build.log', 55 * 60, child_environment(pins))
        if generated_diff(source) != before_diff:
            (output / 'unexpected-postbuild.diff').write_bytes(generated_diff(source))
            raise RuntimeError('Producer changed tracked inputs beyond the recorded version overlay')
        packages = list(package_dir.glob('*-setup.exe' if os.name == 'nt' else '*.deb'))
        if len(packages) != 1 or version not in packages[0].name:
            raise RuntimeError('Producer did not return exactly one installer for the requested version')
        installer = packages[0]
        shutil.copy2(installer, output / installer.name)
        if os.name != 'nt':
            if capture(['dpkg-deb', '-f', str(installer), 'Version'], source) != version:
                raise RuntimeError('Debian package embeds a different version')
            if capture(['dpkg-deb', '-f', str(installer), 'Architecture'], source) != 'amd64':
                raise RuntimeError('Debian package has an unsupported architecture')
            shutil.copy2(package_dir / 'elf-audit.json', output / 'elf-audit.json')
        run(['node', 'scripts/ci/pack-mcpb.mjs', str(output / 'justsearch-mcp.mcpb')],
            source, output / 'mcpb.log', 120)
        shutil.copy2(source / 'packaging/mcpb/server.json', output / 'server.json')
        binary_name = 'JustSearch.exe' if os.name == 'nt' else 'JustSearch'
        target = source / 'modules/shell/src-tauri/target'
        release = target / 'release'
        if os.name == 'nt':
            # Both historical target layouts may be cached. Bind the shell hash
            # to the newly produced bundle, not an older alternate target.
            matches = [directory for directory in (release, target / 'x86_64-pc-windows-msvc/release')
                       if (directory / 'bundle/nsis' / installer.name).is_file()
                       and digest(directory / 'bundle/nsis' / installer.name) == digest(installer)]
            if len(matches) != 1:
                raise RuntimeError('Cannot bind the NSIS installer to one fresh native target directory')
            release = matches[0]
        executable = release / binary_name
        if not executable.is_file():
            raise RuntimeError('Native producer left no supported built shell executable')
        executables = [{'buildPath': executable.relative_to(source).as_posix(),
                        'sha256': digest(executable), 'size': executable.stat().st_size}]
        write_json(output / 'package.json', {'installer': installer.name, 'version': version,
                   'platform': os.environ['PROOF_PLATFORM'], 'sourceSha': sha,
                   'size': installer.stat().st_size, 'sha256': digest(installer),
                   'builtExecutables': executables})
        run(['node', str(root / 'native-tls-proof-fixtures.mjs'), 'assemble', str(source),
             str(root / 'private'), str(public), str(output), version,
             os.environ['PROOF_PLATFORM'], str(public / 'predecessor-register.json')],
            source, output / 'fixture-verification.log', 120)
        outcome['status'] = 'package-and-public-fixtures-verified'
    except BaseException as error:
        outcome['error'] = f'{type(error).__name__}: {error}'
        raise
    finally:
        outcome['seconds'] = round(time.monotonic() - began, 3)
        write_json(output / 'outcome.json', outcome)


def finalize(root):
    # Always remove private files before examining public output or reporting failure.
    shutil.rmtree(root / 'private', ignore_errors=False) if (root / 'private').exists() else None
    public = root / 'public'
    carrier = json.loads((public / 'carrier.json').read_text(encoding='utf-8'))

    def file_ref(relative):
        file = public / relative
        if file.is_symlink() or not file.is_file() or Path(relative).is_absolute() or '..' in Path(relative).parts:
            raise RuntimeError('Missing or unsafe public artifact path: ' + relative)
        return {'path': relative, 'sha256': digest(file), 'size': file.stat().st_size}

    builds = []
    for label, (_, source_sha, version) in BUILDS.items():
        outcome_file = public / label / 'outcome.json'
        if not outcome_file.exists() or json.loads(outcome_file.read_text(encoding='utf-8'))['status'] != 'package-and-public-fixtures-verified':
            continue
        package = json.loads((public / label / 'package.json').read_text(encoding='utf-8'))
        if package['sourceSha'] != source_sha or package['version'] != version or package['platform'] != os.environ['PROOF_PLATFORM']:
            raise RuntimeError('Package receipt changed after fixture verification')
        installer = file_ref(f"{label}/{package['installer']}")
        if installer['sha256'] != package['sha256'] or installer['size'] != package['size']:
            raise RuntimeError('Installer changed after fixture verification')
        builds.append({'label': label, 'sourceSha': source_sha, 'version': version,
            'installer': installer,
            'descriptor': file_ref(f'{label}/release.v2.json'),
            'descriptorSignature': file_ref(f'{label}/release.v2.json.sig'),
            'feed': file_ref(f'{label}/latest.json'),
            'artifactSignature': file_ref(f"{label}/{package['installer']}.sig"),
            'mcpb': file_ref(f'{label}/justsearch-mcp.mcpb'),
            'registry': file_ref(f'{label}/server.json'),
            'packageReceipt': file_ref(f'{label}/package.json'),
            'buildInputs': file_ref(f'{label}/build-inputs.json')})
    manifest = {
        'schemaVersion': 1, 'status': 'complete' if len(builds) == 3 else 'incomplete',
        'platform': os.environ['PROOF_PLATFORM'], 'carrierSha': carrier['carrierSha'],
        'beforeSourceSha': BEFORE, 'candidateSourceSha': CANDIDATE,
        'trust': {}, 'compatibility': {}, 'builds': builds,
    }
    for group, key, relative in (
            ('trust', 'pins', 'trust-pins.json'), ('trust', 'metadataPublicKey', 'metadata-public.pem'),
            ('compatibility', 'predecessor', 'predecessor-register.json'),
            ('compatibility', 'candidate', 'candidate-register.json')):
        if (public / relative).is_file():
            manifest[group][key] = file_ref(relative)
    write_json(public / 'native-proof-artifacts.json', manifest)
    write_json(public / 'runtime-pending.json', {
        'status': 'unverified', 'scenarios': [f'P{i}' for i in range(1, 9)],
        'reason': 'This carrier builds packages only. Native TLS, lifecycle and update proof runs separately in owned runtime guests.',
    })
    inventory = {}
    root_names = {'carrier.json', 'trust-pins.json', 'metadata-public.pem',
                  'predecessor-register.json', 'candidate-register.json', 'toolchain.json',
                  'initialize.log', 'native-proof-artifacts.json', 'runtime-pending.json', 'SHA256SUMS.json'}
    build_names = {'source-status-before.txt', 'generated-input.diff', 'unexpected-postbuild.diff',
                   'version-sync.log', 'npm-ci.log', 'build.log', 'mcpb.log',
                   'fixture-verification.log', 'build-inputs.json', 'outcome.json',
                   'package.json', 'server.json', 'elf-audit.json', 'justsearch-mcp.mcpb',
                   'release.v2.json', 'release.v2.json.sig', 'latest.json', 'fixture-layout.json'}
    for file in sorted(public.rglob('*')):
        if file.is_symlink():
            raise RuntimeError('Symlink in public export: ' + file.name)
        if not file.is_file():
            continue
        parts = file.relative_to(public).parts
        allowed = ((len(parts) == 1 and file.name in root_names)
                   or (len(parts) == 2 and parts[0] in BUILDS
                       and (file.name in build_names or
                            (BUILDS[parts[0]][2] in file.name and
                             re.fullmatch(r'[A-Za-z0-9_.()+ -]+(?:\.deb|setup\.exe)(?:\.sig)?', file.name)))))
        if not allowed or re.search(r'private|token|credential', file.name, re.I):
            raise RuntimeError('Unexpected sensitive file in public export: ' + file.name)
        if file.suffix in ('.pem', '.json', '.log', '.txt', '.diff') and b'PRIVATE KEY' in file.read_bytes():
            raise RuntimeError('Private key material reached the public export: ' + file.name)
        if file.name != 'SHA256SUMS.json':
            inventory[file.relative_to(public).as_posix()] = {'sha256': digest(file), 'size': file.stat().st_size}
    write_json(public / 'SHA256SUMS.json', inventory)


def main():
    admit(os.environ)
    workspace, root = roots()
    command = sys.argv[1:]
    if command == ['stage']:
        stage(workspace, root)
    elif command == ['initialize']:
        initialize(workspace, root)
    elif len(command) == 2 and command[0] == 'build' and command[1] in BUILDS:
        build(workspace, root, command[1])
    elif command == ['finalize']:
        finalize(root)
        # The always-upload step is gated on this audit, including on failed builds.
        with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as output:
            output.write('safe_export=true\n')
    else:
        raise SystemExit('Expected stage | initialize | build before|probe|target | finalize')


if __name__ == '__main__':
    main()
