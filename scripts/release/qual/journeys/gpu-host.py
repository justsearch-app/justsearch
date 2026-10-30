#!/usr/bin/env python3
"""Host command for the gpu-linux adapter; private Xvfb/bus, guard and cleanup."""
import argparse
import datetime
import json
import os
import pwd
from pathlib import Path
import ctypes
import contextlib
import secrets
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time

import gpu
import runner

PRIVATE_ROOTS = Path('/home/elias/dev/justsearch-qual')
BUDGET = 30_000_000_000


def mount_device(root):
    device = subprocess.check_output(['findmnt', '-n', '-o', 'MAJ:MIN', '-T', str(root)], text=True).strip()
    gpu.device_writes(device)  # Refuse unsupported/ambiguous mounts before launch.
    return device


@contextlib.contextmanager
def private_root(resume=None):
    if resume:
        root = resume.resolve()
        if (root.parent != PRIVATE_ROOTS or not root.name.startswith('w4c-gpu-')
                or not (root / 'activated-checkpoint.json').is_file()):
            raise runner.Infrastructure('Resume requires an activated lane root directly under ' + str(PRIVATE_ROOTS))
        (root / 'keep-state').unlink(missing_ok=True)
    else:
        root = Path(tempfile.mkdtemp(prefix='w4c-gpu-', dir=PRIVATE_ROOTS))
    try:
        yield root
    finally:
        if not (root / 'keep-state').exists():
            shutil.rmtree(root)


def desktop_proof(env, *, tmp=Path('/tmp'), runtime=None):
    runtime = runtime or Path('/run/user') / str(os.getuid())
    display = env.get('DISPLAY', '')
    sockets = sorted(p.name for p in (tmp / '.X11-unix').iterdir())
    expected = 'X' + display.removeprefix(':').split('.')[0]
    forbidden = sorted(str(p.relative_to(runtime)) for p in runtime.rglob('*')
                       if p.name.startswith('wayland-') or p.name in ('bus', 'pulse'))
    bus = env.get('DBUS_SESSION_BUS_ADDRESS', '')
    bus_path = bus.removeprefix('unix:path=').split(',', 1)[0]
    valid = (display.startswith(':') and sockets == [expected] and not forbidden
             and bus.startswith('unix:path=/tmp/') and Path(bus_path).is_socket()
             and not env.get('WAYLAND_DISPLAY'))
    return {'display': display, 'sessionBus': bus, 'xSockets': sockets,
            'ownerSessionSockets': forbidden, 'ownerSocketsAbsent': valid,
            'proof': 'Private /tmp and /run/user mounts; only lane Xvfb socket and lane session bus reachable'}


def processes(marker, *, proc=Path('/proc'), owned=()):
    rows = []
    needle = ('QUAL_GPU_SESSION=' + marker).encode()
    for path in proc.glob('[0-9]*/environ'):
        identity = None
        try:
            fields = path.with_name('stat').read_text().rsplit(') ', 1)[1].split()
            identity = (int(path.parent.name), fields[19])
            if needle not in path.read_bytes().split(b'\0'):
                continue
            if fields[0] == 'Z':
                continue
            env = dict(item.split(b'=', 1) for item in path.read_bytes().split(b'\0') if b'=' in item)
            rows.append({'pid': int(path.parent.name), 'ticks': fields[19], 'ppid': int(fields[1]),
                         'exe': str(path.with_name('exe').resolve()),
                         'xvfbArgs': path.with_name('cmdline').read_bytes().decode().strip('\0').split('\0') if Path(path.with_name('exe').resolve()).name == 'Xvfb' else [],
                         'display': env.get(b'DISPLAY', b'').decode(),
                         'wayland': env.get(b'WAYLAND_DISPLAY', b'').decode(),
                         'sessionBus': env.get(b'DBUS_SESSION_BUS_ADDRESS', b'').decode()})
        except PermissionError as exc:
            # Enumeration is diagnostic; desktop isolation uses socket/mount
            # structure, and exact product ownership is checked by the runner.
            continue
        except (OSError, IndexError, ValueError):
            continue
    return rows


def owner_tree(root):
    """Only lstat metadata, never contents or symlink targets in owner state."""
    rows = []
    def visit(path):
        info = path.lstat()
        rows.append({'path': str(path.relative_to(root)), 'mtimeNs': info.st_mtime_ns,
                     'size': info.st_size, 'mode': info.st_mode})
        if stat.S_ISDIR(info.st_mode):
            for child in sorted(path.iterdir()):
                visit(child)
    if root.exists() or root.is_symlink():
        visit(root)
    return rows


def owner_metadata(home):
    roots = [home / '.justsearch']
    for parent in ('.local/share', '.config', '.cache'):
        roots.extend(sorted((home / parent).glob('io.justsearch*')))
    return [{'root': str(root), 'entries': owner_tree(root)} for root in roots]


def assert_home_bindings(home, bindings):
    bindings = {Path(p) for p in bindings if Path(p).is_relative_to(home)}
    def visit(path):
        if path in bindings:
            return
        if not any(p.is_relative_to(path) for p in bindings):
            raise runner.Infrastructure('Sandbox home contains unexpected path: ' + str(path))
        for child in path.iterdir():
            visit(child)
    visit(home)
    return True


def sandbox_command(root, evidence, readonly, command):
    home = Path(pwd.getpwuid(os.getuid()).pw_dir)
    # Recovery uses a new evidence directory. Retire only the old empty mount
    # placeholders, so the sealed home describes this invocation's bindings.
    if (root / 'empty-home').exists():
        shutil.rmtree(root / 'empty-home')
    # Mount destinations must exist before the otherwise empty home is sealed.
    for path in [root, evidence, *readonly]:
        path = Path(path)
        if path.is_relative_to(home):
            target = root / 'empty-home' / path.relative_to(home)
            if path.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.touch()
    # Hide passwd-derived user.home as well as HOME, all desktop sockets and
    # the host /proc view. Only explicit lane inputs are restored read-only.
    args = ['/usr/bin/bwrap', '--die-with-parent', '--unshare-pid',
            '--dev-bind', '/', '/', '--ro-bind', str(root / 'empty-home'), str(home),
            '--tmpfs', '/tmp', '--tmpfs', '/run/user/' + str(os.getuid()),
            '--dev-bind', '/dev', '/dev', '--proc', '/proc',
            '--bind', str(root), str(root), '--bind', str(evidence), str(evidence)]
    for path in dict.fromkeys(map(Path, readonly)):
        args += ['--ro-bind', str(path), str(path)]
    return args + ['--chdir', str(runner.HERE.parents[3]), '--', *command]


def descendants(root_identity, known):
    """Host identities for the external guard; no host environ/maps reads."""
    rows = []
    for path in Path('/proc').glob('[0-9]*/stat'):
        try:
            text = path.read_text()
            fields = text.rsplit(') ', 1)[1].split()
            if fields[0] == 'Z':
                continue
            rows.append({'pid': int(path.parent.name), 'ppid': int(fields[1]), 'ticks': fields[19],
                         'comm': text.split('(', 1)[1].rsplit(')', 1)[0]})
        except (OSError, IndexError, ValueError):
            continue
    owned = {r['pid'] for r in rows if (r['pid'], r['ticks']) == root_identity or (r['pid'], r['ticks']) in known}
    while True:
        more = owned | {r['pid'] for r in rows if r['ppid'] in owned}
        if more == owned:
            return [r for r in rows if r['pid'] in owned]
        owned = more


def sandbox_session(forwarded):
    root = Path(os.environ['QUAL_GPU_ROOT'])
    out = Path(os.environ['QUAL_GPU_EVIDENCE'])
    bindings = json.loads((root / 'bindings.json').read_text())
    home = Path(pwd.getpwuid(os.getuid()).pw_dir)
    proof = {'homeBindings': bindings, 'homeContainsOnlyBindings': assert_home_bindings(home, bindings),
             'pidNamespace': os.readlink('/proc/self/ns/pid'),
             'candidateReadOnly': bool(os.statvfs(root / 'work/package').f_flag & os.ST_RDONLY)}
    save(out / 'sandbox.json', proof)
    if not proof['candidateReadOnly']:
        raise runner.Infrastructure('Extracted candidate is writable')
    marker = os.environ['QUAL_GPU_SESSION']
    observed = {}
    mirror = None
    child = None
    def rows():
        return [r for r in processes(marker, owned=observed) if r['pid'] not in (os.getpid(), 1)]
    try:
        if (root / 'mirror').exists():
            mirror = subprocess.Popen([sys.executable, '-m', 'http.server', os.environ['QUAL_GPU_MIRROR_PORT'],
                '--bind', '127.0.0.1', '--directory', str(root / 'mirror')],
                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            time.sleep(.3)
            if mirror.poll() is not None:
                raise runner.Infrastructure('Development mirror could not bind its private loopback port')
        child = subprocess.Popen(['xvfb-run', '-a', '-s', '-screen 0 1440x1000x24 -nolisten tcp',
            'dbus-run-session', '--', sys.executable, str(Path(__file__).resolve()), '--session', *forwarded])
        while child.poll() is None:
            observed.update({(p['pid'], p['ticks']): p for p in rows()})
            if (root / 'stop-reason').exists():
                for row in rows():
                    try:
                        if b'runner.py' in Path('/proc', str(row['pid']), 'cmdline').read_bytes() and runner.Target().alive([row]):
                            os.kill(row['pid'], signal.SIGTERM)
                    except ProcessLookupError:
                        pass
                runner.Target().stop([r for r in rows() if Path(r['exe']).name in ('JustSearch', 'java', 'llama-server')])
                break
            time.sleep(.2)
        return child.wait(timeout=10)
    finally:
        observed.update({(p['pid'], p['ticks']): p for p in rows()})
        runner.Target().stop(rows())
        if child:
            child.wait(timeout=15)
        if mirror:
            mirror.wait(timeout=15)
        save(out / 'namespace-processes.json', list(observed.values()))
        proof = json.loads((out / 'sandbox.json').read_text())
        proof['homeContainsOnlyBindingsAfter'] = assert_home_bindings(home, bindings)
        proof['processesRemaining'] = rows()
        save(out / 'sandbox.json', proof)


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def add_check(report, name, status, reason, evidence):
    report['journeys'].append({'id': 'gpu-host', 'checks': [
        {'name': name, 'status': status, 'reason': reason, 'evidence': evidence}]})
    report.update(runner.summarize(report['journeys'], report.get('fullSelection', False)))


def collect_crashes(out, since):
    report = json.loads((out / 'summary.json').read_text())
    installed = [str(p) for p in Path(os.environ['QUAL_GPU_ROOT'], 'work/package').rglob('*') if p.is_file()]
    try:
        proc = subprocess.run(['coredumpctl', '--json=short', '--no-pager', '--since', since, 'list'],
                              capture_output=True, text=True, timeout=30)
        (out / 'coredumpctl.stderr.txt').write_text(proc.stderr)
        if proc.returncode and not (proc.returncode == 1 and proc.stderr.strip() == 'No coredumps found.' and proc.stdout.strip() in ('', '[]')):
            raise runner.Infrastructure('coredumpctl collection failed: ' + proc.stderr)
        entries = json.loads(proc.stdout) if proc.stdout.strip() else []
        rows = [{'fields': {key: str(entry.get(journal, entry.get(short, ''))) for key, journal, short in (
            ('ExecutablePath', 'COREDUMP_EXE', 'exe'), ('Signal', 'COREDUMP_SIGNAL', 'sig'),
            ('Package', 'COREDUMP_PACKAGE_NAME', 'package'), ('Date', 'COREDUMP_TIMESTAMP', 'time'),
            ('ProcStatus', 'COREDUMP_PROC_STATUS', 'status'), ('ProcStat', 'COREDUMP_PROC_STAT', 'stat'))}} for entry in entries]
        observed = out / 'app-processes.json'
        runner.attribute_crashes(rows, set(installed), json.loads(observed.read_text()) if observed.exists() else [])
        save(out / 'gpu-crashes.json', rows)
        for attribution, status in (('product', 'failed-product'), ('unattributed', 'owner-validation')):
            found = [row for row in rows if row['attribution'] == attribution]
            if found:
                add_check(report, attribution + ' crashes', status, json.dumps(found), ['gpu-crashes.json'])
    except FileNotFoundError:
        save(out / 'gpu-crashes.json', {'unavailable': 'coredumpctl is not installed; no host packages installed'})
    except Exception as exc:
        add_check(report, 'crash collection', 'failed-infrastructure', str(exc), ['coredumpctl.stderr.txt'])
    # As in hosted.py, successful journal reading alone does not prove that all
    # native crash sources were armed. Keep the explicit owner-validation gate.
    add_check(report, 'GPU crash collection unproven', 'owner-validation',
              'Native crash collection is not proven armed and complete on this host; owner validation required',
              ['gpu-crashes.json'])
    save(out / 'summary.json', report)


def session(forwarded):
    root = Path(os.environ['QUAL_GPU_ROOT'])
    out = Path(os.environ['QUAL_GPU_EVIDENCE'])
    env = os.environ.copy()
    proof = json.loads((out / 'sandbox.json').read_text())
    proof['desktop'] = desktop_proof(env)
    save(out / 'sandbox.json', proof)  # Before any candidate/runner process.
    if not proof['desktop']['ownerSocketsAbsent']:
        raise runner.Infrastructure('Private desktop socket proof failed')
    names = ('DISPLAY', 'XAUTHORITY', 'XDG_RUNTIME_DIR', 'XDG_DATA_HOME', 'XDG_CONFIG_HOME',
             'XDG_CACHE_HOME', 'XDG_CURRENT_DESKTOP', 'HOME', 'PATH')
    subprocess.run(['dbus-update-activation-environment', *[k for k in names if k in env]], check=True)
    with (root / 'openbox.log').open('wb') as log:
        wm = subprocess.Popen(['openbox'], stdout=log, stderr=log)
        since = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')
        try:
            # Affinity is inherited by the shell, Engine and native inference;
            # JVM ergonomics sees at most eight CPUs without Java overrides.
            cpus = sorted(os.sched_getaffinity(0))[:8]
            command = ['taskset', '--cpu-list', ','.join(map(str, cpus)), sys.executable,
                       str(runner.HERE / 'runner.py'), '--lane', 'gpu-linux', *forwarded]
            result = subprocess.run(command)
            if (out / 'summary.json').exists():
                collect_crashes(out, since)
                return runner.report_exit_code(json.loads((out / 'summary.json').read_text()))
            return result.returncode or 1
        finally:
            if wm.poll() is None:
                wm.terminate()
                try:
                    wm.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    wm.kill()
                    wm.wait(timeout=5)


def run_attempt(args, forwarded, evidence, attempt, soak_marker):
    marker = 'w4c-' + secrets.token_hex(12)
    samples, process_rows = [], {}
    failure = None
    child = None
    child_identity = None
    with private_root(args.development_resume_root) as root:
        save(root / 'write-start.json', args.write_start)
        env = gpu.private_environment(os.environ, root, marker, resume=bool(args.development_resume_root))
        env.update(QUAL_GPU_ROOT=str(root), QUAL_GPU_EVIDENCE=str(evidence),
                   QUAL_GPU_SOAK_MARKER=str(soak_marker), QUAL_GPU_BLOCK_DEVICE=args.write_start['device'])
        if args.development_resume_root:
            forwarded = [*forwarded, '--resume', str(root / 'activated-checkpoint.json')]
        for name in ('Xvfb', 'xvfb-run', 'dbus-run-session', 'openbox', 'taskset', 'xdotool', 'scrot', 'WebKitWebDriver'):
            if not shutil.which(name, path=env['PATH']):
                raise runner.Infrastructure('Missing host qualification tool (no host installs allowed): ' + name)
        driver = args.driver
        if not (Path(driver).is_file() if '/' in driver else shutil.which(driver, path=env['PATH'])):
            raise runner.Infrastructure('Missing tauri-driver; provide --driver /path/to/tauri-driver-2.0.4')
        sample = gpu.resource_sample(starting=True)
        samples.append(sample)
        if not sample['available']:
            report = runner.preparation_failure('gpu-linux', gpu.INTERRUPTED)
            evidence.mkdir()
            save(evidence / 'resources.json', samples)
            save(evidence / 'summary.json', report)
            return report, True
        (root / 'guard-armed').write_text(marker)
        evidence.mkdir(exist_ok=True)
        try:
            if args.development_pack_mirror and not args.development_resume_root:
                # Python's server binds only loopback. Serve only named registry
                # ZIPs hardlinked into this run's otherwise empty directory.
                mirror_root = root / 'mirror'
                mirror_root.mkdir()
                for path in args.development_pack_mirror.iterdir():
                    if path.name.endswith('.zip') and path.is_file() and not path.is_symlink():
                        os.link(path, mirror_root / path.name)
            (root / 'empty-home').mkdir(exist_ok=True)
            package = root / 'work/package'
            if not args.development_resume_root:
                package.mkdir(parents=True)
                subprocess.run(['dpkg-deb', '-x', str(args.assets / args.package), str(package)], check=True, env=env)
            readonly = [runner.HERE.parents[3], args.source, args.assets, args.soak_corpus, package]
            if args.development_pack_mirror:
                readonly += [args.development_pack_mirror, root / 'mirror']
            # A linked worktree needs its Git common directory for the J9 diff.
            common = subprocess.check_output(['git', '-C', str(args.source), 'rev-parse', '--git-common-dir'], text=True).strip()
            readonly.append((args.source / common).resolve())
            # Preserve extracted harness tool wrappers and their adjacent data;
            # also restore resolved symlink targets such as tauri-driver.
            for link in (root / 'bin').iterdir():
                executable = Path(os.readlink(link))
                if str(executable).startswith(('/home/', '/tmp/')):
                    readonly.append(executable.parent.parent if executable.parent.name == 'bin' else executable.parent)
                    readonly.append(executable.resolve())
            driver_path = Path(shutil.which(args.driver, path=env['PATH']) or args.driver).absolute()
            readonly += [driver_path.parent, driver_path.resolve()]
            readonly = list(dict.fromkeys(p.absolute() for p in readonly if p is not None))
            save(root / 'bindings.json', list(map(str, [*readonly, root, evidence.parent])))
            env.update(QUAL_GPU_EXTRACTED=str(package), QUAL_GPU_MIRROR_PORT=str(args.development_mirror_port))
            command = sandbox_command(root, evidence.parent, readonly,
                [sys.executable, str(Path(__file__).resolve()), '--sandbox', *forwarded])
            save(evidence / 'sandbox-command.json', command)
            with (root / 'session.log').open('wb') as log:
                child = subprocess.Popen(command, env=env, stdout=log, stderr=log, start_new_session=True)
                child_identity = (child.pid, Path('/proc', str(child.pid), 'stat').read_text().rsplit(') ', 1)[1].split()[19])
                next_check = time.monotonic() + 30
                deadline = time.monotonic() + 3 * 3600
                while child.poll() is None:
                    rows = descendants(child_identity, process_rows)
                    for row in rows:
                        process_rows[(row['pid'], row['ticks'])] = row
                    current_writes = gpu.device_writes(args.write_start['device']) - args.write_start['startBytes']
                    if current_writes >= args.write_budget_bytes - 512_000_000:
                        failure = f'Device write upper bound approached ({args.write_budget_bytes} bytes; 512 MB reserved for cleanup)'
                        save(evidence / 'write-budget-stop.json', {'observedBytes': current_writes, 'budgetBytes': args.write_budget_bytes})
                        break
                    if time.monotonic() >= next_check:
                        sample = gpu.resource_sample({r['pid'] for r in rows})
                        samples.append(sample)
                        if not sample['available']:
                            failure = gpu.INTERRUPTED
                            break
                        next_check = time.monotonic() + 30
                    if time.monotonic() >= deadline:
                        failure = 'GPU lane stalled: three-hour session deadline exceeded'
                        break
                    time.sleep(1)
        except BaseException as exc:
            failure = str(exc) or type(exc).__name__
        finally:
            # Every signal checks the recorded /proc start identity through the
            # shared Target.stop; unrelated processes are never eligible.
            rows = descendants(child_identity, process_rows) if child else []
            process_rows.update({(p['pid'], p['ticks']): p for p in rows})
            if failure:
                (root / 'stop-reason').write_text(failure)
                # Stop namespace children immediately using host PID/start
                # identities; leave the namespace reporter briefly to flush.
                app_rows = [row for row in rows if row['comm'] in ('JustSearch', 'java', 'llama-server')]
                runner.Target().stop(app_rows)
                until = time.monotonic() + 5
                while time.monotonic() < until and child and child.poll() is None:
                    time.sleep(.1)
            runner.Target().stop(descendants(child_identity, process_rows) if child else [])
            if child:
                child.wait(timeout=15)
            evidence.mkdir(exist_ok=True)
            shutil.copy2(root / 'session.log', evidence / 'session.log') if (root / 'session.log').exists() else None
            shutil.copy2(root / 'openbox.log', evidence / 'openbox.log') if (root / 'openbox.log').exists() else None
            save(evidence / 'resources.json', samples)
            save(evidence / 'session-processes.json', list(process_rows.values()))
            summary = evidence / 'summary.json'
            report = json.loads(summary.read_text()) if summary.exists() else runner.preparation_failure('gpu-linux', failure or 'Private session exited without a summary')
            if failure:
                add_check(report, 'resource interruption' if failure == gpu.INTERRUPTED else 'session failure',
                          'failed-infrastructure', failure, ['resources.json', 'session.log'])
            save(summary, report)
        remaining = descendants(child_identity, process_rows) if child else []
        namespace_file = evidence / 'namespace-processes.json'
        namespace_rows = json.loads(namespace_file.read_text()) if namespace_file.exists() else []
        sandbox_file = evidence / 'sandbox.json'
        sandbox_proof = json.loads(sandbox_file.read_text()) if sandbox_file.exists() else {}
        sandbox_proof['outsidePidNamespace'] = os.readlink('/proc/self/ns/pid')
        sandbox_proof['privatePidNamespace'] = bool(sandbox_proof.get('pidNamespace')) and sandbox_proof['pidNamespace'] != sandbox_proof['outsidePidNamespace']
        save(sandbox_file, sandbox_proof)
        isolated = sandbox_proof.get('desktop', {}).get('ownerSocketsAbsent', False)
        isolated = isolated and all(sandbox_proof.get(k) for k in ('homeContainsOnlyBindings', 'homeContainsOnlyBindingsAfter', 'candidateReadOnly'))
        isolated = isolated and sandbox_proof['privatePidNamespace'] and not sandbox_proof.get('processesRemaining', ['unproven'])
        save(evidence / 'isolation.json', {'privateRoot': str(root), 'processesRemaining': remaining,
             'ownerVisibleWindowExcluded': isolated, 'desktop': sandbox_proof.get('desktop'),
             'proof': 'Pre-candidate private socket/mount proof plus private PID namespace and final cleanup'})
        if remaining or not isolated:
            add_check(report, 'session isolation', 'failed-infrastructure',
                      'Could not prove private display/bus ownership and complete process cleanup', ['isolation.json'])
        else:
            add_check(report, 'session isolation', 'passed', 'Private home/mount/PID namespaces and Xvfb/bus; no remaining run-owned process', ['isolation.json', 'sandbox.json', 'namespace-processes.json', 'session-processes.json'])
        size = int(subprocess.check_output(['du', '-s', '-B1', str(root)], text=True).split()[0])
        save(evidence / 'private-root-size.json', {'path': str(root), 'allocatedBytes': size,
             'method': 'du -s -B1 before removal; allocated size, not cumulative writes'})
        statuses = [c['status'] for j in report['journeys'] for c in j['checks']]
        keep = ('failed-infrastructure' in statuses and 'failed-product' not in statuses
                and not failure and (root / 'activated-checkpoint.json').exists())
        if keep:
            (root / 'keep-state').touch()
        report['retainedPrivateRoot'] = str(root) if keep else None
    report['execution'] = {**report.get('execution', {}), 'releaseQualification': not bool(args.development_pack_mirror or args.journeys or args.development_resume_root),
                           'developmentPackMirror': str(args.development_pack_mirror) if args.development_pack_mirror else None}
    report['privateStateRemoved'] = not root.exists()
    if root.exists() and not report['retainedPrivateRoot']:
        add_check(report, 'private state cleanup', 'failed-infrastructure', 'Private state was not removed', ['isolation.json'])
    save(evidence / 'summary.json', report)
    return report, failure == gpu.INTERRUPTED


def main():
    if '--sandbox' in sys.argv:
        return sandbox_session(sys.argv[sys.argv.index('--sandbox') + 1:])
    if '--session' in sys.argv:
        return session(sys.argv[sys.argv.index('--session') + 1:])
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--assets', required=True, type=Path)
    parser.add_argument('--package', required=True)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--soak-corpus', required=True, type=Path)
    parser.add_argument('--driver', default='tauri-driver')
    parser.add_argument('--development-pack-mirror', type=Path)
    parser.add_argument('--development-mirror-port', type=int, default=8769)
    parser.add_argument('--journeys', nargs='+')
    parser.add_argument('--write-budget-bytes', type=int, default=BUDGET,
                        help='Whole-device write upper bound; at most 30 GB, with 512 MB reserved for cleanup')
    parser.add_argument('--development-resume-root', type=Path,
                        help='Development only: retained activated private root; no downloads are repeated')
    args, forwarded = parser.parse_known_args()
    if not 512_000_000 < args.write_budget_bytes <= BUDGET:
        parser.error('--write-budget-bytes must exceed the 512 MB cleanup reserve and be at most 30 GB')
    if not 1 <= args.development_mirror_port <= 65535:
        parser.error('--development-mirror-port must be a TCP port in 1..65535')
    out = args.evidence.resolve()
    out.mkdir(parents=True, exist_ok=False)
    if args.development_resume_root:
        args.write_start = json.loads((args.development_resume_root / 'write-start.json').read_text())
        if not args.journeys or set(args.journeys) != {'J3', 'J4', 'J9'}:
            parser.error('Development recovery requires --journeys J3 J4 J9')
    else:
        device = mount_device(PRIVATE_ROOTS)
        args.write_start = {'device': device, 'startBytes': gpu.device_writes(device)}
    save(out / 'write-start.json', args.write_start)
    args.assets = args.assets.resolve()
    args.source = args.source.resolve()
    args.soak_corpus = args.soak_corpus.resolve()
    forwarded += ['--driver', args.driver, '--assets', str(args.assets), '--package', args.package,
                  '--source', str(args.source), '--soak-corpus', str(args.soak_corpus)]
    if args.development_pack_mirror:
        forwarded += ['--development-pack-mirror', str(args.development_pack_mirror.resolve()),
                      '--development-mirror-port', str(args.development_mirror_port)]
    if args.journeys:
        forwarded += ['--journeys', *args.journeys]
    report = None
    owner_home = Path.home()
    before = owner_metadata(owner_home)
    save(out / 'owner-tree-before.json', before)
    try:
        for attempt in (1, 2):
            evidence = out / f'attempt-{attempt}'
            report, interrupted = run_attempt(args, [*forwarded, '--evidence', str(evidence)], evidence,
                                               attempt, out / 'soak-started')
            if not interrupted or attempt == 2:
                break
            deadline = time.monotonic() + 2 * 3600
            retry_samples = []
            while True:
                sample = gpu.resource_sample(starting=True)
                retry_samples.append(sample)
                save(out / 'retry-resources.json', retry_samples)
                if sample['available'] or time.monotonic() >= deadline:
                    break
                time.sleep(min(300, max(0, deadline - time.monotonic())))
            if not sample['available']:
                break
    except BaseException as exc:
        report = report or runner.preparation_failure('gpu-linux', str(exc) or type(exc).__name__)
        if report.get('status') != 'failed':
            add_check(report, 'host failure', 'failed-infrastructure', str(exc), [])
    finally:
        after = owner_metadata(owner_home)
        save(out / 'owner-tree-after.json', after)
        if report:
            add_check(report, 'owner saved state metadata', 'passed' if before == after else 'failed-infrastructure',
                      'Owner JustSearch metadata unchanged, including root directory mtimes' if before == after else 'Owner JustSearch metadata changed (names, sizes, modes or mtimes, including root directories)',
                      ['owner-tree-before.json', 'owner-tree-after.json'])
        if report:
            report['execution'] = {**report.get('execution', {}),
                'releaseQualification': not bool(args.development_pack_mirror or args.journeys or args.development_resume_root)}
            report['attemptEvidence'] = [p.name for p in sorted(out.glob('attempt-*'))]
            if report['attemptEvidence']:
                base = Path(report['attemptEvidence'][-1])
                for journey in report['journeys']:
                    for check in journey['checks']:
                        check['evidence'] = [str(base / path) if (out / base / path).exists() and not (out / path).exists() else path
                                             for path in check.get('evidence', [])]
                report.update(runner.summarize(report['journeys'], report.get('fullSelection', False)))
            report['attempts'] = [{
                'evidence': directory.name,
                'status': json.loads((directory / 'summary.json').read_text())['status']}
                for directory in sorted(out.glob('attempt-*')) if (directory / 'summary.json').exists()]
            save(out / 'summary.json', report)
        final_bytes = gpu.device_writes(args.write_start['device'])
        save(out / 'write-volume.json', {**args.write_start, 'endBytes': final_bytes,
            'writtenBytes': final_bytes - args.write_start['startBytes'], 'budgetBytes': args.write_budget_bytes,
            'measurement': 'Private-root mount device /proc/diskstats sectors written delta * 512; same baseline as live guard',
            'limit': 'Upper bound includes unrelated host IO on this device. No global sync; pending writeback and final accounting-file write are outside the sample.'})
        if report:
            report['deviceWriteUpperBound'] = json.loads((out / 'write-volume.json').read_text())
            save(out / 'summary.json', report)
    return runner.report_exit_code(report)


if __name__ == '__main__':
    if '--session' in sys.argv or '--sandbox' in sys.argv:
        sys.exit(main())
    # Adopt orphaned grandchildren for cleanup; write accounting uses diskstats.
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(36, 1, 0, 0, 0):  # PR_SET_CHILD_SUBREAPER
        raise OSError(ctypes.get_errno(), 'Cannot enable lane child subreaper')
    result = main()
    while True:
        try:
            os.waitpid(-1, 0)
        except ChildProcessError:
            break
    sys.exit(result)
