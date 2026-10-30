#!/usr/bin/env python3
"""Installed-byte release journeys. Standard library; desktop tools are external.

The same checks run locally on a disposable runner or over the VM harness's SSH.
No production IPC is replaced, and no test-only application switches are used.
"""
import argparse
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
STATUSES = ('passed', 'failed-product', 'failed-infrastructure', 'skipped-by-decision', 'unavailable', 'owner-validation')


def phase_journeys(phase, lane=None):
    selected = {'J1', 'J2', 'J3', 'J4', 'J5', 'J6'} if phase == 'predecessor' else {'J1', 'J2', 'J3', 'J4', 'J7', 'J8'}
    if lane == 'vm-windows':
        selected.add('J6') # Also develop retention against the installed candidate.
    return selected


class Infrastructure(Exception):
    """An observed harness, desktop or download failure, not a product verdict."""


class RunStopped(Infrastructure):
    """The run has been stopped; no polling retry can recover it."""


class ProductFailure(AssertionError):
    """A reported product end state; polling must never hide it."""


class TransientAPI(AssertionError):
    """An Engine API not yet available; retry within the caller's deadline."""


class OwnerValidation(Exception):
    """Automatic evidence is complete; the pictured visible result needs its owner."""


class Unavailable(Exception):
    """A prerequisite did not complete; the dependent check was not executed."""


class SkippedByDecision(Exception):
    """An applicable conditional check was deliberately not required."""


def classify(exc):
    if isinstance(exc, SkippedByDecision):
        return 'skipped-by-decision'
    if isinstance(exc, Unavailable):
        return 'unavailable'
    return 'failed-product' if isinstance(exc, AssertionError) else 'failed-infrastructure'


def attribute_crashes(reports, installed_paths, processes):
    # Process identities include start ticks: a reused PID is not the observed app.
    # Apport can retain a child/parent relationship after the helper has exited.
    identities = {(str(p['pid']), str(p['ticks'])) for p in processes}
    ancestry = set(identities)
    for _ in range(len(reports) + 1):
        previous = set(ancestry)
        for report in reports:
            fields = report['fields']
            exe = fields.get('ExecutablePath', '').removesuffix(' (deleted)')
            status = dict(line.split(':', 1) for line in fields.get('ProcStatus', '').splitlines() if ':' in line)
            pid, parent = (status.get(key, '').strip() for key in ('Pid', 'PPid'))
            ticks = None
            stat = fields.get('ProcStat', '')
            if stat:
                try:
                    stat_pid, rest = stat.split(' (', 1)
                    values = rest.rsplit(') ', 1)[1].split()
                    if pid and pid != stat_pid:
                        raise ValueError('Crash PID differs between ProcStatus and ProcStat')
                    pid, parent, ticks = stat_pid, values[1], str(int(values[19]))
                except (ValueError, IndexError) as exc:
                    raise Infrastructure('Malformed crash process identity') from exc
            # Parent start ticks must precede the crashed child when available.
            descendant = any(parent == observed_pid and (ticks is None or int(start) <= int(ticks))
                             for observed_pid, start in ancestry)
            same_process = ticks is not None and (pid, ticks) in identities
            report['attribution'] = ('product' if fields.get('Package', '').split()[:1] == ['just-search']
                or exe in installed_paths
                or Path(exe).name in ('WebKitWebProcess', 'WebKitNetworkProcess')
                or (exe.endswith('/runtime/bin/java') and any(p.endswith('/runtime/bin/java') for p in installed_paths))
                or same_process or descendant else 'unattributed')
            if report['attribution'] == 'product' and pid.isdigit():
                # Without ProcStat, propagate only the report's parent start lower
                # bound; direct PID matches still always need an exact start tick.
                starts = [int(start) for observed_pid, start in ancestry if observed_pid == parent]
                if ticks is not None:
                    identities.add((pid, ticks))
                    ancestry.add((pid, ticks))
                elif starts:
                    ancestry.add((pid, str(min(starts))))
        if ancestry == previous:
            break


def collect_hosted_crashes(out, since):
    """Fold systemd-coredump observations into the completed hosted report."""
    out = Path(out)
    report = json.loads((out / 'summary.json').read_text())
    try:
        result = subprocess.run(['sudo', '-n', 'coredumpctl', '--json=short', '--no-pager',
                                 '--since', since, 'list'], capture_output=True, text=True)
        (out / 'coredumpctl.stderr.txt').write_text(result.stderr)
        # coredumpctl returns 1 for an empty journal, as well as for real errors.
        if result.returncode and not (result.returncode == 1
                and result.stderr.strip() == 'No coredumps found.'
                and result.stdout.strip() in ('', '[]')):
            raise Infrastructure('coredumpctl collection failed: ' + result.stderr)
        entries = json.loads(result.stdout) if result.stdout.strip() else []
        if not isinstance(entries, list):
            raise Infrastructure('coredumpctl did not return a JSON array')
        (out / 'coredumps.json').write_text(json.dumps(entries, indent=2) + '\n')
        paths = subprocess.run(['dpkg-query', '-L', 'just-search'], capture_output=True, text=True)
        if paths.returncode not in (0, 1):
            raise Infrastructure('Installed package paths unavailable for crash attribution')
        process_record = out / 'app-processes.json'
        processes = json.loads(process_record.read_text()) if process_record.exists() else []
        crashes = []
        for entry in entries:
            # coredumpctl's JSON list uses lowercase summary keys. Accept journal
            # fields too, when diagnostics supply the fuller process identity.
            fields = {key: str(entry.get(journal, entry.get(short, ''))) for key, journal, short in (
                ('ExecutablePath', 'COREDUMP_EXE', 'exe'), ('Signal', 'COREDUMP_SIGNAL', 'sig'),
                ('Package', 'COREDUMP_PACKAGE_NAME', 'package'), ('Date', 'COREDUMP_TIMESTAMP', 'time'),
                ('ProcStatus', 'COREDUMP_PROC_STATUS', 'status'), ('ProcStat', 'COREDUMP_PROC_STAT', 'stat'))}
            crashes.append({'fields': fields})
        attribute_crashes(crashes, set(paths.stdout.splitlines()) if paths.returncode == 0 else set(), processes)
        (out / 'hosted-crashes.json').write_text(json.dumps(crashes, indent=2) + '\n')
        for attribution, status in (('product', 'failed-product'), ('unattributed', 'owner-validation')):
            found = [c for c in crashes if c['attribution'] == attribution]
            if found:
                report['journeys'].append({'id': 'hosted-crashes', 'checks': [{
                    'name': attribution, 'status': status, 'reason': json.dumps(found),
                    'evidence': ['coredumps.json', 'hosted-crashes.json', 'app-processes.json']}]})
    except Exception as exc:
        report['journeys'].append({'id': 'hosted-crashes', 'checks': [{
            'name': 'collection', 'status': 'failed-infrastructure', 'reason': str(exc),
            'evidence': ['coredumpctl.stderr.txt']}]})
    report['journeys'].append({'id': 'hosted-crashes', 'checks': [{
        'name': 'hosted crash collection unproven', 'status': 'owner-validation',
        'reason': 'Hosted crash collection is not proven armed or finished; owner validation is required',
        'evidence': ['coredumps.json', 'hosted-crashes.json']}]})
    report.update(summarize(report['journeys'], report.get('fullSelection', False)))
    (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
    return report_exit_code(report)


def require_disposable_host():
    marker = Path('/run/justsearch-qualification-guest')
    if (os.environ.get('GITHUB_ACTIONS') == 'true'
            and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted'):
        return
    if (not marker.is_symlink() and marker.is_file() and marker.stat().st_uid == 0
            and marker.read_text() == 'justsearch-vm\n'):
        return
    raise Infrastructure('Hosted lane requires GITHUB_ACTIONS=true and RUNNER_ENVIRONMENT=github-hosted or the VM guest marker')


def predecessor_preparation(row, version):
    """Keep preparation evidence outside the candidate's journey identity."""
    row.update(id='predecessor-' + row['id'], scope='predecessor-preparation',
               predecessorVersion=version)
    for check in row['checks']:
        check['originalStatus'] = check['status']
        check['reason'] = (f"Predecessor {version} preparation {row['id']}/{check['name']} "
                           f"({check['status']}): " + check.get('reason', ''))
        if check['status'] == 'failed-product':
            check['status'] = 'owner-validation'
    return row


def full_selection(journeys, lane, definition=None):
    if definition is None:
        definition = json.loads((HERE / 'journeys.json').read_text())
    expected = {(j['id'], c['id']) for j in definition['journeys'] for c in j['checks']
                if lane in c['lanes']}
    observed = {(j['id'], c['name']) for j in journeys for c in j['checks']}
    return bool(expected) and expected <= observed


def summarize(journeys, full_selection=True):
    counts = dict.fromkeys(STATUSES, 0)
    for journey in journeys:
        for check in journey['checks']:
            if check['status'] not in counts:
                raise ValueError(f"Unknown check status: {check['status']}")
            counts[check['status']] += 1
    pending = [{'journey': j['id'], 'check': c['name'],
                **({'reason': c['reason'], 'predecessorVersion': j['predecessorVersion']}
                   if j.get('scope') == 'predecessor-preparation' else {}),
                **({'evidence': c.get('evidence', [])}
                   if j.get('scope') == 'predecessor-preparation' or j['id'] in ('guest-crashes-owner-validation', 'hosted-crashes', 'windows-crashes', 'gpu-host') else
                   {'screenshots': [p for p in c.get('evidence', []) if p.endswith('.png')]})}
               for j in journeys for c in j['checks'] if c['status'] == 'owner-validation']
    failed = any(counts[s] for s in ('failed-product', 'failed-infrastructure', 'unavailable'))
    complete = full_selection and counts['passed'] + counts['owner-validation'] > 0 and not failed
    status = ('failed' if failed else 'incomplete' if not complete else
              'passed-pending-owner-validation' if pending else 'passed')
    return {'counts': counts, 'fullSelection': full_selection,
            'passed': complete and not pending, 'status': status, 'ownerValidation': pending}



def report_exit_code(report):
    return 0 if report['passed'] else 2 if report['status'] == 'passed-pending-owner-validation' else 1


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
    for line in (root / 'SHA256SUMS').read_text(encoding='utf-8-sig').splitlines():
        if not line or line.startswith('#'):
            continue
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
    product_error = None
    while time.monotonic() < deadline:
        try:
            value = fn()
            last = value
            if value:
                return value
        except TransientAPI as exc:
            product_error = last = exc
        except RunStopped:
            raise
        except (OSError, KeyError, ValueError, urllib.error.URLError, Infrastructure) as exc:
            last = exc
        time.sleep(interval)
    if product_error:
        raise AssertionError(f'{description} timed out after {seconds}s; Engine failure: {product_error}; last observation: {last}')
    if isinstance(last, Exception):
        error = Infrastructure
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
        self.app_binary = None
        self.app_processes = {}
        self.process_evidence = None
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
        if not self.vm:
            require_disposable_host()
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
        rows = self.json("""
import json,sys
from pathlib import Path
marker=('QUAL_JOURNEY_RUN='+json.loads(sys.argv[1])).encode(); rows=[]
for path in Path('/proc').glob('[0-9]*/environ'):
 try:
  if marker in path.read_bytes().split(b'\\0'):
   rows.append({'pid':int(path.parent.name),'ticks':path.with_name('stat').read_text().split(') ',1)[1].split()[19],
                'exe':str(path.with_name('exe').resolve()),
                'ppid':int(path.with_name('stat').read_text().split(') ',1)[1].split()[1])})
 except (OSError,IndexError): pass
print(json.dumps(rows))
""", self.env['QUAL_JOURNEY_RUN'], desktop=False)
        if self.app_binary:
            app_pids = {p['pid'] for p in rows if (p['pid'], p['ticks']) in self.app_processes}
            for _ in range(len(rows) + 1):
                children = [p for p in rows if p['exe'].removesuffix(' (deleted)') == self.app_binary
                            or p['ppid'] in app_pids]
                next_pids = app_pids | {p['pid'] for p in children}
                for p in children:
                    self.app_processes[(p['pid'], p['ticks'])] = p
                if next_pids == app_pids:
                    break
                app_pids = next_pids
            if self.process_evidence:
                self.process_evidence.write_text(json.dumps(list(self.app_processes.values()), indent=2) + '\n')
        return rows

    def alive(self, identities):
        return self.json("""
import json,sys
from pathlib import Path
alive=[]
for row in json.loads(sys.argv[1]):
 try:
  stat=Path('/proc/'+str(row['pid'])+'/stat').read_text().split(') ',1)[1].split()
  if stat[19] == row['ticks'] and stat[0] != 'Z':alive.append(row)
 except FileNotFoundError:pass
print(json.dumps(alive))
""", identities, desktop=False)

    def stop(self, identities):
        self.py("""
import json,os,signal,sys,time
from pathlib import Path
rows=json.loads(sys.argv[1])
def same(row):
 try:
  stat=Path('/proc/'+str(row['pid'])+'/stat').read_text().split(') ',1)[1].split()
  return stat[19] == row['ticks'] and stat[0] != 'Z'
 except (FileNotFoundError,ProcessLookupError): return False
for sig in (signal.SIGTERM,signal.SIGKILL):
 for row in rows:
  if same(row):
   try: os.kill(row['pid'],sig)
   except ProcessLookupError: pass
 deadline=time.monotonic()+(3 if sig == signal.SIGTERM else 10)
 while any(same(row) for row in rows) and time.monotonic()<deadline:time.sleep(.1)
if any(same(row) for row in rows):raise RuntimeError('Owned processes did not stop: '+json.dumps([row for row in rows if same(row)]))
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

    def start(self, binary, *, webview_options=None):
        options = {'application': binary}
        if webview_options is not None:
            options['webviewOptions'] = webview_options
        value = self.call('/session', {'capabilities': {'alwaysMatch':
            {'tauri:options': options}}})
        self.session = value['sessionId']

    def execute(self, script, args=None):
        if not self.session:
            raise Unavailable('No attached WebDriver session')
        return self.call(f'/session/{self.session}/execute/sync', {'script': DEEP_QUERY + script, 'args': args or []})

    def element(self, selector, text=None, seconds=30, *, error=Infrastructure):
        return wait_for(lambda: self.execute("""
return all(document,arguments[0]).find(e => {
 const r=e.getBoundingClientRect();
 return r.width>0 && r.height>0 && (!arguments[1] || (e.innerText || e.textContent || '').trim()===arguments[1]);
}) || null;
""", [selector, text]), f'Visible {selector} {text or ""}', seconds, error=error)

    def click(self, selector, text=None, *, error=Infrastructure):
        element = self.element(selector, text, error=error)
        return self.click_element(element)

    def click_element(self, element):
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

    def windows(self):
        """One compositor observation supplies focus and mapped-window identities."""
        return self.target.json("""
import json,os,subprocess
if os.environ.get('WAYLAND_DISPLAY'):
 from gi.repository import Gio,GLib
 proxy=Gio.DBusProxy.new_for_bus_sync(Gio.BusType.SESSION,0,None,'org.gnome.Shell','/org/gnome/Shell','org.gnome.Shell',None)
 script='(()=>{let focus=global.display.focus_window;let rows=global.get_window_actors().filter(a=>a.is_mapped()).map(a=>{let w=a.meta_window,r=w.get_frame_rect();return {pid:w.get_pid(),title:w.get_title(),focused:w===focus,rect:{x:r.x,y:r.y,width:r.width,height:r.height}}});return {focused:rows.find(w=>w.focused)||null,windows:rows}})()'
 ok,raw=proxy.call_sync('Eval',GLib.Variant('(s)',(script,)),0,5000,None).unpack()
 if not ok:raise RuntimeError('GNOME window observation unavailable; prepare the disposable compositor debugger: '+raw)
 result=json.loads(raw)
else:
 def x(*args):return subprocess.check_output(['xdotool',*args],text=True).strip()
 focus=x('getwindowfocus')
 visible=subprocess.run(['xdotool','search','--onlyvisible','--name','.*'],capture_output=True,text=True)
 if visible.returncode not in (0,1):raise RuntimeError('X11 mapped-window observation unavailable: '+visible.stderr)
 rows=[]
 for window in visible.stdout.splitlines():
  try:
   geo=dict(line.split('=',1) for line in x('getwindowgeometry','--shell',window).splitlines())
   rows.append({'pid':int(x('getwindowpid',window)),'title':x('getwindowname',window),'focused':window==focus,
                'rect':{k:int(geo[v]) for k,v in [('x','X'),('y','Y'),('width','WIDTH'),('height','HEIGHT')]}})
  except subprocess.CalledProcessError:pass # A window can disappear during enumeration.
 result={'focused':next((w for w in rows if w['focused']),None),'windows':rows}
print(json.dumps(result))
""")

    def focused_window(self):
        return self.windows()['focused']

    def app_window(self, binary, focused=True, observation=None, exclude=()):
        if observation is None:
            observation = self.windows()
        pids = {row['pid'] for row in self.target.owned() if row['exe'] == binary
                and not any(row['pid'] == old['pid'] and row['ticks'] == old['ticks'] for old in exclude)}
        candidates = [observation['focused']] if focused else observation['windows']
        return next((w for w in candidates if w and w['pid'] in pids), None)

    def window_failure(self, name, binary):
        observation = self.windows()
        pids = [row['pid'] for row in self.target.owned() if row['exe'] == binary]
        (self.evidence / (name + '-window-miss.json')).write_text(
            json.dumps({'ownedPids': pids, **observation}, indent=2) + '\n')
        raise ProductFailure('Installed app has no owned, mapped window')

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
        text = subprocess.check_output(['tesseract', str(shot), 'stdout', '--psm', str(psm)], stderr=subprocess.DEVNULL, text=True, timeout=30)
        (self.evidence / (name + '.txt')).write_text(text)
        return text

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
        self.target.process_evidence = self.out / 'app-processes.json'
        self.native = Native(self.target, self.out)
        self.results = []
        self.facts = {}
        self.processes = []
        self.wd = None
        self.run_id = f'w4a-{time.time_ns()}'
        self.work = None
        self.data = None
        self.binary = None
        self.crash_since_ns = None

    def setup(self):
        self.target.prepare(self.args.lane, self.run_id)
        self.crash_since_ns = int(self.target.py('import time;print(time.time_ns())').strip())
        self.work = self.target.py('import tempfile;from pathlib import Path;p=Path.home()/".cache/justsearch-journeys";p.mkdir(parents=True,exist_ok=True);print(tempfile.mkdtemp(prefix="run-",dir=p))').strip()
        self.data = self.target.py('import os;from pathlib import Path;print(Path(os.environ.get("XDG_DATA_HOME",str(Path.home()/".local/share")))/"io.justsearch.shell")').strip()
        if self.args.resume:
            state = json.loads(self.args.resume.read_text())
            if state['assets'] != self.facts['assets'] or state['lane'] != self.args.lane or state['phase'] != self.args.phase:
                raise Infrastructure('Setup checkpoint does not match the selected assets, lane and phase')
            self.work, self.data, self.binary = (state[k] for k in ('work', 'data', 'binary'))
            self.facts.update(state['facts'])
            self.facts['resumedSetup'] = True
            self.start_ui()
        else:
            existing = self.target.py('from pathlib import Path;import sys,json;print(Path(json.loads(sys.argv[1])).exists())', self.data).strip()
            if existing != 'False':
                raise Infrastructure('Fresh lane requires a clean app data folder; refusing inherited saved state')
        self.evidence('environment', {'session': self.target.env.get('XDG_SESSION_TYPE'),
                     'wayland': bool(self.target.env.get('WAYLAND_DISPLAY')), 'display': self.target.env.get('DISPLAY'),
                     'os': self.target.run(['cat', '/etc/os-release']).stdout,
                     'developerJavaPresent': self.target.run(['sh', '-c', 'command -v javac'], check=False).returncode == 0})

    def evidence(self, name, value):
        (self.out / (name + '.json')).write_text(json.dumps(redact(value), indent=2) + '\n')

    def save(self):
        report = {'schemaVersion': 1, 'lane': self.args.lane, 'runId': self.run_id,
                  'scenarios': self.definition['scenarios'], 'assets': self.facts.get('assets', {}),
                  'scope': self.definition.get('scope', {}),
                  'predecessorVersion': (self.facts.get('predecessorPackage') or self.facts.get('initialPackage', {})).get('version') if getattr(self.args, 'phase', 'candidate') == 'predecessor' else None,
                  'journeys': self.results, **summarize(self.results,
                      full_selection(self.results, self.args.lane, self.definition)),
                  'execution': {'mode': 'development-rerun' if getattr(self.args, 'resume', None) else 'fresh',
                                'requestedJourneys': getattr(self.args, 'journeys', None)}}
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
        except OwnerValidation as exc:
            row.update(status='owner-validation', reason=str(exc))
        except SkippedByDecision as exc:
            row.update(status='skipped-by-decision', reason=str(exc))
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
        result = self.target.json("""
import http.client,json,os,sys,urllib.request,urllib.error
from pathlib import Path
a=json.loads(sys.argv[1])
try:
 m=json.loads((Path(a['data'])/'runtime/manifest.json').read_text())
 pid=m['pid']; os.kill(pid,0)
 assert ('QUAL_JOURNEY_RUN='+a['runId']).encode() in Path('/proc/'+str(pid)+'/environ').read_bytes().split(b'\\0'), 'Engine not owned by this run'
 port=m['head']['apiPort']; assert type(port) is int and 0<port<65536, 'Invalid Engine API port'
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 base='http://127.0.0.1:'+str(port)
 with opener.open(base+'/api/runtime/live',timeout=10) as r: live=json.load(r)
 assert live['pid']==pid and live['instanceId']==m['instanceId'], 'Engine identity changed'
 body=a['body']; req=urllib.request.Request(base+a['endpoint'],data=None if body is None else json.dumps(body).encode(),headers={'X-JustSearch-Session':m['head']['sessionToken'],'Content-Type':'application/json'})
 with opener.open(req,timeout=30) as r: value=json.load(r)
 print(json.dumps({'status':'ok','value':value}))
except (AssertionError,FileNotFoundError,ProcessLookupError,KeyError,ValueError,urllib.error.HTTPError,http.client.HTTPException,ConnectionError) as exc:
 print(json.dumps({'status':'product','reason':type(exc).__name__+': '+str(exc)}))
except urllib.error.URLError as exc:
 print(json.dumps({'status':'infrastructure' if isinstance(exc.reason,TimeoutError) else 'product','reason':str(exc)}))
except TimeoutError as exc:
 print(json.dumps({'status':'infrastructure','reason':str(exc)}))
""", {'data': self.data, 'endpoint': endpoint, 'body': body, 'runId': self.run_id})
        if result['status'] == 'product':
            raise TransientAPI('Installed Engine: ' + result['reason'])
        if result['status'] != 'ok':
            raise Infrastructure('Engine transport: ' + result.get('reason', 'invalid result'))
        return result['value']

    def ready(self):
        def probe():
            status = self.api('/api/status')
            if status.get('knowledgeServerStartError'):
                raise ProductFailure(status['knowledgeServerStartError'])
            health = self.api('/api/health')
            if status.get('indexAvailable') and health.get('components', {}).get('index', {}).get('state') == 'READY':
                return {'status': status, 'health': health}
        result = wait_for(probe, 'Installed Engine ready', seconds=240)
        self.facts['ready'] = True
        return result

    def start_ui(self):
        self.stop_app()
        self.target.app_binary = self.binary
        driver = self.args.driver
        port, native_port = self.facts.get('driverPorts', (4444, 4445))
        self.desktop_spawn([driver, '--port', str(port), '--native-port', str(native_port)], self.work + '/driver.log')
        self.wd = WebDriver(self.target, port)
        wait_for(lambda: self.target.http(port, '/status'), 'tauri-driver ready', seconds=20, error=Infrastructure)
        self.wd.start(self.binary)
        self.observe_window('automation')
        self.ready()
        wait_for(lambda: self.wd.execute("return all(document,'button[data-surface-id]').length > 0"),
                 'Packaged UI rail mounted', seconds=60)
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
        self.facts['preLaunchProcesses'] = identities
        paths = self.target.run(['dpkg-query', '-L', 'just-search']).stdout.splitlines()
        self.target.stop([row for row in identities if row['exe'].removesuffix(' (deleted)') in paths
                          or row['exe'].removesuffix(' (deleted)').endswith(('/tauri-driver', '/WebKitWebDriver'))])

    def normal_launch(self, args=None):
        self.stop_app()
        self.desktop_spawn([self.binary, *(args or [])], self.work + '/normal.log')
        self.observe_window('normal')
        self.ready()

    def observe_window(self, name):
        name = name + '-' + str(time.time_ns())
        started = time.monotonic()
        try:
            window = wait_for(lambda: self.native.app_window(self.binary, focused=False,
                                  exclude=self.facts.get('preLaunchProcesses', [])),
                              'Installed app window shown', seconds=60)
        except AssertionError:
            self.native.window_failure(name, self.binary)
        self.native.shot('window-shown-' + name)
        self.facts['windowShownAt'] = time.monotonic()
        self.facts['windowShownEvidence'] = 'window-shown-' + name + '.json'
        self.evidence('window-shown-' + name, {'window': window,
                      'secondsToObserve': round(time.monotonic() - started, 3),
                      'signal': "Mapped window belongs to this run's exact installed JustSearch executable"})

    def desktop_spawn(self, command, logfile):
        self.target.app_binary = self.binary
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

    def close_settings(self):
        script = "return all(document,'button[aria-label=\"Close Settings\"]').some(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0})"
        if self.wd.execute(script):
            self.wd.click('button[aria-label="Close Settings"]')
            wait_for(lambda: not self.wd.execute(script), 'Restored Settings closed before navigation',
                     seconds=30, error=ProductFailure)

    def nav(self, surface):
        self.require('ui')
        self.close_settings()
        ids = {'Library': 'core.library-surface', 'Search': 'core.search-v3-surface',
               'AI Brain': 'core.brain-surface', 'Settings': 'core.settings-surface'}
        # Rail buttons are ordinary native buttons in one shadow root. Use
        # WebDriver's element click so moved/recreated windows resolve their own
        # hit point; the viewport adapter remains for nested component controls.
        element = self.wd.element('button[data-surface-id=' + json.dumps(ids[surface]) + ']')
        key = element['element-6066-11e4-a52e-4f735466cecf']
        self.wd.call(f'/session/{self.wd.session}/element/{key}/click', {})

    def jvm_crashes(self):
        if self.crash_since_ns is None:
            raise Infrastructure('JVM crash collection has no run start timestamp')
        crashes = self.target.json("""
import json,subprocess,sys
from pathlib import Path
a=json.loads(sys.argv[1])
package=subprocess.run(['dpkg-query','-L','just-search'],capture_output=True,text=True)
if package.returncode not in (0,1):raise RuntimeError('Cannot locate Engine working directory for JVM crash collection')
roots={Path.home(),Path('/tmp'),Path.cwd(),Path(a['work']),Path(a['data'])/'crashes'}
roots.update(Path(p).parent for p in package.stdout.splitlines() if p.endswith('/ui-headless.jar'))
roots.update(Path(p) for p in a['engineDirectories'])
rows=[];seen=set();budget=20_000_000
for root in sorted(roots):
 try:files=sorted(root.iterdir())
 except FileNotFoundError:continue
 for path in files:
  if not path.match('hs_err_pid*.log'):continue
  if path.is_symlink():raise RuntimeError('JVM crash report is a symlink: '+str(path))
  stat=path.stat()
  if stat.st_mtime_ns<a['since'] or (stat.st_dev,stat.st_ino) in seen:continue
  seen.add((stat.st_dev,stat.st_ino))
  with path.open('rb') as stream:content=stream.read(min(4_000_000,budget))
  budget-=len(content)
  rows.append({'file':str(path),'bytes':stat.st_size,'truncated':len(content)<stat.st_size,
               'content':content.decode(errors='replace'),'attribution':'product'})
print(json.dumps(rows))
""", {'since': self.crash_since_ns, 'work': self.work, 'data': self.data,
      'engineDirectories': self.facts.get('engineWorkingDirectories', [])})
        self.evidence('jvm-crashes', crashes)
        if crashes:
            raise ProductFailure('Bundled JVM crash: ' + ', '.join(c['file'] for c in crashes))

    def finish(self):
        errors = []
        try:
            logs = [(name, self.work + '/' + name) for name in
                    ('driver.log', 'normal.log', 'cold-deep-link.log', 'warm-deep-link.log')]
            logs.append(('engine.log', self.data + '/logs/engine.log'))
            for name, source in logs:
                try:
                    content = self.target.py('import json,sys;from pathlib import Path;p=Path(json.loads(sys.argv[1]));print(p.read_text(errors="replace")[-4000000:] if p.exists() else "not created")', source)
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
            row = {'id': 'jvm-crashes', 'checks': []}
            self.results.append(row)
            self.check(row, 'bundled-jvm-crashes', self.jvm_crashes)
            if errors:
                self.results.append({'id': 'cleanup', 'checks': [{'name': 'cleanup',
                    'status': 'failed-infrastructure', 'reason': '; '.join(errors), 'evidence': []}]})
            self.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lane', choices=['hosted-linux-x11', 'vm-linux-wayland', 'vm-linux-x11', 'hosted-windows', 'vm-windows', 'gpu-linux'], required=True)
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
    parser.add_argument('--native-driver', default='msedgedriver.exe', help='Windows WebView2-matching driver')
    parser.add_argument('--evidence', required=True, help='new, non-existing directory')
    parser.add_argument('--journeys', nargs='+', help='Run only these journey IDs; no implicit dependencies')
    parser.add_argument('--resume', type=Path, help='Development only: restore a stopped setup checkpoint')
    parser.add_argument('--checkpoint', type=Path, help='Save stopped setup state for the VM wrapper')
    parser.add_argument('--source', type=Path, help='GPU: candidate source checkout, for the J9 release diff')
    parser.add_argument('--last-release', help='GPU: last release tag (default: latest reachable version tag)')
    parser.add_argument('--soak-corpus', type=Path, help='GPU: reusable, generated soak corpus directory')
    parser.add_argument('--development-pack-mirror', type=Path,
                        help='GPU development only: loopback pack mirror; never release qualification')
    parser.add_argument('--development-mirror-port', type=int, default=8769)
    args = parser.parse_args()
    if args.lane.endswith('windows') and args.vm:
        parser.error('Windows adapters execute inside the guest; do not pass --vm')
    if args.lane == 'gpu-linux' and (args.resume or args.checkpoint or args.predecessor or args.mirror or args.phase != 'candidate'):
        parser.error('GPU lane accepts fresh extracted candidates only; use gpu-host.py')
    if not args.lane.endswith('windows') and bool(args.vm) != args.lane.startswith('vm-'):
        parser.error('VM lanes require --vm; hosted lane runs inside its own Xvfb session')
    sys.modules['runner'] = sys.modules[__name__]
    if args.lane == 'gpu-linux':
        from gpu import Run as PlatformRun, CHECKS
    elif args.lane.endswith('windows'):
        from windows import Run as PlatformRun, CHECKS
    else:
        from linux import CHECKS
        PlatformRun = Run
    definition = json.loads((HERE / 'journeys.json').read_text())
    if args.journeys and not set(args.journeys) <= {j['id'] for j in definition['journeys']}:
        parser.error('Unknown journey ID')
    run = PlatformRun(args, definition)
    if args.lane == 'gpu-linux':
        import signal
        def interrupted(signum, frame):
            run.interrupted = True
            reason = Path(os.environ['QUAL_GPU_ROOT']) / 'stop-reason'
            raise RunStopped(reason.read_text() if reason.exists() else 'GPU lane supervisor interrupted the session')
        signal.signal(signal.SIGTERM, interrupted)
    try:
        setup_error = None
        try:
            run.facts['assets'] = verify_assets(args.assets, [args.package] if args.lane == 'gpu-linux' else [args.package, args.mcpb])
            run.evidence('journey-source-sha256', {p.name: digest(p) for p in HERE.iterdir()
                                                   if p.suffix in ('.py', '.json', '.ps1')})
            run.setup()
        except Exception as exc:
            setup_error = exc
        for journey in definition['journeys']:
            if args.journeys and journey['id'] not in args.journeys:
                continue
            if journey['id'] not in phase_journeys(args.phase, args.lane) and (args.vm or args.lane == 'vm-windows') and args.lane != 'vm-linux-x11':
                continue
            row = {'id': journey['id'], 'title': journey['title'], 'scenarioIds': journey['scenarioIds'], 'checks': []}
            run.results.append(row)
            from writes import COUNTERS, difference
            before_writes = None
            try:
                before_writes = run.write_counters() if args.lane.endswith('windows') or args.lane == 'gpu-linux' else run.target.json(COUNTERS)
            except Exception as exc:
                row['writeVolume'] = {'unavailable': str(exc)}
            for check in journey['checks']:
                if args.lane not in check['lanes']:
                    row['checks'].append({'name': check['id'], 'status': 'skipped-by-decision',
                                          'reason': check['applicability'], 'evidence': []})
                elif setup_error:
                    row['checks'].append({'name': check['id'], 'status': classify(setup_error),
                                          'reason': 'setup: ' + str(setup_error), 'evidence': []})
                else:
                    run.check(row, check['id'], lambda c=check: CHECKS[c['id']](run))
            if before_writes is not None:
                try:
                    row['writeVolume'] = difference(before_writes, run.write_counters() if args.lane.endswith('windows') or args.lane == 'gpu-linux' else run.target.json(COUNTERS))
                except Exception as exc:
                    row['writeVolume'] = {'unavailable': str(exc)}
            run.save()
    finally:
        if run.work:
            run.finish()
        else:
            run.save()
    checkpoint_ready = run.facts.get('folder') or (args.lane.endswith('windows') and run.facts.get('installed'))
    if args.checkpoint and not setup_error and run.binary and checkpoint_ready:
        # finish() has stopped every owned app/driver. Only durable facts cross
        # the disk snapshot; a new process gets a new ownership marker.
        args.checkpoint.write_text(json.dumps({'assets': run.facts['assets'], 'lane': args.lane,
            'phase': args.phase, 'work': run.work, 'data': run.data, 'binary': run.binary,
            'facts': {k: v for k, v in run.facts.items() if k not in ('ui', 'ready')}}, indent=2) + '\n')
    report = run.save()
    print(json.dumps(report['counts'], indent=2), flush=True)
    # VM-only omissions are explicitly scoped decisions, never passes. Applicable
    # unavailable/failing checks always fail the lane even if earlier checks passed.
    return report_exit_code(report)


if __name__ == '__main__':
    sys.exit(main())
