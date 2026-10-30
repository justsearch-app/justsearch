#!/usr/bin/env python3
"""Host command for the gpu-linux adapter; private Xvfb/bus, guard and cleanup."""
import argparse
import datetime
import json
import os
from pathlib import Path
import resource
import secrets
import shutil
import signal
import subprocess
import sys
import tempfile
import time

import gpu
import runner


def processes(marker):
    rows = []
    needle = ('QUAL_GPU_SESSION=' + marker).encode()
    for path in Path('/proc').glob('[0-9]*/environ'):
        try:
            if needle not in path.read_bytes().split(b'\0'):
                continue
            fields = path.with_name('stat').read_text().rsplit(') ', 1)[1].split()
            if fields[0] == 'Z':
                continue
            env = dict(item.split(b'=', 1) for item in path.read_bytes().split(b'\0') if b'=' in item)
            rows.append({'pid': int(path.parent.name), 'ticks': fields[19], 'ppid': int(fields[1]),
                         'exe': str(path.with_name('exe').resolve()),
                         'xvfbArgs': path.with_name('cmdline').read_bytes().decode().strip('\0').split('\0') if Path(path.with_name('exe').resolve()).name == 'Xvfb' else [],
                         'display': env.get(b'DISPLAY', b'').decode(),
                         'wayland': env.get(b'WAYLAND_DISPLAY', b'').decode(),
                         'sessionBus': env.get(b'DBUS_SESSION_BUS_ADDRESS', b'').decode()})
        except (OSError, IndexError, ValueError):
            continue
    return rows


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
    samples, process_rows, io = [], {}, {}
    failure = None
    child = None
    start_io = resource.getrusage(resource.RUSAGE_CHILDREN).ru_oublock * 512
    with tempfile.TemporaryDirectory(prefix='justsearch-gpu-') as directory:
        root = Path(directory)
        env = gpu.private_environment(os.environ, root, marker)
        env.update(QUAL_GPU_ROOT=str(root), QUAL_GPU_EVIDENCE=str(evidence),
                   QUAL_GPU_SOAK_MARKER=str(soak_marker))
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
        mirror = None
        try:
            if args.development_pack_mirror:
                # Python's server binds only loopback. Serve only named registry
                # ZIPs hardlinked into this run's otherwise empty directory.
                mirror_root = root / 'mirror'
                mirror_root.mkdir()
                for path in args.development_pack_mirror.iterdir():
                    if path.name.endswith('.zip') and path.is_file() and not path.is_symlink():
                        os.link(path, mirror_root / path.name)
                mirror = subprocess.Popen([sys.executable, '-m', 'http.server', str(args.development_mirror_port),
                                           '--bind', '127.0.0.1', '--directory', str(mirror_root)],
                                          env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
                time.sleep(.3)
                if mirror.poll() is not None:
                    raise runner.Infrastructure('Development mirror could not bind its private loopback port')
            with (root / 'session.log').open('wb') as log:
                child = subprocess.Popen(['xvfb-run', '-a', '-s', '-screen 0 1440x1000x24 -nolisten tcp',
                                          'dbus-run-session', '--', sys.executable, str(Path(__file__).resolve()),
                                          '--session', *forwarded], env=env, stdout=log, stderr=log,
                                         start_new_session=True)
                next_check = time.monotonic() + 30
                deadline = time.monotonic() + 3 * 3600
                while child.poll() is None:
                    rows = processes(marker)
                    for row in rows:
                        process_rows[(row['pid'], row['ticks'])] = row
                        try:
                            counters = dict(line.split(':', 1) for line in Path('/proc', str(row['pid']), 'io').read_text().splitlines())
                            io[(row['pid'], row['ticks'])] = max(io.get((row['pid'], row['ticks']), 0), int(counters['write_bytes']))
                        except (OSError, ValueError):
                            pass
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
            rows = processes(marker)
            process_rows.update({(p['pid'], p['ticks']): p for p in rows})
            if failure:
                (root / 'stop-reason').write_text(failure)
                runners = [r for r in rows if r['exe'] == str(Path(sys.executable).resolve())]
                # Interrupt runner.py first so its final report/crash logs flush.
                for row in runners:
                    try:
                        cmd = Path('/proc', str(row['pid']), 'cmdline').read_bytes()
                        if b'runner.py' in cmd and runner.Target().alive([row]):
                            os.kill(row['pid'], signal.SIGTERM)
                    except ProcessLookupError:
                        pass
                # Release GPU/Engine resources immediately; keep the reporter
                # alive briefly to flush its interrupted check and cleanup logs.
                app_rows = [r for r in rows if Path(r['exe']).name in ('JustSearch', 'java', 'llama-server')]
                runner.Target().stop(app_rows)
                until = time.monotonic() + 5
                while time.monotonic() < until and child and child.poll() is None:
                    time.sleep(.1)
            runner.Target().stop(processes(marker))
            if child:
                child.wait(timeout=15)
            if mirror:
                mirror.wait(timeout=15)
            evidence.mkdir(exist_ok=True)
            shutil.copy2(root / 'session.log', evidence / 'session.log') if (root / 'session.log').exists() else None
            shutil.copy2(root / 'openbox.log', evidence / 'openbox.log') if (root / 'openbox.log').exists() else None
            save(evidence / 'resources.json', samples)
            save(evidence / 'session-processes.json', list(process_rows.values()))
            save(evidence / 'write-volume.json', {
                'sampledProcessWrittenBytes': sum(io.values()),
                'waitedForChildWrittenBytes': resource.getrusage(resource.RUSAGE_CHILDREN).ru_oublock * 512 - start_io,
                'measurement': 'sum of sampled /proc/pid/io by PID/start identity; waited-for child block IO separate; overlap, do not add',
                'limitation': 'short-lived processes can be missed, and ancestor counters can include waited-for child IO already sampled; neither an exact total nor a bound'})
            summary = evidence / 'summary.json'
            report = json.loads(summary.read_text()) if summary.exists() else runner.preparation_failure('gpu-linux', failure or 'Private session exited without a summary')
            if failure:
                add_check(report, 'resource interruption' if failure == gpu.INTERRUPTED else 'session failure',
                          'failed-infrastructure', failure, ['resources.json', 'session.log'])
            save(summary, report)
        remaining = processes(marker)
        app_rows = [r for r in process_rows.values() if Path(r['exe']).name == 'JustSearch']
        displays = {r['display'] for r in app_rows}
        buses = {r['sessionBus'] for r in app_rows}
        isolated = bool(app_rows) and len(displays) == 1 and bool(next(iter(displays), '')) and len(buses) == 1 and bool(next(iter(buses), '')) and not any(r['wayland'] for r in app_rows)
        isolated = isolated and any(next(iter(displays), None) in r.get('xvfbArgs', []) for r in process_rows.values())
        isolated = isolated and os.environ.get('DISPLAY') not in displays and os.environ.get('DBUS_SESSION_BUS_ADDRESS') not in buses
        save(evidence / 'isolation.json', {'privateRoot': str(root), 'processesRemaining': remaining,
             'appDisplays': sorted(displays), 'appSessionBuses': sorted(buses),
             'ownerVisibleWindowExcluded': isolated,
             'proof': 'Every observed app GUI process uses only the run-owned Xvfb display and private D-Bus; WAYLAND_DISPLAY absent'})
        if remaining or not isolated:
            add_check(report, 'session isolation', 'failed-infrastructure',
                      'Could not prove private display/bus ownership and complete process cleanup', ['isolation.json'])
        else:
            add_check(report, 'session isolation', 'passed', 'Private Xvfb/bus and no remaining run-owned process', ['isolation.json', 'session-processes.json'])
    # TemporaryDirectory has removed only this run's state and extracted bytes.
    report['execution'] = {**report.get('execution', {}), 'releaseQualification': not bool(args.development_pack_mirror or args.journeys),
                           'developmentPackMirror': str(args.development_pack_mirror) if args.development_pack_mirror else None}
    report['privateStateRemoved'] = not root.exists()
    if root.exists():
        add_check(report, 'private state cleanup', 'failed-infrastructure', 'Private state was not removed', ['isolation.json'])
    save(evidence / 'summary.json', report)
    return report, failure == gpu.INTERRUPTED


def main():
    if '--session' in sys.argv:
        return session(sys.argv[sys.argv.index('--session') + 1:])
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--driver', default='tauri-driver')
    parser.add_argument('--development-pack-mirror', type=Path)
    parser.add_argument('--development-mirror-port', type=int, default=8769)
    parser.add_argument('--journeys', nargs='+')
    args, forwarded = parser.parse_known_args()
    if not 1 <= args.development_mirror_port <= 65535:
        parser.error('--development-mirror-port must be a TCP port in 1..65535')
    out = args.evidence.resolve()
    out.mkdir(parents=True, exist_ok=False)
    forwarded += ['--driver', args.driver]
    if args.development_pack_mirror:
        forwarded += ['--development-pack-mirror', str(args.development_pack_mirror.resolve()),
                      '--development-mirror-port', str(args.development_mirror_port)]
    if args.journeys:
        forwarded += ['--journeys', *args.journeys]
    # Only the explicitly requested sentinel is touched in the owner's real app
    # folder. Existing data/settings/index contents are never opened.
    owner_data = Path(os.environ.get('XDG_DATA_HOME', str(Path.home() / '.local/share'))) / 'io.justsearch.shell'
    sentinel = owner_data / ('.w4c-isolation-' + secrets.token_hex(12))
    sentinel_bytes = secrets.token_bytes(64)
    report = None
    try:
        with sentinel.open('xb') as stream:
            stream.write(sentinel_bytes)
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
        intact = sentinel.read_bytes() == sentinel_bytes
        save(out / 'owner-sentinel.json', {'path': str(sentinel), 'unchanged': intact})
        add_check(report, 'owner data sentinel', 'passed' if intact else 'failed-infrastructure',
                  'Owner data sentinel unchanged' if intact else 'Owner data sentinel changed', ['owner-sentinel.json'])
    except BaseException as exc:
        report = report or runner.preparation_failure('gpu-linux', str(exc) or type(exc).__name__)
        if report.get('status') != 'failed':
            add_check(report, 'host failure', 'failed-infrastructure', str(exc), [])
    finally:
        if sentinel.exists():
            sentinel.unlink()
        if report:
            report['execution'] = {**report.get('execution', {}),
                'releaseQualification': not bool(args.development_pack_mirror or args.journeys)}
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
    return runner.report_exit_code(report)


if __name__ == '__main__':
    sys.exit(main())
