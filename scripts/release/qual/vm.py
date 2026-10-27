#!/usr/bin/env python3
"""Disposable, single-guest QEMU release machines. Python standard library only."""
import argparse
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.parse
import urllib.request

ROOT = Path('/home/elias/dev/justsearch-qual')
HERE = Path(__file__).resolve().parent
OVMF = Path('/usr/share/OVMF')
OS = {
    'ubuntu': {'ssh': 22224, 'qmp': 44424, 'size': '64G',
               'iso': 'ubuntu-24.04.5.1-desktop-amd64.iso',
               'url': 'https://releases.ubuntu.com/24.04/ubuntu-24.04.5.1-desktop-amd64.iso',
               'sha256': '4da4a0c9035da8e68a59a838674f403f0a54472c78a83b4fb7f78d03588f85a7'},
    'windows': {'ssh': 22211, 'qmp': 44411, 'size': '80G', 'iso': 'windows.iso',
                'url': 'https://go.microsoft.com/fwlink/?LinkId=2382600&clcid=0x409&country=us&culture=en-us',
                'sha256': 'bc3f24086ebadc94489066b5ad78089e2cf5c3491e90e790bb81a2b199c10e38'},
}
STATE = ROOT / 'running.json'
MEMORY_MIB = 6144


def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def save(path, data):
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(data, indent=2) + '\n')
    tmp.replace(path)


def config():
    ROOT.mkdir(parents=True, exist_ok=True)
    path = ROOT / 'config.json'
    if not path.exists():
        save(path, {'user': 'qual', 'password': secrets.token_urlsafe(18)})
        path.chmod(0o600)
    key = ROOT / 'id_ed25519'
    if not key.exists():
        run('ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', key)
    return json.loads(path.read_text())


@contextlib.contextmanager
def locked():
    ROOT.mkdir(parents=True, exist_ok=True)
    with (ROOT / 'vm.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield


def identity(pid):
    try:
        stat = Path(f'/proc/{pid}/stat').read_text().split(') ', 1)[1].split()
        if stat[0] == 'Z':
            return None
        return stat[19]  # /proc stat field 22, kernel process start ticks
    except FileNotFoundError:
        return None


def current(os_name=None):
    if not STATE.exists():
        return None
    data = json.loads(STATE.read_text())
    if identity(data['pid']) != data['start_ticks']:
        return None
    if os_name and data['os'] != os_name:
        raise RuntimeError(f"{data['os']} is running; stop it first")
    return data


class QMP:
    def __init__(self, os_name):
        if not current(os_name):
            raise RuntimeError(f'{os_name} is not running')
        self.sock = socket.create_connection(('127.0.0.1', OS[os_name]['qmp']), timeout=10)
        self.file = self.sock.makefile('rwb')
        greeting = json.loads(self.file.readline())
        if 'QMP' not in greeting:
            raise RuntimeError('Not a QMP server')
        self.command('qmp_capabilities')

    def command(self, name, **arguments):
        self.file.write((json.dumps({'execute': name, 'arguments': arguments}) + '\n').encode())
        self.file.flush()
        while line := self.file.readline():
            reply = json.loads(line)
            if 'error' in reply:
                raise RuntimeError(str(reply['error']))
            if 'return' in reply:
                return reply['return']
        raise RuntimeError('QMP connection closed')

    def close(self):
        self.file.close()
        self.sock.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


def launch(os_name, disk, variables, visible=False, installer=None):
    if current():
        raise RuntimeError('A qualification VM is already running; stop it first')
    cpus = sorted(os.sched_getaffinity(0))[:8]
    if len(cpus) != 8:
        raise RuntimeError('Eight allowed host threads are required')
    for port in (OS[os_name]['qmp'], OS[os_name]['ssh']):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    args = ['taskset', '-c', ','.join(map(str, cpus)), 'qemu-system-x86_64',
            '-name', f'justsearch-qual-{os_name}', '-machine', 'q35,accel=kvm',
            '-cpu', 'host', '-smp', '8', '-m', str(MEMORY_MIB),
            '-drive', f'if=pflash,format=raw,readonly=on,file={OVMF}/OVMF_CODE_4M.fd',
            '-drive', f'if=pflash,format=raw,file={variables}',
            '-drive', f'file={disk},format=qcow2,if=ide',
            '-nic', f"user,model=e1000e,hostfwd=tcp:127.0.0.1:{OS[os_name]['ssh']}-:22",
            '-qmp', f"tcp:127.0.0.1:{OS[os_name]['qmp']},server=on,wait=off",
            '-monitor', 'none', '-serial', f'file:{ROOT}/{os_name}-serial.log',
            '-vga', 'std', '-device', 'qemu-xhci', '-device', 'usb-tablet',
            '-display', 'gtk,gl=off' if visible else 'none']
    if installer:
        args += ['-drive', f'file={ROOT}/iso/{OS[os_name]["iso"]},media=cdrom,readonly=on',
                 '-drive', f'file={ROOT}/{os_name}/seed.iso,media=cdrom,readonly=on',
                 '-boot', 'order=d,once=d']
        if os_name == 'ubuntu':
            args += ['-kernel', str(ROOT / 'ubuntu/vmlinuz'), '-initrd', str(ROOT / 'ubuntu/initrd'),
                     '-append', 'boot=casper autoinstall ds=nocloud console=tty0 console=ttyS0,115200n8 ---']
    else:
        args += ['-boot', 'order=c']
    with (ROOT / f'{os_name}-qemu.log').open('a') as log:
        proc = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                start_new_session=True)
    data = {'os': os_name, 'pid': proc.pid, 'start_ticks': identity(proc.pid),
            'disk': str(disk), 'visible': visible, 'installing': bool(installer), 'cpus': cpus}
    save(STATE, data)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(f'QEMU exited {proc.returncode}: {(ROOT / f"{os_name}-qemu.log").read_text()}')
        try:
            with QMP(os_name) as q:
                q.command('query-status')
            print(json.dumps(data), flush=True)
            return data
        except (OSError, RuntimeError):
            time.sleep(.25)
    proc.terminate()
    proc.wait(timeout=10)
    raise RuntimeError('QMP did not become ready')


def resources(data):
    try:
        values = dict(line.split(':', 1) for line in Path(f"/proc/{data['pid']}/status").read_text().splitlines() if ':' in line)
        data['peak_rss_kib'] = max(data.get('peak_rss_kib', 0), int(values['VmHWM'].split()[0]))
        data['cpu_affinity'] = values['Cpus_allowed_list'].strip()
    except (FileNotFoundError, KeyError):
        pass


def record_resources(data):
    evidence = ROOT / 'evidence'
    evidence.mkdir(exist_ok=True)
    with (evidence / 'resources.jsonl').open('a') as log:
        log.write(json.dumps(data) + '\n')


def stop():
    data = current()
    if not data:
        print('No qualification VM running')
        return True
    pid = data['pid']
    resources(data)
    try:
        with QMP(data['os']) as q:
            q.command('system_powerdown')
    except (OSError, RuntimeError):
        pass
    deadline = time.monotonic() + 180
    while identity(pid) == data['start_ticks'] and time.monotonic() < deadline:
        resources(data)
        time.sleep(.5)
    forced = identity(pid) == data['start_ticks']
    # Signal only the recorded process, using a pidfd to prevent PID reuse races.
    if identity(pid) == data['start_ticks']:
        fd = os.pidfd_open(pid)
        try:
            if identity(pid) == data['start_ticks']:
                signal.pidfd_send_signal(fd, signal.SIGTERM)
        finally:
            os.close(fd)
        deadline = time.monotonic() + 10
        while identity(pid) == data['start_ticks'] and time.monotonic() < deadline:
            time.sleep(.1)
    if identity(pid) == data['start_ticks']:
        raise RuntimeError(f'QEMU {pid} did not stop; files preserved')
    data['forced_stop'] = forced
    record_resources(data)
    save(ROOT / f"{data['os']}-last-run.json", data)
    STATE.unlink(missing_ok=True)
    print(f"Stopped {data['os']} (pid {pid}, forced={forced})")
    return not forced


def start(os_name, visible=False):
    with locked():
        if current():
            raise RuntimeError('A qualification VM is already running; stop it first')
        folder = ROOT / os_name
        if not (folder / 'base.json').exists():
            raise RuntimeError(f'Build {os_name} base first')
        overlay = folder / 'run.qcow2'
        overlay.unlink(missing_ok=True)
        run('qemu-img', 'create', '-q', '-f', 'qcow2', '-F', 'qcow2', '-b', folder / 'base.qcow2', overlay)
        shutil.copyfile(folder / 'base-vars.fd', folder / 'run-vars.fd')
        return launch(os_name, overlay, folder / 'run-vars.fd', visible)


def ssh_args(os_name):
    cfg = config()
    return ['-i', str(ROOT / 'id_ed25519'), '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes',
            '-o', 'ConnectTimeout=5', '-o', 'StrictHostKeyChecking=accept-new',
            '-o', f'UserKnownHostsFile={ROOT}/{os_name}/known_hosts']


def ssh(os_name, cmd, **kwargs):
    if not current(os_name):
        raise RuntimeError(f'{os_name} is not running')
    cfg = config()
    return subprocess.run(['ssh', *ssh_args(os_name), '-p', str(OS[os_name]['ssh']),
                           f"{cfg['user']}@127.0.0.1", cmd], **kwargs)


def download(os_name):
    spec = OS[os_name]
    folder = ROOT / 'iso'
    folder.mkdir(exist_ok=True)
    target = folder / spec['iso']
    if not target.exists():
        # Redirects are checked before fetching their content; only official hosts.
        class Official(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, url):
                host = urllib.parse.urlparse(url).hostname or ''
                if not (host == 'releases.ubuntu.com' or host.endswith('.microsoft.com')):
                    raise RuntimeError(f'Non-official download redirect: {url}')
                return super().redirect_request(req, fp, code, msg, headers, url)
        opener = urllib.request.build_opener(Official())
        with opener.open(spec['url'], timeout=120) as src, target.with_suffix('.part').open('wb') as dst:
            shutil.copyfileobj(src, dst, 4 * 1024 * 1024)
        target.with_suffix('.part').replace(target)
    h = hashlib.sha256()
    with target.open('rb') as src:
        while block := src.read(4 * 1024 * 1024):
            h.update(block)
    if h.hexdigest() != spec['sha256']:
        raise RuntimeError(f'ISO SHA256 mismatch: {target}')
    print(f'ISO SHA256 verified: {h.hexdigest()}', flush=True)
    return target


def seed(os_name, iso, cfg):
    folder = ROOT / os_name
    media = folder / 'seed'
    media.mkdir(exist_ok=True)
    key = (ROOT / 'id_ed25519.pub').read_text().strip()
    if os_name == 'ubuntu':
        password_hash = subprocess.check_output(['openssl', 'passwd', '-6', '-stdin'],
                                                input=cfg['password'].encode()).decode().strip()
        content = (HERE / 'ubuntu-user-data').read_text().replace('@PASSWORD_HASH@', password_hash).replace('@SSH_KEY@', key)
        (media / 'user-data').write_text(content)
        (media / 'meta-data').write_text('instance-id: justsearch-qual-ubuntu\nlocal-hostname: qual-ubuntu\n')
        for name in ('vmlinuz', 'initrd'):
            if not (folder / name).exists():
                run('xorriso', '-osirrox', 'on', '-indev', iso, '-extract', f'/casper/{name}', folder / name,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        from xml.sax.saxutils import escape
        content = (HERE / 'autounattend.xml').read_text().replace('@PASSWORD@', escape(cfg['password']))
        (media / 'autounattend.xml').write_text(content)
        (media / 'bootstrap.ps1').write_text((HERE / 'windows-bootstrap.ps1').read_text().replace('@SSH_KEY@', key))
    run('genisoimage', '-quiet', '-J', '-r', '-V', 'cidata' if os_name == 'ubuntu' else 'QUAL',
        '-o', folder / 'seed.iso', media)


def build(os_name):
    cfg = config()
    folder = ROOT / os_name
    with locked():
        if current():
            raise RuntimeError('Stop the running qualification VM first')
        folder.mkdir(exist_ok=True)
        if (folder / 'base.qcow2').exists():
            raise RuntimeError(f'{folder}/base.qcow2 already exists; move it aside explicitly to rebuild')
    started = time.time()
    iso = download(os_name)
    seed(os_name, iso, cfg)
    with locked():
        if current():
            raise RuntimeError('A qualification VM started while preparing installer')
        run('qemu-img', 'create', '-q', '-f', 'qcow2', folder / 'install.qcow2', OS[os_name]['size'])
        shutil.copyfile(OVMF / 'OVMF_VARS_4M.fd', folder / 'install-vars.fd')
        (folder / 'known_hosts').unlink(missing_ok=True)
        data = launch(os_name, folder / 'install.qcow2', folder / 'install-vars.fd', installer=True)
    try:
        if os_name == 'windows':
            # UEFI Windows media asks for a key; the host supplies it automatically.
            for _ in range(12):
                time.sleep(1)
                with QMP(os_name) as q:
                    q.command('input-send-event', events=[{'type': 'key', 'data': {'down': True, 'key': {'type': 'qcode', 'data': 'ret'}}},
                                                          {'type': 'key', 'data': {'down': False, 'key': {'type': 'qcode', 'data': 'ret'}}}])
        deadline = time.monotonic() + 3 * 3600
        while time.monotonic() < deadline:
            resources(data)
            if not current():
                if os_name == 'ubuntu':
                    record_resources(data)
                    break  # autoinstall powers off; next boot validates completion
                raise RuntimeError('Windows installer stopped before SSH became available')
            if os_name == 'windows':
                probe = ssh(os_name, 'if exist C:\\Qual\\failed.txt (exit /b 2) else (if exist C:\\Qual\\ready.txt (exit /b 0) else (exit /b 1))',
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
                if probe.returncode == 2:
                    ssh(os_name, 'type C:\\Qual\\failed.txt & type C:\\Qual\\bootstrap.log')
                    raise RuntimeError('Windows first-logon preparation failed')
                if probe.returncode == 0:
                    break
            time.sleep(5)
        else:
            raise RuntimeError('Installer exceeded three hours; inspect serial/QEMU logs and screen')
        with locked():
            if not stop():
                raise RuntimeError('Guest required forced stop; installation disk preserved, base not sealed')
            # Boot the installed disk without installation media, still before sealing.
            launch(os_name, folder / 'install.qcow2', folder / 'install-vars.fd')
        deadline = time.monotonic() + 600
        check = 'test -f /var/lib/qual-ready' if os_name == 'ubuntu' else 'if exist C:\\Qual\\ready.txt (exit /b 0) else (exit /b 1)'
        while time.monotonic() < deadline:
            result = ssh(os_name, check, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
            if result.returncode == 0:
                break
            time.sleep(5)
        else:
            raise RuntimeError('Installed guest did not become ready over SSH within ten minutes')
        with locked():
            if not stop():
                raise RuntimeError('Guest required forced stop; installation disk preserved, base not sealed')
            (folder / 'install.qcow2').rename(folder / 'base.qcow2')
            (folder / 'install-vars.fd').rename(folder / 'base-vars.fd')
            info = {'os': os_name, 'seconds': round(time.time() - started, 1),
                    'sha256': OS[os_name]['sha256'], 'bytes': (folder / 'base.qcow2').stat().st_size,
                    'allocated_bytes': (folder / 'base.qcow2').stat().st_blocks * 512,
                    'autologin': True, 'memory_mib': MEMORY_MIB}
            save(folder / 'base.json', info)
            print(json.dumps(info), flush=True)
    finally:
        with locked():
            if current() and current()['os'] == os_name:
                stop()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    for action in ('build-base', 'start', 'ssh', 'copy-in'):
        p = sub.add_parser(action)
        p.add_argument('os', choices=OS)
        if action == 'start':
            p.add_argument('--visible', action='store_true')
        if action == 'ssh':
            p.add_argument('cmd')
        if action == 'copy-in':
            p.add_argument('src')
            p.add_argument('dst')
    for action in ('stop', 'reset', 'status'):
        sub.add_parser(action)
    args = parser.parse_args()
    if args.action == 'build-base':
        ROOT.mkdir(parents=True, exist_ok=True)
        with (ROOT / 'build.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            build(args.os)
    elif args.action == 'start':
        config()
        start(args.os, args.visible)
    elif args.action == 'stop':
        with locked():
            stop()
    elif args.action == 'reset':
        with locked():
            stop()
            for os_name in OS:
                for name in ('run.qcow2', 'run-vars.fd'):
                    (ROOT / os_name / name).unlink(missing_ok=True)
            print('Overlays removed; next start is clean')
    elif args.action == 'status':
        data = current()
        if data:
            data['proc_status'] = {line.split(':')[0]: line.split(':', 1)[1].strip()
                                   for line in Path(f"/proc/{data['pid']}/status").read_text().splitlines()
                                   if line.startswith(('VmRSS:', 'VmHWM:', 'Cpus_allowed_list:'))}
        print(json.dumps({'running': data, 'bases': {name: (ROOT / name / 'base.json').exists() for name in OS}}, indent=2))
    elif args.action == 'ssh':
        sys.exit(ssh(args.os, args.cmd).returncode)
    elif args.action == 'copy-in':
        if not current(args.os):
            raise RuntimeError(f'{args.os} is not running')
        cfg = config()
        run('scp', *ssh_args(args.os), '-P', str(OS[args.os]['ssh']), args.src,
            f"{cfg['user']}@127.0.0.1:{args.dst}")


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, subprocess.SubprocessError) as exc:
        sys.exit(str(exc))
