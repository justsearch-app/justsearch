#!/usr/bin/env python3
"""Run the shared Windows adapter on a disposable hosted interactive desktop."""
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    from runner import Infrastructure
    from windows import powershell
    guest = Path('C:/Qual/justsearch-qualification-guest')
    hosted = os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted'
    if not hosted and not (guest.is_file() and guest.read_text() == 'justsearch-vm\n'):
        raise Infrastructure('Windows entry point requires a disposable hosted runner or owned guest')
    if int(powershell('[Diagnostics.Process]::GetCurrentProcess().SessionId')) == 0:
        raise Infrastructure('Windows entry point requires an interactive logon session')
    tools = json.loads(Path('C:/Qual/tools.json').read_text())
    env = dict(os.environ)
    env['PATH'] = str(Path(tools['node']).parent) + os.pathsep + env['PATH']
    return subprocess.run([sys.executable, str(Path(__file__).with_name('runner.py')),
        '--lane', 'hosted-windows', '--driver', tools['driver'], '--native-driver', tools['nativeDriver'],
        *sys.argv[1:]], env=env).returncode


if __name__ == '__main__':
    sys.exit(main())
