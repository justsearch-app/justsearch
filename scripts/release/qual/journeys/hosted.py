#!/usr/bin/env python3
"""Host adapter: only run on a disposable Ubuntu runner, inside Xvfb + D-Bus.

Uses the logged-in runner HOME, with isolated XDG directories. This wrapper
owns its openbox process and viewer association. The journey uses apt itself.
"""
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile


def main():
    if not os.environ.get('DISPLAY') or not os.environ.get('DBUS_SESSION_BUS_ADDRESS'):
        raise SystemExit('Run under xvfb-run dbus-run-session; no graphical session detected')
    with tempfile.TemporaryDirectory(prefix='justsearch-hosted-') as temp:
        env = {key: value for key, value in os.environ.items()
               if not key.startswith(('JUSTSEARCH_', 'JAVA_', 'JDK_', 'LD_', 'CUDA_', 'TAURI_'))}
        env['PATH'] = os.pathsep.join(part for part in env.get('PATH', '').split(os.pathsep)
                                      if not re.search(r'java|jdk|jre|temurin|corretto|zulu', part, re.I))
        env.update(XDG_DATA_HOME=temp + '/data', XDG_CONFIG_HOME=temp + '/config',
                   XDG_CACHE_HOME=temp + '/cache', XDG_SESSION_TYPE='x11', XDG_CURRENT_DESKTOP='Openbox')
        env.pop('WAYLAND_DISPLAY', None)
        for name in ('data', 'config', 'cache', 'runtime'):
            Path(temp, name).mkdir(mode=0o700)
        env['XDG_RUNTIME_DIR'] = temp + '/runtime'
        # D-Bus was started before these isolated directories were created.
        # Its activated portal/GVfs services must join this same desktop, rather
        # than collide with another login's /run/user/<uid>/doc and gvfs mounts.
        names = [key for key in ('DISPLAY', 'XAUTHORITY', 'XDG_RUNTIME_DIR', 'XDG_DATA_HOME',
                                  'XDG_CONFIG_HOME', 'XDG_CACHE_HOME', 'XDG_CURRENT_DESKTOP', 'PATH') if key in env]
        subprocess.run(['dbus-update-activation-environment', *names], env=env, check=True)
        # The actual viewer receives the unique fixture through the OS opener.
        subprocess.run(['xdg-mime', 'default', 'org.xfce.mousepad.desktop', 'text/plain'], env=env, check=True)
        with Path(temp, 'openbox.log').open('wb') as log:
            wm = subprocess.Popen(['openbox'], env=env, stdout=log, stderr=log, start_new_session=True)
            try:
                return subprocess.run([sys.executable, str(Path(__file__).with_name('runner.py')),
                                       '--lane', 'hosted-linux-x11', *sys.argv[1:]], env=env).returncode
            finally:
                if wm.poll() is None:
                    os.killpg(wm.pid, signal.SIGTERM)
                    try:
                        wm.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(wm.pid, signal.SIGKILL)
                        wm.wait(timeout=10)


if __name__ == '__main__':
    sys.exit(main())
