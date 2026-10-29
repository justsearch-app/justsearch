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
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import time

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import vm
from runner import Native, Target, digest, verify_assets, preparation_failure, summarize, full_selection, wait_for, Infrastructure
from writes import COUNTERS


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


def extract_evidence(archive, out, root='evidence'):
    with tarfile.open(archive) as tar:
        members = tar.getmembers()
        if len(members) > 1000 or sum(m.size for m in members) > 100_000_000:
            raise Infrastructure('Guest evidence archive exceeds its size limit')
        for member in members:
            path = PurePosixPath(member.name)
            if path.is_absolute() or '..' in path.parts or path.parts[:1] != (root,) or not (member.isfile() or member.isdir()):
                raise Infrastructure('Unsafe guest evidence archive member: ' + member.name)
        tar.extractall(out, filter='data')
    if root != 'evidence':
        (out / root).rename(out / 'evidence')


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
    parser.add_argument('--journeys', nargs='+', help='Only these journeys, without implicit dependent tests')
    parser.add_argument('--mode', choices=['fresh', 'rerun'], default='fresh',
                        help='Fresh release qualification, or development-only snapshot reuse')
    parser.add_argument('--snapshot', type=Path, help='Stopped setup snapshot from an earlier fresh run')
    parser.add_argument('--memory-mib', type=int, choices=[8192, 12288], default=vm.MEMORY_MIB)
    parser.add_argument('--memory-reason', help='Evidence for a journey using nondefault memory')
    args = parser.parse_args()
    vm.owner()
    if not args.driver.is_file():
        parser.error('--driver must be a host file containing tauri-driver 2.0.4')
    ids = {j['id'] for j in json.loads((HERE / 'journeys.json').read_text())['journeys']}
    if args.journeys and not set(args.journeys) <= ids:
        parser.error('Unknown journey ID')
    if args.mode == 'rerun' and (not args.snapshot or not args.journeys):
        parser.error('Development reruns require --snapshot and explicit --journeys')
    if args.mode == 'fresh' and args.snapshot:
        parser.error('Fresh qualification cannot inherit a setup snapshot')
    if args.memory_mib != vm.MEMORY_MIB and not args.memory_reason:
        parser.error('Nondefault memory requires --memory-reason with journey evidence')
    if args.session == 'wayland' and args.mode == 'fresh' and (not args.predecessor or not args.mirror):
        parser.error('VM desktop lanes require --predecessor and --mirror for J5')
    return args


def pins(args, phase):
    return {'assets': verify_assets(args.assets, [args.package, 'justsearch-mcp.mcpb']),
            'predecessor': digest(args.predecessor) if args.predecessor else None,
            'registry': digest(args.registry), 'driver': digest(args.driver),
            'session': args.session, 'phase': phase,
            'base': json.loads((vm.ROOT / 'ubuntu/base.json').read_text())}


def start_snapshot(snapshot):
    # Use the same ownership, ports, lifetime lock and resource limits as vm.start.
    vm.ensure_free()
    vm.wait_host_memory()
    with vm.locked() as lock:
        vm.ensure_free(check_lock=False)
        folder = vm.ROOT / 'ubuntu'
        disk, variables = folder / 'run.qcow2', folder / 'run-vars.fd'
        disk.unlink(missing_ok=True)
        variables.unlink(missing_ok=True)
        try:
            vm.run('qemu-img', 'create', '-q', '-f', 'qcow2', '-F', 'qcow2',
                   '-b', (snapshot / 'disk.qcow2').resolve(), disk)
            shutil.copyfile(snapshot / 'vars.fd', variables)
            return vm.launch('ubuntu', disk, variables, lease=lock.fileno())
        except BaseException:
            if not vm.current():
                disk.unlink(missing_ok=True)
                variables.unlink(missing_ok=True)
            raise


def seal_snapshot(out, started, inode, args, phase):
    snapshot = out / 'snapshot'
    with vm.locked():
        vm.ensure_free(check_lock=False)
        disk = Path(started['disk'])
        if disk.stat().st_ino != inode:
            raise Infrastructure('Run disk changed before snapshot sealing')
        if disk.stat().st_dev != out.stat().st_dev:
            raise Infrastructure('Snapshot sealing requires the evidence and VM disk on the same filesystem; refusing a cross-filesystem rename')
        snapshot.mkdir()
        # Rename, never copy/rebase/flatten the gigabytes already written.
        disk.rename(snapshot / 'disk.qcow2')
        (disk.parent / 'run-vars.fd').rename(snapshot / 'vars.fd')
        shutil.copyfile(out / 'checkpoint.json', snapshot / 'checkpoint.json')
        (snapshot / 'manifest.json').write_text(json.dumps({'pins': pins(args, phase),
            'sourceSummary': str((out / 'summary.json').resolve()),
            'memoryMiB': args.memory_mib}, indent=2) + '\n')
    return snapshot


def provision(args, out):
    # Guest-only services unrelated to the journeys. Keep journal evidence and
    # swap enabled; disabling either would conceal failures under pressure.
    vm.guest_root('ubuntu', 'systemctl mask --now snapd.service snapd.socket '
                  'fwupd.service fwupd-refresh.timer apt-daily.service apt-daily.timer '
                  'apt-daily-upgrade.service apt-daily-upgrade.timer unattended-upgrades.service')
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


def crash_evidence(out):
    # Keep reports even when the disposable desktop's notification is disabled.
    result = vm.ssh('ubuntu', "find /var/crash -maxdepth 1 -name '*.crash' -printf '%f\\n'",
                    capture_output=True, text=True, check=True, timeout=30)
    names = result.stdout.splitlines()
    if any(Path(name).name != name or not name.endswith('.crash') for name in names):
        raise Infrastructure('Unsafe guest crash report filename')
    if names:
        archive_path = out / 'guest-crashes.tar'
        if archive_path.exists():
            archive_path = out / ('guest-crashes-' + str(time.time_ns()) + '.tar')
        # Stream only bounded diagnostic fields before CoreDump. A large core
        # must neither hide its product crash nor be copied to the host.
        source = """
import io,sys,tarfile
from pathlib import Path
keys=(b'ExecutablePath:',b'Signal:',b'ProblemType:',b'Date:',b'Package:',b'ProcCmdline:')
with tarfile.open(fileobj=sys.stdout.buffer,mode='w|') as archive:
 for name in sys.argv[1:]:
  path=Path('/var/crash',name)
  assert not path.is_symlink(), 'Crash report is a symlink'
  fields={}
  with path.open('rb') as report:
   continuation=False
   while True:
    raw=report.readline(65536)
    if not raw:break
    if not continuation:
     if raw.startswith(b'CoreDump:'):break
     if raw.startswith(keys):fields[raw.split(b':',1)[0]]=raw.rstrip(b'\\n')+b'\\n'
    continuation=not raw.endswith(b'\\n')
  header=b''.join(fields.values()); member=tarfile.TarInfo(name);member.size=len(header)
  archive.addfile(member,io.BytesIO(header))
"""
        with archive_path.open('wb') as stream:
            vm.ssh('ubuntu', "sudo -S -p '' python3 -c " + shlex.quote(source) + ' ' + shlex.join(names),
                   input=(vm.config()['password'] + '\n').encode(), stdout=stream,
                   check=True, timeout=60)
        fields = []
        with tarfile.open(archive_path) as archive:
            for member in archive.getmembers():
                row = {'file': '/var/crash/' + member.name, 'fields': {}}
                for raw in archive.extractfile(member):
                    line = raw.decode(errors='replace')
                    if line[:1].isspace() or ':' not in line:
                        continue
                    key, value = line.split(':', 1)
                    if key in ('ExecutablePath', 'Signal', 'ProblemType', 'Date', 'Package', 'ProcCmdline'):
                        row['fields'][key] = value.strip()
                fields.append(row)
        record = out / 'guest-crashes.json'
        previous = json.loads(record.read_text()) if record.exists() else []
        record.write_text(json.dumps(previous + fields, indent=2) + '\n')
        recorded = '/var/crash/qualification-recorded-' + str(time.time_ns())
        vm.guest_root('ubuntu', 'mkdir ' + shlex.quote(recorded) + '; mv -- '
                      + shlex.join('/var/crash/' + n for n in names) + ' ' + shlex.quote(recorded) + '/')
        product = [r for r in fields if r['fields'].get('Package', '').split()[:1] == ['just-search']]
        if product:
            raise AssertionError('Product crash in guest: ' + json.dumps(product))
        installed = vm.ssh('ubuntu', 'dpkg-query -L just-search', capture_output=True, text=True, timeout=30)
        if installed.returncode not in (0, 1):
            raise Infrastructure('Installed package paths unavailable for crash attribution')
        paths = set(installed.stdout.splitlines()) if installed.returncode == 0 else set()
        product = [r for r in fields if r['fields'].get('ExecutablePath', '') in paths]
        if product:
            raise AssertionError('Product crash in guest: ' + json.dumps(product))


def prepare_desktop(args, out):
    target = Target('ubuntu')
    def ready():
        deadline = time.monotonic() + 120
        while True:
            try:
                target.prepare('vm-linux-' + ('wayland' if args.session == 'hosted' else args.session), 'prepare-only')
                return
            except Exception:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(2)
    ready()
    crash_evidence(out)
    # Ubuntu's release-upgrade checker can crash before qualification starts.
    # Disable only desktop notifications; apport still writes crash reports.
    notify = ['gsettings', 'get', 'com.ubuntu.update-notifier', 'show-apport-crashes']
    if target.run(notify).stdout.strip() != 'false':
        target.run(['gsettings', 'set', 'com.ubuntu.update-notifier', 'show-apport-crashes', 'false'])
        vm.guest_root('ubuntu', 'systemctl restart gdm3')
        ready()
    (out / 'crash-notifications.txt').write_text(target.run(notify).stdout)
    banners = ['gsettings', 'get', 'org.gnome.desktop.notifications', 'show-banners']
    target.run(['gsettings', 'set', 'org.gnome.desktop.notifications', 'show-banners', 'false'])
    observed = target.run(banners).stdout.strip()
    (out / 'notification-banners.txt').write_text(observed + '\n')
    if observed != 'false':
        raise Infrastructure('GNOME notification banners could not be disabled')
    target.run(['gsettings', 'set', 'org.gnome.desktop.session', 'idle-delay', '0'])
    target.run(['gsettings', 'set', 'org.gnome.desktop.screensaver', 'lock-enabled', 'false'])
    target.run(['xdg-mime', 'default', 'org.xfce.mousepad.desktop', 'text/plain'])
    import screen
    with vm.QMP('ubuntu') as qmp:
        screen.key(qmp, 'ctrl+alt+f2')
    if args.session == 'wayland':
        # Disposable guest only: Looking Glass enables read-only compositor
        # queries over its debugger. Never enable this on the owner's desktop.
        native = Native(target, out)
        for attempt in range(2):
            try:
                native.key('escape')
                native.key('alt+f2')
                wait_for(lambda: 'Run a Command' in native.screen_text('compositor-command', psm=6),
                         'GNOME command dialog', seconds=20, error=Infrastructure)
                native.type('lg')
                native.key('enter')
                wait_for(lambda: 'Evaluator' in native.screen_text('compositor-debugger', psm=6),
                         'Disposable compositor debugger', seconds=20, error=Infrastructure)
                native.type('global.context.unsafe_mode = true')
                native.key('enter')
                native.key('escape')
                native.focused_window()
                break
            except Infrastructure:
                if attempt:
                    raise
                vm.guest_root('ubuntu', 'systemctl restart gdm3')
                ready()
                with vm.QMP('ubuntu') as qmp:
                    screen.key(qmp, 'ctrl+alt+f2')
    target.run(['systemctl', '--user', 'mask', '--now', 'tracker-miner-fs-3.service'], check=False)


def execute(args, out, phase, journeys, snapshot):
    checkpoint = snapshot / 'checkpoint.json' if snapshot else None
    if args.session == 'hosted':
        remote = '/home/qual/hosted-journeys'
        vm.guest_root('ubuntu', "printf '%s\n' 'qual ALL=(root) NOPASSWD: /usr/bin/env DEBIAN_FRONTEND=noninteractive apt-get *' > /etc/sudoers.d/justsearch-journeys; chmod 440 /etc/sudoers.d/justsearch-journeys")
        with tempfile.TemporaryDirectory(prefix='journey-hosted-upload-') as temp:
            archive = Path(temp) / 'input.tar'
            with tarfile.open(archive, 'w') as tar:
                for p in HERE.iterdir():
                    if p.suffix in ('.py', '.json'):
                        tar.add(p, arcname='journeys/' + p.name)
                if not snapshot:
                    for name in (args.package, 'justsearch-mcp.mcpb', 'SHA256SUMS'):
                        tar.add(args.assets / name, arcname='assets/' + name)
                    tar.add(args.registry, arcname='server.json')
                if checkpoint:
                    tar.add(checkpoint, arcname='checkpoint.json')
            # Stream input once, avoiding a second 558 MB guest archive copy.
            with archive.open('rb') as stream:
                vm.ssh('ubuntu', 'mkdir -p ' + remote + '; tar -xf - -C ' + remote,
                       stdin=stream, check=True, timeout=300)
        command = ['xvfb-run', '-a', '-s', '-screen 0 1440x1000x24', 'dbus-run-session', '--',
                   'python3', remote + '/journeys/hosted.py', '--state-root', remote + '/state',
                   '--assets', remote + '/assets', '--package', args.package,
                   '--registry', remote + '/server.json', '--driver', '/home/qual/tauri-driver',
                   '--evidence', remote + '/evidence-' + out.name,
                   '--checkpoint', remote + '/next-checkpoint.json']
        if checkpoint:
            command += ['--resume', remote + '/checkpoint.json']
        command += ['--journeys', *journeys]
        with (out / 'run.log').open('wb') as log:
            result = vm.ssh('ubuntu', shlex.join(command), stdout=log, stderr=subprocess.STDOUT, timeout=4800)
        vm.ssh('ubuntu', 'tar -cf /home/qual/journey-evidence.tar -C ' + remote + ' ' + shlex.quote('evidence-' + out.name), check=True)
        subprocess.run(['scp', *vm.ssh_args('ubuntu'), '-P', str(vm.OS['ubuntu']['ssh']),
                        'qual@127.0.0.1:/home/qual/journey-evidence.tar', str(out / 'evidence.tar')], check=True)
        extract_evidence(out / 'evidence.tar', out, 'evidence-' + out.name)
        subprocess.run(['scp', *vm.ssh_args('ubuntu'), '-P', str(vm.OS['ubuntu']['ssh']),
                        'qual@127.0.0.1:' + remote + '/next-checkpoint.json', str(out / 'checkpoint.json')], check=False)
        shutil.copyfile(out / 'evidence/summary.json', out / 'summary.json')
        return result.returncode
    command = [sys.executable, str(HERE / 'runner.py'), '--vm', 'ubuntu', '--lane',
               'vm-linux-' + args.session, '--assets', str(args.assets.resolve()), '--package', args.package,
               '--registry', str(args.registry.resolve()), '--phase', phase,
               '--driver', '/home/qual/tauri-driver', '--evidence', str(out / 'run'),
               '--checkpoint', str(out / 'checkpoint.json')]
    if checkpoint:
        command += ['--resume', str(checkpoint)]
    if args.predecessor:
        command += ['--predecessor', str(args.predecessor.resolve())]
    if args.mirror:
        command += ['--mirror', str(args.mirror.resolve())]
    command += ['--journeys', *journeys]
    with (out / 'run.log').open('wb') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=4800)
    shutil.copyfile(out / 'run/summary.json', out / 'summary.json')
    return result.returncode


def run_overlay(args, out, phase, journeys, snapshot=None, seal=False):
    out.mkdir(parents=True, exist_ok=False)
    started = None
    try:
        if snapshot:
            manifest = json.loads((snapshot / 'manifest.json').read_text())
            if manifest['pins'] != pins(args, phase):
                raise Infrastructure('Snapshot package/tool/base pins do not match this run')
        else:
            pins(args, phase)
        vm.wait_free(28800)
        wait_for(lambda: ports_available([vm.OS['ubuntu']['qmp'], vm.OS['ubuntu']['ssh']]),
                 'Stopped VM ports can be bound for the next overlay', seconds=90,
                 interval=2, error=Infrastructure)
        vm.MEMORY_MIB = args.memory_mib
        if snapshot:
            start_snapshot(snapshot)
        else:
            vm.start('ubuntu')
        started = vm.require_owner('ubuntu')
        inode = Path(started['disk']).stat().st_ino
        vm.wait_ssh('ubuntu', timeout=600)
        vm.guest_root('ubuntu', "printf 'justsearch-vm\\n' > /run/justsearch-qualification-guest; chmod 644 /run/justsearch-qualification-guest")
        vm.guest_root('ubuntu', 'sysctl -w vm.swappiness=1')
        if not snapshot:
            provision(args, out)
        prepare_desktop(args, out)
        (out / 'vm.json').write_text(json.dumps(dict(started, memoryMiB=args.memory_mib,
            memoryReason=args.memory_reason), indent=2) + '\n')
        return execute(args, out, phase, journeys, snapshot)
    except Exception as exc:
        lane = 'hosted-linux-x11' if args.session == 'hosted' else 'vm-linux-' + args.session
        report = preparation_failure(lane, str(exc))
        if isinstance(exc, AssertionError):
            report['journeys'][0]['checks'][0].update(status='failed-product',
                evidence=['../guest-crashes.json', '../guest-crashes.tar'])
            report.update(summarize(report['journeys'], report.get('fullSelection', False)))
        (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
        (out / 'wrapper-error.txt').write_text(repr(exc) + '\n')
        return 1
    finally:
        if started and vm.read_state() == started:
            try:
                crash_evidence(out)
            except Exception as exc:
                report = json.loads((out / 'summary.json').read_text())
                report['journeys'].append({'id': 'guest-crashes', 'checks': [{
                    'name': 'guest-crashes', 'status': 'failed-product' if isinstance(exc, AssertionError)
                    else 'failed-infrastructure', 'reason': str(exc),
                    'evidence': ['../guest-crashes.json', '../guest-crashes.tar']}]})
                report.update(summarize(report['journeys'], report.get('fullSelection', False)))
                (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
            try:
                volume = Target('ubuntu').json(COUNTERS, desktop=False)
                volume['hostQemuIo'] = Path('/proc/' + str(started['pid']) + '/io').read_text()
                with vm.QMP('ubuntu') as qmp:
                    volume['qmp'] = qmp.command('query-blockstats')
            except Exception as exc:
                volume = {'unavailable': str(exc)}
            try:
                if (args.mirror and args.session != 'hosted'
                        and vm.ssh('ubuntu', 'test -f /home/qual/qual-mirror-guest.py',
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15).returncode == 0):
                    vm.mirror('ubuntu', None, True, False)
            finally:
                clean = vm.stop()
            report = json.loads((out / 'summary.json').read_text())
            report.update(writeVolume=volume, execution={'mode': args.mode,
                'requestedJourneys': journeys, 'snapshot': str(snapshot) if snapshot else None,
                'memoryMiB': args.memory_mib, 'memoryReason': args.memory_reason})
            (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
            if seal and clean and (out / 'checkpoint.json').exists():
                seal_snapshot(out, started, inode, args, phase)


def combine(args, out, sources):
    rows = []
    for name, source in sources.items():
        for row in source['journeys']:
            if name.startswith('predecessor') and row['id'] in ('J1', 'J2', 'J3', 'J4'):
                continue  # Only predecessor preparation journeys are outside candidate scope.
            row = json.loads(json.dumps(row))
            for check in row['checks']:
                prefix = name + ('/evidence/' if args.session == 'hosted' else '/run/')
                check['evidence'] = [os.path.normpath(prefix + path) for path in check['evidence']]
            rows.append(row)
    report = dict(next(iter(sources.values())))
    total = sum(s.get('writeVolume', {}).get('writtenBytes', 0) for s in sources.values())
    report.update(journeys=rows, overlays={p: p + '/summary.json' for p in sources},
                  writeVolume={'writtenBytes': total,
                    'source': 'guest whole-device counters, boot to pre-shutdown, all overlays',
                    'complete': all('writtenBytes' in s.get('writeVolume', {}) for s in sources.values())},
                  execution={'mode': args.mode, 'requestedJourneys': args.journeys,
                             'releaseQualification': args.mode == 'fresh' and not args.journeys},
                  **summarize(rows, full_selection(rows,
                      'hosted-linux-x11' if args.session == 'hosted' else 'vm-linux-' + args.session)))
    (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
    return int(not report['passed'])


def main():
    args = parse_args()
    out = args.evidence.resolve()
    out.mkdir(parents=True, exist_ok=False)
    sources = {}
    def stage(name, phase, journeys, snapshot=None, seal=False):
        run_overlay(args, out / name, phase, journeys, snapshot, seal)
        sources[name] = json.loads((out / name / 'summary.json').read_text())
    if args.mode == 'rerun':
        manifest = json.loads((args.snapshot / 'manifest.json').read_text())
        if set(args.journeys) & {'J1', 'J2'}:
            raise Infrastructure('J1/J2 require fresh setup; use --mode fresh --journeys with explicit prerequisites')
        stage('rerun', manifest['pins']['phase'], args.journeys, args.snapshot)
    elif args.journeys:
        stage('selected', 'candidate', args.journeys, seal=True)
    elif args.session == 'x11':
        stage('candidate', 'candidate', ['J1'])
    else:
        phases = ['candidate', 'predecessor'] if args.session == 'wayland' else ['candidate']
        for phase in phases:
            setup = ['J1', 'J2', 'J3'] + (['J4'] if phase == 'predecessor' else [])
            name = phase + '-setup'
            stage(name, phase, setup, seal=True)
            snapshot = out / name / 'snapshot'
            remaining = ['J5', 'J6'] if phase == 'predecessor' else ['J4', 'J7', 'J8']
            if args.session == 'hosted':
                remaining = ['J4', 'J5', 'J6', 'J7', 'J8']  # Retain explicit VM-only decision skips.
            if snapshot.exists():
                stage(phase, phase, remaining, snapshot)
            else:
                sources[phase] = preparation_failure('hosted-linux-x11' if args.session == 'hosted'
                    else 'vm-linux-wayland', 'Setup did not produce a clean stopped checkpoint')
    return combine(args, out, sources)


if __name__ == '__main__':
    sys.exit(main())
