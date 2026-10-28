#!/usr/bin/env python3
"""Installed-byte release journeys. Standard library; desktop tools are external.

The same checks run locally on a disposable runner or over the VM harness's SSH.
No production IPC is replaced, and no test-only application switches are used.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.request

HERE = Path(__file__).resolve().parent
STATUSES = ('passed', 'failed-product', 'failed-infrastructure', 'skipped-by-decision', 'unavailable')


def phase_journeys(phase):
    return {'J1', 'J2', 'J3', 'J4', 'J5', 'J6'} if phase == 'predecessor' else {'J1', 'J2', 'J3', 'J4', 'J7', 'J8'}


class Infrastructure(Exception):
    """An observed harness, desktop or download failure, not a product verdict."""


class Unavailable(Exception):
    """A prerequisite did not complete; the dependent check was not executed."""


def classify(exc):
    if isinstance(exc, Unavailable):
        return 'unavailable'
    return 'failed-product' if isinstance(exc, AssertionError) else 'failed-infrastructure'


def summarize(journeys):
    counts = dict.fromkeys(STATUSES, 0)
    for journey in journeys:
        for check in journey['checks']:
            if check['status'] not in counts:
                raise ValueError(f"Unknown check status: {check['status']}")
            counts[check['status']] += 1
    return {'counts': counts, 'passed': counts['passed'] > 0 and sum(counts.values()) == counts['passed']}


def preparation_failure(lane, reason):
    definition = json.loads((HERE / 'journeys.json').read_text())
    rows = [{'id': 'preparation', 'checks': [{'name': 'preparation', 'status': 'failed-infrastructure',
                                              'reason': reason, 'evidence': []}]}]
    for journey in definition['journeys']:
        rows.append({'id': journey['id'], 'checks': [
            {'name': check['id'], 'status': 'unavailable' if lane in check['lanes'] else 'skipped-by-decision',
             'reason': reason if lane in check['lanes'] else check['applicability'], 'evidence': []}
            for check in journey['checks']]})
    return {'schemaVersion': 1, 'lane': lane, 'scenarios': definition['scenarios'],
            'scope': definition['scope'], 'journeys': rows, **summarize(rows)}


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def verify_assets(root, names):
    """Hash every selected asset before any archive extraction or execution."""
    root = Path(root).resolve()
    sums = {}
    for line in (root / 'SHA256SUMS').read_text().splitlines():
        match = re.fullmatch(r'([a-fA-F0-9]{64}) [ *](.+)', line)
        if not match:
            raise Infrastructure('Malformed SHA256SUMS line')
        checksum, name = match.groups()
        if Path(name).name != name or name in sums:
            raise Infrastructure('Unsafe or duplicate SHA256SUMS filename')
        sums[name] = checksum.lower()
    result = {}
    for name in names:
        if Path(name).name != name or not (root / name).is_file() or (root / name).is_symlink():
            raise Infrastructure(f'Unsafe or missing release asset: {name}')
        actual = digest(root / name)
        if sums.get(name) != actual:
            raise Infrastructure(f'SHA256SUMS mismatch: {name}')
        result[name] = actual
    return result


def wait_for(fn, description, seconds=90, interval=1, error=AssertionError):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            value = fn()
            if value:
                return value
        except (OSError, KeyError, ValueError, urllib.error.URLError, Infrastructure) as exc:
            last = str(exc)
        time.sleep(interval)
    raise error(f'{description} timed out after {seconds}s; last observation: {last}')


def redact(value):
    if isinstance(value, dict):
        return {k: '<redacted>' if re.search('token|password|secret', k, re.I) else redact(v)
                for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v) for v in value]
    return value


class Target:
    def __init__(self, vm_name=None):
        self.vm = None
        self.name = vm_name
        self.env = {}
        if vm_name:
            sys.path.insert(0, str(HERE.parent))
            import vm
            vm.require_owner(vm_name)
            self.vm = vm

    def run(self, args, *, input=None, timeout=60, check=True, desktop=True):
        command = shlex.join(map(str, args))
        if desktop and self.env:
            # Deliberately drop developer runtime/model overrides in the child.
            command = 'env -i ' + shlex.join(f'{k}={v}' for k, v in self.env.items()) + ' ' + command
        if self.vm:
            proc = self.vm.ssh(self.name, command, input=input, capture_output=True, text=True, timeout=timeout)
        else:
            proc = subprocess.run(['sh', '-c', command], input=input, capture_output=True, text=True, timeout=timeout)
        if check and proc.returncode:
            raise Infrastructure(f'Command {args[0]} exited {proc.returncode}: {proc.stdout[-2000:]} {proc.stderr[-2000:]}')
        return proc

    def py(self, source, value=None, **kwargs):
        return self.run(['python3', '-c', source, json.dumps(value)], **kwargs).stdout

    def json(self, source, value=None, **kwargs):
        return json.loads(self.py(source, value, **kwargs))

    def prepare(self, lane, run_id):
        if self.vm:
            env = self.json("""
import json,os,subprocess
from pathlib import Path
for path in Path('/proc').glob('[0-9]*/comm'):
 try:
  if path.stat().st_uid == os.getuid() and path.read_text().strip() == 'gnome-shell':
   env=dict(v.split('=',1) for v in path.with_name('environ').read_bytes().decode().split('\\0') if '=' in v)
   # Mutter starts Xwayland after gnome-shell itself. Its current DISPLAY and
   # XAUTHORITY live in the user manager environment, not /proc at exec time.
   current=subprocess.check_output(['systemctl','--user','show-environment'],text=True)
   env.update(dict(v.split('=',1) for v in current.splitlines() if '=' in v))
   print(json.dumps(env));break
 except OSError: pass
else: raise RuntimeError('No logged-in GNOME session')
""", desktop=False)
        else:
            env = dict(os.environ)
        keys = ('HOME', 'USER', 'LOGNAME', 'PATH', 'DISPLAY', 'WAYLAND_DISPLAY', 'XAUTHORITY',
                'XDG_RUNTIME_DIR', 'DBUS_SESSION_BUS_ADDRESS', 'XDG_SESSION_TYPE', 'XDG_CURRENT_DESKTOP',
                'XDG_SESSION_DESKTOP', 'LANG', 'LC_ALL', 'XDG_DATA_DIRS', 'XDG_CONFIG_DIRS',
                'XDG_DATA_HOME', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME')
        self.env = {key: env[key] for key in keys if key in env}
        self.env.update({'QUAL_JOURNEY_RUN': run_id, 'OMP_NUM_THREADS': '8',
                         'OPENBLAS_NUM_THREADS': '8', 'RAYON_NUM_THREADS': '8'})
        if not self.env.get('DISPLAY'):
            raise Infrastructure('No graphical DISPLAY; use the logged-in session or xvfb-run')
        if lane.endswith('wayland') and not self.env.get('WAYLAND_DISPLAY'):
            raise Infrastructure('Wayland lane has no WAYLAND_DISPLAY')
        if lane.endswith('x11') and self.env.get('WAYLAND_DISPLAY'):
            raise Infrastructure('X11 lane is still logged into Wayland')
        return self.env

    def copy(self, source, destination):
        if self.vm:
            self.vm.copy_in(self.name, str(source), str(destination))
        else:
            import shutil
            shutil.copy2(source, destination)

    def spawn(self, args, logfile):
        return self.json("""
import json,subprocess,sys
from pathlib import Path
a=json.loads(sys.argv[1]); log=Path(a['log']);log.parent.mkdir(parents=True,exist_ok=True)
with log.open('ab') as out:
 p=subprocess.Popen(a['args'],stdout=out,stderr=out,stdin=subprocess.DEVNULL,start_new_session=True)
print(json.dumps({'pid':p.pid,'ticks':Path('/proc/'+str(p.pid)+'/stat').read_text().split(') ',1)[1].split()[19]}))
""", {'args': list(map(str, args)), 'log': logfile})

    def owned(self):
        # The marker is inherited only by processes this run starts, including
        # driver children, external viewers and updater relaunches.
        return self.json("""
import json,sys
from pathlib import Path
marker=('QUAL_JOURNEY_RUN='+json.loads(sys.argv[1])).encode(); rows=[]
for path in Path('/proc').glob('[0-9]*/environ'):
 try:
  if marker in path.read_bytes().split(b'\\0'):
   rows.append({'pid':int(path.parent.name),'ticks':path.with_name('stat').read_text().split(') ',1)[1].split()[19],
                'exe':str(path.with_name('exe').resolve())})
 except (OSError,IndexError): pass
print(json.dumps(rows))
""", self.env['QUAL_JOURNEY_RUN'], desktop=False)

    def stop(self, identities):
        self.py("""
import json,os,signal,sys,time
from pathlib import Path
rows=json.loads(sys.argv[1])
def same(row):
 try: return Path('/proc/'+str(row['pid'])+'/stat').read_text().split(') ',1)[1].split()[19] == row['ticks']
 except OSError: return False
for sig in (signal.SIGTERM,signal.SIGKILL):
 for row in rows:
  if same(row):
   try: os.kill(row['pid'],sig)
   except ProcessLookupError: pass
 if sig == signal.SIGTERM: time.sleep(3)
""", identities, desktop=False)

    def http(self, port, path, body=None, method=None):
        return self.json("""
import json,sys,urllib.request,urllib.error
a=json.loads(sys.argv[1]); data=None if a['body'] is None else json.dumps(a['body']).encode()
req=urllib.request.Request('http://127.0.0.1:'+str(a['port'])+a['path'],data=data,method=a['method'],headers={'Content-Type':'application/json'})
try:
 with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=180) as r:
  print(json.dumps(json.load(r)))
except urllib.error.HTTPError as e:
 print(json.dumps(json.load(e)))
""", {'port': port, 'path': path, 'body': body, 'method': method}, timeout=190)


DEEP_QUERY = """
function all(root, selector) {
 let found = Array.from(root.querySelectorAll(selector));
 for (const node of root.querySelectorAll('*')) if (node.shadowRoot) found.push(...all(node.shadowRoot, selector));
 return found;
}
"""


class WebDriver:
    def __init__(self, target, port):
        self.target, self.port, self.session = target, port, None

    def call(self, path, body=None, method=None):
        value = self.target.http(self.port, path, body, method).get('value')
        if isinstance(value, dict) and value.get('error'):
            raise Infrastructure(f"WebDriver {value['error']}: {value.get('message')}")
        return value

    def start(self, binary):
        value = self.call('/session', {'capabilities': {'alwaysMatch':
            {'tauri:options': {'application': binary}}}})
        self.session = value['sessionId']

    def execute(self, script, args=None):
        if not self.session:
            raise Unavailable('No attached WebDriver session')
        return self.call(f'/session/{self.session}/execute/sync', {'script': DEEP_QUERY + script, 'args': args or []})

    def element(self, selector, text=None, seconds=30):
        return wait_for(lambda: self.execute("""
return all(document,arguments[0]).find(e => {
 const r=e.getBoundingClientRect();
 return r.width>0 && r.height>0 && (!arguments[1] || (e.innerText || e.textContent || '').trim()===arguments[1]);
}) || null;
""", [selector, text]), f'Visible {selector} {text or ""}', seconds, error=Infrastructure)

    def click(self, selector, text=None):
        element = self.element(selector, text)
        point = self.execute("arguments[0].scrollIntoView({block:'center',inline:'nearest'});const r=arguments[0].getBoundingClientRect();return {x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2)}", [element])
        # Resolve the viewport point explicitly: WebKitGTK's element-origin
        # hit test can report an ancestor shadow host for nested components.
        return self.call(f'/session/{self.session}/actions', {'actions': [{
            'type': 'pointer', 'id': 'journey-mouse', 'parameters': {'pointerType': 'mouse'},
            'actions': [{'type': 'pointerMove', 'duration': 100,
                         'origin': 'viewport', 'x': point['x'], 'y': point['y']},
                        {'type': 'pointerDown', 'button': 0}, {'type': 'pointerUp', 'button': 0}]}]})

    def keys(self, selector, text):
        element = self.element(selector)
        key = element['element-6066-11e4-a52e-4f735466cecf']
        self.call(f'/session/{self.session}/element/{key}/clear', {})
        self.call(f'/session/{self.session}/element/{key}/value', {'text': text})

    def text(self):
        return self.execute("return all(document,'*').filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&!e.shadowRoot&&e.children.length===0&&!['SCRIPT','STYLE'].includes(e.tagName)}).map(e=>e.textContent).join('\\n');")

    def close(self):
        if self.session:
            try:
                self.call(f'/session/{self.session}', method='DELETE')
            finally:
                self.session = None


class Native:
    def __init__(self, target, evidence):
        self.target, self.evidence = target, evidence

    def key(self, combo):
        if self.target.vm:
            import screen
            self.target.vm.require_owner(self.target.name)
            with self.target.vm.QMP(self.target.name) as qmp:
                screen.key(qmp, combo)
        else:
            self.target.run(['xdotool', 'key', '--clearmodifiers', combo.replace('win', 'super').replace('enter', 'Return').replace('escape', 'Escape')])

    def type(self, text):
        if self.target.vm:
            import screen
            self.target.vm.require_owner(self.target.name)
            with self.target.vm.QMP(self.target.name) as qmp:
                screen.type_text(qmp, text)
        else:
            self.target.run(['xdotool', 'type', '--clearmodifiers', '--delay', '30', text])

    def shot(self, name):
        path = self.evidence / (name + '.png')
        if self.target.vm:
            import screen
            self.target.vm.require_owner(self.target.name)
            with self.target.vm.QMP(self.target.name) as qmp:
                screen.shot(qmp, path)
        else:
            self.target.run(['scrot', '--overwrite', str(path)])
        return path

    def screen_text(self, name, psm=11):
        shot = self.shot(name)
        return subprocess.check_output(['tesseract', str(shot), 'stdout', '--psm', str(psm)], stderr=subprocess.DEVNULL, text=True, timeout=30)

    def picker_visible(self, name):
        if self.target.vm:
            return 'select folder' in self.screen_text(name).lower()
        # Openbox's small title-bar font is not a reliable OCR target. X11
        # exposes the receiving native chooser's actual visible window title.
        self.shot(name)
        return self.target.run(['xdotool', 'search', '--onlyvisible', '--name', '^Select folder$'],
                               check=False).returncode == 0


class Run:
    def __init__(self, args, definition):
        self.args, self.definition = args, definition
        self.out = Path(args.evidence).resolve()
        self.out.mkdir(parents=True, exist_ok=False)
        self.target = Target(args.vm)
        self.native = Native(self.target, self.out)
        self.results = []
        self.facts = {}
        self.processes = []
        self.wd = None
        self.run_id = f'w4a-{time.time_ns()}'
        self.work = None
        self.data = None
        self.binary = None

    def evidence(self, name, value):
        (self.out / (name + '.json')).write_text(json.dumps(redact(value), indent=2) + '\n')

    def save(self):
        report = {'schemaVersion': 1, 'lane': self.args.lane, 'runId': self.run_id,
                  'scenarios': self.definition['scenarios'], 'assets': self.facts.get('assets', {}),
                  'scope': self.definition.get('scope', {}),
                  'journeys': self.results, **summarize(self.results)}
        self.evidence('summary', report)
        return report

    def check(self, journey, name, fn):
        row = {'name': name, 'status': 'unavailable', 'reason': 'interrupted before completion', 'evidence': []}
        journey['checks'].append(row)
        self.save()
        start = time.monotonic()
        before = set(self.out.iterdir())
        try:
            value = fn()
            row.update(status='passed', reason='assertions completed')
            if value is not None:
                self.evidence(journey['id'] + '-' + name, value)
        except Exception as exc:
            row.update(status=classify(exc), reason=str(exc))
            (self.out / f"{journey['id']}-{name}.error.txt").write_text(traceback.format_exc())
            if not isinstance(exc, Unavailable):
                try:
                    self.native.shot(journey['id'] + '-' + name + '-failure')
                except Exception:
                    pass
        row['seconds'] = round(time.monotonic() - start, 3)
        row['evidence'] = sorted(p.name for p in set(self.out.iterdir()) - before if p.name != 'summary.json')
        print(f"{journey['id']} {name}: {row['status']} — {row['reason']}", flush=True)
        self.save()

    def require(self, key):
        if key not in self.facts:
            raise Unavailable('Prerequisite unavailable: ' + key)
        return self.facts[key]

    def api(self, endpoint, body=None):
        # Follow the installed shell's production manifest, never a guessed port.
        # Verify its live instance before sending its owner-only credential.
        return self.target.json("""
import json,os,sys,urllib.request
from pathlib import Path
a=json.loads(sys.argv[1]); m=json.loads((Path(a['data'])/'runtime/manifest.json').read_text())
pid=m['pid']; os.kill(pid,0)
assert ('QUAL_JOURNEY_RUN='+a['runId']).encode() in Path('/proc/'+str(pid)+'/environ').read_bytes().split(b'\\0'), 'Engine not owned by this run'
port=m['head']['apiPort']; assert type(port) is int and 0<port<65536
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
base='http://127.0.0.1:'+str(port)
with opener.open(base+'/api/runtime/live',timeout=10) as r: live=json.load(r)
assert live['pid']==pid and live['instanceId']==m['instanceId'], 'Engine identity changed'
body=a['body']; req=urllib.request.Request(base+a['endpoint'],data=None if body is None else json.dumps(body).encode(),headers={'X-JustSearch-Session':m['head']['sessionToken'],'Content-Type':'application/json'})
with opener.open(req,timeout=30) as r: print(json.dumps(json.load(r)))
""", {'data': self.data, 'endpoint': endpoint, 'body': body, 'runId': self.run_id})

    def ready(self):
        def probe():
            status = self.api('/api/status')
            if status.get('knowledgeServerStartError'):
                raise AssertionError(status['knowledgeServerStartError'])
            health = self.api('/api/health')
            if status.get('indexAvailable') and health.get('components', {}).get('index', {}).get('state') == 'READY':
                return {'status': status, 'health': health}
        result = wait_for(probe, 'Installed Engine ready', seconds=240)
        self.facts['ready'] = True
        return result

    def start_ui(self):
        self.stop_app()
        driver = self.args.driver
        self.desktop_spawn([driver, '--port', '4444', '--native-port', '4445'], self.work + '/driver.log')
        self.wd = WebDriver(self.target, 4444)
        wait_for(lambda: self.target.http(4444, '/status'), 'tauri-driver ready', seconds=20, error=Infrastructure)
        self.wd.start(self.binary)
        self.observe_window('automation')
        self.ready()
        wait_for(lambda: self.wd.execute("return all(document,'button[data-surface-id]').length > 0"),
                 'Packaged UI rail mounted', seconds=60, error=Infrastructure)
        self.facts['ui'] = True

    def stop_app(self):
        self.facts.pop('ui', None)
        self.facts.pop('ready', None)
        if self.wd:
            try:
                self.wd.close()
            except Exception:
                pass
            self.wd = None
        # Only identities observed with our inherited marker are eligible.
        identities = self.target.owned()
        self.target.stop([row for row in identities if row['exe'].endswith(('/JustSearch', '/java', '/tauri-driver', '/WebKitWebDriver'))])

    def normal_launch(self, args=None):
        self.stop_app()
        self.desktop_spawn([self.binary, *(args or [])], self.work + '/normal.log')
        self.observe_window('normal')
        self.ready()

    def observe_window(self, name):
        name = name + '-' + str(time.time_ns())
        started = time.monotonic()
        text = wait_for(lambda: (s if 'JustSearch' in s or 'Search v3' in s else None)
                        if (s := self.native.screen_text('window-shown-' + name)) else None,
                        'Installed app window shown', seconds=60, error=Infrastructure)
        self.facts['windowShownAt'] = time.monotonic()
        self.facts['windowShownEvidence'] = 'window-shown-' + name + '.json'
        self.evidence('window-shown-' + name, {'text': text,
                      'secondsToObserve': round(time.monotonic() - started, 3),
                      'signal': 'Native screenshot OCR contains JustSearch or Search v3'})

    def desktop_spawn(self, command, logfile):
        if not self.target.vm:
            self.processes.append(self.target.spawn(command, logfile))
            return
        # Alt+F2 gives descendants the active graphical login session, which
        # PolicyKit needs. Merely exporting DISPLAY over SSH is insufficient.
        # One short launcher path is reused only after the previous invocation
        # acknowledged entry and exec'ed; none of these invocations overlap.
        path = self.work + '/launch.sh'
        ack = path + '.started'
        script = ': >' + shlex.quote(ack) + '\nexec env -i ' + shlex.join(f'{k}={v}' for k, v in self.target.env.items()) + ' ' + shlex.join(command)
        script += ' >>' + shlex.quote(logfile) + ' 2>&1\n'
        self.target.py('import json,sys;from pathlib import Path;a=json.loads(sys.argv[1]);Path(a[0]).write_text(a[1]);Path(a[0]+".started").unlink(missing_ok=True)', [path, script])
        self.native.key('escape')
        self.native.key('alt+f2')
        shot_name = 'native-launch-dialog-' + str(time.time_ns())
        wait_for(lambda: 'Run a Command' in self.native.screen_text(shot_name, psm=6),
                 'GNOME command dialog ready for input', seconds=20, error=Infrastructure)
        self.native.key('ctrl+a')
        self.native.type('sh ' + path)
        self.native.key('enter')
        wait_for(lambda: self.target.py('import json,sys;from pathlib import Path;print(Path(json.loads(sys.argv[1])).exists())', ack).strip() == 'True',
                 'Desktop launcher acknowledged execution', seconds=20, error=Infrastructure)

    def nav(self, surface):
        self.require('ui')
        ids = {'Library': 'core.library-surface', 'Search': 'core.search-v3-surface',
               'AI Brain': 'core.brain-surface', 'Settings': 'core.settings-surface'}
        # Rail buttons are ordinary native buttons in one shadow root. Use
        # WebDriver's element click so moved/recreated windows resolve their own
        # hit point; the viewport adapter remains for nested component controls.
        element = self.wd.element('button[data-surface-id=' + json.dumps(ids[surface]) + ']')
        key = element['element-6066-11e4-a52e-4f735466cecf']
        self.wd.call(f'/session/{self.wd.session}/element/{key}/click', {})

    def finish(self):
        errors = []
        try:
            for name in ('driver.log', 'normal.log', 'cold-deep-link.log', 'warm-deep-link.log'):
                try:
                    content = self.target.py('import json,sys;from pathlib import Path;p=Path(json.loads(sys.argv[1]));print(p.read_text(errors="replace")[-4000000:] if p.exists() else "not created")', self.work + '/' + name)
                    content = re.sub(r'("sessionToken"\s*:\s*")[^"]+', r'\1<redacted>', content)
                    (self.out / name).write_text(content)
                except Exception as exc:
                    errors.append(str(exc))
        finally:
            try:
                self.stop_app()
            except Exception as exc:
                errors.append(str(exc))
            try:
                self.target.stop(self.target.owned())
            except Exception as exc:
                errors.append(str(exc))
            if errors:
                self.results.append({'id': 'cleanup', 'checks': [{'name': 'cleanup',
                    'status': 'failed-infrastructure', 'reason': '; '.join(errors), 'evidence': []}]})
            self.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lane', choices=['hosted-linux-x11', 'vm-linux-wayland', 'vm-linux-x11'], required=True)
    parser.add_argument('--vm', choices=['ubuntu'])
    parser.add_argument('--phase', choices=['candidate', 'predecessor'], default='candidate',
                        help='VM wrapper runs predecessor J5/J6 on a separate fresh overlay')
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--package', required=True, help='basename within --assets')
    parser.add_argument('--mcpb', default='justsearch-mcp.mcpb')
    parser.add_argument('--registry', type=Path, help='server.json from the same tagged source (not executed)')
    parser.add_argument('--predecessor', type=Path)
    parser.add_argument('--mirror', type=Path)
    parser.add_argument('--driver', default='tauri-driver')
    parser.add_argument('--evidence', required=True, help='new, non-existing directory')
    args = parser.parse_args()
    if bool(args.vm) != args.lane.startswith('vm-'):
        parser.error('VM lanes require --vm; hosted lane runs inside its own Xvfb session')
    sys.modules['runner'] = sys.modules[__name__]
    from linux import CHECKS
    definition = json.loads((HERE / 'journeys.json').read_text())
    run = Run(args, definition)
    try:
        setup_error = None
        try:
            run.facts['assets'] = verify_assets(args.assets, [args.package, args.mcpb])
            run.evidence('journey-source-sha256', {p.name: digest(p) for p in HERE.iterdir()
                                                   if p.suffix in ('.py', '.json')})
            run.target.prepare(args.lane, run.run_id)
            run.work = run.target.py('import tempfile;print(tempfile.mkdtemp(prefix="justsearch-journey-"))').strip()
            run.data = run.target.py('import os;from pathlib import Path;print(Path(os.environ.get("XDG_DATA_HOME",str(Path.home()/".local/share")))/"io.justsearch.shell")').strip()
            existing = run.target.py('from pathlib import Path;import sys,json;print(Path(json.loads(sys.argv[1])).exists())', run.data).strip()
            if existing != 'False':
                raise Infrastructure('Fresh lane requires a clean app data folder; refusing inherited saved state')
            run.evidence('environment', {'session': run.target.env.get('XDG_SESSION_TYPE'),
                         'wayland': bool(run.target.env.get('WAYLAND_DISPLAY')), 'display': run.target.env.get('DISPLAY'),
                         'os': run.target.run(['cat', '/etc/os-release']).stdout,
                         'developerJavaPresent': run.target.run(['sh', '-c', 'command -v javac'], check=False).returncode == 0})
        except Exception as exc:
            setup_error = exc
        for journey in definition['journeys']:
            if journey['id'] not in phase_journeys(args.phase) and args.vm and args.lane != 'vm-linux-x11':
                continue
            row = {'id': journey['id'], 'title': journey['title'], 'scenarioIds': journey['scenarioIds'], 'checks': []}
            run.results.append(row)
            for check in journey['checks']:
                if args.lane not in check['lanes']:
                    row['checks'].append({'name': check['id'], 'status': 'skipped-by-decision',
                                          'reason': check['applicability'], 'evidence': []})
                elif setup_error:
                    row['checks'].append({'name': check['id'], 'status': classify(setup_error),
                                          'reason': 'setup: ' + str(setup_error), 'evidence': []})
                else:
                    run.check(row, check['id'], lambda c=check: CHECKS[c['id']](run))
            run.save()
    finally:
        if run.work:
            run.finish()
        else:
            run.save()
    report = run.save()
    print(json.dumps(report['counts'], indent=2), flush=True)
    # VM-only omissions are explicitly scoped decisions, never passes. Applicable
    # unavailable/failing checks always fail the lane even if earlier checks passed.
    return int(any(report['counts'][key] for key in ('failed-product', 'failed-infrastructure', 'unavailable')))


if __name__ == '__main__':
    sys.exit(main())
