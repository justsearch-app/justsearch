#!/usr/bin/env python3
"""Disposable, single-guest QEMU release machines. Python standard library only."""
import argparse
import contextlib
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import secrets
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import time
import tempfile
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
    # Unique exclusive temporary files also keep the screen broker from following
    # a client-created symlink when publishing a reply.
    with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, prefix=path.name + '.', delete=False) as out:
        tmp = Path(out.name)
        try:
            json.dump(data, out, indent=2)
            out.write('\n')
            out.close()
            tmp.replace(path)
        finally:
            tmp.unlink(missing_ok=True)


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


def owner():
    value = os.environ.get('QUAL_OWNER', '').strip()
    if not value:
        data = read_state()
        raise RuntimeError(f"QUAL_OWNER is required (VM owner {data.get('owner', 'unknown')}; use the same token for the whole session)")
    return value


@contextlib.contextmanager
def locked(name='vm.lock'):
    ROOT.mkdir(parents=True, exist_ok=True)
    with (ROOT / name).open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            data = read_state()
            raise RuntimeError(f"Qualification VM lock held (owner {data.get('owner', 'unknown')}); wait-free first") from None
        yield lock


def identity(pid):
    try:
        stat = Path(f'/proc/{pid}/stat').read_text().split(') ', 1)[1].split()
        if stat[0] == 'Z':
            return None
        return stat[19]
    except (FileNotFoundError, PermissionError, ProcessLookupError):
        return None


def visible_process(data):
    return bool(data.get('start_ticks')) and identity(data['pid']) == data['start_ticks']


def read_state():
    try:
        data = json.loads(STATE.read_text())
        if (not isinstance(data, dict) or not {'os', 'pid', 'start_ticks'} <= data.keys()
                or not isinstance(data['os'], str) or data['os'] not in OS
                or type(data['pid']) is not int or data['pid'] <= 0
                or data['start_ticks'] is not None and (not isinstance(data['start_ticks'], str)
                                                       or not data['start_ticks'].isdigit())):
            raise ValueError('invalid running record')
        return data
    except FileNotFoundError:
        return {}
    except (ValueError, OSError) as exc:
        raise RuntimeError(f'Cannot read {STATE}; preserve it and investigate: {exc}') from exc


def port_open(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=.5):
            return True
    except ConnectionRefusedError:
        return False
    except OSError as exc:
        # EPERM in a restricted session, timeout, etc. cannot prove absence.
        raise RuntimeError(f'Cannot establish whether port {port} is free: {exc}; run on host') from exc


def live_reasons(check_lock=True):
    reasons = []
    if check_lock and (ROOT / 'vm.lock').exists():
        with (ROOT / 'vm.lock').open('r') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                reasons.append('VM lifetime lock held')
    for name, spec in OS.items():
        for kind in ('qmp', 'ssh'):
            try:
                if port_open(spec[kind]):
                    reasons.append(f'{name} {kind} port {spec[kind]} answers')
            except RuntimeError as exc:
                reasons.append(str(exc))
        for filename in ('run.qcow2', 'install.qcow2'):
            disk = ROOT / name / filename
            if disk.exists():
                result = subprocess.run(['qemu-img', 'info', '--output=json', str(disk)],
                                        capture_output=True, text=True, timeout=15)
                if result.returncode:
                    reasons.append(f'{disk} may be open: {result.stderr.strip()}')
    # Also detect a visible QEMU whose metadata/ports have been lost.
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit():
            continue
        try:
            cmd = (entry / 'cmdline').read_bytes().split(b'\0')
            if cmd and Path(os.fsdecode(cmd[0])).name == 'qemu-system-x86_64' and any(
                    part.startswith(b'justsearch-qual-') for part in cmd):
                reasons.append(f'harness QEMU pid {entry.name}')
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            continue
    data = read_state()
    if data and visible_process(data):
        reasons.append(f"recorded QEMU pid {data['pid']}")
    return reasons


def ensure_free(check_lock=True):
    reasons = list(live_reasons(check_lock))
    data = read_state()
    if data:
        reasons.insert(0, f"running.json exists (owner {data.get('owner', 'unknown')}); if stale, use clear-stale")
    if reasons:
        raise RuntimeError('Qualification VM may be running: ' + '; '.join(reasons))


def current(os_name=None):
    data = read_state()
    if not data:
        return None
    if os_name and data['os'] != os_name:
        raise RuntimeError(f"{data['os']} is reserved by owner {data.get('owner', 'unknown')}")
    return data


def require_owner(os_name=None):
    token = owner()
    data = current(os_name)
    if not data:
        raise RuntimeError('No qualification VM recorded')
    if data.get('owner') != token:
        raise RuntimeError(f"VM belongs to owner {data.get('owner', 'unknown')}; caller is {token}")
    return data


def clear_stale():
    with locked():
        reasons = live_reasons(check_lock=False)
        if reasons:
            raise RuntimeError('Refusing clear-stale: ' + '; '.join(reasons))
        data = read_state()
        if data:
            STATE.unlink()
            print(f"Cleared stale {data['os']} record (owner {data.get('owner', 'unknown')})")
        else:
            print('No stale record')


def wait_free(timeout):
    deadline = time.monotonic() + timeout
    while True:
        try:
            ensure_free()
            print('Qualification VMs free')
            return
        except RuntimeError as exc:
            if time.monotonic() >= deadline:
                raise RuntimeError(f'wait-free timed out: {exc}') from exc
        time.sleep(min(5, max(0, deadline - time.monotonic())))


class QMP:
    def __init__(self, os_name):
        require_owner(os_name)
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


def launch(os_name, disk, variables, visible=False, installer=None, lease=None):
    token = owner()
    ensure_free(check_lock=False)
    if lease is None:
        raise RuntimeError('launch requires the VM lifetime lock')
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
            '-add-fd', f'fd={lease},set=1,opaque=qual-lifetime-lock',
            '-monitor', 'none', '-serial', f'file:{ROOT}/{os_name}-serial.log',
            '-vga', 'std', '-device', 'qemu-xhci', '-device', 'usb-tablet',
            '-display', 'gtk,gl=off' if visible else 'none']
    if installer:
        args += ['-drive', f'file={ROOT}/iso/{OS[os_name]["iso"]},media=cdrom,readonly=on',
                 '-drive', f'file={ROOT}/{os_name}/seed.iso,media=cdrom,readonly=on',
                 '-boot', 'order=d,once=d']
        if os_name == 'ubuntu':
            # Keep QEMU/lease alive after autoinstall powers off; the builder can
            # observe shutdown and close its own run without a stale-record bypass.
            args += ['-no-shutdown']
            args += ['-kernel', str(ROOT / 'ubuntu/vmlinuz'), '-initrd', str(ROOT / 'ubuntu/initrd'),
                     '-append', 'boot=casper autoinstall ds=nocloud console=tty0 console=ttyS0,115200n8 ---']
    else:
        args += ['-boot', 'order=c']
    log_path = ROOT / f'{os_name}-qemu.log'
    with log_path.open('a') as log:
        log_start = log.tell()
        proc = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                start_new_session=True, pass_fds=(lease,))
    data = {'os': os_name, 'owner': token, 'pid': proc.pid, 'start_ticks': identity(proc.pid),
            'disk': str(disk), 'visible': visible, 'installing': bool(installer), 'cpus': cpus}
    try:
        save(STATE, data)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                with log_path.open() as log:
                    log.seek(log_start)
                    error = log.read().strip()
                raise RuntimeError(f'QEMU exited {proc.returncode}: {error}')
            try:
                with QMP(os_name) as q:
                    q.command('query-status')
                print(json.dumps(data), flush=True)
                return data
            except (OSError, RuntimeError):
                time.sleep(.25)
        raise RuntimeError('QMP did not become ready')
    except BaseException:
        # Reap only this launch's process before releasing its reservation. If it
        # cannot be stopped, preserve the record and disks for investigation.
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=10)
        if read_state() == data:
            STATE.unlink()
        raise


def resources(data):
    try:
        values = dict(line.split(':', 1) for line in Path(f"/proc/{data['pid']}/status").read_text().splitlines() if ':' in line)
        data['peak_rss_kib'] = max(data.get('peak_rss_kib', 0), int(values['VmHWM'].split()[0]))
        data['cpu_affinity'] = values['Cpus_allowed_list'].strip()
    except (FileNotFoundError, PermissionError, ProcessLookupError, KeyError):
        pass


def record_resources(data):
    evidence = ROOT / 'evidence'
    evidence.mkdir(exist_ok=True)
    with (evidence / 'resources.jsonl').open('a') as log:
        log.write(json.dumps(data) + '\n')


def stop():
    if not current():
        ensure_free()
        print('No qualification VM running')
        return True
    data = require_owner()
    if not live_reasons():
        raise RuntimeError('Stale running.json; use vm.py clear-stale explicitly')
    pid = data['pid']
    resources(data)
    try:
        with QMP(data['os']) as q:
            if q.command('query-status')['status'] == 'shutdown':
                q.command('quit')
            else:
                q.command('system_powerdown')
    except (OSError, RuntimeError):
        pass
    deadline = time.monotonic() + 180
    while live_reasons() and time.monotonic() < deadline:
        resources(data)
        time.sleep(.5)
    forced = bool(live_reasons())
    if forced:
        # QMP remains usable even when /proc cannot see QEMU. Only our owner can quit it.
        try:
            with QMP(data['os']) as q:
                q.command('quit')
        except (OSError, RuntimeError):
            pass
        if visible_process(data):
            fd = os.pidfd_open(pid)
            try:
                if visible_process(data):
                    signal.pidfd_send_signal(fd, signal.SIGTERM)
            finally:
                os.close(fd)
        deadline = time.monotonic() + 10
        while live_reasons() and time.monotonic() < deadline:
            time.sleep(.1)
    if live_reasons():
        raise RuntimeError(f'QEMU {pid} may still run; files and running.json preserved')
    data['forced_stop'] = forced
    record_resources(data)
    save(ROOT / f"{data['os']}-last-run.json", data)
    STATE.unlink()
    print(f"Stopped {data['os']} (pid {pid}, forced={forced})")
    return not forced


def start(os_name, visible=False):
    owner()
    ensure_free()
    with locked() as lock:
        ensure_free(check_lock=False)
        folder = ROOT / os_name
        if not (folder / 'base.json').exists():
            raise RuntimeError(f'Build {os_name} base first')
        config()
        overlay = folder / 'run.qcow2'
        variables = folder / 'run-vars.fd'
        overlay.unlink(missing_ok=True)
        variables.unlink(missing_ok=True)
        try:
            run('qemu-img', 'create', '-q', '-f', 'qcow2', '-F', 'qcow2', '-b', folder / 'base.qcow2', overlay)
            shutil.copyfile(folder / 'base-vars.fd', variables)
            return launch(os_name, overlay, variables, visible, lease=lock.fileno())
        except BaseException:
            if not current():
                overlay.unlink(missing_ok=True)
                variables.unlink(missing_ok=True)
            raise


def ssh_args(os_name):
    return ['-i', str(ROOT / 'id_ed25519'), '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes',
            '-o', 'ConnectTimeout=5', '-o', 'StrictHostKeyChecking=accept-new',
            '-o', f'UserKnownHostsFile={ROOT}/{os_name}/known_hosts']


def ssh(os_name, cmd, **kwargs):
    require_owner(os_name)
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
    owner()
    ensure_free()
    cfg = config()
    folder = ROOT / os_name
    with locked():
        ensure_free(check_lock=False)
        folder.mkdir(exist_ok=True)
        if (folder / 'base.qcow2').exists():
            raise RuntimeError(f'{folder}/base.qcow2 already exists; move it aside explicitly to rebuild')
    started = time.time()
    iso = download(os_name)
    seed(os_name, iso, cfg)
    with locked() as lock:
        ensure_free(check_lock=False)
        run('qemu-img', 'create', '-q', '-f', 'qcow2', folder / 'install.qcow2', OS[os_name]['size'])
        shutil.copyfile(OVMF / 'OVMF_VARS_4M.fd', folder / 'install-vars.fd')
        (folder / 'known_hosts').unlink(missing_ok=True)
        data = launch(os_name, folder / 'install.qcow2', folder / 'install-vars.fd', installer=True, lease=lock.fileno())
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
            if not live_reasons():
                raise RuntimeError('Installer exited unexpectedly; inspect logs and clear-stale explicitly')
            if os_name == 'ubuntu':
                with QMP(os_name) as q:
                    if q.command('query-status')['status'] == 'shutdown':
                        record_resources(data)
                        break  # autoinstall powered off; QEMU still holds its lease
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
        if not stop():
            raise RuntimeError('Guest required forced stop; installation disk preserved, base not sealed')
        with locked() as lock:
            # Boot the installed disk without installation media, still before sealing.
            launch(os_name, folder / 'install.qcow2', folder / 'install-vars.fd', lease=lock.fileno())
        deadline = time.monotonic() + 600
        check = 'test -f /var/lib/qual-ready' if os_name == 'ubuntu' else 'if exist C:\\Qual\\ready.txt (exit /b 0) else (exit /b 1)'
        while time.monotonic() < deadline:
            result = ssh(os_name, check, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
            if result.returncode == 0:
                break
            time.sleep(5)
        else:
            raise RuntimeError('Installed guest did not become ready over SSH within ten minutes')
        if not stop():
            raise RuntimeError('Guest required forced stop; installation disk preserved, base not sealed')
        with locked():
            ensure_free(check_lock=False)
            (folder / 'install.qcow2').rename(folder / 'base.qcow2')
            (folder / 'install-vars.fd').rename(folder / 'base-vars.fd')
            info = {'os': os_name, 'seconds': round(time.time() - started, 1),
                    'sha256': OS[os_name]['sha256'], 'bytes': (folder / 'base.qcow2').stat().st_size,
                    'allocated_bytes': (folder / 'base.qcow2').stat().st_blocks * 512,
                    'autologin': True, 'memory_mib': MEMORY_MIB}
            save(folder / 'base.json', info)
            print(json.dumps(info), flush=True)
    finally:
        if current() and current()['os'] == os_name:
            stop()


def guest_root(os_name, command):
    # The sealed Ubuntu user requires sudo authentication. Supply only this disposable
    # guest's password on stdin; never put it in argv, logs or the host sudo command.
    password = config()['password']
    return ssh(os_name, "sudo -S -p '' -- sh -c " + shlex.quote(command),
               input=(password + '\n').encode(), check=True)


def copy_in(os_name, src, dst):
    require_owner(os_name)
    cfg = config()
    run('scp', *ssh_args(os_name), '-P', str(OS[os_name]['ssh']), src,
        f"{cfg['user']}@127.0.0.1:{dst}")


def wait_ssh(os_name, timeout=600):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        require_owner(os_name)
        result = ssh(os_name, 'echo ready', stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
        if result.returncode == 0:
            return
        time.sleep(5)
    raise RuntimeError(f'{os_name} did not become ready over SSH within {timeout}s')


def powershell(script):
    import base64
    encoded = base64.b64encode(script.encode('utf-16le')).decode()
    return 'powershell.exe -NoProfile -NonInteractive -EncodedCommand ' + encoded


def archive_folder(folder, target):
    import tarfile
    folder = Path(folder).resolve(strict=True)
    if not folder.is_dir():
        raise ValueError(f'Not a directory: {folder}')
    # Do not copy files outside the supplied corpus/mirror via symlinks.
    paths = sorted(folder.rglob('*'))
    if any(p.is_symlink() or not (p.is_dir() or p.is_file()) for p in paths):
        raise ValueError('Only regular files and directories may be copied')
    with tarfile.open(target, 'w:gz') as tar:
        for path in paths:
            tar.add(path, arcname=str(path.relative_to(folder)), recursive=False)


def prepare_owner(os_name, package=None, corpus=None):
    package = Path(package).resolve(strict=True) if package else None
    if os_name == 'ubuntu' and package is None:
        raise ValueError('Ubuntu prepare-owner requires --package FILE.deb')
    if package and (not package.is_file() or package.suffix.lower() != ('.deb' if os_name == 'ubuntu' else '.exe')):
        raise ValueError('Expected a .deb on Ubuntu or an NSIS .exe on Windows')
    # Validate and pack before acquiring a VM or opening a visible window.
    with tempfile.TemporaryDirectory(prefix='prepare-owner-') as temp:
        archive = Path(temp) / 'corpus.tar.gz'
        if corpus:
            archive_folder(corpus, archive)
        data = start(os_name)
        try:
            wait_ssh(os_name)
            if os_name == 'ubuntu':
                copy_in(os_name, package, '/home/qual/qual-package.deb')
                guest_root(os_name, 'env DEBIAN_FRONTEND=noninteractive apt-get install -y /home/qual/qual-package.deb')
                if corpus:
                    copy_in(os_name, archive, '/home/qual/qual-corpus.tar.gz')
                    ssh(os_name, 'mkdir -p "$HOME/Documents" && tar -xzf "$HOME/qual-corpus.tar.gz" -C "$HOME/Documents" && rm "$HOME/qual-corpus.tar.gz"', check=True)
                ssh(os_name, "if pgrep -x JustSearch; then echo 'App unexpectedly started' >&2; exit 1; fi", check=True)
            else:
                ssh(os_name, powershell("$ErrorActionPreference='Stop'; New-Item -ItemType Directory -Force C:/Qual,$HOME/Documents | Out-Null"), check=True)
                if corpus:
                    copy_in(os_name, archive, 'C:/Qual/corpus.tar.gz')
                    ssh(os_name, powershell("& tar.exe -xzf C:/Qual/corpus.tar.gz -C ($HOME + '/Documents'); if ($LASTEXITCODE -ne 0) {exit $LASTEXITCODE}; Remove-Item C:/Qual/corpus.tar.gz"), check=True)
                if package:
                    copy_in(os_name, package, 'C:/Qual/package.exe')
                    # SSH is a service session. Task Scheduler runs the silent installer
                    # in the already logged-in user's interactive session/profile.
                    script = (HERE / 'windows-prepare.ps1').read_text()
                    ssh(os_name, powershell(script), check=True)
            # Install/copy headlessly, then reboot the prepared overlay visibly.
            # This keeps setup work off the owner's display.
            if not stop():
                raise RuntimeError('Preparation required forced shutdown; refusing owner handoff')
            with locked() as lock:
                folder = ROOT / os_name
                data = launch(os_name, folder / 'run.qcow2', folder / 'run-vars.fd',
                              visible=True, lease=lock.fileno())
            print(f"Ready for owner {data['owner']}: {os_name}, visible, app not started", flush=True)
            return data
        except BaseException as exc:
            # A failed visible relaunch has already cleaned up its own record.
            # Do not mask its QEMU diagnostic with a second cleanup error.
            if current():
                try:
                    stop()
                except Exception as cleanup:
                    exc.add_note(f'Preparation cleanup failed: {cleanup}')
            raise


def mirror(os_name, folder=None, remove=False, untrusted=False):
    require_owner(os_name)
    if os_name != 'ubuntu':
        raise ValueError('The update mirror currently supports Ubuntu only')
    if remove:
        guest_root(os_name, 'python3 /home/qual/qual-mirror-guest.py remove')
        return
    if not folder:
        raise ValueError('mirror requires a folder, or --remove')
    with tempfile.TemporaryDirectory(prefix='qual-mirror-') as temp:
        temp = Path(temp)
        archive_folder(folder, temp / 'files.tar.gz')
        run('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2',
            '-subj', '/CN=JustSearch qualification test CA', '-keyout', temp / 'ca.key', '-out', temp / 'ca.crt',
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        run('openssl', 'req', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=github.com',
            '-keyout', temp / 'server.key', '-out', temp / 'server.csr',
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        (temp / 'ext.cnf').write_text('subjectAltName=DNS:github.com\nbasicConstraints=CA:FALSE\nextendedKeyUsage=serverAuth\n')
        run('openssl', 'x509', '-req', '-days', '2', '-in', temp / 'server.csr', '-CA', temp / 'ca.crt',
            '-CAkey', temp / 'ca.key', '-CAcreateserial', '-extfile', temp / 'ext.cnf', '-out', temp / 'server.crt',
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        copy_in(os_name, HERE / 'mirror-guest.py', '/home/qual/qual-mirror-guest.py')
        ssh(os_name, 'mkdir -p /home/qual/qual-mirror-input && chmod 700 /home/qual/qual-mirror-input', check=True)
        for name in ('ca.crt', 'server.crt', 'server.key', 'files.tar.gz'):
            copy_in(os_name, temp / name, '/home/qual/qual-mirror-input/' + name)
        guest_root(os_name, 'python3 /home/qual/qual-mirror-guest.py setup' + (' --untrusted' if untrusted else ''))
        print('HTTPS mirror ready at https://github.com/ (CA ' + ('untrusted' if untrusted else 'in system trust store') + ')')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    for action in ('build-base', 'start', 'ssh', 'copy-in', 'prepare-owner', 'mirror'):
        p = sub.add_parser(action)
        p.add_argument('os', choices=OS)
        if action == 'start':
            p.add_argument('--visible', action='store_true')
        elif action == 'ssh':
            p.add_argument('cmd')
        elif action == 'copy-in':
            p.add_argument('src')
            p.add_argument('dst')
        elif action == 'prepare-owner':
            p.add_argument('--package')
            p.add_argument('--corpus')
        elif action == 'mirror':
            p.add_argument('folder', nargs='?')
            p.add_argument('--remove', action='store_true')
            p.add_argument('--untrusted', action='store_true')
    for action in ('stop', 'reset', 'status', 'clear-stale'):
        sub.add_parser(action)
    p = sub.add_parser('wait-free')
    p.add_argument('--timeout', type=float, required=True)
    args = parser.parse_args()
    if args.action == 'build-base':
        owner()
        ensure_free()
        with locked('build.lock'):
            build(args.os)
    elif args.action == 'start':
        start(args.os, args.visible)
    elif args.action == 'stop':
        with locked('control.lock'):
            stop()
    elif args.action == 'reset':
        owner()
        with locked('control.lock'):
            stop()
            with locked():
                ensure_free(check_lock=False)
                for os_name in OS:
                    for name in ('run.qcow2', 'run-vars.fd'):
                        (ROOT / os_name / name).unlink(missing_ok=True)
                print('Overlays removed; next start is clean')
    elif args.action == 'clear-stale':
        clear_stale()
    elif args.action == 'wait-free':
        if not math.isfinite(args.timeout) or args.timeout < 0:
            parser.error('--timeout must be finite and nonnegative')
        wait_free(args.timeout)
    elif args.action == 'status':
        data = current()
        reasons = live_reasons()
        if data:
            visible = visible_process(data)
            data['status'] = ('running' if visible else
                              f"running (owner {data.get('owner', 'unknown')}, process not visible)" if reasons else
                              'stale record; use clear-stale')
            if visible:
                resources(data)
        print(json.dumps({'running': data, 'signals': reasons,
                          'bases': {name: (ROOT / name / 'base.json').exists() for name in OS}}, indent=2))
    elif args.action == 'prepare-owner':
        with locked('control.lock'):
            prepare_owner(args.os, args.package, args.corpus)
    else:
        require_owner(args.os)
        if args.action == 'ssh':
            sys.exit(ssh(args.os, args.cmd).returncode)
        elif args.action == 'copy-in':
            copy_in(args.os, args.src, args.dst)
        elif args.action == 'mirror':
            mirror(args.os, args.folder, args.remove, args.untrusted)


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as exc:
        sys.exit(str(exc))
