#!/usr/bin/env python3
"""Disposable Ubuntu guest only: HTTPS github.com mirror with system CA trust."""
import argparse
import http.server
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import ssl
import subprocess
import sys
import tarfile
import time

ROOT = Path('/var/lib/justsearch-qual-mirror')
INPUT = Path('/home/qual/qual-mirror-input')
CA = Path('/usr/local/share/ca-certificates/justsearch-qual.crt')
HOSTS = Path('/etc/hosts')
MARKER = '# justsearch-qual-mirror'


def identity(pid):
    try:
        parts = Path(f'/proc/{pid}/stat').read_text().split(') ', 1)[1].split()
        return parts[19] if parts[0] != 'Z' else None
    except FileNotFoundError:
        return None


def remove():
    state = ROOT / 'server.json'
    if state.exists():
        data = json.loads(state.read_text())
        if data['ticks'] and identity(data['pid']) == data['ticks']:
            os.kill(data['pid'], signal.SIGTERM)
            deadline = time.monotonic() + 10
            while identity(data['pid']) == data['ticks'] and time.monotonic() < deadline:
                time.sleep(.1)
            if data['ticks'] and identity(data['pid']) == data['ticks']:
                raise RuntimeError('Mirror server did not stop; state preserved')
        state.unlink()
    original_hosts = ROOT / 'hosts.before'
    if original_hosts.exists():
        HOSTS.write_bytes(original_hosts.read_bytes())
    else:
        HOSTS.write_text(''.join(line for line in HOSTS.read_text().splitlines(keepends=True) if MARKER not in line))
    CA.unlink(missing_ok=True)
    subprocess.run(['update-ca-certificates', '--fresh'], check=True, stdout=subprocess.DEVNULL)
    if ROOT.exists():
        shutil.rmtree(ROOT)
    if INPUT.exists():
        shutil.rmtree(INPUT)
    print('Mirror removed; github.com hosts override and test CA removed', flush=True)


def setup(untrusted):
    # Keep the incoming replacement separate from remove() cleanup.
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / 'input'
        shutil.copytree(INPUT, source)
        remove()
        ROOT.mkdir(mode=0o700)
        (ROOT / 'www').mkdir()
        with tarfile.open(source / 'files.tar.gz') as archive:
            archive.extractall(ROOT / 'www', filter='data')
        for name in ('ca.crt', 'server.crt', 'server.key'):
            shutil.copyfile(source / name, ROOT / name)
            (ROOT / name).chmod(0o600)
    for line in HOSTS.read_text().splitlines():
        if 'github.com' in line.split('#', 1)[0].split()[1:]:
            raise RuntimeError('Existing github.com hosts entry; refusing to replace it')
    original = HOSTS.read_bytes()
    (ROOT / 'hosts.before').write_bytes(original)
    HOSTS.write_bytes(original + (b'\n' if original and not original.endswith(b'\n') else b'')
                     + ('127.0.0.1 github.com ' + MARKER + '\n').encode())
    if not untrusted:
        shutil.copyfile(ROOT / 'ca.crt', CA)
        CA.chmod(0o644)
        subprocess.run(['update-ca-certificates'], check=True, stdout=subprocess.DEVNULL)
    shutil.copyfile(__file__, ROOT / 'serve.py')
    with (ROOT / 'access.log').open('ab') as log:
        proc = subprocess.Popen([sys.executable, str(ROOT / 'serve.py'), 'serve'],
                                stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
    state = {'pid': proc.pid, 'ticks': identity(proc.pid)}
    (ROOT / 'server.json').write_text(json.dumps(state))
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError('Mirror exited: ' + (ROOT / 'access.log').read_text())
        try:
            with socket.create_connection(('127.0.0.1', 443), timeout=1):
                print('Mirror listening on HTTPS 443; CA trusted=' + str(not untrusted), flush=True)
                return
        except ConnectionRefusedError:
            time.sleep(.1)
    raise RuntimeError('Mirror did not listen within 10 seconds')


def serve():
    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(ROOT / 'www'), **kwargs)
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 443), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(ROOT / 'server.crt', ROOT / 'server.key')
    server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('setup', 'remove', 'serve'))
    parser.add_argument('--untrusted', action='store_true')
    args = parser.parse_args()
    if os.geteuid() != 0 or not Path('/var/lib/qual-ready').exists():
        sys.exit('Run as root inside the qualification Ubuntu guest only')
    if args.action == 'setup':
        try:
            setup(args.untrusted)
        except BaseException:
            remove()
            raise
    elif args.action == 'remove':
        remove()
    else:
        serve()
