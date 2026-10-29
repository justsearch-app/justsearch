"""Windows adapter for runner.py. Execute inside a disposable interactive guest.

No OCR evidence, application test hooks, developer JVM or seeded model files.
Windows process observations bind handle-derived creation times to PID/image paths.
"""
import base64
import json
import ntpath
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import threading
import zipfile
import stat
from pathlib import PurePosixPath

from runner import (Infrastructure, OwnerValidation, ProductFailure, Unavailable,
                    Target as LinuxTarget, Run as SharedRun,
                    WebDriver, digest, wait_for)


def regular_files(root):
    """Do not silently turn access errors or directory aliases into empty scans."""
    def failed(exc):
        raise Infrastructure('Crash scan failed: ' + str(exc)) from exc
    for directory, folders, files in os.walk(root, onerror=failed, followlinks=False):
        base = Path(directory)
        folders[:] = [name for name in folders if not (base/name).is_symlink()
                      and not (hasattr(Path, 'is_junction') and (base/name).is_junction())]
        for name in files:
            yield base/name


def match_driver(webview, driver):
    versions = [re.search(r'\b(\d+)\.(\d+)\.(\d+)\.(\d+)\b', value) for value in (webview, driver)]
    if any(v is None for v in versions) or versions[0].groups() != versions[1].groups():
        raise Infrastructure(f'WebView2/msedgedriver build mismatch: {webview!r}, {driver!r}')


def verify_driver_receipt(path, receipt):
    if receipt.get('version') != '2.0.4' or receipt.get('sha256', '').lower() != digest(path):
        raise Infrastructure('tauri-driver 2.0.4 build receipt/checksum mismatch')


def installer_result(observation):
    if observation.get('blockedBy'):
        raise Infrastructure('Installer blocked by environment: ' + observation['blockedBy'])
    if type(observation.get('exitCode')) is not int:
        raise Infrastructure('Installer completion was not observed')
    if observation['exitCode'] != 0:
        raise ProductFailure('NSIS installer exit code: ' + str(observation['exitCode']))


def same_process(a, b):
    return bool(a.get('created') and a.get('exe') and a.get('pid') and
                a.get('created') == b.get('created') and a.get('pid') == b.get('pid') and
                ntpath.normcase(a['exe']) == ntpath.normcase(b.get('exe', '')))


def crash_attribution(crash, processes, install_dir):
    image = ntpath.normcase(crash.get('exe') or '')
    root = ntpath.normcase(install_dir).rstrip('\\/') + '\\'
    if (image and image.startswith(root)) or any(same_process(crash, p) for p in processes):
        return 'product'
    # A bare parent PID can have been reused. Require its recorded creation time.
    if any(crash.get('ppid') == p['pid'] and crash.get('parentCreated') == p['created']
           for p in processes if p.get('created')):
        return 'product'
    return 'unattributed'


def update_result(observation, version):
    if (observation.get('installedVersion') != version or
            any(observation.get(key) is not True for key in ('window', 'engineReady', 'stateUnchanged')) or
            any(observation.get(key) is not False for key in ('oldShellAlive', 'oldEngineAlive'))):
        raise ProductFailure('Update did not complete with retained data and retired old processes: ' + json.dumps(observation))


def cancel_result(observation):
    if any(observation.get(key) is not True for key in
           ('installerObserved', 'installerExited', 'versionUnchanged', 'engineReady', 'reoffered')):
        raise ProductFailure('Declined install did not recover and re-offer: ' + json.dumps(observation))


def powershell(source, *, timeout=60):
    encoded = base64.b64encode(('$ErrorActionPreference="Stop"; ' + source).encode('utf-16-le')).decode()
    result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-EncodedCommand', encoded],
                            capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise Infrastructure(f'PowerShell observation failed (exit={result.returncode}): ' + result.stderr[-3000:] + result.stdout[-1000:])
    return result.stdout.strip().lstrip('\ufeff')


def ps_json(source, *, array=False):
    expression = '@(& {' + source + '})' if array else '& {' + source + '}'
    return json.loads(powershell('$qualValue=' + expression + '; ConvertTo-Json -InputObject $qualValue -Depth 12 -Compress'))


PROCESSES = r"""
@(Get-CimInstance Win32_Process | ForEach-Object {
 if ($_.ExecutablePath) {
  $qualProcess=$null
  try {
   $qualProcess=[Diagnostics.Process]::GetProcessById($_.ProcessId)
   $birth=$qualProcess.StartTime.ToUniversalTime().ToString('o')
   @{pid=[int]$_.ProcessId;ppid=[int]$_.ParentProcessId;exe=$_.ExecutablePath;created=$birth;command=$_.CommandLine}
  } catch [ArgumentException] {} catch [InvalidOperationException] {}
  finally {if($qualProcess){$qualProcess.Dispose()}}
 }
})
"""


def process_times(process):
    """Creation/exit times remain queryable on the held handle after exit."""
    import ctypes
    from ctypes import wintypes
    import datetime
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    get_times = kernel.GetProcessTimes
    get_times.argtypes = [wintypes.HANDLE, *([ctypes.POINTER(wintypes.FILETIME)] * 4)]
    get_times.restype = wintypes.BOOL
    created, exited, kernel_time, user_time = (wintypes.FILETIME() for _ in range(4))
    if not get_times(int(process._handle), ctypes.byref(created), ctypes.byref(exited), ctypes.byref(kernel_time), ctypes.byref(user_time)):
        raise Infrastructure('Cannot record held process creation time: ' + str(ctypes.get_last_error()))
    filetime = (created.dwHighDateTime << 32) | created.dwLowDateTime
    stamp = datetime.datetime(1601, 1, 1) + datetime.timedelta(seconds=filetime // 10_000_000)
    result = {'created': stamp.strftime('%Y-%m-%dT%H:%M:%S') + f'.{filetime % 10_000_000:07d}Z'}
    end = (exited.dwHighDateTime << 32) | exited.dwLowDateTime
    if end:
        ended = datetime.datetime(1601, 1, 1) + datetime.timedelta(seconds=end // 10_000_000)
        result['exited'] = ended.strftime('%Y-%m-%dT%H:%M:%S') + f'.{end % 10_000_000:07d}Z'
    return result


def spawned_identity(process, args):
    """Record the image once while alive, plus times from the held handle."""
    import ctypes
    from ctypes import wintypes
    result = process_times(process)
    query_image = ctypes.WinDLL('kernel32', use_last_error=True).QueryFullProcessImageNameW
    query_image.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
    query_image.restype = wintypes.BOOL
    buffer = ctypes.create_unicode_buffer(32768)
    size = wintypes.DWORD(len(buffer))
    if not query_image(int(process._handle), 0, buffer, ctypes.byref(size)):
        raise Infrastructure('Cannot record held process image: ' + str(ctypes.get_last_error()))
    return dict(result, pid=process.pid, ppid=os.getpid(), exe=buffer.value,
                command=subprocess.list2cmdline(list(map(str, args))))



def clean_environment(environment, run_id):
    allowed = {'SYSTEMROOT', 'SYSTEMDRIVE', 'WINDIR', 'COMSPEC', 'USERPROFILE', 'USERNAME', 'USERDOMAIN',
               'APPDATA', 'LOCALAPPDATA', 'TEMP', 'TMP', 'PROGRAMDATA', 'PROGRAMFILES', 'PROGRAMFILES(X86)',
               'PROGRAMW6432', 'COMMONPROGRAMFILES', 'COMMONPROGRAMFILES(X86)', 'ALLUSERSPROFILE',
               'HOMEDRIVE', 'HOMEPATH', 'PATH', 'PATHEXT', 'OS', 'PROCESSOR_ARCHITECTURE', 'NUMBER_OF_PROCESSORS'}
    result = {k.upper(): v for k, v in environment.items() if k.upper() in allowed}
    result['PATH'] = ';'.join(p for p in result.get('PATH', '').split(';')
                              if not re.search(r'java|jdk|jre|temurin|corretto|zulu', p, re.I))
    result.update(QUAL_JOURNEY_RUN=run_id, OMP_NUM_THREADS='8', OPENBLAS_NUM_THREADS='8', RAYON_NUM_THREADS='8')
    return result

class Target(LinuxTarget):
    def __init__(self):
        super().__init__()
        self.roots = []
        self.handles = {}
        self.guard = threading.RLock()
        self.observer_done = threading.Event()
        self.observer_errors = []
        self.observer = None
        self.last_process_evidence = None

    def run(self, args, *, input=None, timeout=60, check=True, desktop=True):
        args = list(map(str, args))
        # subprocess cannot directly execute .cmd files with shell=False. Resolve
        # the npm CLI's JavaScript entry instead of shell-interpolating arguments.
        if args[0] in ('npx', 'npm'):
            node = shutil.which('node')
            if not node:
                raise Infrastructure('Node qualification client is missing')
            args = [node, str(Path(node).parent / 'node_modules/npm/bin' / (args[0] + '-cli.js')), *args[1:]]
        result = subprocess.run(args, input=input, timeout=timeout, capture_output=True,
                                text=True, env=self.env if desktop and self.env else None)
        if check and result.returncode:
            raise Infrastructure(f'Command {args[0]} exited {result.returncode}: {result.stdout[-2000:]} {result.stderr[-2000:]}')
        return result

    def py(self, source, value=None, **kwargs):
        return self.run([sys.executable, '-c', source, json.dumps(value)], **kwargs).stdout

    def prepare(self, lane, run_id):
        if os.name != 'nt':
            raise Infrastructure('Windows adapter must execute inside Windows')
        hosted = os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted'
        marker = Path('C:/Qual/justsearch-qualification-guest')
        if not hosted and not (marker.is_file() and marker.read_text() == 'justsearch-vm\n'):
            raise Infrastructure('Windows journeys require a disposable hosted runner or VM guest marker')
        if int(powershell('[System.Diagnostics.Process]::GetCurrentProcess().SessionId')) == 0:
            raise Infrastructure('Windows journeys require the logged-in interactive desktop, not SSH session zero')
        self.env = clean_environment(os.environ, run_id)
        if shutil.which('javac', path=self.env['PATH']):
            raise Infrastructure('Clean-install prerequisite failed: developer javac remains on PATH')
        return self.env

    def copy(self, source, destination):
        if Path(source).resolve() != Path(destination).resolve():
            shutil.copy2(source, destination)

    def spawn(self, args, logfile):
        with open(logfile, 'ab') as log:
            process = subprocess.Popen(list(map(str, args)), env=self.env, stdin=subprocess.DEVNULL,
                                       stdout=log, stderr=log)
        row = spawned_identity(process, args)
        with self.guard:
            self.roots.append(row)
            self.handles[process.pid] = process
        return row

    def owned(self):
        with self.guard:
            return self._owned()

    def _owned(self):
        for pid, handle in self.handles.items():
            if handle.poll() is not None:
                ended = process_times(handle)
                for root in self.roots:
                    if root['pid'] == pid and root['created'] == ended['created'] and ended.get('exited'):
                        root['exited'] = ended['exited']
        rows = ps_json(PROCESSES, array=True)
        owned = [r for r in rows if any(same_process(r, p) for p in self.roots)]
        # CIM records the parent PID but not its lifetime. Bind each new child to
        # a parent that is alive in this observation and older than the child.
        for _ in range(len(rows) + 1):
            parents = owned + [p for p in self.roots if p.get('exited')]
            children = [dict(r, parentCreated=p['created']) for r in rows for p in parents
                        if r['ppid'] == p['pid'] and r['created'] >= p['created']
                        and (not p.get('exited') or r['created'] <= p['exited'])
                        and not any(same_process(r, a) for a in owned)]
            if not children:
                break
            owned.extend(children)
        for row in owned:
            self.roots = [p for p in self.roots if not same_process(p, row)] + [row]
        app = [r for r in owned if self.app_binary and ntpath.normcase(r['exe']) == ntpath.normcase(self.app_binary)]
        for _ in range(len(owned) + 1):
            descendants = [r for r in owned if any(r['ppid'] == p['pid'] and r.get('parentCreated') == p['created'] for p in app)
                           and not any(same_process(r, a) for a in app)]
            if not descendants:
                break
            app.extend(descendants)
        for row in app:
            self.app_processes[(row['pid'], row['created'])] = row
        if self.process_evidence:
            encoded = json.dumps(list(self.app_processes.values()), indent=2) + '\n'
            if encoded != self.last_process_evidence:
                self.process_evidence.write_text(encoded)
                self.last_process_evidence = encoded
        return owned

    def observe(self):
        def loop():
            while not self.observer_done.wait(1):
                try:
                    self.owned()
                except Exception as exc:
                    self.observer_errors.append(str(exc))
                    return
        self.observer = threading.Thread(target=loop, name='windows-process-evidence', daemon=True)
        self.observer.start()

    def end_observation(self):
        self.observer_done.set()
        if self.observer:
            self.observer.join(timeout=65)
            if self.observer.is_alive():
                raise Infrastructure('Process evidence observer did not stop')
        if self.observer_errors:
            raise Infrastructure('Process evidence observation failed: ' + '; '.join(self.observer_errors))

    def alive(self, identities):
        rows = ps_json(PROCESSES, array=True)
        return [r for r in rows if any(same_process(r, p) for p in identities)]

    def stop(self, identities):
        # Revalidate creation time and image immediately before terminating.
        payload = base64.b64encode(json.dumps(identities).encode()).decode()
        powershell(r"""
$rows = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('""" + payload + r"""')) | ConvertFrom-Json
foreach ($row in $rows) {
 try {$p=[Diagnostics.Process]::GetProcessById($row.pid)} catch [ArgumentException] {continue}
 try {
  $null=$p.Handle # pin the Windows process object before checking its lifetime
  if (!$p.HasExited -and $p.StartTime.ToUniversalTime().ToString('o') -ceq $row.created -and $p.MainModule.FileName -ieq $row.exe) {
   $p.Kill(); if(!$p.WaitForExit(20000)){throw 'Owned process did not stop'}
  }
 } finally {$p.Dispose()}
}
# A caught GetProcessById failure for an already-exited child can leave $?
# false. Reaching here means every requested identity was checked; unhandled
# errors still terminate under ErrorActionPreference=Stop.
exit 0
""")
        wait_for(lambda: not self.alive(identities), 'Owned processes stopped', seconds=20, error=Infrastructure)

    def http(self, port, path, body=None, method=None):
        import urllib.request
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        request = urllib.request.Request('http://127.0.0.1:' + str(port) + path,
            data=None if body is None else json.dumps(body).encode(), method=method,
            headers={'Content-Type': 'application/json'})
        import urllib.error
        try:
            with opener.open(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as exc:
            return json.load(exc)


UIA = r"""
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public class QualWindow {
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr handle);
 delegate bool WindowCallback(IntPtr handle,IntPtr data);
 [DllImport("user32.dll")] static extern bool EnumWindows(WindowCallback callback,IntPtr data);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr handle);
 public static IntPtr[] VisibleWindows() {
  var handles=new System.Collections.Generic.List<IntPtr>();
  if(!EnumWindows(delegate(IntPtr handle,IntPtr data){if(IsWindowVisible(handle))handles.Add(handle);return true;},IntPtr.Zero))
   throw new Exception("Native window enumeration failed");
  return handles.ToArray();
 }
}
'@
$root = [System.Windows.Automation.AutomationElement]::RootElement
$children = [System.Windows.Automation.TreeScope]::Children
$descendants = [System.Windows.Automation.TreeScope]::Descendants
$all = [System.Windows.Automation.Condition]::TrueCondition
"""


class Native:
    def __init__(self, target, evidence):
        self.target, self.evidence = target, evidence
        self._automation = None

    def automation(self):
        if self._automation is None:
            # Use the native COM client. The legacy managed UIA provider reports
            # standard Win32 controls as opaque panes in this Windows guest.
            sys.coinit_flags = 0
            import comtypes.client
            module = comtypes.client.GetModule(str(Path(os.environ['WINDIR']) / 'System32/UIAutomationCore.dll'))
            client = comtypes.client.CreateObject(module.CUIAutomation, interface=module.IUIAutomation)
            self._automation = client, module
        return self._automation

    def element(self, window):
        process = window['process']
        if not self.target.alive([process]):
            raise Infrastructure('Native UIA target process identity changed')
        client, _ = self.automation()
        element = client.ElementFromHandle(window['handle'])
        if (element.CurrentProcessId != process['pid'] or element.CurrentNativeWindowHandle != window['handle']
                or element.CurrentClassName != window['class']):
            raise Infrastructure('Native UIA target window identity changed')
        return element

    def descendants(self, window):
        client, module = self.automation()
        elements = self.element(window).FindAll(module.TreeScope_Descendants, client.CreateTrueCondition())
        return [elements.GetElement(i) for i in range(elements.Length)]

    def buttons(self, window, identifier):
        buttons = [e for e in self.descendants(window) if e.CurrentAutomationId == str(identifier)
                   and e.CurrentClassName == 'Button' and e.CurrentIsEnabled and not e.CurrentIsOffscreen]
        if any(e.CurrentProcessId != window['process']['pid'] for e in buttons):
            raise Infrastructure('Native button process identity differs from its dialog')
        return buttons

    def windows(self):
        rows = ps_json(UIA + r"""
@([QualWindow]::VisibleWindows() | ForEach-Object {
 # UIA can parent an owned native dialog under its app window. Enumerating
 # desktop children alone omits that dialog; each HWND still gets UIA identity.
 $element=[System.Windows.Automation.AutomationElement]::FromHandle($_)
 $c=$element.Current
 if ($c.NativeWindowHandle -and !$c.IsOffscreen) {
  @{pid=$c.ProcessId;handle=$c.NativeWindowHandle;title=$c.Name;class=$c.ClassName;
    automationId=$c.AutomationId;focused=([QualWindow]::GetForegroundWindow().ToInt64() -eq $c.NativeWindowHandle)}
 }
})
""", array=True)
        return rows

    def app_window(self, binary, focused=True, observation=None, exclude=()):
        processes = self.target.owned()
        for window in (observation if observation is not None else self.windows()):
            if focused and not window['focused']:
                continue
            process = next((p for p in processes if p['pid'] == window['pid'] and
                            ntpath.normcase(p['exe']) == ntpath.normcase(binary) and
                            not any(same_process(p, old) for old in exclude)), None)
            if process and self.target.alive([process]):
                return dict(window, process=process)

    def window_failure(self, name, binary):
        (self.evidence / (name + '-windows.json')).write_text(json.dumps(self.windows(), indent=2))
        raise ProductFailure('No UI Automation window belonging to the exact installed app process')

    def shot(self, name):
        path = self.evidence / (name + '.png')
        encoded = base64.b64encode(str(path).encode()).decode()
        powershell(r"""
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$p=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('""" + encoded + r"""'))
$bounds=[System.Windows.Forms.SystemInformation]::VirtualScreen
$bitmap=New-Object Drawing.Bitmap $bounds.Width,$bounds.Height
$graphics=[Drawing.Graphics]::FromImage($bitmap)
try {$graphics.CopyFromScreen($bounds.Location,[Drawing.Point]::Empty,$bounds.Size);$bitmap.Save($p,[Drawing.Imaging.ImageFormat]::Png)}
finally {$graphics.Dispose();$bitmap.Dispose()}
""")
        return path

    def key(self, combo):
        keys = {'escape': '{ESC}', 'enter': '{ENTER}', 'ctrl+a': '^a'}
        if combo not in keys:
            raise Infrastructure('Unsupported Windows native key: ' + combo)
        window = self.app_window(self.target.app_binary, focused=True)
        if not window:
            raise Infrastructure('Refusing keyboard input without the exact app foreground window')
        self.send(window, keys[combo])

    def send(self, window, keys):
        process = window['process']
        payload = base64.b64encode(json.dumps({'window': window, 'process': process, 'keys': keys}).encode()).decode()
        powershell(UIA + r"""
$a=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('""" + payload + r"""')) | ConvertFrom-Json
$p=Get-CimInstance Win32_Process -Filter "ProcessId=$($a.process.pid)"
if (!$p -or ([Diagnostics.Process]::GetProcessById($p.ProcessId)).StartTime.ToUniversalTime().ToString('o') -cne $a.process.created -or $p.ExecutablePath -ine $a.process.exe) {throw 'Input target process changed'}
$w=[System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]$a.window.handle)
if ($w.Current.ProcessId -ne $a.process.pid) {throw 'Input target window changed'}
[void][QualWindow]::SetForegroundWindow([IntPtr]$a.window.handle)
if ([QualWindow]::GetForegroundWindow().ToInt64() -ne $a.window.handle) {throw 'Input target is not foreground'}
[System.Windows.Forms.SendKeys]::SendWait($a.keys)
""")

    def select_folder(self, folder):
        def picker():
            processes = self.target.owned()
            windows = self.windows()
            (self.evidence / 'J2-picker-observation.json').write_text(json.dumps({'windows':windows,'processes':processes},indent=2))
            for window in windows:
                if window['class'] != '#32770':
                    continue
                process = next((p for p in processes if p['pid'] == window['pid'] and
                                ntpath.normcase(p['exe']) == ntpath.normcase(self.target.app_binary)), None)
                if process:
                    return dict(window, process=process)
        window = wait_for(picker, 'System folder picker belonging to the installed app', seconds=30, error=ProductFailure)
        client, module = self.automation()
        controls = self.descendants(window)
        (self.evidence / 'J2-picker-controls.json').write_text(json.dumps([
            {'name': e.CurrentName, 'automationId': e.CurrentAutomationId, 'class': e.CurrentClassName,
             'type': e.CurrentControlType, 'handle': e.CurrentNativeWindowHandle,
             'valuePattern': bool(e.GetCurrentPropertyValue(module.UIA_IsValuePatternAvailablePropertyId)),
             'invokePattern': bool(e.GetCurrentPropertyValue(module.UIA_IsInvokePatternAvailablePropertyId))}
            for e in controls], indent=2))
        edits = [e for e in controls if e.CurrentAutomationId == '1152' and e.CurrentClassName == 'Edit']
        buttons = [e for e in controls if e.CurrentAutomationId == '1' and e.CurrentClassName == 'Button']
        if len(edits) != 1 or len(buttons) != 1:
            raise Infrastructure('Unique native picker controls unavailable; see J2-picker-controls.json')
        edit, button = edits[0], buttons[0]
        if any(e.CurrentProcessId != window['process']['pid'] or not e.CurrentIsEnabled for e in (edit, button)):
            raise Infrastructure('Native picker control owner/enabled state differs')
        value = edit.GetCurrentPattern(module.UIA_ValuePatternId).QueryInterface(module.IUIAutomationValuePattern)
        self.element(window) # Revalidate lifetime/window immediately before input.
        value.SetValue(folder)
        if value.CurrentValue != folder:
            raise Infrastructure('Native picker did not accept the requested path')
        invoke = button.GetCurrentPattern(module.UIA_InvokePatternId).QueryInterface(module.IUIAutomationInvokePattern)
        result = {'handle':window['handle'], 'pid':window['pid'], 'value':folder, 'invoked':True,
                  'interface':'IUIAutomation', 'editHandle':edit.CurrentNativeWindowHandle}
        self.element(window)
        invoke.Invoke()
        self.shot('J2-folder-picker-submitted')
        return result


class Run(SharedRun):
    def __init__(self, args, definition):
        super().__init__(args, definition)
        self.target = Target()
        self.target.process_evidence = self.out / 'app-processes.json'
        self.native = Native(self.target, self.out)
        self.run_id = f'w4b-{time.time_ns()}'
        self.platform = 'windows'

    def setup(self):
        self.target.prepare(self.args.lane, self.run_id)
        tools = json.loads(Path('C:/Qual/tools.json').read_text())
        self.facts['driverIdentity'] = {'version': tools['driverVersion'], 'sha256': tools['driverSha256']}
        self.target.observe()
        self.results.append({'id': 'windows-gpu', 'checks': [{'name': 'gpu', 'status': 'skipped-by-decision',
            'reason': 'Owner decision: skip GPU tests on Windows', 'evidence': []}]})
        self.work = str(self.out / 'work')
        Path(self.work).mkdir()
        self.data = str(Path(os.environ['APPDATA']) / 'io.justsearch.shell')
        if self.args.resume:
            state = json.loads(self.args.resume.read_text())
            if state['assets'] != self.facts['assets'] or state['lane'] != self.args.lane or state['phase'] != self.args.phase:
                raise Infrastructure('Setup checkpoint does not match assets, lane and phase')
            self.work, self.data, self.binary = (state[k] for k in ('work', 'data', 'binary'))
            self.facts.update(state['facts'])
            # Windows development checkpoints can precede J3. Only a snapshot
            # with completed AI installation can use the cached-model path.
            self.facts['resumedSetup'] = bool(state['facts'].get('ai'))
        if (Path(self.data).exists() or (Path(os.environ['LOCALAPPDATA']) / 'io.justsearch.shell').exists()) and not self.args.resume:
            raise Infrastructure('Fresh Windows lane refuses inherited app state')
        self.crash_since_ns = time.time_ns()
        self.facts['crashStart'] = self.crash_since_ns
        self.evidence('environment', {'session': powershell('[Diagnostics.Process]::GetCurrentProcess().SessionId'),
            'os': powershell('[Environment]::OSVersion.VersionString'),
            'developerJavaPresent': bool(shutil.which('javac', path=self.target.env['PATH'])),
            'appHarnessElevated': ps_json('([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)'),
            'installerContext': 'VM host highest interactive NSIS task; app/WebDriver inherit this limited task' if self.args.lane == 'vm-windows' else 'hosted runner caller token'})
        self.evidence('before-install-crashes', self.crash_records(since=0))
        self.evidence('before-install-wer', [{'path': str(p), 'bytes': p.stat().st_size, 'modifiedNs': p.stat().st_mtime_ns}
            for directory in self.crash_directories() if directory.exists() for p in regular_files(directory)
            if p.suffix.lower() in ('.wer', '.dmp') and p.stat().st_mtime_ns < self.crash_since_ns])
        armed = ps_json(r"""
$p=Get-ItemProperty 'HKLM:\Software\Microsoft\Windows\Windows Error Reporting\LocalDumps' -ErrorAction Stop
@{folder=$p.DumpFolder;type=$p.DumpType;count=$p.DumpCount}
""")
        self.evidence('crash-collection-configuration', armed)
        if armed != {'folder': r'C:\Qual\crash-dumps', 'type': 1, 'count': 1024}:
            raise Infrastructure('Machine WER LocalDumps was not armed by elevated provisioning')
        if self.args.resume:
            self.start_ui()

    def write_counters(self):
        return ps_json(r"""
$disks=@(Get-CimInstance Win32_PerfRawData_PerfDisk_PhysicalDisk | Where-Object {$_.Name -ne '_Total'})
if (!$disks.Count) {throw 'No physical disk write counters'}
$devices=@{};$total=[long]0
foreach($d in $disks) {$devices[$d.Name]=[long]$d.DiskWriteBytesPersec;$total+=[long]$d.DiskWriteBytesPersec}
@{devices=$devices;writtenBytes=$total;source='Windows physical-disk raw byte counters; no forced cache flush';swapWrittenBytes=$null;swapMeasurement='unavailable: Windows page-out counter includes non-swap writes'}
""")

    def installed_version(self):
        entries = ps_json(r"""
@(Get-ChildItem 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall' -ErrorAction SilentlyContinue |
 Get-ItemProperty | Where-Object {$_.DisplayName -eq 'JustSearch'} |
 ForEach-Object {@{version=$_.DisplayVersion;location=([string]$_.InstallLocation).Trim('"');uninstaller=$_.UninstallString}})
""", array=True)
        if len(entries) > 1:
            raise ProductFailure('Multiple per-user JustSearch uninstall entries')
        return entries[0] if entries else None

    def desktop_spawn(self, command, logfile):
        self.target.app_binary = self.binary
        self.processes.append(self.target.spawn(command, logfile))

    def start_ui(self):
        views = [p for p in self.target.owned() if ntpath.basename(p['exe']).lower() == 'msedgewebview2.exe']
        versions = {ntpath.basename(ntpath.dirname(p['exe'])) for p in views}
        if len(versions) == 1:
            self.facts['webview2Version'] = versions.pop()
        self.stop_app()
        self.target.app_binary = self.binary
        if self.facts.get('webview2Version'):
            versions = [self.facts['webview2Version']]
        else:
            versions = ps_json(r"""
@(Get-ChildItem "${env:ProgramFiles(x86)}\Microsoft\EdgeWebView\Application" -Directory |
 Where-Object {$_.Name -match '^\d+\.\d+\.\d+\.\d+$'} | ForEach-Object {$_.Name})
""", array=True)
        if len(versions) != 1:
            raise Infrastructure('Installed WebView2 version is ambiguous: ' + repr(versions))
        import socket
        for port in (4444, 4445):
            try:
                with socket.socket() as probe:
                    probe.bind(('127.0.0.1', port))
            except OSError as exc:
                raise Infrastructure('Qualification WebDriver port already occupied: ' + str(port)) from exc
        verify_driver_receipt(self.args.driver, self.facts['driverIdentity'])
        driver_version = self.target.run([self.args.native_driver, '--version']).stdout.strip()
        self.evidence('webdriver-versions', {'webview2': versions[0], 'msedgedriver': driver_version})
        match_driver(versions[0], driver_version)
        self.desktop_spawn([self.args.driver, '--port', '4444', '--native-port', '4445',
                            '--native-driver', self.args.native_driver], self.work + '/driver.log')
        self.wd = WebDriver(self.target, 4444)
        wait_for(lambda: self.target.http(4444, '/status'), 'tauri-driver ready', seconds=20, error=Infrastructure)
        # EdgeDriver otherwise creates a fresh temporary profile on every
        # launch, which cannot exercise the app's persisted Web Storage.
        profile = ntpath.join(os.environ['LOCALAPPDATA'], 'io.justsearch.shell')
        self.wd.start(self.binary, webview_options={'userDataFolder': profile})
        observed = []
        for process in self.target.owned():
            if ntpath.basename(process['exe']).lower() != 'msedgewebview2.exe':
                continue
            match = re.search(r'(?:^|\s)--user-data-dir=(?:"([^"]+)"|(\S+))', process.get('command', ''))
            if match:
                observed.append({'process': process, 'userDataDir': match[1] or match[2]})
        self.evidence('webdriver-profile', {'userDataFolder': profile, 'observed': observed,
            'source': 'Normal installed Tauri profile, also observed in J1 normal-launch process arguments'})
        expected = ntpath.normcase(ntpath.join(profile, 'EBWebView'))
        if not observed or any(ntpath.normcase(ntpath.normpath(v['userDataDir'])) != expected for v in observed):
            raise Infrastructure('WebDriver did not use the normal installed app profile; see webdriver-profile.json')
        self.observe_window('automation')
        self.ready()
        wait_for(lambda: self.wd.execute("return all(document,'button[data-surface-id]').length>0"),
                 'Packaged UI mounted in own WebDriver session', seconds=60, error=ProductFailure)
        self.facts['ui'] = True

    def stop_app(self):
        self.facts.pop('ui', None)
        self.facts.pop('ready', None)
        if self.wd:
            try:
                self.wd.close()
            finally:
                self.wd = None
        identities = self.target.owned()
        self.facts['preLaunchProcesses'] = identities
        self.target.stop(identities)

    def api(self, endpoint, body=None):
        import urllib.error
        import urllib.request
        from runner import TransientAPI
        try:
            manifest = json.loads((Path(self.data) / 'runtime/manifest.json').read_text())
            rows = self.target.owned()
            process = next((r for r in rows if r['pid'] == manifest['pid']), None)
            if not process or ntpath.normcase(process['exe']) != ntpath.normcase(self.facts.get('bundledJava', '')):
                raise TransientAPI('Engine manifest does not identify this run\'s bundled process')
            port = manifest['head']['apiPort']
            if type(port) is not int or not 0 < port < 65536:
                raise TransientAPI('Invalid Engine manifest port')
            live = self.target.http(port, '/api/runtime/live')
            if live['pid'] != process['pid'] or live['instanceId'] != manifest['instanceId']:
                raise TransientAPI('Engine live identity differs from its manifest')
            headers = {'X-JustSearch-Session': manifest['head']['sessionToken'], 'Content-Type': 'application/json'}
            request = urllib.request.Request(f'http://127.0.0.1:{port}' + endpoint,
                data=None if body is None else json.dumps(body).encode(), headers=headers)
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(request, timeout=30) as response:
                return json.load(response)
        except (FileNotFoundError, ConnectionError, urllib.error.HTTPError, KeyError, ValueError) as exc:
            raise TransientAPI('Installed Engine unavailable: ' + str(exc)) from exc
        except urllib.error.URLError as exc:
            if isinstance(exc.reason, TimeoutError):
                raise Infrastructure('Engine transport timeout: ' + str(exc)) from exc
            raise TransientAPI('Installed Engine unavailable: ' + str(exc)) from exc

    def memory_sample(self):
        return ps_json(r"""
$m=Get-CimInstance Win32_OperatingSystem
@{totalKiB=$m.TotalVisibleMemorySize;freeKiB=$m.FreePhysicalMemory;epochSeconds=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()}
""")

    def state_snapshot(self):
        from linux import search_api, stored_bookmarks
        files = {str(p.relative_to(self.data)): digest(p) for p in Path(self.data).rglob('*')
                 if p.is_file() and (p.suffix in ('.onnx', '.gguf', '.safetensors') or p == Path(self.data) / 'ui/settings.json')}
        return {'settings': self.api('/api/settings/v2'), 'roots': self.api('/api/indexing/roots'),
                'savedFiles': {'files': files, 'savedViews': stored_bookmarks(self)}, 'search': search_api(self)}

    def crash_records(self, since=None):
        start_ns = self.crash_since_ns if since is None else since
        start_filetime = 116444736000000000 + start_ns // 100
        end_filter = (';EndTime=[DateTime]::FromFileTimeUtc(' + str(116444736000000000 + self.crash_since_ns // 100) + ')' if since == 0 else '')
        return ps_json(r"""
$log=Get-WinEvent -ListLog Application -ErrorAction Stop
if (!$log.IsEnabled) {throw 'Application crash event log disabled'}
try {$events=@(Get-WinEvent -FilterHashtable @{LogName='Application';Id=1000,1001;StartTime=[DateTime]::FromFileTimeUtc(START)END} -MaxEvents 10001 -ErrorAction Stop)}
catch {if($_.FullyQualifiedErrorId -like 'NoMatchingEventsFound*') {$events=@()} else {throw}}
if($events.Count -gt 10000) {throw 'Crash event collection exceeds 10000 records'}
@($events | ForEach-Object {
 $xml=[xml]$_.ToXml();$fields=@{};foreach($d in $xml.Event.EventData.Data){$fields[[string]$d.Name]=[string]$d.'#text'}
 $pidValue=$null;$created=$null
 if($fields['ProcessId']) {try {$pidValue=if($fields['ProcessId'] -match '^0x'){[Convert]::ToInt32(($fields['ProcessId'] -replace '^0x',''),16)}else{[int]$fields['ProcessId']}}catch{}}
 if($fields['ProcessCreationTime']) {try {$born=if($fields['ProcessCreationTime'] -match '^0x'){[Convert]::ToInt64(($fields['ProcessCreationTime'] -replace '^0x',''),16)}else{[long]$fields['ProcessCreationTime']};$created=[DateTime]::FromFileTimeUtc($born).ToString('o')}catch{}}
 @{recordId=$_.RecordId;time=$_.TimeCreated.ToUniversalTime().ToString('o');fields=$fields;xml=$_.ToXml();exe=$fields['AppPath'];pid=$pidValue;created=$created}
})
""".replace('START', str(start_filetime)).replace('END', end_filter), array=True)

    def crash_directories(self):
        return [Path(os.environ['PROGRAMDATA']) / 'Microsoft/Windows/WER',
                Path(os.environ['LOCALAPPDATA']) / 'Microsoft/Windows/WER', Path('C:/Qual/crash-dumps'),
                Path(os.environ['LOCALAPPDATA']) / 'io.justsearch.shell/EBWebView/Crashpad']

    def jvm_directories(self):
        roots = [Path.home(), Path(os.environ['TEMP']), Path(self.work), Path(self.data)]
        if self.facts.get('engineJar'):
            roots.append(Path(self.facts['engineJar']).parent)
        return roots

    def collect_crashes(self):
        records = self.crash_records()
        processes = list(self.target.app_processes.values()) + self.facts.get('installerProcesses', [])
        root = str(Path(self.binary).parent) if self.binary else 'C:/not-installed'
        for record in records:
            record['attribution'] = crash_attribution(record, processes, root)
        self.evidence('windows-crashes', records)
        # Preserve original WER diagnostics and dump metadata. Unknown dump
        # identity requires owner validation; a basename/PID alone is insufficient.
        wer = []
        budget = 20_000_000
        roots = self.crash_directories()
        for directory in roots:
            if not directory.exists():
                continue
            for path in regular_files(directory):
                if not path.is_file() or path.suffix.lower() not in ('.wer', '.dmp') or path.stat().st_mtime_ns < self.crash_since_ns:
                    continue
                if path.is_symlink():
                    raise Infrastructure('Crash evidence is a symlink: ' + str(path))
                row = {'path': str(path), 'bytes': path.stat().st_size, 'attribution': 'unattributed'}
                if path.suffix.lower() == '.wer':
                    limit = min(4_000_000, budget)
                    with path.open('rb') as stream:
                        content = stream.read(limit)
                    budget -= len(content)
                    row['truncated'] = len(content) < path.stat().st_size
                    row['content'] = content.decode('utf-16' if content.startswith(b'\xff\xfe') else 'utf-8', errors='replace')
                    fields = dict(line.split('=', 1) for line in row['content'].splitlines() if '=' in line)
                    row['exe'] = fields.get('AppPath', '')
                    row['attribution'] = crash_attribution(row, processes, root)
                else:
                    row['sha256'] = digest(path)
                    destination = self.out / ('wer-dump-' + str(len(wer)) + '.dmp')
                    with path.open('rb') as source, destination.open('wb') as output:
                        content = source.read(min(4_000_000, budget))
                        output.write(content)
                        budget -= len(content)
                    row.update(evidence=destination.name, truncated=len(content) < path.stat().st_size)
                    if path.is_relative_to(Path(os.environ['LOCALAPPDATA']) / 'io.justsearch.shell/EBWebView/Crashpad'):
                        row['attribution'] = 'product'
                wer.append(row)
        self.evidence('windows-wer', wer)
        jvm = []
        seen = set()
        # Scan the app's actual working directory, crash directory, user home,
        # temporary directory and harness work. Bound total transferred content.
        log_roots = self.jvm_directories()
        for directory in log_roots:
            if not directory.exists():
                continue
            for path in regular_files(directory):
                if not path.match('hs_err_pid*.log'):
                    continue
                resolved = path.resolve()
                if resolved in seen or path.stat().st_mtime_ns < self.crash_since_ns:
                    continue
                seen.add(resolved)
                if path.is_symlink():
                    raise Infrastructure('JVM crash report is a symlink: ' + str(path))
                limit = min(4_000_000, budget)
                with path.open('rb') as stream:
                    content = stream.read(limit)
                budget -= len(content)
                jvm.append({'path': str(path), 'bytes': path.stat().st_size,
                    'content': content.decode(errors='replace'), 'truncated': len(content) < path.stat().st_size})
        self.evidence('jvm-crashes', jvm)
        if any(r['attribution'] == 'product' for r in records + wer) or jvm:
            raise ProductFailure('App process, WebView2 child or bundled JVM crashed; see original Windows crash evidence')
        if records or wer:
            raise OwnerValidation('Unattributed crashes during run; see Windows event/WER/dump evidence')
        return {'eventRecords': 0, 'werReports': 0, 'jvmLogs': 0}

    def finish(self):
        row = {'id': 'windows-crashes', 'checks': []}
        self.results.append(row)
        errors = []
        try:
            self.target.owned()
            self.stop_app()
        except Exception as exc:
            errors.append(str(exc))
        finally:
            try:
                self.target.end_observation()
            except Exception as exc:
                errors.append(str(exc))
        for name, source in [('driver.log', Path(self.work) / 'driver.log'),
                             ('normal.log', Path(self.work) / 'normal.log'),
                             ('engine.log', Path(self.data) / 'logs/engine.log')]:
            try:
                if source.exists():
                    content = source.read_text(encoding='utf-8', errors='replace')[-4_000_000:]
                    content = re.sub(r'("sessionToken"\s*:\s*")[^"]+', r'\1<redacted>', content)
                    (self.out / name).write_text(content, encoding='utf-8')
            except Exception as exc:
                errors.append(str(exc))
        if errors:
            row['checks'].append({'name': 'cleanup', 'status': 'failed-infrastructure', 'reason': '; '.join(errors), 'evidence': ['app-processes.json']})
        self.check(row, 'collection', self.collect_crashes)
        if self.args.lane == 'hosted-windows':
            self.check(row, 'hosted crash collection unproven', lambda: (_ for _ in ()).throw(
                OwnerValidation('Hosted crash collection is not proven armed and finished in this run')))
            row['checks'][-1]['evidence'] = ['windows-crashes.json', 'windows-wer.json', 'jvm-crashes.json', 'app-processes.json']
        self.save()


def installer_version(path):
    value = ps_json('$p=' + ps_value(str(path)) + '; (Get-Item -LiteralPath $p).VersionInfo.ProductVersion')
    # NSIS may encode a trailing fourth zero in the PE version resource.
    match = re.fullmatch(r'(\d+\.\d+\.\d+)(?:\.0)?', str(value))
    if not match:
        raise Infrastructure('Installer has no unambiguous version resource: ' + repr(value))
    return match[1]


def ps_value(value):
    """PowerShell single-quoted literal, including paths with apostrophes."""
    return "'" + value.replace("'", "''") + "'"



INSTALL_BUS = Path('C:/Qual')


def broker_installer(run, path, arguments):
    """The owning VM host runs only approved NSIS bytes in its highest task."""
    import uuid
    if tuple(arguments) != ('/S',):
        raise Infrastructure('VM installer broker only accepts shipped silent install/uninstall')
    nonce = uuid.uuid4().hex
    request = INSTALL_BUS / 'installer-request.json'
    response = INSTALL_BUS / ('installer-result-' + nonce + '.json')
    if request.exists():
        raise Infrastructure('An installer operation is already pending')
    value = {'nonce': nonce, 'path': str(path), 'sha256': digest(path)}
    temporary = request.with_suffix('.tmp')
    temporary.write_text(json.dumps(value))
    temporary.replace(request)
    try:
        wait_for(response.exists, 'Owned host NSIS operation completed', seconds=900, error=Infrastructure)
        result = json.loads(response.read_text())
        if result.get('nonce') != nonce or result.get('sha256') != value['sha256']:
            raise Infrastructure('Installer broker reply identity/checksum differs')
        run.evidence('nsis-' + nonce, result)
        if result.get('error'):
            raise Infrastructure(result['error'])
        installer_result(result)
        process = result.get('process', {})
        if (type(process.get('pid')) is not int or not process.get('created') or not process.get('exited')
                or ntpath.normcase(process.get('exe', '')) != ntpath.normcase(str(path))):
            raise Infrastructure('Installer broker omitted exact process lifetime/image evidence')
        run.facts.setdefault('installerProcesses', []).append(process)
        with run.target.guard:
            run.target.roots.append(result['process'])
        wait_for(lambda: not run.target.owned(), 'Installer descendants completed', seconds=120, error=Infrastructure)
        return result
    finally:
        response.unlink(missing_ok=True)


def run_installer(run, path, arguments=('/S',)):
    if run.args.lane == 'vm-windows':
        return broker_installer(run, path, arguments)
    before = run.target.owned()
    row = run.target.spawn([str(path), *arguments], run.work + '/nsis.log')
    process = run.target.handles[row['pid']]
    run.facts.setdefault('installerProcesses', []).append(row)
    try:
        wait_for(lambda: process.poll() is not None, 'NSIS completion', seconds=600, error=Infrastructure)
    except BaseException:
        run.target.stop([p for p in run.target.owned() if not any(same_process(p, old) for old in before)])
        raise
    # Standard NSIS uninstallers briefly copy/relaunch from TEMP. A held parent
    # exit time proves child ancestry even if that parent has already exited.
    root = dict(row)
    def settled():
        owned = run.target.owned()
        branch = [root]
        for _ in range(len(owned) + 1):
            children = [p for p in owned if any(p['ppid'] == a['pid'] and p.get('parentCreated') == a['created'] for a in branch)
                        and not any(same_process(p, a) for a in branch)]
            if not children:
                break
            branch.extend(children)
        return not run.target.alive(branch)
    wait_for(settled, 'NSIS process tree completed', seconds=120, error=Infrastructure)
    result = {'exitCode': process.returncode, 'process': row, 'sha256': digest(path)}
    run.evidence('nsis-' + str(time.time_ns()), result)
    installer_result(result)
    return result


def extract_mcpb(bundle, root):
    with zipfile.ZipFile(bundle) as archive:
        entries = archive.infolist()
        if len(entries) >= 100 or sum(e.file_size for e in entries) >= 5_000_000:
            raise Infrastructure('MCPB exceeds qualification extraction limits')
        for entry in entries:
            path = PurePosixPath(entry.filename)
            if (path.is_absolute() or '..' in path.parts or '\\' in entry.filename
                    or ':' in entry.filename or stat.S_ISLNK(entry.external_attr >> 16)):
                raise Infrastructure('Unsafe MCPB member: ' + entry.filename)
        archive.extractall(root)


def verify_install(run, expected):
    entry = run.installed_version()
    if not entry or entry['version'] != expected:
        raise ProductFailure('NSIS completed without the expected per-user installed version: ' + repr(entry))
    root = Path(entry['location'])
    expected_root = Path(os.environ['LOCALAPPDATA']) / 'JustSearch'
    if ntpath.normcase(str(root)) != ntpath.normcase(str(expected_root)):
        raise ProductFailure('Shipped per-user NSIS installation is outside LOCALAPPDATA/JustSearch')
    run.binary = str(root / 'JustSearch.exe')
    run.target.app_binary = run.binary
    if not Path(run.binary).is_file():
        raise ProductFailure('Installed JustSearch.exe is missing')
    if installer_version(run.binary) != expected:
        raise ProductFailure('Installed shell file version differs from NSIS registry version')
    # Match lib.rs's production java-gui launcher on Windows exactly.
    java = list(root.rglob('runtime/bin/javaw.exe'))
    jars = list(root.rglob('ui-headless.jar'))
    if len(java) != 1 or len(jars) != 1:
        raise ProductFailure('Installer did not ship a unique bundled JVM and Engine jar')
    run.facts['bundledJava'] = str(java[0])
    run.facts['engineJar'] = str(jars[0])
    return {'entry': entry, 'binary': run.binary, 'binarySha256': digest(run.binary),
            'bundledJVM': str(java[0]), 'engineJar': str(jars[0])}


def package_install(run):
    from corpus import generate
    run.facts['initialVersion'] = installer_version(run.args.assets / run.args.package)
    initial = run.args.predecessor if run.args.phase == 'predecessor' else run.args.assets / run.args.package
    if not initial or not initial.is_file():
        raise Unavailable('Initial NSIS package missing')
    if run.installed_version():
        raise Infrastructure('Fresh Windows lane refuses a pre-existing JustSearch installation')
    version = installer_version(initial)
    run.facts['initialPackage'] = {'name': initial.name, 'sha256': digest(initial), 'version': version}
    result = run_installer(run, initial)
    installed = verify_install(run, version)
    extract_mcpb(run.args.assets / run.args.mcpb, Path(run.work) / 'mcpb')
    corpus = Path('C:/Qual/corpus')
    receipt = Path('C:/Qual/corpus-sha256.json')
    if receipt.is_file():
        hashes = json.loads(receipt.read_bytes())
        if hashes != {p.name: digest(p) for p in corpus.iterdir() if p.is_file()}:
            raise Infrastructure('Cached corpus differs from setup snapshot receipt')
    else:
        corpus = Path(run.work) / 'corpus'
        hashes = generate(corpus)
    run.facts['corpus'] = str(corpus)
    run.evidence('corpus-sha256', hashes)
    run.facts['installed'] = True
    return dict(installed, installer=result, package=run.facts['initialPackage'])


def first_run(run):
    run.require('installed')
    run.normal_launch()
    run.native.shot('J1-desktop-first-run')
    run.start_ui()
    wait_for(lambda: run.wd.execute("return all(document,'jf-shell').length>0"),
             'Packaged web UI mounted in own session', seconds=60, error=ProductFailure)
    buttons = run.wd.execute("return all(document,'button').map(e=>e.textContent.trim())")
    if 'Get started' in buttons:
        run.wd.click('button', 'Get started')
    if run.wd.execute("return all(document,'jf-walkthrough-card').some(e=>!e.hidden)"):
        run.wd.click('button[aria-label="Dismiss"]')
    run.native.shot('J1-first-run')
    raise OwnerValidation('Installed shell has an identified UIA window and ready Engine; validate normal-launch first-run text in J1-desktop-first-run.png')


def add_folder(run):
    run.require('ui')
    run.nav('Library')
    run.wd.click('button[aria-label="Add Folder"]')
    picker = run.native.select_folder(run.require('corpus'))
    run.evidence('J2-picker', picker)
    wait_for(lambda: run.wd.execute("return all(document,'input[aria-label=\"Folder path\"]').some(e=>e.value===arguments[0])", [run.require('corpus')]),
             'Picker returned exact corpus path in app DOM', seconds=30, error=ProductFailure)
    run.wd.click('button[aria-label="Add"]')
    roots = wait_for(lambda: (roots if json.dumps(run.require('corpus')) in json.dumps(roots) else None)
                     if (roots := run.api('/api/indexing/roots')) else None,
                     'System picker added watched folder', seconds=45, error=ProductFailure)
    run.facts['folder'] = roots
    return roots


def viewer_identity(rows, windows, fixture, expected_image, before):
    """OS opener may use Explorer as parent; require a new exact image/fixture/window."""
    for process in rows:
        if (ntpath.normcase(process['exe']) != ntpath.normcase(expected_image)
                or any(same_process(process, old) for old in before)):
            continue
        # CommandLineToArgvW on the real guest supplies argv; do not accept a
        # matching substring, a title or a same-named executable elsewhere.
        if not any(ntpath.normcase(arg) == ntpath.normcase(fixture) for arg in process.get('argv', [])):
            continue
        window = next((w for w in windows if w['pid'] == process['pid']), None)
        if window:
            return dict(process, window=window)


ARGV = r"""
Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;
public class QualArgv {
 [DllImport("shell32.dll",SetLastError=true)] static extern IntPtr CommandLineToArgvW([MarshalAs(UnmanagedType.LPWStr)] string command,out int count);
 [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr value);
 public static string[] Split(string command) {
  int count;IntPtr ptr=CommandLineToArgvW(command,out count);if(ptr==IntPtr.Zero)throw new Exception("CommandLineToArgvW failed");
  try {string[] result=new string[count];for(int i=0;i<count;i++)result[i]=Marshal.PtrToStringUni(Marshal.ReadIntPtr(ptr,i*IntPtr.Size));return result;}finally{LocalFree(ptr);}
 }
}
'@
"""



def default_viewer():
    return ps_json(r"""
Add-Type -TypeDefinition @'
using System;using System.Text;using System.Runtime.InteropServices;
public class QualAssociation {
 [DllImport("Shlwapi.dll",CharSet=CharSet.Unicode)] static extern uint AssocQueryString(uint flags,uint str,string assoc,string extra,StringBuilder output,ref uint chars);
 public static string Query(uint kind) {uint count=32768;var buffer=new StringBuilder((int)count);uint result=AssocQueryString(0,kind,".txt",null,buffer,ref count);if(result!=0)throw new Exception("Default viewer association unavailable: "+result);return buffer.ToString();}
}
'@
@{executable=[QualAssociation]::Query(2);command=[QualAssociation]::Query(1)}
""")

def search_open(run):
    from corpus import OPEN_FILE, QUERY
    from linux import search_api
    run.require('ui'); run.require('folder')
    result = search_api(run)
    fixture = str(Path(run.require('corpus')) / OPEN_FILE)
    # Query the actual .txt association. Provisioning establishes classic Notepad
    # as this disposable user's default; an unknown association cannot certify it.
    tools = json.loads(Path('C:/Qual/tools.json').read_text())
    expected_image = tools['viewer']
    association = default_viewer()
    if ntpath.normcase(association['executable']) != ntpath.normcase(expected_image):
        raise Infrastructure('OS default text viewer differs from qualification provisioning: ' + repr(association))
    before = ps_json(PROCESSES, array=True)
    run.nav('Search')
    run.wd.click('button[aria-label="Mode: Search"]')
    run.wd.click('button[data-tier="search"]')
    run.wd.keys('textarea[data-testid="sv3-composer-input"]', QUERY + '\ue007')
    hit = wait_for(lambda: run.wd.execute("""
return all(document,'[data-testid="search-result-row"]').find(e =>
 (e.getAttribute('data-addressable-id') || '').toLowerCase() === arguments[0].toLowerCase()) || null;
""", [fixture]), 'Exact fixture row in search DOM', seconds=45, error=ProductFailure)
    run.wd.call(f'/session/{run.wd.session}/actions', {'actions': [{'type': 'pointer', 'id': 'result-menu',
        'parameters': {'pointerType': 'mouse'}, 'actions': [
            {'type': 'pointerMove', 'duration': 0, 'origin': hit, 'x': 0, 'y': 0},
            {'type': 'pointerDown', 'button': 2}, {'type': 'pointerUp', 'button': 2}]}]})
    run.wd.click('button,[role="menuitem"]', 'Open file', error=ProductFailure)
    def receiving():
        rows = ps_json(ARGV + '$rows=' + PROCESSES + r""";
@($rows | ForEach-Object {$_.argv=if($_.command){[QualArgv]::Split($_.command)}else{@()};$_})
""", array=True)
        run.evidence('J2-viewer-processes', rows)
        return viewer_identity(rows, run.native.windows(), fixture, expected_image, before)
    viewer = wait_for(receiving, 'Default viewer process and UIA window opened exact fixture', seconds=30, error=ProductFailure)
    # The OS may broker this launch; record the identity before adopting it for cleanup.
    run.target.roots.append(viewer)
    run.native.shot('J2-receiving-viewer')
    run.target.stop([viewer])
    run.facts['search'] = True
    return {'search': result, 'receivingViewerProcess': viewer, 'association': association}


def mcp_metadata(run):
    with zipfile.ZipFile(run.args.assets / run.args.mcpb) as archive:
        manifest = json.loads(archive.read('manifest.json'))
    if not run.args.registry:
        raise Unavailable('Tagged server.json is required for S21 registry metadata')
    registry = json.loads(run.args.registry.read_text())
    expected = run.require('initialVersion')
    assert manifest['version'] == registry['version'] == expected, 'App, MCPB and registry versions differ'
    assert {'linux', 'win32'} <= set(manifest['compatibility']['platforms']), 'MCPB platforms incomplete'
    package = registry['packages'][0]
    assert package['identifier'] == f'https://github.com/justsearch-app/justsearch/releases/download/v{expected}/justsearch-mcp.mcpb', 'Registry release URL differs'
    assert package['fileSha256'] == digest(run.args.assets / run.args.mcpb), 'Registry bundle checksum differs'
    return {'manifest': manifest, 'registry': registry}


def reinstall(run):
    from linux import assert_state
    run.require('beforeState')
    version = run.require('initialVersion')
    entry = run.installed_version()
    if not entry or entry['version'] != version:
        raise Unavailable('Candidate is not installed; cannot certify candidate reinstall')
    run.stop_app()
    result = run_installer(run, run.args.assets / run.args.package)
    verify_install(run, version)
    run.start_ui()
    return {'installer': result, 'state': assert_state(run)}


def remove_reinstall(run):
    from linux import assert_state
    run.require('beforeState')
    entry = run.installed_version()
    if not entry or entry['version'] != run.require('initialVersion'):
        raise Unavailable('Candidate is not installed; cannot certify candidate uninstall/reinstall')
    run.stop_app()
    # Same authoritative per-user data root as update-preserves-models-test.ps1.
    # Its GITHUB_ACTIONS-only guard cannot be bypassed on the VM; reuse the
    # filesystem preservation method on actual models/settings here, never seeds.
    before = retained_files(Path(run.data))
    uninstaller = Path(entry['location']) / 'uninstall.exe'
    if not uninstaller.is_file() or ntpath.normcase(entry['uninstaller'].strip('"')) != ntpath.normcase(str(uninstaller)):
        raise ProductFailure('Per-user uninstall entry does not name its installed uninstaller')
    removed = run_installer(run, uninstaller)
    wait_for(lambda: run.installed_version() is None, 'Per-user uninstall registration removed', seconds=60, error=ProductFailure)
    after = retained_files(Path(run.data))
    assert before == after, 'Uninstall changed retained user model/settings bytes'
    result = run_installer(run, run.args.assets / run.args.package)
    verify_install(run, run.require('initialVersion'))
    run.start_ui()
    return {'uninstall': removed, 'reinstall': result, 'retainedFiles': after, 'state': assert_state(run)}


def retained_files(root):
    return {str(p.relative_to(root)): digest(p) for p in root.rglob('*') if p.is_file()
            and (p.is_relative_to(root / 'models') or p == root / 'ui/settings.json')}


def installer_witness(run):
    path = Path(run.data) / 'upgrade/installer-launch-witness.v1.json'
    if not path.exists():
        return None
    witness = json.loads(path.read_text())
    checksum = run.require('assets')[run.args.package]
    staged = Path(witness.get('stagedPath', ''))
    # JSON is camelCase in the product. Never control an installer by its title.
    if (not staged.is_file() or not staged.is_relative_to(Path(run.data) / 'upgrade/staged')
            or witness.get('stagedSha256') != checksum or digest(staged) != checksum):
        raise ProductFailure('Updater launch witness does not identify exact candidate bytes')
    rows = ps_json(PROCESSES, array=True)
    process = next((p for p in rows if p['pid'] == witness.get('processId')
                    and ntpath.normcase(p['exe']) == ntpath.normcase(str(staged))), None)
    if not process:
        return None
    import datetime
    born = datetime.datetime.fromisoformat(process['created'].replace('Z', '+00:00')).timestamp() * 1000
    if not run.require('updateClickAtEpochMs') - 1000 <= born <= witness.get('launchedAtEpochMs', 0) + 1000:
        raise Infrastructure('Could not bind the installer witness to this update process lifetime')
    with run.target.guard:
        if not any(same_process(process, p) for p in run.target.roots):
            run.target.roots.append(process)
    window = next((w for w in run.native.windows() if w['pid'] == process['pid'] and w['class'] == '#32770'), None)
    return {'witness': witness, 'process': process, 'window': dict(window, process=process)} if window else None


def invoke_nsis(run, installation, button_id):
    """Invoke a standard NSIS button through UIA on the witnessed process/window."""
    window = installation['window']
    run.evidence('J5-installer-window-' + str(time.time_ns()), installation)
    buttons = run.native.buttons(window, button_id)
    if len(buttons) != 1:
        raise Infrastructure('Expected unique NSIS action unavailable')
    _, module = run.native.automation()
    button = buttons[0]
    label = button.CurrentName
    invoke = button.GetCurrentPattern(module.UIA_InvokePatternId).QueryInterface(module.IUIAutomationInvokePattern)
    run.native.element(window) # Recheck exact lifetime and HWND before invocation.
    invoke.Invoke()
    return {'handle':window['handle'], 'pid':window['process']['pid'], 'button':str(button_id),
            'label':label, 'invoked':True, 'interface':'IUIAutomation'}


def update_prepare(run):
    from linux import open_update_offer
    if not run.args.predecessor or not run.args.mirror:
        raise Unavailable('J5 needs a parent NSIS build and descriptor v2 mirror with identical test trust pins')
    if run.args.phase != 'predecessor':
        raise Infrastructure('J5 requires a fresh predecessor overlay')
    run.require('beforeState')
    if run.installed_version()['version'] != installer_version(run.args.predecessor):
        raise ProductFailure('J5 did not start with the parent package')
    # The owning host wrapper sets the guest-local HTTPS mirror after J3's
    # production downloads. No HTTP/test-build override reaches the product.
    marker = Path('C:/Qual/mirror/server.json')
    if not marker.is_file():
        raise Unavailable('Windows HTTPS mirror not installed by owning VM wrapper')
    run.start_ui()
    open_update_offer(run, 'J5-update-entry')
    run.native.shot('J5-update-offered')
    run.facts['updateOffered'] = True
    return {'predecessor': run.facts['initialPackage'], 'candidateVersion': run.require('initialVersion')}



def launch_update(run, prior_attempt):
    run.facts['updateClickAtEpochMs'] = time.time_ns() // 1_000_000
    transport = None
    try:
        run.wd.click('button[aria-label="Install update"]')
    except Infrastructure as exc:
        # The Windows shell exits after handoff; a lost WebDriver connection can
        # be expected only when the app's fresh launch witness proves the step.
        transport = exc
        run.evidence('J5-install-click-transport', {'error': str(exc)})
    try:
        return wait_for(lambda: (v if v['witness'].get('attemptId') != prior_attempt else None)
                        if (v := installer_witness(run)) else None,
                        'Fresh witnessed candidate NSIS window', seconds=180,
                        error=Infrastructure if transport else ProductFailure)
    except Exception as exc:
        if transport:
            raise Infrastructure('Update click transport failed without a fresh installer witness: ' + str(transport)) from exc
        raise

def cancel_update(run):
    from linux import open_update_offer, assert_state
    run.require('updateOffered')
    version = run.installed_version()['version']
    previous = Path(run.data) / 'upgrade/installer-launch-witness.v1.json'
    prior_attempt = json.loads(previous.read_text()).get('attemptId') if previous.exists() else None
    installation = launch_update(run, prior_attempt)
    run.native.shot('J5-decline-installer')
    # Shipped currentUser NSIS requests no UAC. Exercise the settled alternative:
    # decline the actual installer; do not fabricate an elevation prompt.
    action = invoke_nsis(run, installation, 2) # IDCANCEL
    # NSIS may ask for confirmation in a child dialog of the same process.
    def exited():
        if not run.target.alive([installation['process']]):
            return True
        confirm_nsis_cancel(run, installation['process'])
        return False
    wait_for(exited, 'Declined NSIS process exited', seconds=60, error=ProductFailure)
    run.ready()
    open_update_offer(run, 'J5-cancel-reoffer')
    observation = {'installerObserved': True, 'installerExited': not run.target.alive([installation['process']]),
                   'versionUnchanged': run.installed_version()['version'] == version,
                   'engineReady': run.facts.get('ready') is True, 'reoffered': True}
    cancel_result(observation)
    state = assert_state(run)
    run.facts['updateCancelled'] = True
    return {'observation': observation, 'installer': installation, 'action': action, 'state': state}


def confirm_nsis_cancel(run, process):
    # IDYES=6 is only accepted inside a #32770 dialog owned by the same exact
    # witnessed NSIS image/PID/creation time. An unrelated Yes is never invoked.
    if not run.target.alive([process]):
        return
    for window in run.native.windows():
        if window['pid'] != process['pid'] or window['class'] != '#32770':
            continue
        owned_window = dict(window, process=process)
        if run.native.buttons(owned_window, 6):
            invoke_nsis(run, {'window': owned_window}, 6)



def complete_nsis(run, installation):
    actions = []
    allowed = {'Next >', 'Install', 'Finish'}
    def advance():
        if not run.target.alive([installation['process']]):
            return True
        for window in run.native.windows():
            if window['pid'] != installation['process']['pid'] or window['class'] != '#32770':
                continue
            owned_window = dict(window, process=installation['process'])
            buttons = [b.CurrentName for b in run.native.buttons(owned_window, 1)]
            if not buttons:
                continue # Real installation progress disables Next.
            if len(buttons) != 1 or buttons[0].replace('&', '').strip() not in allowed:
                raise Infrastructure('Unrecognized NSIS page action: ' + repr(buttons))
            page = dict(installation, window=dict(window, process=installation['process']))
            actions.append(invoke_nsis(run, page, 1))
            run.evidence('J5-nsis-actions', actions)
            if len(actions) > 10:
                raise Infrastructure('NSIS navigation did not progress within ten identified actions')
            return False
        return False
    wait_for(advance, 'Identified NSIS wizard completed', seconds=600, interval=1, error=Infrastructure)
    if not actions:
        raise Infrastructure('No identified NSIS install/finish action was delivered')
    return actions


def adopt_relaunch(run, old, installation):
    """NSIS RunAsUser can use Explorer; record that brokered root, never Explorer."""
    import datetime
    threshold = installation['witness']['launchedAtEpochMs']
    def probe():
        rows = ps_json(PROCESSES, array=True)
        for row in rows:
            if ntpath.normcase(row['exe']) != ntpath.normcase(run.binary) or any(same_process(row, p) for p in old):
                continue
            born = datetime.datetime.fromisoformat(row['created'].replace('Z', '+00:00')).timestamp() * 1000
            if born < threshold:
                continue
            window = next((w for w in run.native.windows() if w['pid'] == row['pid']), None)
            if not window:
                continue
            parent = next((p for p in rows if p['pid'] == row['ppid'] and p['created'] <= row['created']), None)
            with run.target.guard:
                run.target.roots.append(row)
            run.evidence('J5-brokered-relaunch', {'process': row, 'parent': parent, 'window': window,
                'trigger': 'Candidate NSIS Finish action delivered through its witnessed UIA window'})
            return row
    return wait_for(probe, 'Automatic candidate relaunch after witnessed NSIS actions', seconds=60, error=ProductFailure)

def update_success(run):
    from linux import open_update_offer, assert_state
    run.require('updateCancelled')
    open_update_offer(run, 'J5-success-entry')
    old = [p for p in run.target.owned() if ntpath.normcase(p['exe']) in
           (ntpath.normcase(run.binary), ntpath.normcase(run.require('bundledJava')))]
    if {ntpath.normcase(p['exe']) for p in old} != {ntpath.normcase(run.binary), ntpath.normcase(run.require('bundledJava'))}:
        raise Infrastructure('Old shell and bundled Engine identities were not both recorded')
    run.evidence('J5-old-processes', old)
    run.facts['preLaunchProcesses'] = old
    prior = json.loads((Path(run.data) / 'upgrade/installer-launch-witness.v1.json').read_text())['attemptId']
    installation = launch_update(run, prior)
    run.native.shot('J5-accept-installer')
    actions = complete_nsis(run, installation)
    expected = run.require('initialVersion')
    wait_for(lambda: (entry := run.installed_version()) and entry['version'] == expected,
             'Candidate installed by production updater', seconds=600, error=ProductFailure)
    run.wd = None
    run.facts.pop('ui', None)
    # No harness launch until after automatic relaunch and old-process retirement
    # have been established on the production process/window/API interfaces.
    adopt_relaunch(run, old, installation)
    run.observe_window('updater-relaunch')
    run.ready()
    wait_for(lambda: not run.target.alive(old), 'Old shell and Engine exited', seconds=60, error=ProductFailure)
    observed = {'installedVersion': run.installed_version()['version'], 'window': True,
                'engineReady': run.facts.get('ready') is True,
                'oldShellAlive': bool(run.target.alive([p for p in old if ntpath.normcase(p['exe']) == ntpath.normcase(run.binary)])),
                'oldEngineAlive': bool(run.target.alive([p for p in old if ntpath.normcase(p['exe']) == ntpath.normcase(run.require('bundledJava'))]))}
    run.native.shot('J5-automatic-relaunch')
    verify_install(run, expected)
    live = run.api('/api/runtime/live')
    engine = next((p for p in run.target.owned() if p['pid'] == live['pid']
                   and ntpath.normcase(p['exe']) == ntpath.normcase(run.require('bundledJava'))), None)
    if not engine:
        raise ProductFailure('Updated Engine is not this run\'s bundled JVM')
    # Web Storage is scoped to a WebDriver-owned session. Restart only now to
    # observe the persisted authored records without claiming its DOM for the
    # preceding automatically relaunched window.
    run.start_ui()
    state = assert_state(run)
    observed['stateUnchanged'] = True
    update_result(observed, expected)
    run.facts['updated'] = True
    return {'observation': observed, 'installer': installation, 'actions': actions, 'engine': engine, 'state': state}


from linux import install_ai, author_state, restart, mcp

CHECKS = {'install': package_install, 'first-run': first_run, 'folder-picker': add_folder,
          'search-open': search_open, 'cpu-ai': install_ai, 'author-state': author_state,
          'restart': restart, 'update-offer': update_prepare, 'update-cancel': cancel_update,
          'update-success': update_success, 'reinstall': reinstall,
          'remove-retain-reinstall': remove_reinstall, 'mcp-metadata': mcp_metadata, 'mcp': mcp}
