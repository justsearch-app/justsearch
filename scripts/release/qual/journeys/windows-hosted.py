#!/usr/bin/env python3
"""Run the shared Windows adapter on a disposable hosted interactive desktop."""
import json
import os
from pathlib import Path
import subprocess
import sys
from contextlib import contextmanager


@contextmanager
def elevated_webdriver(elevated):
    """Elevated WebView2 ignores EdgeDriver's environment debugging flag.

    Use the supported machine policy for this app only, on the disposable
    hosted runner. Keep the normal profile and restore any prior policy.
    """
    if not elevated:
        yield
        return
    import winreg
    from runner import Infrastructure
    policy = r'SOFTWARE\Policies\Microsoft\Edge\WebView2\AdditionalBrowserArguments'
    with winreg.CreateKeyEx(winreg.HKEY_LOCAL_MACHINE, policy, 0,
                            winreg.KEY_QUERY_VALUE | winreg.KEY_SET_VALUE) as key:
        try:
            previous = winreg.QueryValueEx(key, 'JustSearch.exe')
        except FileNotFoundError:
            previous = None
        if previous and previous[1] != winreg.REG_SZ:
            raise Infrastructure('JustSearch WebView2 browser policy must be a string')
        flags = (previous[0] + ' ' if previous else '') + '--remote-debugging-port=0'
        try:
            winreg.SetValueEx(key, 'JustSearch.exe', 0, winreg.REG_SZ, flags)
            yield
        finally:
            if previous is None:
                winreg.DeleteValue(key, 'JustSearch.exe')
            else:
                winreg.SetValueEx(key, 'JustSearch.exe', 0, previous[1], previous[0])


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
    elevated = powershell('([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)') == 'True'
    with elevated_webdriver(elevated):
        return subprocess.run([sys.executable, str(Path(__file__).with_name('runner.py')),
            '--lane', 'hosted-windows', '--driver', tools['driver'], '--native-driver', tools['nativeDriver'],
            *sys.argv[1:]], env=env).returncode


if __name__ == '__main__':
    sys.exit(main())
