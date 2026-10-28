#!/usr/bin/env python3
"""Own one clean, headless Ubuntu overlay and run the shared Linux journeys.

All apt operations and downloads happen in the disposable guest. The caller
supplies a tauri-driver 2.0.4 tool binary; this never builds the tested app.
"""
import argparse
import json
import os
from pathlib import Path, PurePosixPath
import shlex
import socket
import subprocess
import sys
import tarfile
import tempfile
import time

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import vm
from runner import Target, verify_assets, preparation_failure, summarize, wait_for, Infrastructure


def ports_available(ports):
    # A stopped QEMU can leave TIME_WAIT sockets: connection probes say free,
    # but the harness's conservative bind probe still refuses an immediate boot.
    for port in ports:
        try:
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', port))
        except OSError:
            return False
    return True


def extract_evidence(archive, out):
    with tarfile.open(archive) as tar:
        members = tar.getmembers()
        if len(members) > 1000 or sum(m.size for m in members) > 100_000_000:
            raise Infrastructure('Guest evidence archive exceeds its size limit')
        for member in members:
            path = PurePosixPath(member.name)
            if path.is_absolute() or '..' in path.parts or path.parts[:1] != ('evidence',) or not (member.isfile() or member.isdir()):
                raise Infrastructure('Unsafe guest evidence archive member: ' + member.name)
        tar.extractall(out, filter='data')


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', required=True, choices=['wayland', 'x11', 'hosted'])
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('--registry', type=Path, required=True)
    parser.add_argument('--predecessor', type=Path)
    parser.add_argument('--mirror', type=Path)
    parser.add_argument('--driver', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    vm.owner()
    if not args.driver.is_file():
        parser.error('--driver must be a host file containing tauri-driver 2.0.4')
    if args.session == 'wayland' and (not args.predecessor or not args.mirror):
        parser.error('VM desktop lanes require --predecessor and --mirror for J5')
    return args


def run_overlay(args, out, phase):
    out.mkdir(parents=True, exist_ok=False)
    started = None
    try:
        verify_assets(args.assets, [args.package, 'justsearch-mcp.mcpb'])
        vm.wait_free(28800)
        wait_for(lambda: ports_available([vm.OS['ubuntu']['qmp'], vm.OS['ubuntu']['ssh']]),
                 'Stopped VM ports can be bound for the next overlay', seconds=90,
                 interval=2, error=Infrastructure)
        vm.start('ubuntu')
        started = vm.require_owner('ubuntu')
        vm.wait_ssh('ubuntu', timeout=600)
        with (out / 'provision.log').open('wb') as log:
            command = ('env DEBIAN_FRONTEND=noninteractive apt-get update && '
                       'env DEBIAN_FRONTEND=noninteractive apt-get install -y '
                       'curl webkit2gtk-driver xdotool mousepad openbox python3-pil '
                       'scrot xvfb fonts-dejavu-core tesseract-ocr dbus-x11')
            vm.ssh('ubuntu', 'sudo -S -p "" sh -c ' + shlex.quote(command),
                   input=(vm.config()['password'] + '\n').encode(), stdout=log,
                   stderr=subprocess.STDOUT, check=True, timeout=1200)
        vm.copy_in('ubuntu', args.driver.resolve(), '/home/qual/tauri-driver')
        # Node is qualification-client tooling only; the app still uses its bundled JVM.
        node = '/home/qual/journey-tools/node-v22.20.0-linux-x64'
        command = ('set -eu; mkdir -p /home/qual/journey-tools; cd /home/qual/journey-tools; '
                   'curl -fsSLO https://nodejs.org/dist/v22.20.0/node-v22.20.0-linux-x64.tar.xz; '
                   'curl -fsSL https://nodejs.org/dist/v22.20.0/SHASUMS256.txt -o SHASUMS256.txt; '
                   'sha256sum --check --ignore-missing SHASUMS256.txt; '
                   'tar -xf node-v22.20.0-linux-x64.tar.xz')
        with (out / 'node-provision.log').open('wb') as log:
            vm.ssh('ubuntu', command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
        # System-wide tool links avoid inheriting a developer runtime path.
        vm.guest_root('ubuntu', 'ln -s ' + node + '/bin/node /usr/local/bin/node; ln -s '
                      + node + '/bin/npm /usr/local/bin/npm; ln -s ' + node + '/bin/npx /usr/local/bin/npx')
        if args.session == 'x11':
            vm.guest_root('ubuntu', r"sed -i '/^\[daemon\]/a WaylandEnable=false' /etc/gdm3/custom.conf; "
                          "sed -i 's/^Session=.*/Session=ubuntu-xorg/;s/^XSession=.*/XSession=ubuntu-xorg/' /var/lib/AccountsService/users/qual; "
                          'systemctl restart gdm3')
        target = Target('ubuntu')
        deadline = time.monotonic() + 120
        while True:
            try:
                target.prepare('vm-linux-' + ('wayland' if args.session == 'hosted' else args.session), 'prepare-only')
                break
            except Exception:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(2)
        target.run(['gsettings', 'set', 'org.gnome.desktop.session', 'idle-delay', '0'])
        target.run(['gsettings', 'set', 'org.gnome.desktop.screensaver', 'lock-enabled', 'false'])
        target.run(['xdg-mime', 'default', 'org.xfce.mousepad.desktop', 'text/plain'])
        import screen
        with vm.QMP('ubuntu') as qmp:
            screen.key(qmp, 'ctrl+alt+f2')
        (out / 'vm.json').write_text(json.dumps(started, indent=2) + '\n')
        if args.session == 'hosted':
            # Allow the same noninteractive apt commands as the hosted runner.
            # This file exists only in our disposable overlay, removed below.
            vm.guest_root('ubuntu', "printf '%s\n' 'qual ALL=(root) NOPASSWD: /usr/bin/env DEBIAN_FRONTEND=noninteractive apt-get *' > /etc/sudoers.d/justsearch-journeys; chmod 440 /etc/sudoers.d/justsearch-journeys")
            with tempfile.TemporaryDirectory(prefix='journey-hosted-upload-') as temp:
                archive = Path(temp) / 'input.tar'
                with tarfile.open(archive, 'w') as tar:
                    for p in HERE.iterdir():
                        if p.suffix in ('.py', '.json'):
                            tar.add(p, arcname='journeys/' + p.name)
                    for name in (args.package, 'justsearch-mcp.mcpb', 'SHA256SUMS'):
                        tar.add(args.assets / name, arcname='assets/' + name)
                    tar.add(args.registry, arcname='server.json')
                vm.copy_in('ubuntu', archive, '/home/qual/input.tar')
            remote = '/home/qual/hosted-journeys'
            vm.ssh('ubuntu', 'mkdir ' + remote + '; tar -xf /home/qual/input.tar -C ' + remote, check=True)
            command = ['xvfb-run', '-a', '-s', '-screen 0 1440x1000x24', 'dbus-run-session', '--',
                       'python3', remote + '/journeys/hosted.py', '--assets', remote + '/assets',
                       '--package', args.package, '--registry', remote + '/server.json',
                       '--driver', '/home/qual/tauri-driver', '--evidence', remote + '/evidence']
            with (out / 'run.log').open('wb') as log:
                result = vm.ssh('ubuntu', shlex.join(command), stdout=log, stderr=subprocess.STDOUT, timeout=4800)
            # Copy only this run's observations; keep even a failing summary.
            vm.ssh('ubuntu', 'tar -cf /home/qual/journey-evidence.tar -C ' + remote + ' evidence', check=True)
            subprocess.run(['scp', *vm.ssh_args('ubuntu'), '-P', str(vm.OS['ubuntu']['ssh']),
                            'qual@127.0.0.1:/home/qual/journey-evidence.tar', str(out / 'evidence.tar')], check=True)
            with tarfile.open(out / 'evidence.tar') as tar:
                report = json.load(tar.extractfile('evidence/summary.json'))
            extract_evidence(out / 'evidence.tar', out)
            (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
            return result.returncode
        command = [sys.executable, str(HERE / 'runner.py'), '--vm', 'ubuntu', '--lane',
                   'vm-linux-' + args.session, '--assets', str(args.assets.resolve()), '--package', args.package,
                   '--registry', str(args.registry.resolve()), '--phase', phase,
                   '--driver', '/home/qual/tauri-driver',
                   '--evidence', str(out / 'run')]
        if args.predecessor:
            command.extend(['--predecessor', str(args.predecessor.resolve())])
        if args.mirror:
            command.extend(['--mirror', str(args.mirror.resolve())])
        with (out / 'run.log').open('wb') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=4800)
        (out / 'summary.json').write_bytes((out / 'run/summary.json').read_bytes())
        return result.returncode
    except Exception as exc:
        lane = 'hosted-linux-x11' if args.session == 'hosted' else 'vm-linux-' + args.session
        (out / 'summary.json').write_text(json.dumps(preparation_failure(lane, str(exc)), indent=2) + '\n')
        (out / 'wrapper-error.txt').write_text(repr(exc) + '\n')
        return 1
    finally:
        if started and vm.read_state() == started:
            # Stop only the run we started, even if a child command failed.
            try:
                if (args.mirror and args.session != 'hosted'
                        and vm.ssh('ubuntu', 'test -f /home/qual/qual-mirror-guest.py',
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15).returncode == 0):
                    vm.mirror('ubuntu', None, True, False)
            finally:
                vm.stop()


def main():
    args = parse_args()
    out = args.evidence.resolve()
    out.mkdir(parents=True, exist_ok=False)
    phases = ['candidate', 'predecessor'] if args.session == 'wayland' else ['candidate']
    reports = {}
    for phase in phases:
        run_overlay(args, out / phase, phase)
        reports[phase] = json.loads((out / phase / 'summary.json').read_text())
    report = dict(reports['candidate'])
    rows = []
    for journey in json.loads((HERE / 'journeys.json').read_text())['journeys']:
        phase = 'predecessor' if len(phases) == 2 and journey['id'] in ('J5', 'J6') else 'candidate'
        source = reports[phase]
        row = next((r for r in source['journeys'] if r['id'] == journey['id']), None)
        if row is None:
            raise RuntimeError('Missing journey from overlay report: ' + journey['id'])
        # Evidence is relative to its exact overlay's runner output.
        row = json.loads(json.dumps(row))
        for check in row['checks']:
            prefix = phase + ('/evidence/' if args.session == 'hosted' else '/run/')
            check['evidence'] = [prefix + name for name in check['evidence']]
        rows.append(row)
    for phase, source in reports.items():
        for row in source['journeys']:
            if row['id'] in ('preparation', 'cleanup'):
                rows.append(dict(row, id=phase + '-' + row['id']))
    report.update(journeys=rows, overlays={p: p + '/summary.json' for p in phases}, **summarize(rows))
    (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
    return int(any(report['counts'][s] for s in ('failed-product', 'failed-infrastructure', 'unavailable')))


if __name__ == '__main__':
    sys.exit(main())
