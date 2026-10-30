#!/usr/bin/env python3
"""Guest-only Windows HTTPS mirror; called by the owning host VM wrapper."""
import argparse
import ctypes
import functools
import http.server
import json
import os
import re
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import sys
import tarfile
import tempfile
import time

sys.path.insert(0, str(Path(__file__).parent / 'journeys'))
from windows import powershell, ps_json, ps_value, PROCESSES, same_process

ROOT = Path('C:/Qual/mirror')
INPUT = Path('C:/Qual/mirror-input')
HOSTS = Path('C:/Windows/System32/drivers/etc/hosts')
TASK = 'JustSearch-qual-https-mirror'


def stop_server(data):
    process = data.get('process')
    if process:
        rows = ps_json(PROCESSES, array=True)
        if any(same_process(p, process) for p in rows):
            # Revalidate immediately before killing; never taskkill by name.
            powershell('$p=Get-CimInstance Win32_Process -Filter ' + ps_value('ProcessId=' + str(process['pid'])) +
                '; if ($p -and ([Diagnostics.Process]::GetProcessById($p.ProcessId)).StartTime.ToUniversalTime().ToString("o") -ceq ' + ps_value(process['created']) +
                ' -and $p.ExecutablePath -ieq ' + ps_value(process['exe']) + ') {Stop-Process -Id $p.ProcessId -Force}')
            deadline = time.monotonic() + 15
            while any(same_process(p, process) for p in ps_json(PROCESSES, array=True)):
                if time.monotonic() >= deadline:
                    raise RuntimeError('Mirror process did not stop; state retained')
                time.sleep(.2)


def suspend():
    original, expected = ROOT / 'hosts.before', ROOT / 'hosts.mirror'
    state_path = ROOT / 'server.json'
    data = json.loads(state_path.read_text())
    wanted = original if data.get('suspended') else expected
    if HOSTS.read_bytes() != wanted.read_bytes():
        raise RuntimeError('Guest hosts changed during mirror; refusing to discard those edits')
    stop_server(data)
    HOSTS.write_bytes(original.read_bytes())
    subprocess.run(['ipconfig.exe', '/flushdns'], check=True, capture_output=True)
    (ROOT / 'ready.json').unlink(missing_ok=True)
    data.update(process=None, suspended=True)
    state_path.write_text(json.dumps(data))


def resume():
    data = json.loads((ROOT / 'server.json').read_text())
    if not data.get('suspended') or HOSTS.read_bytes() != (ROOT / 'hosts.before').read_bytes():
        raise RuntimeError('Guest hosts changed or mirror is not suspended')
    HOSTS.write_bytes((ROOT / 'hosts.mirror').read_bytes())
    subprocess.run(['ipconfig.exe', '/flushdns'], check=True, capture_output=True)
    data['suspended'] = False
    (ROOT / 'server.json').write_text(json.dumps(data))
    start_server(data)


def remove():
    state = ROOT / 'server.json'
    original, expected = ROOT / 'hosts.before', ROOT / 'hosts.mirror'
    data = json.loads(state.read_text()) if state.exists() else {}
    if original.exists() and HOSTS.read_bytes() != (original if data.get('suspended') else expected).read_bytes():
        raise RuntimeError('Guest hosts changed during mirror; refusing to discard those edits')
    if state.exists():
        if original.exists():
            suspend()
        else:
            stop_server(data)
        thumb = data.get('caThumbprint')
        if thumb:
            if not re.fullmatch(r'[0-9A-Fa-f]{40}', thumb):
                raise RuntimeError('Invalid recorded test CA thumbprint')
            certificate = ps_value('Cert:/LocalMachine/Root/' + thumb)
            powershell('if (Test-Path -LiteralPath ' + certificate + ') {Remove-Item -LiteralPath ' + certificate + ' -ErrorAction Stop}')
    if original.exists():
        HOSTS.write_bytes(original.read_bytes())
        subprocess.run(['ipconfig.exe', '/flushdns'], check=True, capture_output=True)
    powershell('if (Get-ScheduledTask -TaskName ' + ps_value(TASK) +
               ' -ErrorAction SilentlyContinue) {Unregister-ScheduledTask -TaskName ' + ps_value(TASK) + ' -Confirm:$false}')
    if ROOT.exists():
        shutil.rmtree(ROOT)
    if INPUT.exists():
        shutil.rmtree(INPUT)


def setup(untrusted):
    with tempfile.TemporaryDirectory(prefix='qual-mirror-') as tmp:
        incoming = Path(tmp) / 'input'
        shutil.copytree(INPUT, incoming)
        remove()
        ROOT.mkdir()
        (ROOT / 'www').mkdir()
        with tarfile.open(incoming / 'files.tar.gz') as archive:
            entries = archive.getmembers()
            if any(not (e.isfile() or e.isdir()) for e in entries):
                raise RuntimeError('Mirror archive must contain regular files/directories only')
            archive.extractall(ROOT / 'www', filter='data')
        for name in ('ca.crt', 'server.crt', 'server.key'):
            shutil.copyfile(incoming / name, ROOT / name)
    for line in HOSTS.read_text().splitlines():
        if 'github.com' in line.split('#', 1)[0].split()[1:]:
            raise RuntimeError('Existing github.com hosts entry; refusing to replace it')
    before = HOSTS.read_bytes()
    expected = before + b'\r\n127.0.0.1 github.com # justsearch-qual-mirror\r\n'
    (ROOT / 'hosts.before').write_bytes(before)
    (ROOT / 'hosts.mirror').write_bytes(expected)
    HOSTS.write_bytes(expected)
    subprocess.run(['ipconfig.exe', '/flushdns'], check=True, capture_output=True)
    state = {'process': None, 'caThumbprint': None}
    (ROOT / 'server.json').write_text(json.dumps(state))
    if not untrusted:
        state['caThumbprint'] = powershell('(Import-Certificate -FilePath C:/Qual/mirror/ca.crt -CertStoreLocation Cert:/LocalMachine/Root).Thumbprint')
        (ROOT / 'server.json').write_text(json.dumps(state))
    start_server(state)


def start_server(state):
    (ROOT / 'ready.json').unlink(missing_ok=True)
    # A scheduled interactive task survives the provisioning SSH job. Its script
    # and exact executable are fixed, and cleanup retains a creation-time witness.
    script = Path(__file__).resolve()
    powershell('$a=New-ScheduledTaskAction -Execute ' + ps_value(sys.executable) +
        ' -Argument ' + ps_value('"' + str(script) + '" serve') +
        ';$p=New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Highest;' +
        'Register-ScheduledTask -TaskName ' + ps_value(TASK) + ' -Action $a -Principal $p -Force | Out-Null;' +
        'Start-ScheduledTask -TaskName ' + ps_value(TASK))
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        ready = ROOT / 'ready.json'
        if ready.exists():
            state['process'] = json.loads(ready.read_text())
            (ROOT / 'server.json').write_text(json.dumps(state))
            with socket.create_connection(('127.0.0.1', 443), timeout=2):
                return
        time.sleep(.2)
    raise RuntimeError('Mirror task did not listen; see Windows task history')


def serve():
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 443),
        functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(ROOT / 'www')))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(ROOT / 'server.crt', ROOT / 'server.key')
    server.socket = context.wrap_socket(server.socket, server_side=True)
    row = next(p for p in ps_json(PROCESSES, array=True) if p['pid'] == os.getpid())
    (ROOT / 'ready.json').write_text(json.dumps(row))
    # Scheduled tasks have no inherited output handles; give the request log a
    # durable destination independent of the provisioning SSH connection.
    with (ROOT / 'access.log').open('a', buffering=1) as log:
        sys.stderr = log
        server.serve_forever()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['setup', 'remove', 'suspend', 'resume', 'serve'])
    parser.add_argument('--untrusted', action='store_true')
    args = parser.parse_args()
    if (os.name != 'nt' or not Path('C:/Qual/justsearch-qualification-guest').is_file()
            or Path('C:/Qual/justsearch-qualification-guest').read_text() != 'justsearch-vm\n'
            or not ctypes.windll.shell32.IsUserAnAdmin()):
        raise SystemExit('Run elevated inside the disposable Windows qualification guest only')
    if args.action == 'setup':
        try:
            setup(args.untrusted)
        except BaseException:
            remove()
            raise
    elif args.action == 'remove':
        remove()
    elif args.action == 'suspend':
        suspend()
    elif args.action == 'resume':
        resume()
    else:
        serve()


if __name__ == '__main__':
    main()
