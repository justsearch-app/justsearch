"""GPU host adapter for runner.py. No installation, developer runtime or product hooks."""
import json
import datetime
import gzip
import ipaddress
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time
import xml.etree.ElementTree as ET
from urllib.parse import urlparse

import linux
import runner
from runner import Infrastructure, ProductFailure, RunStopped, Unavailable, wait_for

GIB = 1024 ** 3
INTERRUPTED = 'interrupted: GPU or memory needed by the owner'
# Conservative named production owners: JVM/index lifecycle and configuration also
# affect inference/enrichment. Harness, documentation and tests alone do not.
SOAK_PATHS = (
    'modules/worker-core/src/main/', 'modules/worker-services/src/main/',
    'modules/indexer-worker/src/main/', 'modules/app-engine/src/main/',
    'modules/app-inference/src/main/', 'modules/ai-backend/src/main/',
    'modules/ort-common/src/main/', 'modules/gpu-bridge/src/main/',
    'modules/app-services/src/main/', 'modules/configuration/src/main/',
    'modules/ui/src/main/', 'modules/core/src/main/', 'modules/shell/src-tauri/src/',
    'build-logic/', 'gradle/libs.versions.toml', 'scripts/ai/build-linux-gpu-packs.py',
    'scripts/ai/assemble-linux-gpu-packs.py', 'scripts/ai/update-linux-gpu-registry.py',
    'scripts/ai/linux-gpu-inputs.v1.json',
)
CUDA_LIBRARY = re.compile(r'^lib(?:cudart|cudnn[^/]*|cublas[^/]*|cufft[^/]*|curand|cusolver[^/]*|cusparse[^/]*|cupti|cufile[^/]*|cudla|cuinj[^/]*|nvrtc[^/]*|nvJitLink|nvfatbin|nvToolsExt|nvblas|nvvm|nvptxcompiler|npp[^/]*|nccl|cutensor[^/]*)\.so(?:\.|$)')


def cuda_libraries(maps, pack):
    """Check canonical mapped files, including SONAMEs and paths with spaces.

    libcuda/libnvidia are the host driver ABI; they are deliberately excluded.
    ORT's JNI/provider library itself comes from the candidate's bundled JAR.
    """
    nominal_pack = Path(pack).absolute()
    pack = nominal_pack.resolve()
    if pack != nominal_pack:
        raise ProductFailure('Installed pack directory escapes its private path: ' + str(nominal_pack))
    libraries = set()
    for line in maps.splitlines():
        fields = line.split(None, 5)
        if len(fields) != 6:
            continue
        raw = fields[5]
        name = Path(raw.removesuffix(' (deleted)')).name
        if not CUDA_LIBRARY.match(name):
            continue
        path = Path(raw.removesuffix(' (deleted)')).resolve()
        if raw.endswith(' (deleted)') or not path.is_relative_to(pack) or not path.is_file():
            raise ProductFailure('CUDA library outside installed pack: ' + raw)
        libraries.add(str(path))
    return sorted(libraries)


def require_cuda_maps(libraries):
    for role in ('engine', 'llama'):
        if not libraries.get(role):
            raise ProductFailure('No loaded CUDA libraries observed for ' + role)


def gpu_process_memory(xml):
    rows = []
    try:
        for process in ET.fromstring(xml).findall('.//process_info'):
            value = process.findtext('used_memory', '')
            match = re.fullmatch(r'(\d+) MiB', value)
            if not match:
                raise ValueError('GPU process memory unavailable: ' + value)
            rows.append((int(process.findtext('pid')), int(match[1])))
    except (ET.ParseError, ValueError, TypeError) as exc:
        raise Infrastructure('Cannot enforce GPU resource guard: ' + str(exc)) from exc
    return rows


def resources_available(available, processes, owned, *, starting):
    return available >= (14 if starting else 12) * GIB and not any(
        mib > 1024 and pid not in owned for pid, mib in processes)


def resource_sample(owned=(), *, starting=False):
    raw = subprocess.check_output(['nvidia-smi', '-q', '-x'], text=True, timeout=10)
    processes = gpu_process_memory(raw)
    memory = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
    available = int(memory['MemAvailable'].split()[0]) * 1024
    return {'epochSeconds': time.time(), 'memAvailableBytes': available,
            'gpuProcessesMiB': processes, 'ownedPids': sorted(owned),
            'available': resources_available(available, processes, set(owned), starting=starting)}


def private_environment(source, root, marker, *, resume=False):
    # Allowlist rather than trying to enumerate every native/Java/model override.
    env = {k: source[k] for k in ('USER', 'LOGNAME', 'LANG', 'LC_ALL', 'PATH') if k in source}
    search = os.pathsep.join(p for p in env.get('PATH', '/usr/bin:/bin').split(os.pathsep)
                            if p and not re.search(r'java|jdk|jre|temurin|corretto|zulu', p, re.I))
    # This host has /usr/bin/javac as well as a developer JDK directory. Give
    # children only named OS/harness tools; the app must use its bundled JVM.
    bin_dir = root / 'bin'
    bin_dir.mkdir(exist_ok=resume)
    for name in ('sh', 'bash', 'python3', 'git', 'env', 'cat', 'dirname', 'which', 'id', 'grep', 'uname',
                 'sed', 'find', 'readlink', 'realpath', 'hostname', 'dbus-run-session', 'dbus-daemon',
                 'dbus-update-activation-environment', 'gdbus', 'Xvfb', 'xvfb-run', 'xauth', 'openbox', 'taskset',
                 'getopt', 'mktemp', 'rm', 'fmt', 'awk', 'mcookie', 'touch',
                 'tauri-driver', 'WebKitWebDriver', 'scrot', 'xdotool', 'tesseract', 'nvidia-smi',
                 'curl', 'xdg-open', 'xdg-settings', 'xdg-mime', 'gio', 'dpkg-deb', 'tar', 'gzip', 'xz', 'zstd', 'coredumpctl'):
        executable = shutil.which(name, path=search)
        if executable and not (bin_dir / name).exists():
            (bin_dir / name).symlink_to(executable)
    env['PATH'] = str(bin_dir)
    for key, directory in (('HOME', 'home'), ('XDG_DATA_HOME', 'data'), ('XDG_CONFIG_HOME', 'config'),
                           ('XDG_CACHE_HOME', 'cache'), ('XDG_RUNTIME_DIR', 'runtime')):
        path = root / directory
        path.mkdir(mode=0o700, exist_ok=resume)
        env[key] = str(path)
    env.update(QUAL_GPU_SESSION=marker, OMP_NUM_THREADS='8', OPENBLAS_NUM_THREADS='8',
               JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR=str(root / 'cache/ort-optimized'),
               RAYON_NUM_THREADS='8', JUSTSEARCH_ORT_INTRA_OP_THREADS='8',
               XDG_SESSION_TYPE='x11', XDG_CURRENT_DESKTOP='Openbox')
    return env


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def device_writes(device, diskstats=Path('/proc/diskstats')):
    """Linux diskstats sectors are 512 bytes, independent of sector size."""
    for line in diskstats.read_text().splitlines():
        fields = line.split()
        if ':'.join(fields[:2]) == device:
            return int(fields[9]) * 512
    raise Infrastructure('Private-root block device missing from diskstats: ' + device)


def soak_relevant(paths):
    return any(p.startswith(SOAK_PATHS) or (p.startswith('modules/') and
               p.endswith(('/build.gradle.kts', '/build.gradle'))) for p in paths)


def soak_decision(source, last_release=None):
    if not source:
        raise Unavailable('J9 needs --source identifying the exact candidate source checkout')
    def git(*args):
        result = subprocess.run(['git', '-C', str(source), *args], capture_output=True, text=True)
        if result.returncode:
            raise Infrastructure('J9 release diff unavailable: ' + result.stderr)
        return result.stdout.strip()
    candidate = git('rev-parse', 'HEAD')
    tag = last_release or git('describe', '--tags', '--match', 'v[0-9]*', '--abbrev=0', candidate)
    if not re.fullmatch(r'v\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?', tag):
        raise Infrastructure('--last-release must be a version release tag')
    if tag not in git('tag', '--list', tag).splitlines():
        raise Infrastructure('--last-release must name a local release tag')
    paths = git('diff', '--name-only', tag, candidate).splitlines()
    # Uncommitted production changes cannot be described by the pinned candidate.
    dirty = git('diff', '--name-only', 'HEAD').splitlines() + git('ls-files', '--others', '--exclude-standard').splitlines()
    if soak_relevant(dirty):
        raise Infrastructure('Candidate source has uncommitted production changes; J9 diff cannot identify its bytes')
    relevant = [p for p in paths if soak_relevant([p])]
    return {'run': bool(relevant), 'lastRelease': tag, 'candidateCommit': candidate,
            'paths': list(SOAK_PATHS), 'changedRelevantPaths': relevant,
            'reason': ('Indexing, enrichment or inference owners changed' if relevant else
                       'No indexing, enrichment or inference owner changed since ' + tag)}


class SoakProgress:
    def __init__(self, started):
        self.started = self.last_progress = started
        self.high_water = -1
        self.identity = None
        self.samples = 0

    def observe(self, now, progress, used, maximum, identity):
        if not maximum or maximum <= 0 or used < 0:
            raise Infrastructure('Engine memory guard is unavailable')
        if used >= maximum:
            raise ProductFailure(f'Engine memory guard exceeded: {used} >= {maximum}')
        if self.identity is not None and identity != self.identity:
            raise ProductFailure('Engine identity changed during soak (unexpected restart)')
        self.identity = identity
        if progress > self.high_water:
            self.high_water = progress
            self.last_progress = now
        if now - self.last_progress > 600:
            raise ProductFailure('No steady indexing/enrichment progress for over 10 minutes')
        self.samples += 1

    def complete(self, now):
        if now - self.started < 3600:
            raise Infrastructure('J9 did not observe the full 60 minutes')
        return {'seconds': now - self.started, 'samples': self.samples,
                'progressHighWater': self.high_water, 'engineIdentity': self.identity,
                'memoryGuard': 'Production /api/status memoryUsedBytes < memoryMaxBytes (-Xmx2g)'}


def require_gpu_plan(preview):
    if preview.get('downloadProfile') != 'GPU_FULL':
        raise ProductFailure('Default plan did not select GPU_FULL')
    components = {c['id']: c for c in preview.get('components', [])}
    for key in ('chat', 'cuda-runtime-linux'):
        if components.get(key, {}).get('state') not in ('to-download', 'installed'):
            raise ProductFailure('GPU_FULL plan does not include ' + key)
    if components.get('cuda-runtime', {}).get('state') in ('to-download', 'installed'):
        raise ProductFailure('Linux plan includes Windows native runtime')


def require_gpu_install(status):
    packages = {p['packageId']: p for p in status.get('packages', [])}
    if not status.get('installedFully') or any(packages.get(key, {}).get('state') != 'installed'
                                               for key in ('chat', 'cuda-runtime-linux')):
        raise ProductFailure('GPU plan and installed readiness disagree: ' + json.dumps(status))
    if packages.get('cuda-runtime', {}).get('state') == 'installed':
        raise ProductFailure('Windows native runtime installed on Linux')


def require_gpu_encoders(runtime):
    features = {f['id']: f for f in runtime.get('onnxFeatures', [])}
    for name in ('embed', 'splade'):
        feature = features.get(name, {})
        if (feature.get('modelActive') is not True or feature.get('executionProvider') != 'cuda'
                or feature.get('gpuFallback') is not False):
            raise ProductFailure('Encoder is not executing on CUDA: ' + name + ': ' + json.dumps(feature))


class SoakGpu:
    def __init__(self):
        self.previous = None
        self.observations = []
        self.confirmed_checks = 0

    def observe(self, sample):
        features = {f['id']: f for f in sample['runtime'].get('onnxFeatures', [])}
        cpu = {name for name in ('embed', 'splade')
               if features.get(name, {}).get('executionProvider') == 'cpu'}
        try:
            require_gpu_encoders(sample['runtime'])
            matched = True
        except ProductFailure:
            matched = False
        worker = sample['workerCuda']
        active = worker['computeMemoryMiB'] > 0 and bool(worker['libraries'])
        if not matched or not active:
            self.observations.append(sample)
        else:
            self.confirmed_checks += 1
        previous = self.previous
        self.previous = sample
        if previous and sample['seconds'] - previous['seconds'] >= 30:
            prior_cpu = {f['id'] for f in previous['runtime'].get('onnxFeatures', [])
                         if f.get('executionProvider') == 'cpu'}
            if (cpu & prior_cpu and worker['computeMemoryMiB'] == 0 and not worker['libraries']
                    and previous['workerCuda']['computeMemoryMiB'] == 0
                    and not previous['workerCuda']['libraries']):
                raise ProductFailure('Persistent CPU fallback: consecutive runtime samples >=30 s apart and worker has neither compute-app memory nor CUDA library maps')


def compute_memory(raw, pid):
    # nvidia-smi runs inside the lane's PID namespace and reports those PIDs.
    memory = 0
    for line in raw.splitlines():
        fields = line.split(',')
        if len(fields) != 2 or not all(value.strip().isdigit() for value in fields):
            raise Infrastructure('J9 compute-app memory unavailable: ' + raw)
        if int(fields[0]) == pid:
            memory += int(fields[1])
    return memory


def worker_cuda(run, pid):
    rows = [p for p in run.target.owned() if p['pid'] == pid]
    if len(rows) != 1 or not run.target.alive(rows):
        raise Infrastructure('J9 worker identity unavailable for CUDA evidence')
    row = rows[0]
    raw = subprocess.check_output(['nvidia-smi', '--query-compute-apps=pid,used_gpu_memory',
                                   '--format=csv,noheader,nounits'], text=True, timeout=10)
    memory = compute_memory(raw, pid)
    maps = Path('/proc', str(pid), 'maps').read_text()
    pack = Path(run.data, 'native-bin/llama-server/variants/cuda12')
    libraries = cuda_libraries(maps, pack)
    if not run.target.alive(rows):
        raise Infrastructure('J9 worker changed during CUDA probe')
    return {'identity': row, 'computeAppsRaw': raw,
            'computeMemoryMiB': memory, 'libraries': libraries, 'maps': maps}


def soak_logs(path, started, ended, observations):
    warnings, contexts = [], [[] for _ in observations]
    logs = [*sorted(path.parent.glob('engine.*.log.gz')), path]
    lines = []
    for log in logs:
        content = gzip.open(log, 'rt', errors='replace').read() if log.suffix == '.gz' else log.read_text(errors='replace')
        lines.extend(content.splitlines())
    for line in lines:
        try:
            row = json.loads(line)
            stamp = datetime.datetime.fromisoformat(row['@timestamp']).timestamp()
        except (ValueError, KeyError, TypeError):
            continue
        if started <= stamp <= ended and row.get('level') == 'WARN' and 'BackfillScheduler' in row.get('logger_name', '') and 'ZERO stage advancement' in row.get('message', ''):
            warnings.append(row['@timestamp'])
        for observation, context in zip(observations, contexts):
            if abs(stamp - observation['epochSeconds']) <= 5:
                context.append(row)
    return {'zeroStageAdvancement': {'count': len(warnings),
                'firstTime': min(warnings) if warnings else None, 'lastTime': max(warnings) if warnings else None},
            'runtimeMismatchObservations': [{**sample, 'engineLogWithin5Seconds': context}
                                           for sample, context in zip(observations, contexts)]}


def require_registry_urls(registry, development=False):
    def visit(value):
        if isinstance(value, dict):
            if 'downloadUrl' in value:
                url = urlparse(value['downloadUrl'])
                local_pack = development and url.hostname == '127.0.0.1'
                try:
                    local_address = not ipaddress.ip_address(url.hostname).is_global
                except ValueError:
                    local_address = url.hostname == 'localhost'
                if (not url.hostname or (local_address and not local_pack) or
                        (url.scheme != 'https' and not local_pack) or ('TEST-ONLY' in url.path and not development)):
                    raise Infrastructure('Development registry requires explicit --development-pack-mirror; release download URLs are used unchanged')
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)
    visit(registry)


def require_chat_answer(events):
    if any(name == 'error' for name, body in events):
        raise ProductFailure('Chat returned a terminal error: ' + json.dumps(events))
    done = [body for name, body in events if name == 'done']
    text = ''.join(body.get('text', '') for name, body in events if name == 'chunk')
    citations = [c for name, body in events if name in ('rag.citations', 'done') for c in body.get('citations', [])]
    if not done or not text.strip() or not citations:
        raise ProductFailure('Chat did not complete with an answer and source citations')
    if any(body.get('cancelled') or body.get('error') for body in done):
        raise ProductFailure('Chat ended unsuccessfully')
    return {'answer': text, 'citations': citations}


class Run(runner.Run):
    interrupted = False

    def check(self, journey, name, fn):
        def guarded():
            if self.interrupted:
                raise Unavailable('Lane stopped after interruption or failed check')
            value = fn()
            if self.interrupted:
                raise RunStopped(INTERRUPTED)
            return value
        result = super().check(journey, name, guarded)
        if journey['checks'][-1]['status'] in ('failed-product', 'failed-infrastructure'):
            self.interrupted = True
        return result

    def setup(self):
        # Only gpu-host.py may create the isolated session and arm the guard.
        root = os.environ.get('QUAL_GPU_ROOT')
        if not root or not os.environ.get('QUAL_GPU_SESSION') or not Path(root, 'guard-armed').is_file():
            raise Infrastructure('Use gpu-host.py: a private display, bus and resource guard are required')
        self.work = root + '/work'
        Path(self.work).mkdir(exist_ok=True)
        self.data = os.environ['XDG_DATA_HOME'] + '/io.justsearch.shell'
        self.crash_since_ns = time.time_ns()
        # Preserve only the wrapper's private environment. No prepare() hosted
        # prerequisite: the owner host must never be treated as disposable.
        self.target.env = private = {k: v for k, v in os.environ.items() if k in (
            'HOME', 'USER', 'LOGNAME', 'PATH', 'DISPLAY', 'XAUTHORITY', 'LANG', 'LC_ALL',
            'XDG_RUNTIME_DIR', 'DBUS_SESSION_BUS_ADDRESS', 'XDG_SESSION_TYPE', 'XDG_CURRENT_DESKTOP',
            'XDG_DATA_HOME', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME', 'QUAL_GPU_SESSION',
            'OMP_NUM_THREADS', 'OPENBLAS_NUM_THREADS', 'RAYON_NUM_THREADS', 'JUSTSEARCH_ORT_INTRA_OP_THREADS',
            'JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR')}
        private.update(QUAL_JOURNEY_RUN=self.run_id, JUSTSEARCH_API_PORT=str(free_port()))
        self.facts['driverPorts'] = [free_port(), free_port()]
        if self.args.resume:
            state = json.loads(self.args.resume.read_text())
            if (state['assets'] != self.facts['assets'] or state['lane'] != 'gpu-linux'
                    or state['work'] != self.work or state['data'] != self.data):
                raise Infrastructure('GPU checkpoint does not match private root and candidate assets')
            self.binary = state['binary']
            self.facts.update(state['facts'])
            self.facts['driverPorts'] = [free_port(), free_port()]
            self.start_ui()
        elif Path(self.data).exists():
            raise Infrastructure('GPU run inherited existing application state')
        self.evidence('environment', {k: v for k, v in private.items() if k != 'DBUS_SESSION_BUS_ADDRESS'})
        self.facts['soakDecision'] = soak_decision(self.args.source, self.args.last_release)
        self.evidence('J9-relevance', self.facts['soakDecision'])
        if self.target.run(['sh', '-c', 'command -v javac'], check=False).returncode == 0:
            raise Infrastructure('GPU lane PATH still exposes a developer JDK')

    def save(self):
        report = super().save()
        report['execution']['releaseQualification'] = not bool(
            getattr(self.args, 'development_pack_mirror', None) or getattr(self.args, 'journeys', None)
            or getattr(self.args, 'resume', None))
        report['execution']['developmentPackMirror'] = str(self.args.development_pack_mirror) if getattr(self.args, 'development_pack_mirror', None) else None
        if getattr(self.args, 'journeys', None) == ['J1', 'J3', 'J9']:
            report['execution'].update(mode='development-continuation', freshPrivateState=not bool(self.args.resume))
        report['soakDecision'] = self.facts.get('soakDecision', {'run': None, 'reason': 'setup did not determine relevance'})
        if 'soak' in self.facts:
            report['soak'] = self.facts['soak']
        self.evidence('summary', report)
        return report

    def activation_checkpoint(self):
        # Kept only inside the private root, never in owner state. This is a
        # development recovery point, not evidence that remaining checks passed.
        path = Path(os.environ['QUAL_GPU_ROOT']) / 'activated-checkpoint.json'
        path.write_text(json.dumps({'assets': self.facts['assets'], 'lane': 'gpu-linux',
            'work': self.work, 'data': self.data, 'binary': self.binary,
            'facts': {k: v for k, v in self.facts.items()
                      if k not in ('ui', 'ready', 'preLaunchProcesses')}}) + '\n')

    def stop_app(self):
        self.facts.pop('ui', None)
        self.facts.pop('ready', None)
        if self.wd:
            try:
                self.wd.close()
            finally:
                self.wd = None
        identities = self.target.owned()
        self.facts['preLaunchProcesses'] = identities
        self.target.stop(identities)

    def jvm_crashes(self):
        # Avoid host installed-package paths and the owner's home entirely.
        rows = []
        roots = {Path(self.work), Path(self.data), *map(Path, self.facts.get('engineWorkingDirectories', []))}
        for root in roots:
            if not root.exists():
                continue
            for path in root.rglob('hs_err_pid*.log'):
                if path.is_symlink():
                    raise Infrastructure('JVM crash report is a symlink')
                if path.stat().st_mtime_ns >= self.crash_since_ns:
                    rows.append({'file': str(path), 'content': path.read_text(errors='replace')[:4_000_000]})
        self.evidence('jvm-crashes', rows)
        if rows:
            raise ProductFailure('Bundled JVM crash: ' + json.dumps(rows))

    def write_counters(self):
        device = os.environ['QUAL_GPU_BLOCK_DEVICE']
        value = device_writes(device)
        return {'devices': {device: value}, 'writtenBytes': value, 'swapWrittenBytes': None,
                'source': 'Private-root mount device /proc/diskstats sectors written * 512; includes unrelated host IO (upper bound); no host sync',
                'swapMeasurement': 'separate swap attribution unavailable'}


def extract_package(run):
    package = run.args.assets.resolve() / run.args.package
    root = Path(run.work) / 'package'
    if not (os.environ.get('QUAL_GPU_EXTRACTED') == str(root) and root.is_dir()):
        run.target.run(['dpkg-deb', '-x', package, root], timeout=180)
    binaries = list(root.glob('usr/bin/JustSearch'))
    if len(binaries) != 1:
        raise ProductFailure('Candidate has no unique packaged JustSearch executable')
    run.binary = str(binaries[0])
    run.target.app_binary = run.binary
    resources = list(root.glob('usr/lib/*/resources/headless'))
    if len(resources) != 1:
        raise ProductFailure('Candidate has no adjacent packaged headless resources')
    java = resources[0] / 'runtime/bin/java'
    if not java.is_file() or not java.resolve().is_relative_to(root.resolve()):
        raise ProductFailure('Candidate has no bundled JVM')
    run.facts['engineWorkingDirectories'] = [str(resources[0])]
    # Never rewrite the extracted payload. The development option requires pins
    # to be built into the selected .deb and hashes them like every other asset.
    import zipfile
    jars = list(resources[0].rglob('configuration-*.jar'))
    registries = []
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            if 'ai/model-registry.v2.json' in archive.namelist():
                registries.append(json.loads(archive.read('ai/model-registry.v2.json')))
    if len(registries) != 1:
        raise ProductFailure('Candidate registry is not uniquely identifiable')
    registry = registries[0]
    run.evidence('candidate-ai-registry', registry)
    require_registry_urls(registry, bool(run.args.development_pack_mirror))
    if run.args.development_pack_mirror:
        package_entry = next((p for p in registry['packages'] if p['id'] == 'cuda-runtime-linux'), None)
        if not package_entry:
            raise Infrastructure('Development candidate must already contain Linux GPU registry pins')
        for artifact in package_entry['supportingFiles']:
            path = run.args.development_pack_mirror / artifact['filename']
            url = f'http://127.0.0.1:{run.args.development_mirror_port}/{artifact["filename"]}'
            if (artifact['downloadUrl'] != url or not path.is_file() or
                    path.stat().st_size != artifact['sizeBytes'] or runner.digest(path).lower() != artifact['sha256'].lower()):
                raise Infrastructure('Development mirror does not match candidate registry: ' + str(path))
    from corpus import generate
    corpus = Path(run.work) / 'corpus'
    run.evidence('corpus-sha256', generate(corpus))
    run.facts['corpus'] = str(corpus)
    return {'package': str(package), 'sha256': runner.digest(package), 'binary': run.binary,
            'bundledJVM': str(java), 'resourceDirectory': str(resources[0]),
            'installation': 'not applicable: exact candidate extracted, no host package installation'}


def first_run(run):
    if not run.binary:
        raise Unavailable('Candidate extraction did not complete')
    run.start_ui()
    wait_for(lambda: run.wd.execute("return all(document,'jf-shell').length > 0"), 'Packaged UI mounted', seconds=60)
    buttons = run.wd.execute("return all(document,'button').map(e=>e.textContent.trim())")
    if 'Get started' in buttons:
        run.wd.click('button', 'Get started')
    if run.wd.execute("return all(document,'jf-walkthrough-card').some(e=>!e.hidden)"):
        run.wd.click('button[aria-label="Dismiss"]')
    run.native.shot('J1-gpu-first-run')
    return {'mountedDOM': True, 'window': run.native.app_window(run.binary, focused=False),
            'firstRunButtons': buttons, 'ready': run.ready()}


def gpu_install(run):
    if run.args.resume:
        run.require('gpuInstalled')
        return gpu_installed_checks(run)
    run.require('ui')
    if (getattr(run.args, 'journeys', None) == ['J1', 'J3', 'J9']
            and 'folder' not in run.facts):
        run.evidence('J3-small-corpus-prerequisite', linux.add_folder_api(run))
    run.require('folder')
    run.nav('AI Brain')
    preview = run.api('/api/ai/install/plan-preview')
    run.evidence('J3-default-plan', preview)
    run.evidence('J3-production-manifest', run.api('/api/ai/install/manifest'))
    require_gpu_plan(preview)
    # BrainSurface.confirmInstall -> OperationClient.invoke (MEDIUM risk).
    # UI installation/consent is qualified in the CPU VMs; this lane covers GPU use.
    request = {'method': 'POST', 'endpoint': '/api/operations/core.start-ai-install/invoke',
               'body': {'args': {'acceptTerms': True}},
               'source': 'modules/ui-web/src/shell-v0/views/BrainSurface.ts confirmInstall'}
    run.evidence('J3-install-request', request)
    response = run.api(request['endpoint'], request['body'])
    run.evidence('J3-install-response', response)
    if response.get('success') is not True:
        raise ProductFailure('GPU AI install operation rejected: ' + json.dumps(response))
    samples = []
    def settled():
        status = run.api('/api/ai/install/status')
        samples.append(status)
        run.evidence('J3-install-observations', samples)
        if status.get('state') in ('failed', 'error', 'cancelled'):
            raw = json.dumps(status)
            if re.search(r'time.?out|connection reset|resolve host|HTTP (429|50[23])', raw, re.I):
                raise Infrastructure('GPU AI download failed: ' + raw)
            raise ProductFailure('GPU AI install failed: ' + raw)
        return status if status.get('phase') == 'activation_required' or status.get('state') == 'completed' else None
    wait_for(settled, 'Default GPU AI download', seconds=1800, interval=5)
    linux.activate_downloaded(run)
    def activated():
        install = settled()
        runtime = run.api('/api/ai/runtime/status')
        run.evidence('J3-activation-runtime', runtime)
        operations = run.wd.execute("return all(document,'jf-operation-outcome-detail').map(e=>e.operationKey).filter(Boolean)")
        for key in operations:
            operation = run.api('/api/operation-history/' + key)
            if operation.get('state') in ('failed', 'cancelled'):
                raise ProductFailure('GPU activation failed: ' + json.dumps(operation))
        if install and install.get('installedFully'):
            require_gpu_install(install)
            return install
    installed = wait_for(activated, 'Default GPU AI activation', seconds=900, interval=5)
    run.facts['gpuInstalled'] = installed
    run.activation_checkpoint()
    return gpu_installed_checks(run, preview)


def gpu_installed_checks(run, preview=None):
    installed = run.require('gpuInstalled')
    if run.facts.get('chatStart', {}).get('acknowledged'):
        wait_chat_ready(run, 'J3')
    else:
        start_chat(run)
    run.activation_checkpoint()
    retrieval = linux.semantic_search(run)
    runtime = run.api('/api/ai/runtime/status')
    run.evidence('J3-gpu-runtime', runtime)
    require_gpu_encoders(runtime)
    answer = chat(run, 'J3')
    run.facts['ai'] = installed
    return {'installed': installed, 'preview': preview, 'runtime': runtime, 'retrieval': retrieval, 'chat': answer}


def chat_runtime_ready(status, runtime):
    active = runtime.get('active') or {}
    inference = status.get('inference') or {}
    return (inference.get('engineState') == 'Healthy' and inference.get('chatEnabledSpec') is True
            and bool(active.get('modelPath')))


def wait_chat_ready(run, label):
    proof = run.facts.get('chatStart', {})
    if proof.get('invoked') is not True or proof.get('acknowledged') is not True:
        raise Infrastructure('Start AI action was not invoked and acknowledged; product chat failure cannot be established')
    samples = []
    def probe():
        status = run.api('/api/status')
        runtime = run.api('/api/ai/runtime/status')
        samples.append({'inference': status.get('inference'), 'runtime': runtime})
        run.evidence(label + '-chat-start-observations', samples)
        if runtime.get('activation', {}).get('state') == 'failed':
            raise ProductFailure('GPU runtime activation failed: ' + json.dumps(runtime))
        inference = status.get('inference') or {}
        if inference.get('engineState') == 'Down' and inference.get('lastFailure'):
            raise ProductFailure('Chat runtime failed to start: ' + json.dumps(inference))
        return runtime if chat_runtime_ready(status, runtime) else None
    return wait_for(probe, 'Realized chat runtime healthy and model loaded', seconds=240, interval=3)


def start_chat(run):
    # Model promotion and persisted chat intent are separate product actions.
    # The CPU adapter has no chat action, so it cannot supply this GPU step.
    run.nav('AI Brain')
    run.facts['chatStart'] = {'invoked': False, 'acknowledged': False}
    run.wd.click('button[aria-label="Start AI"]')
    run.facts['chatStart']['invoked'] = True
    def acknowledged():
        status = run.api('/api/status')
        run.facts['chatStart']['acknowledged'] = (status.get('inference') or {}).get('chatEnabledSpec') is True
        run.evidence('J3-start-ai-action', {'control': 'Start AI', **run.facts['chatStart'],
                                          'inference': status.get('inference')})
        return run.facts['chatStart']['acknowledged']
    wait_for(acknowledged, 'Start AI intent acknowledgment', seconds=30, error=Infrastructure)
    return wait_chat_ready(run, 'J3')


def chat(run, label):
    run.require('gpuInstalled')
    wait_chat_ready(run, label)
    # Execute the normal app-origin fetch in this harness's own WebDriver. This
    # preserves the production session/origin checks and consumes its real SSE.
    script = """
window.__qualChat={pending:true};
(async()=>{try {
 const credential=await window.__TAURI_INTERNALS__.invoke('session_token');
 if(!credential)throw new Error('No production shell session credential');
 const response=await fetch(credential.origin+'/api/chat/dispatch',{method:'POST',headers:{'Content-Type':'application/json','X-JustSearch-Session':credential.token},
 body:JSON.stringify({shapeId:'core.rag-ask',question:'What colour is the telescope mentioned in the inspection message?',allowLoad:true})});
 const text=await response.text();window.__qualChat={status:response.status,text};
}catch(e){window.__qualChat={error:String(e)}}})();return true;
"""
    run.wd.execute(script)
    def completed():
        result = run.wd.execute('return window.__qualChat')
        run.evidence(label + '-chat-response', result)
        return result if result and not result.get('pending') else None
    response = wait_for(completed, 'GPU answer with sources', seconds=300, interval=2)
    if response.get('status') != 200:
        raise ProductFailure('Production chat request failed: ' + json.dumps(response))
    events = []
    for block in response['text'].replace('\r\n', '\n').split('\n\n'):
        lines = block.splitlines()
        name = next((line[6:].strip() for line in lines if line.startswith('event:')), '')
        data = '\n'.join(line[5:].strip() for line in lines if line.startswith('data:'))
        if name and data:
            events.append((name, json.loads(data)))
    result = require_chat_answer(events)
    if 'turquoise' not in result['answer'].casefold() or not any('message.eml' in json.dumps(c) for c in result['citations']):
        raise ProductFailure('Chat answer/citations do not identify the indexed inspection message: ' + json.dumps(result))
    runtime = run.api('/api/ai/runtime/status')
    run.evidence(label + '-chat-runtime', runtime)
    active = runtime.get('active', {})
    expected = Path(run.data) / 'native-bin/llama-server/variants/cuda12/llama-server'
    if active.get('activeVariantId') != 'cuda12' or Path(active.get('serverExecutablePath', '')).resolve() != expected.resolve():
        raise ProductFailure('Chat did not use candidate-installed CUDA llama-server: ' + json.dumps(active))
    # Actual loaded CUDA maps plus llama offload log, not requested gpuLayers.
    processes = run.target.owned()
    engine = json.loads(Path(run.data, 'runtime/manifest.json').read_text())
    roles = {'engine': [p for p in processes if p['pid'] == engine['pid']],
             'llama': [p for p in processes if p['exe'] == str(expected)]}
    libraries = {}
    for role, rows in roles.items():
        if len(rows) != 1 or not run.target.alive(rows):
            raise ProductFailure('No unique run-owned live ' + role + ' process')
        row = rows[0]
        if role == 'engine':
            java = Path(run.require('engineWorkingDirectories')[0]) / 'runtime/bin/java'
            if row['exe'] != str(java.resolve()) or not java.resolve().is_relative_to(Path(run.work).resolve()):
                raise ProductFailure('Engine did not execute the candidate bundled JVM')
        raw = Path('/proc', str(row['pid']), 'maps').read_text()
        (run.out / (label + '-' + role + '-maps.txt')).write_text(raw)
        libraries[role] = cuda_libraries(raw, expected.parent)
    require_cuda_maps(libraries)
    run.evidence(label + '-cuda-library-ownership', {'processes': roles, 'libraries': libraries})
    logs = []
    for path in Path(run.data).rglob('*.log'):
        if path.stat().st_size <= 20_000_000:
            for line in path.read_text(errors='replace').splitlines():
                if re.search(r'offloaded [1-9][0-9]*/[0-9]+ layers to GPU', line):
                    logs.append({'file': str(path), 'line': line})
    run.evidence(label + '-gpu-offload', logs)
    if not logs:
        raise ProductFailure('No realized llama GPU layer offload found in candidate logs')
    return result


def restart(run):
    run.require('ai')
    state = linux.restart(run)
    return {'state': state, 'chat': chat(run, 'J4')}


def author_state(run):
    run.require('ai')
    if run.args.resume and run.facts.get('authored'):
        # A development recovery must preserve the already-authored record,
        # rather than trying to press a bookmark control that is now a toggle.
        state = linux.assert_state(run)
        if run.require('bookmark') not in linux.stored_bookmarks(run):
            raise ProductFailure('Previously authored bookmark missing from Web Storage on recovery')
        run.evidence('J4-restored-authored-state', {'checkpoint': str(run.args.resume),
                                                  'bookmark': run.facts['bookmark']})
        return state
    state = linux.author_state(run)
    run.activation_checkpoint()
    return state


def search(run):
    run.require('folder')
    run.require('ui')
    return linux.search_api(run)


def soak(run):
    decision = run.require('soakDecision')
    if not decision['run']:
        raise runner.SkippedByDecision(decision['reason'])
    run.require('ai')
    run.require('folder')
    if not run.args.soak_corpus:
        raise Unavailable('J9 needs --soak-corpus, generated once using corpus.py --soak')
    from corpus import verify_soak
    manifest = verify_soak(run.args.soak_corpus)
    run.evidence('J9-corpus', manifest)
    # Marker survives an interrupted attempt: a development invocation never
    # starts a second hour of soak behind the owner's back.
    started_marker = Path(os.environ['QUAL_GPU_SOAK_MARKER'])
    try:
        with started_marker.open('x') as stream:
            stream.write(run.run_id + '\n')
    except FileExistsError:
        raise Unavailable('J9 already started in this development round; no second soak')
    root = str(run.args.soak_corpus.resolve())
    run.api('/api/indexing/roots', {'path': root, 'collection': 'default'})
    tracker = SoakProgress(time.monotonic())
    gpu_tracker = SoakGpu()
    started_epoch = time.time()
    samples = []
    try:
        while True:
            status = run.api('/api/status')
            manifest = json.loads(Path(run.data, 'runtime/manifest.json').read_text())
            core = status.get('worker', {}).get('core', {})
            enrichment = status.get('worker', {}).get('enrichment', {})
            if type(core.get('indexedDocuments')) is not int:
                raise Infrastructure('J9 progress counters unavailable')
            progress = core['indexedDocuments'] + sum(enrichment.get(key, 0) for key in
                ('embeddingCompletedCount', 'spladeCompletedCount', 'completedNerCount'))
            runtime = run.api('/api/ai/runtime/status')
            epoch = time.time()
            worker = worker_cuda(run, manifest['pid'])
            maps_file = f'J9-worker-maps-{len(samples):03d}.txt'
            (run.out / maps_file).write_text(worker.pop('maps'))
            worker['mapsEvidence'] = maps_file
            sample = {'seconds': time.monotonic() - tracker.started, 'epochSeconds': epoch,
                      'status': status, 'runtime': runtime, 'workerCuda': worker,
                      'identity': [manifest['pid'], manifest['instanceId']], 'progress': progress}
            samples.append(sample)
            run.evidence('J9-observations', samples)
            tracker.observe(time.monotonic(), progress, status.get('memoryUsedBytes', 0),
                            status.get('memoryMaxBytes', 0), sample['identity'])
            gpu_tracker.observe(sample)
            if time.monotonic() - tracker.started >= 3600:
                return tracker.complete(time.monotonic())
            time.sleep(30)
    finally:
        diagnostics = soak_logs(Path(run.data, 'logs/engine.log'), started_epoch, time.time(),
                                gpu_tracker.observations)
        diagnostics.update(confirmedGpuChecks=gpu_tracker.confirmed_checks,
                           gpuObservationCount=len(gpu_tracker.observations),
                           observationMeaning='Runtime/worker mismatches are observations, not passed GPU checks; fallback requires consecutive CPU samples >=30s apart and absent worker compute memory and CUDA maps in both samples')
        run.facts['soak'] = diagnostics
        run.evidence('J9-gpu-and-backfill-summary', diagnostics)



CHECKS = {**linux.CHECKS, 'install': extract_package, 'first-run': first_run, 'author-state': author_state,
          'gpu-search': search, 'gpu-ai': gpu_install, 'restart': restart, 'soak': soak}
