#!/usr/bin/env python3
"""Own one headless Windows overlay at a time; run runner.py on its desktop."""
import argparse
import importlib.util
import json
import os
import ntpath
import re
import base64
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import threading

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import vm
from runner import Infrastructure, RunStopped, digest, verify_assets, preparation_failure, summarize, full_selection, report_exit_code, wait_for
from windows import ps_value, verify_driver_receipt, memory_counters

spec = importlib.util.spec_from_file_location('linux_vm_wrapper', HERE / 'vm-run.py')
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)
TASK = 'JustSearch-qual-journeys'
WRITE_LIMIT = 15_000_000_000
DISK_COUNTERS = r"""
$d=@(Get-CimInstance Win32_PerfRawData_PerfDisk_PhysicalDisk | Where-Object {$_.Name -ne '_Total'})
if(!$d.Count){throw 'Physical disk counters unavailable'}
$total=[long]0;foreach($disk in $d){$total+=[long]$disk.DiskWriteBytesPersec}
@{writtenBytes=$total} | ConvertTo-Json -Compress
"""
UPDATE_ACTIVITY = r"""
$folder='C:/Windows/SoftwareDistribution/Download'
$files=@(if(Test-Path $folder){Get-ChildItem $folder -File -Recurse -ErrorAction Stop | Sort-Object FullName | ForEach-Object {
 @{name=$_.FullName.Substring($folder.Length);bytes=$_.Length;modified=$_.LastWriteTimeUtc.ToString('o')}
}})
$bytes=[long]0;foreach($file in $files){$bytes+=$file.bytes}
$log=Get-WinEvent -ListLog 'Microsoft-Windows-WindowsUpdateClient/Operational'
$events=@()
if($since){
 $errors=@()
 $events=@(Get-WinEvent -FilterHashtable @{LogName=@('System',$log.LogName);ProviderName='Microsoft-Windows-WindowsUpdateClient';StartTime=[DateTime]::Parse($since).ToUniversalTime()} -ErrorAction SilentlyContinue -ErrorVariable errors | ForEach-Object {
  @{id=$_.Id;recordId=$_.RecordId;log=$_.LogName;time=$_.TimeCreated.ToUniversalTime().ToString('o');keywords=@($_.KeywordsDisplayNames);message=$_.Message}
 })
 foreach($e in $errors){if($e.FullyQualifiedErrorId -notlike 'NoMatchingEventsFound*'){throw $e}}
}
$policy=Get-ItemProperty 'HKLM:/SOFTWARE/Policies/Microsoft/Windows/WindowsUpdate/AU'
@{time=[DateTime]::UtcNow.ToString('o');services=@(Get-CimInstance Win32_Service | Where-Object {$_.Name -in 'wuauserv','UsoSvc'} | Select-Object Name,StartMode,State);
 policies=@{NoAutoUpdate=$policy.NoAutoUpdate;AUOptions=$policy.AUOptions};downloads=$files;downloadBytes=$bytes;
 events=$events;eventLogEnabled=$log.IsEnabled} | ConvertTo-Json -Depth 8 -Compress
"""


def verify_update_effect(before, after):
    for observation in (before, after):
        services = {s['Name']: s for s in observation['services']}
        if any(services.get(name, {}).get('State') != 'Stopped' for name in ('wuauserv', 'UsoSvc')):
            raise Infrastructure('Windows Update services are not stopped; see update-activity.json')
        if observation['policies'] != {'NoAutoUpdate': 1, 'AUOptions': 2} or not observation['eventLogEnabled']:
            raise Infrastructure('Windows Update policy or event collection unavailable; see update-activity.json')
    if before['downloads'] != after['downloads'] or before['downloadBytes'] != after['downloadBytes']:
        raise Infrastructure('Windows Update download folder changed during run; see update-activity.json')
    if any(re.search(r'download|install', ' '.join(event['keywords']), re.I) for event in after['events']):
        raise Infrastructure('Windows Update download/install activity during run; see update-activity.json')


def update_observation(since=None):
    return json.loads(guest_ps('$since=' + (ps_value(since) if since else '$null') + ';' + UPDATE_ACTIVITY))


def write_limit(journeys):
    return 25_000_000_000 if journeys == ['J3'] else WRITE_LIMIT


def enforce_write_limit(volume, limit=WRITE_LIMIT):
    for key in ('hostQemuWrittenBytes', 'guestWrittenBytes', 'overlayGrowthBytes'):
        if volume.get(key, 0) > limit:
            raise RunStopped(f'Windows run exceeded {limit // 1_000_000_000} GB write budget: {key}={volume[key]}; stopped, do not rerun')


class WriteBudget:
    """Watch even while an SSH command blocks; shut down only this owned VM."""
    def __init__(self, started, out, limit=WRITE_LIMIT):
        self.started, self.out = started, out
        self.disk = Path(started['disk'])
        self.initial = self.disk.stat().st_blocks * 512
        self.limit = limit
        self.volume = {'limitBytes': limit, 'overlayInitialBytes': self.initial,
                       'measurement': 'QEMU process lifetime and guest boot through pre-shutdown; overlay allocation after shutdown'}
        self.done = threading.Event()
        self.error = None
        self.next_resources = 0
        self.thread = threading.Thread(target=self.watch, daemon=True)

    def resources(self):
        """Keep J3 diagnostics on the host even if the write guard stops the VM."""
        source = '$memory=& { ' + memory_counters() + r""" }
$data=Join-Path $env:APPDATA 'io.justsearch.shell'
$files=@(if(Test-Path $data){Get-ChildItem $data -File -Recurse -ErrorAction Stop})
$sizes=@($files | Group-Object {($_.FullName.Substring($data.Length+1) -split '\\')[0]} | ForEach-Object {
 @{directory=$_.Name;fileBytes=($_.Group | Measure-Object Length -Sum).Sum;files=$_.Count}
})
$logs=@(Get-ChildItem (Join-Path $data 'logs') -File -Recurse -ErrorAction SilentlyContinue | Where-Object {$_.Extension -in '.log','.jsonl'} | ForEach-Object {
 @{path=$_.FullName;bytes=$_.Length;tail=@(Get-Content $_.FullName -Tail 120 -Encoding UTF8)}
})
@{memory=$memory;dataDirectories=$sizes;engineLogs=$logs} | ConvertTo-Json -Depth 8 -Compress
"""
        try:
            observation = json.loads(guest_ps(source, timeout=25))
        except Exception as exc:
            observation = {'unavailable': str(exc), 'epochSeconds': time.time()}
        observation['writeVolume'] = dict(self.volume)
        with (self.out / 'J3-resources.jsonl').open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(observation) + '\n')

    def sample(self):
        io = dict(line.split(':', 1) for line in Path(f"/proc/{self.started['pid']}/io").read_text().splitlines())
        self.volume.update(hostQemuWrittenBytes=int(io['write_bytes']),
                           overlayGrowthBytes=max(0, self.disk.stat().st_blocks * 512 - self.initial))
        enforce_write_limit(self.volume, self.limit)

    def watch(self):
        count = 0
        while not self.done.is_set():
            try:
                if vm.read_state() != self.started or not vm.visible_process(self.started):
                    return
                self.sample()
                if count % 5 == 0:
                    try:
                        self.volume['guestWrittenBytes'] = json.loads(guest_ps(DISK_COUNTERS, timeout=15))['writtenBytes']
                        self.volume.pop('guestUnavailable', None)
                    except Exception as exc:
                        self.volume['guestUnavailable'] = str(exc)
                    enforce_write_limit(self.volume, self.limit)
                count += 1
                if self.limit == 25_000_000_000 and time.monotonic() >= self.next_resources:
                    self.next_resources = time.monotonic() + 30
                    self.resources()
            except Infrastructure as exc:
                self.error = str(exc)
                (self.out / 'write-budget-exceeded.json').write_text(json.dumps(self.volume, indent=2) + '\n')
                if vm.read_state() == self.started:
                    vm.stop()
                return
            except (OSError, RuntimeError) as exc:
                self.volume['hostUnavailable'] = str(exc)
                return
            self.done.wait(2)

    def check(self):
        if self.error:
            raise RunStopped(self.error)

    def finish(self):
        self.done.set()
        self.thread.join()
        if vm.read_state() == self.started and vm.visible_process(self.started):
            try:
                self.sample()
                self.volume['guestWrittenBytes'] = json.loads(guest_ps(DISK_COUNTERS, timeout=15))['writtenBytes']
                enforce_write_limit(self.volume, self.limit)
            except Exception as exc:
                self.volume['finalMeasurementError'] = str(exc)
                if isinstance(exc, RunStopped):
                    self.error = str(exc)
        return self.volume


def guest_ps(source, timeout=60):
    result = vm.ssh('windows', vm.powershell('$ErrorActionPreference="Stop";' + source),
                    capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise Infrastructure('Guest PowerShell failed: ' + result.stderr[-3000:])
    return result.stdout.strip().lstrip('\ufeff')


def copy_out(source, destination, check=True):
    vm.require_owner('windows')
    return subprocess.run(['scp', *vm.ssh_args('windows'), '-P', str(vm.OS['windows']['ssh']),
        'qual@127.0.0.1:' + source, str(destination)], check=check, capture_output=True, text=True)


def pins(args, phase):
    receipt = json.loads(Path(str(args.driver) + '.json').read_text())
    verify_driver_receipt(args.driver, receipt)
    return {'driverReceipt': receipt, 'assets': verify_assets(args.assets, [args.package, 'justsearch-mcp.mcpb']),
            'registry': digest(args.registry), 'driver': digest(args.driver),
            'predecessor': digest(args.predecessor) if args.predecessor else None,
            'mirror': {str(p.relative_to(args.mirror)): digest(p) for p in sorted(args.mirror.rglob('*')) if p.is_file()} if args.mirror else None,
            'phase': phase, 'session': args.session,
            'setupSources': {name: digest(HERE / name) for name in ('windows-tools.ps1', 'windows-edge.ps1', 'windows-association.py', 'windows-cache-tools.ps1', 'windows-quiet.ps1', 'corpus.py')},
            'base': json.loads((vm.ROOT / 'windows/base.json').read_text())}


def start_snapshot(snapshot):
    vm.ensure_free()
    vm.wait_host_memory()
    with vm.locked() as lease:
        vm.ensure_free(check_lock=False)
        folder = vm.ROOT / 'windows'
        disk, variables = folder / 'run.qcow2', folder / 'run-vars.fd'
        disk.unlink(missing_ok=True); variables.unlink(missing_ok=True)
        try:
            vm.run('qemu-img', 'create', '-q', '-f', 'qcow2', '-F', 'qcow2',
                   '-b', (snapshot / 'disk.qcow2').resolve(), disk)
            shutil.copyfile(snapshot / 'vars.fd', variables)
            return vm.launch('windows', disk, variables, lease=lease.fileno())
        except BaseException:
            if not vm.current():
                disk.unlink(missing_ok=True); variables.unlink(missing_ok=True)
            raise


def seal_snapshot(out, started, inode, args, phase):
    with vm.locked():
        vm.ensure_free(check_lock=False)
        disk = Path(started['disk'])
        if disk.stat().st_ino != inode or disk.stat().st_dev != out.stat().st_dev:
            raise Infrastructure('Snapshot disk changed or requires a cross-filesystem copy; refusing')
        snapshot = out / 'snapshot'
        snapshot.mkdir()
        disk.rename(snapshot / 'disk.qcow2')
        disk.with_name('run-vars.fd').rename(snapshot / 'vars.fd')
        shutil.copyfile(out / 'checkpoint.json', snapshot / 'checkpoint.json')
        shutil.copyfile(out / 'quiet-verify.json', snapshot / 'quiet-verify.json')
        (snapshot / 'manifest.json').write_text(json.dumps({'kind': 'application', 'pins': pins(args, phase),
            'sourceSummary': str((out / 'summary.json').resolve())}, indent=2) + '\n')


def provision(args, out):
    guest_ps("New-Item -ItemType Directory -Force C:/Qual | Out-Null; [IO.File]::WriteAllText('C:/Qual/justsearch-qualification-guest',\"justsearch-vm`n\")")
    vm.copy_in('windows', args.driver, 'C:/Qual/tauri-driver.exe')
    vm.copy_in('windows', Path(str(args.driver) + '.json'), 'C:/Qual/tauri-driver.exe.json')
    vm.copy_in('windows', HERE / 'windows-tools.ps1', 'C:/Qual/windows-tools.ps1')
    vm.copy_in('windows', HERE / 'windows-edge.ps1', 'C:/Qual/windows-edge.ps1')
    with (out / 'provision.log').open('wb') as log:
        result = vm.ssh('windows', 'powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:/Qual/windows-tools.ps1 -Driver C:/Qual/tauri-driver.exe',
                        stdout=log, stderr=subprocess.STDOUT, timeout=1200)
    if result.returncode:
        raise Infrastructure('Windows qualification tools failed; see provision.log')


def quiet_guest(mode, out):
    """Use SYSTEM for protected service/task settings; preserve every refusal."""
    task = 'JustSearch-qual-quiet'
    remote = 'C:/Qual/quiet-exit.txt'
    with tempfile.TemporaryDirectory(prefix='windows-quiet-') as tmp:
        script = Path(tmp) / 'quiet-worker.ps1'
        script.write_text("$ErrorActionPreference='Stop'\n$code=0\ntry { & C:/Qual/journeys/windows-quiet.ps1 -Mode " + mode +
            " > C:/Qual/quiet-worker.log 2>&1\n" +
            "} catch { $_ | Out-File C:/Qual/quiet-worker.log -Append; $_.ScriptStackTrace | Out-File C:/Qual/quiet-worker.log -Append; $code=1 }\n" +
            "[IO.File]::WriteAllText('C:/Qual/quiet-exit.txt',[string]$code)\n")
        vm.copy_in('windows', script, 'C:/Qual/quiet-worker.ps1')
    guest_ps("Remove-Item C:/Qual/quiet-exit.txt -ErrorAction SilentlyContinue;" +
        "$a=New-ScheduledTaskAction -Execute powershell.exe -Argument '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:/Qual/quiet-worker.ps1';" +
        "$p=New-ScheduledTaskPrincipal -UserId SYSTEM -LogonType ServiceAccount -RunLevel Highest;" +
        'Register-ScheduledTask -TaskName ' + ps_value(task) + ' -Action $a -Principal $p -Force | Out-Null;' +
        'Start-ScheduledTask -TaskName ' + ps_value(task))
    try:
        result = wait_for(lambda: guest_ps('if(Test-Path ' + ps_value(remote) + '){Get-Content ' + ps_value(remote) + '}'),
                          'Guest write settings ' + mode, seconds=180, interval=2, error=Infrastructure)
        copy_out('C:/Qual/quiet-' + mode.lower() + '.json', out / ('quiet-' + mode.lower() + '.json'), check=False)
        copy_out('C:/Qual/quiet-worker.log', out / ('quiet-' + mode.lower() + '.log'))
        if result != '0':
            raise Infrastructure('Windows write settings ' + mode + ' failed; see quiet-' + mode.lower() + '.log')
    finally:
        guest_ps('Unregister-ScheduledTask -TaskName ' + ps_value(task) + ' -Confirm:$false')


def prepare_viewer(out, verify=False):
    task = 'JustSearch-qual-viewer'
    mode = 'verify' if verify else 'configure'
    remote = 'C:/Qual/viewer-setup/' + mode + '.json'
    arguments = 'C:/Qual/journeys/windows-association.py --evidence C:/Qual/viewer-setup' + (' --verify' if verify else '')
    guest_ps('Remove-Item -LiteralPath ' + ps_value(remote) + ' -ErrorAction SilentlyContinue;' +
        '$a=New-ScheduledTaskAction -Execute C:/Qual/Python/Scripts/python.exe -Argument ' + ps_value(arguments) + ';' +
        '$p=New-ScheduledTaskPrincipal -UserId qual -LogonType Interactive -RunLevel Limited;' +
        'Register-ScheduledTask -TaskName ' + ps_value(task) + ' -Action $a -Principal $p -Force | Out-Null;' +
        'Start-ScheduledTask -TaskName ' + ps_value(task))
    try:
        wait_for(lambda: guest_ps('Test-Path ' + ps_value(remote)) == 'True',
                 'Limited desktop default viewer ' + mode, seconds=300, interval=2, error=Infrastructure)
        copy_out(remote, out / ('viewer-' + mode + '.json'))
        if json.loads((out / ('viewer-' + mode + '.json')).read_text()).get('verified') is not True:
            raise Infrastructure('Default viewer ' + mode + ' failed; see viewer-' + mode + '.json')
    finally:
        guest_ps('Unregister-ScheduledTask -TaskName ' + ps_value(task) + ' -Confirm:$false')


def verify_crash_access(out):
    """Prove archive reads with the same limited token as the app journeys."""
    task = 'JustSearch-qual-crash-access'
    with tempfile.TemporaryDirectory(prefix='windows-crash-access-') as tmp:
        script = Path(tmp) / 'crash-access.py'
        script.write_text("""import json,sys,traceback
from pathlib import Path
sys.path.insert(0,'C:/Qual/journeys')
from windows import regular_files, ps_json
result={'verified':False,'files':[]}
try:
    result['elevated']=ps_json('([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)')
    if result['elevated']: raise RuntimeError('Crash read check must run limited')
    for path in regular_files(Path('C:/ProgramData/Microsoft/Windows/WER')):
        if path.suffix.lower() in ('.wer','.dmp'):
            with path.open('rb') as stream: stream.read(1)
            result['files'].append({'path':str(path),'bytes':path.stat().st_size})
    result['verified']=True
except Exception:
    result['error']=traceback.format_exc()
Path('C:/Qual/crash-access.json').write_text(json.dumps(result,indent=2))
""")
        vm.copy_in('windows', script, 'C:/Qual/crash-access.py')
    guest_ps("Remove-Item C:/Qual/crash-access.json -ErrorAction SilentlyContinue;" +
        "$a=New-ScheduledTaskAction -Execute C:/Qual/Python/Scripts/python.exe -Argument C:/Qual/crash-access.py;" +
        "$p=New-ScheduledTaskPrincipal -UserId qual -LogonType Interactive -RunLevel Limited;" +
        'Register-ScheduledTask -TaskName ' + ps_value(task) + ' -Action $a -Principal $p -Force | Out-Null;' +
        'Start-ScheduledTask -TaskName ' + ps_value(task))
    try:
        wait_for(lambda: guest_ps('Test-Path C:/Qual/crash-access.json') == 'True',
                 'Limited-token crash archive read check', seconds=120, interval=2, error=Infrastructure)
        copy_out('C:/Qual/crash-access.json', out / 'crash-access.json')
        if json.loads((out / 'crash-access.json').read_text()).get('verified') is not True:
            raise Infrastructure('Limited app task cannot read crash archives; see crash-access.json')
    finally:
        guest_ps('Unregister-ScheduledTask -TaskName ' + ps_value(task) + ' -Confirm:$false')


def prepare_setup(args, out, source=None):
    """No app journeys: cache unchanged inputs, reboot, verify, then rename."""
    out.mkdir(parents=True, exist_ok=False)
    started = budget = None
    volumes = []
    try:
        vm.wait_free(28800)
        wait_for(lambda: common.ports_available([vm.OS['windows']['ssh'], vm.OS['windows']['qmp']]),
                 'Stopped Windows ports bindable', seconds=90, interval=2, error=Infrastructure)
        if source:
            start_snapshot(source)
        else:
            vm.start('windows')
        started = vm.require_owner('windows')
        budget = WriteBudget(started, out); budget.thread.start()
        vm.wait_ssh('windows', timeout=600)
        if not source:
            provision(args, out)
        stage_inputs(args, out, source)
        guest_ps('& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:/Qual/journeys/windows-cache-tools.ps1; if($LASTEXITCODE){throw "Qualification client cache failed"}', timeout=600)
        copy_out('C:/Qual/inspector-cache.json', out / 'inspector-cache.json')
        copy_out('C:/Qual/uia-client.json', out / 'uia-client.json')
        if args.mirror:
            if guest_ps('Test-Path C:/Qual/mirror/server.json') != 'True':
                vm.mirror('windows', str(args.mirror))
            guest_ps('& C:/Qual/Python/Scripts/python.exe C:/Qual/mirror-windows.py suspend; if($LASTEXITCODE){throw "Mirror snapshot suspension failed"}')
        # Generate corpus once; subsequent runs verify these exact cached bytes.
        guest_ps("if(!(Test-Path C:/Qual/corpus-sha256.json)){ $hashes=& C:/Qual/Python/Scripts/python.exe C:/Qual/journeys/corpus.py C:/Qual/corpus; if($LASTEXITCODE){throw 'Corpus generation failed'}; [IO.File]::WriteAllText('C:/Qual/corpus-sha256.json',($hashes -join [Environment]::NewLine))}")
        prepare_viewer(out)
        quiet_guest('Apply', out)
        guest_ps('& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:/Qual/journeys/windows-edge.ps1; if($LASTEXITCODE){throw "Exact WebDriver setup failed"}', timeout=300)
        volumes.append(budget.finish()); budget.check(); budget = None
        if not vm.stop():
            raise Infrastructure('Guest settings reboot required a forced stop')
        volumes[-1]['overlayGrowthBytes'] = max(0, Path(started['disk']).stat().st_blocks * 512 - volumes[-1]['overlayInitialBytes'])
        wait_for(lambda: common.ports_available([vm.OS['windows']['ssh'], vm.OS['windows']['qmp']]),
                 'Reboot ports bindable', seconds=90, interval=2, error=Infrastructure)
        vm.wait_host_memory()
        with vm.locked() as lease:
            vm.ensure_free(check_lock=False)
            vm.launch('windows', Path(started['disk']), Path(started['disk']).with_name('run-vars.fd'), lease=lease.fileno())
        started = vm.require_owner('windows')
        budget = WriteBudget(started, out); budget.thread.start()
        vm.wait_ssh('windows', timeout=600)
        quiet_guest('Verify', out)
        prepare_viewer(out, verify=True)
        guest_ps('& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:/Qual/journeys/windows-edge.ps1 -VerifyOnly; if($LASTEXITCODE){throw "Exact WebDriver verification failed"}', timeout=60)
        copy_out('C:/Qual/edge/versions.json', out / 'webdriver-versions.json')
        verify_crash_access(out)
        budget.check()
        verified = json.loads((out / 'quiet-verify.json').read_text())
        if verified.get('verified') is not True:
            raise Infrastructure('Guest write settings not verified after reboot')
        # Readable crash evidence is a provisioning requirement for the limited
        # app task. Preserve WER, including pre-install reports and their ACLs.
        volumes.append(budget.finish()); budget.check(); budget = None
        enforce_write_limit({key: sum(v.get(key, 0) for v in volumes) for key in ('hostQemuWrittenBytes', 'guestWrittenBytes')})
        inode = Path(started['disk']).stat().st_ino
        if not vm.stop():
            raise Infrastructure('Setup shutdown required a forced stop; snapshot not sealed')
        volumes[-1]['overlayGrowthBytes'] = max(0, Path(started['disk']).stat().st_blocks * 512 - volumes[-1]['overlayInitialBytes'])
        volumes[-1]['overlayAllocationMeasured'] = 'after shutdown'
        enforce_write_limit(volumes[-1])
        with vm.locked():
            vm.ensure_free(check_lock=False)
            disk = Path(started['disk'])
            if disk.stat().st_ino != inode or disk.stat().st_dev != out.stat().st_dev:
                raise Infrastructure('Setup snapshot requires unchanged disk and same filesystem')
            disk.rename(out / 'disk.qcow2')
            disk.with_name('run-vars.fd').rename(out / 'vars.fd')
        (out / 'manifest.json').write_text(json.dumps({'kind': 'tools', 'pins': pins(args, 'tools'),
            'webview2': json.loads((out / 'webdriver-versions.json').read_text())['webview2'],
            'edgeDriver': json.loads((out / 'webdriver-versions.json').read_text())}, indent=2) + '\n')
        (out / 'summary.json').write_text(json.dumps({'status': 'setup-ready', 'journeysExecuted': False,
            'writeVolume': volumes, 'guestSettings': 'quiet-verify.json'}, indent=2) + '\n')
        return 0
    except Exception as exc:
        (out / 'summary.json').write_text(json.dumps({'status': 'failed-infrastructure', 'reason': str(exc),
            'journeysExecuted': False, 'writeVolume': volumes}, indent=2) + '\n')
        return 1
    finally:
        if budget:
            volumes.append(budget.finish())
            report = json.loads((out / 'summary.json').read_text())
            report['writeVolume'] = volumes
            (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
        if started and vm.read_state() == started:
            vm.stop()
        if volumes and started and Path(started['disk']).exists():
            volumes[-1]['overlayGrowthBytes'] = max(0, Path(started['disk']).stat().st_blocks * 512 - volumes[-1]['overlayInitialBytes'])
            report = json.loads((out / 'summary.json').read_text())
            report['writeVolume'] = volumes
            (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')


def stage_inputs(args, out, snapshot):
    current = json.loads(guest_ps("if(Test-Path C:/Qual/journey-code-sha256.json){Get-Content C:/Qual/journey-code-sha256.json -Raw}else{'{}'}"))
    sources = {path.name: path for path in HERE.iterdir() if path.suffix in ('.py', '.json', '.ps1')}
    sources['../mirror-windows.py'] = HERE.parent / 'mirror-windows.py'
    hashes = {name: digest(path) for name, path in sources.items()}
    changed = {name: path for name, path in sources.items() if current.get(name) != hashes[name]}
    checkpoint = snapshot and (snapshot / 'checkpoint.json').is_file()
    if not changed and snapshot and not checkpoint:
        return
    with tempfile.TemporaryDirectory(prefix='windows-journey-input-') as tmp:
        archive = Path(tmp) / 'input.tar'
        with tarfile.open(archive, 'w') as tar:
            for name, path in changed.items():
                tar.add(path, arcname='mirror-windows.py' if name.startswith('../') else 'journeys/' + name)
            manifest = Path(tmp) / 'journey-code-sha256.json'
            manifest.write_text(json.dumps(hashes))
            tar.add(manifest, arcname=manifest.name)
            if not snapshot:
                for name in (args.package, 'justsearch-mcp.mcpb', 'SHA256SUMS'):
                    tar.add(args.assets / name, arcname='assets/' + name)
                tar.add(args.registry, arcname='server.json')
                if args.predecessor:
                    tar.add(args.predecessor, arcname='predecessor/' + args.predecessor.name)
            if checkpoint:
                tar.add(snapshot / 'checkpoint.json', arcname='checkpoint.json')
        vm.copy_in('windows', archive, 'C:/Qual/input.tar')
    guest_ps('& tar.exe -xf C:/Qual/input.tar -C C:/Qual; if($LASTEXITCODE){throw "Input extraction failed"}; Remove-Item C:/Qual/input.tar')



def approved_installer(args, request):
    if not re.fullmatch(r'[a-f0-9]{32}', request.get('nonce', '')) or not re.fullmatch(r'[a-f0-9]{64}', request.get('sha256', '')):
        raise Infrastructure('Malformed NSIS broker request')
    image = ntpath.normcase(request.get('path', ''))
    allowed = {ntpath.normcase('C:/Qual/assets/' + args.package): digest(args.assets / args.package)}
    if args.predecessor:
        allowed[ntpath.normcase('C:/Qual/predecessor/' + args.predecessor.name)] = digest(args.predecessor)
    uninstall = ntpath.normcase(r'C:\Users\qual\AppData\Local\JustSearch\uninstall.exe')
    if image == uninstall:
        return 'uninstall'
    if allowed.get(image) != request['sha256']:
        raise Infrastructure('Refusing an NSIS request outside the exact approved release bytes')
    return 'install'


def serve_installer(args, out):
    raw = guest_ps("if(Test-Path C:/Qual/installer-request.json){Get-Content C:/Qual/installer-request.json -Raw}")
    if not raw:
        return
    if len(raw) > 4096:
        raise Infrastructure('NSIS broker request exceeds its limit')
    request = json.loads(raw)
    kind = approved_installer(args, request)
    nonce, installer = request['nonce'], request['path']
    # The request is consumed before launching. One VM/runner owns this queue.
    guest_ps('Move-Item C:/Qual/installer-request.json ' + ps_value('C:/Qual/installer-request-' + nonce + '.json'))
    if kind == 'uninstall':
        valid = guest_ps(r"$p=Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\JustSearch'; $p.UninstallString.Trim([char]34) -ieq " + ps_value(installer))
        if valid != 'True':
            raise Infrastructure('Uninstaller request differs from the registered per-user installation')
    worker = 'C:/Qual/installer-worker-' + nonce + '.json'
    task = 'JustSearch-qual-package-operation'
    arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File C:/Qual/journeys/windows-installer.ps1 -Installer "' + installer + '" -Sha256 ' + request['sha256'] + ' -Nonce ' + nonce
    try:
        guest_ps('$a=New-ScheduledTaskAction -Execute powershell.exe -Argument ' + ps_value(arguments) +
            ';$p=New-ScheduledTaskPrincipal -UserId qual -LogonType Interactive -RunLevel Highest;' +
            'Register-ScheduledTask -TaskName ' + ps_value(task) + ' -Action $a -Principal $p -Force | Out-Null;' +
            'Start-ScheduledTask -TaskName ' + ps_value(task))
        wait_for(lambda: guest_ps('Test-Path ' + ps_value(worker)) == 'True',
                 'Highest interactive NSIS task completed', seconds=660, interval=2, error=Infrastructure)
        result = json.loads(guest_ps('Get-Content -LiteralPath ' + ps_value(worker) + ' -Raw'))
        (out / ('installer-' + nonce + '.json')).write_text(json.dumps(result, indent=2) + '\n')
        encoded = base64.b64encode(json.dumps(result).encode()).decode()
        response = 'C:/Qual/installer-result-' + nonce + '.json'
        guest_ps('$s=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(' + ps_value(encoded) +
            '));[IO.File]::WriteAllText(' + ps_value(response + '.tmp') + ',$s);Move-Item -LiteralPath ' +
            ps_value(response + '.tmp') + ' -Destination ' + ps_value(response))
    finally:
        guest_ps('Unregister-ScheduledTask -TaskName ' + ps_value(task) + ' -Confirm:$false -ErrorAction SilentlyContinue')

def execute(args, out, phase, journeys, snapshot, budget=None):
    stage_inputs(args, out, snapshot)
    if 'J5' in journeys and args.mirror:
        guest_ps('& C:/Qual/Python/Scripts/python.exe C:/Qual/mirror-windows.py resume; if($LASTEXITCODE){throw "Cached mirror resume failed"}')
    # A stopped checkpoint retains earlier evidence/work directories. Every
    # child run needs a new directory even when its host stage is named rerun.
    remote_name = 'evidence-' + str(time.time_ns())
    remote = 'C:/Qual/' + remote_name
    command = ['C:/Qual/Python/Scripts/python.exe', 'C:/Qual/journeys/' + ('windows-hosted.py' if args.session == 'hosted' else 'runner.py')]
    if args.session != 'hosted':
        command += ['--lane', 'vm-windows', '--driver', 'C:/Qual/tauri-driver.exe', '--native-driver', 'C:/Qual/edge/msedgedriver.exe']
    command += ['--assets', 'C:/Qual/assets', '--package', args.package, '--registry', 'C:/Qual/server.json',
                '--phase', phase, '--evidence', remote, '--checkpoint', 'C:/Qual/next-checkpoint.json', '--journeys', *journeys]
    if args.predecessor:
        command += ['--predecessor', 'C:/Qual/predecessor/' + args.predecessor.name]
    if args.mirror:
        command += ['--mirror', 'C:/Qual/mirror/www']
    if snapshot and (snapshot / 'checkpoint.json').is_file():
        command += ['--resume', 'C:/Qual/checkpoint.json']
    job = "$ErrorActionPreference='Stop'\ntry {\n"
    job += "$tools=Get-Content C:/Qual/tools.json -Raw | ConvertFrom-Json\n$env:PATH=(Split-Path $tools.node)+';'+$env:PATH\n"
    job += '& ' + ps_value(command[0]) + ' @(' + ','.join(map(ps_value, command[1:])) + ') > C:/Qual/journey-run.log 2>&1\n'
    job += "$code=$LASTEXITCODE\n} catch {$_ | Out-File C:/Qual/journey-run.log -Append; $code=1}\n"
    job += "[IO.File]::WriteAllText('C:/Qual/journey-exit.tmp',[string]$code)\nMove-Item C:/Qual/journey-exit.tmp C:/Qual/journey-exit.txt -Force\n"
    with tempfile.TemporaryDirectory(prefix='windows-journey-task-') as tmp:
        script = Path(tmp) / 'run.ps1'
        script.write_text(job)
        vm.copy_in('windows', script, 'C:/Qual/run-journeys.ps1')
    guest_ps("Remove-Item C:/Qual/journey-exit.txt,C:/Qual/next-checkpoint.json -ErrorAction SilentlyContinue; " +
        "$a=New-ScheduledTaskAction -Execute powershell.exe -Argument '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File C:/Qual/run-journeys.ps1' -WorkingDirectory C:/Qual;" +
        '$p=New-ScheduledTaskPrincipal -UserId qual -LogonType Interactive -RunLevel ' + ('Highest' if args.session == 'hosted' else 'Limited') + ';' +
        'Register-ScheduledTask -TaskName ' + ps_value(TASK) + ' -Action $a -Principal $p -Force | Out-Null;' +
        'Start-ScheduledTask -TaskName ' + ps_value(TASK))
    def completed():
        if budget:
            budget.check()
        if args.session != 'hosted':
            serve_installer(args, out)
        value = guest_ps("if(Test-Path C:/Qual/journey-exit.txt){Get-Content C:/Qual/journey-exit.txt}else{" +
            '$t=Get-ScheduledTask -TaskName ' + ps_value(TASK) +
            ';if($t.State -ne "Running"){throw "Interactive journey task exited without a result"}}')
        return value if value else None
    result = int(wait_for(completed, 'Interactive Windows journey completion', seconds=7200, interval=3, error=Infrastructure))
    copy_out('C:/Qual/journey-run.log', out / 'run.log')
    # tar excludes links through the shared bounded extraction validator.
    guest_ps('& tar.exe -cf C:/Qual/evidence.tar -C C:/Qual ' + ps_value(remote_name) + '; if($LASTEXITCODE){throw "Evidence archive failed"}')
    copy_out('C:/Qual/evidence.tar', out / 'evidence.tar')
    common.extract_evidence(out / 'evidence.tar', out, remote_name)
    shutil.copyfile(out / 'evidence/summary.json', out / 'summary.json')
    copy_out('C:/Qual/next-checkpoint.json', out / 'checkpoint.json', check=False)
    return result


def run_overlay(args, out, phase, journeys, snapshot=None, seal=False):
    out.mkdir(parents=True, exist_ok=False)
    started = None
    budget = None
    update_before = None
    try:
        manifest = json.loads((snapshot / 'manifest.json').read_text()) if snapshot else {}
        expected = pins(args, 'tools' if manifest.get('kind') == 'tools' else phase)
        if snapshot and manifest['pins'] != expected:
            raise Infrastructure('Snapshot package/tool/base/mirror pins differ')
        if manifest.get('kind') not in ('tools', 'application') or not json.loads((snapshot / 'quiet-verify.json').read_text()).get('verified'):
            raise Infrastructure('Snapshot guest write settings are not verified after reboot')
        if not snapshot:
            raise Infrastructure('Windows journeys require --setup-snapshot; provision once before running journeys')
        shutil.copyfile(snapshot / 'quiet-verify.json', out / 'quiet-verify.json')
        vm.wait_free(28800)
        wait_for(lambda: common.ports_available([vm.OS['windows']['ssh'], vm.OS['windows']['qmp']]),
                 'Stopped Windows ports bindable', seconds=90, interval=2, error=Infrastructure)
        if snapshot:
            start_snapshot(snapshot)
        else:
            vm.start('windows')
        started = vm.require_owner('windows')
        budget = WriteBudget(started, out, write_limit(journeys))
        budget.thread.start()
        inode = Path(started['disk']).stat().st_ino
        vm.wait_ssh('windows', timeout=600)
        update_before = update_observation()
        (out / 'update-activity.json').write_text(json.dumps({'before': update_before}, indent=2) + '\n')
        verify_update_effect(update_before, update_before)
        (out / 'vm.json').write_text(json.dumps(started, indent=2) + '\n')
        return execute(args, out, phase, journeys, snapshot, budget)
    except Exception as exc:
        lane = 'hosted-windows' if args.session == 'hosted' else 'vm-windows'
        (out / 'summary.json').write_text(json.dumps(preparation_failure(lane, str(exc)), indent=2) + '\n')
        (out / 'wrapper-error.txt').write_text(repr(exc) + '\n')
        return 1
    finally:
        volume = budget.finish() if budget else {'unavailable': 'VM did not start'}
        if started and vm.read_state() == started:
            errors = []
            try:
                if update_before:
                    update_after = update_observation(update_before['time'])
                    (out / 'update-activity.json').write_text(json.dumps({'before': update_before, 'after': update_after}, indent=2) + '\n')
                    verify_update_effect(update_before, update_after)
                guest_ps('if (Get-ScheduledTask -TaskName ' + ps_value(TASK) + ' -ErrorAction SilentlyContinue) {Unregister-ScheduledTask -TaskName ' + ps_value(TASK) + ' -Confirm:$false}')
                if args.mirror and guest_ps('Test-Path C:/Qual/mirror/server.json') == 'True':
                    if seal:
                        guest_ps('& C:/Qual/Python/Scripts/python.exe C:/Qual/mirror-windows.py suspend; if($LASTEXITCODE){throw "Mirror snapshot suspension failed"}')
                    else:
                        vm.mirror('windows', remove=True)
            except Exception as exc:
                errors.append(str(exc))
            finally:
                clean = vm.stop()
            if started and Path(started['disk']).exists():
                volume['overlayGrowthBytes'] = max(0, Path(started['disk']).stat().st_blocks * 512 - budget.initial)
            report = json.loads((out / 'summary.json').read_text())
            if errors:
                report['journeys'].append({'id': 'wrapper-cleanup', 'checks': [{'name': 'cleanup',
                    'status': 'failed-infrastructure', 'reason': '; '.join(errors), 'evidence': []}]})
            report.update(writeVolume=volume, execution={'mode': args.mode, 'snapshot': str(snapshot) if snapshot else None,
                'requestedJourneys': journeys}, **summarize(report['journeys'], report.get('fullSelection', False)))
            (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
            if seal and clean and not errors and not budget.error and (out / 'checkpoint.json').is_file():
                seal_snapshot(out, started, inode, args, phase)
        if budget:
            report = json.loads((out / 'summary.json').read_text())
            if budget.error:
                report['journeys'].append({'id': 'write-budget', 'checks': [{'name': 'write-limit',
                    'status': 'failed-infrastructure', 'reason': budget.error, 'evidence': []}]})
            report.update(writeVolume=volume, **summarize(report['journeys'], report.get('fullSelection', False)))
            (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
        if started and vm.read_state() != started and Path(started['disk']).exists():
            # Sealed snapshots have already moved their disk. Ordinary overlays
            # hold no unique inputs; diagnostics were copied before shutdown.
            with vm.locked():
                vm.ensure_free(check_lock=False)
                disk = Path(started['disk'])
                if disk.stat().st_ino == inode:
                    disk.unlink()
                    disk.with_name('run-vars.fd').unlink(missing_ok=True)


def combine(args, out, sources):
    rows = []
    for name, source in sources.items():
        for original in source['journeys']:
            if name.startswith('predecessor') and original['id'] in ('J1', 'J2', 'J3', 'J4'):
                continue
            row = json.loads(json.dumps(original))
            for check in row['checks']:
                check['evidence'] = [name + '/evidence/' + p for p in check['evidence']]
            rows.append(row)
    report = dict(next(iter(sources.values())))
    lane = 'hosted-windows' if args.session == 'hosted' else 'vm-windows'
    complete = args.mode == 'fresh' and not args.journeys and full_selection(rows, lane)
    report.update(journeys=rows, overlays={name: name + '/summary.json' for name in sources},
        execution={'mode': args.mode, 'requestedJourneys': args.journeys, 'releaseQualification': complete},
        writeVolume={**{key: sum(s.get('writeVolume', {}).get(key, 0) for s in sources.values())
                        for key in ('guestWrittenBytes', 'hostQemuWrittenBytes', 'overlayGrowthBytes')},
            'complete': all(all(key in s.get('writeVolume', {}) for key in ('guestWrittenBytes', 'hostQemuWrittenBytes', 'overlayGrowthBytes')) for s in sources.values()),
            'source': 'guest raw bytes and QEMU process write_bytes through pre-shutdown; final overlay allocation growth; every overlay'},
        **summarize(rows, complete))
    (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
    return report_exit_code(report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', choices=['vm', 'hosted'], default='vm')
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('--registry', type=Path, required=True)
    parser.add_argument('--driver', type=Path, required=True)
    parser.add_argument('--predecessor', type=Path)
    parser.add_argument('--mirror', type=Path)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--journeys', nargs='+')
    parser.add_argument('--mode', choices=['prepare', 'fresh', 'rerun'], default='fresh')
    parser.add_argument('--snapshot', type=Path)
    parser.add_argument('--setup-snapshot', type=Path, help='Reboot-verified tool/input snapshot; required for fresh runs')
    args = parser.parse_args()
    vm.owner()
    ids = {j['id'] for j in json.loads((HERE / 'journeys.json').read_text())['journeys']}
    if args.journeys and not set(args.journeys) <= ids:
        parser.error('Unknown journey ID')
    if args.mode == 'rerun' and (not args.snapshot or not args.journeys or len(args.journeys) != 1 or 'J1' in args.journeys):
        parser.error('Development reruns require --snapshot and one explicit journey excluding J1')
    if args.mode == 'fresh' and args.snapshot:
        parser.error('Fresh qualification cannot inherit a setup snapshot')
    if args.mode == 'fresh' and not args.setup_snapshot:
        parser.error('Fresh runs require --setup-snapshot (create once with --mode prepare)')
    if args.mode == 'prepare':
        if args.journeys or args.snapshot or args.setup_snapshot:
            parser.error('Preparation runs no journeys and takes no inherited application state')
        return prepare_setup(args, args.evidence.resolve())
    if args.session == 'vm' and not args.journeys and (not args.predecessor or not args.mirror):
        parser.error('Full Windows VM qualification requires --predecessor and --mirror')
    out = args.evidence.resolve()
    out.mkdir(parents=True, exist_ok=False)
    sources = {}
    def stage(name, phase, journeys, snapshot=None, seal=False):
        run_overlay(args, out / name, phase, journeys, snapshot, seal)
        sources[name] = json.loads((out / name / 'summary.json').read_text())
        return not any(row['id'] == 'write-budget' for row in sources[name]['journeys'])
    if args.mode == 'rerun':
        phase = json.loads((args.snapshot / 'manifest.json').read_text())['pins']['phase']
        stage('rerun', phase, args.journeys, args.snapshot, seal=True)
    elif args.journeys:
        stage('selected', 'candidate', args.journeys, args.setup_snapshot, seal=True)
    else:
        for phase in (['candidate'] if args.session == 'hosted' else ['candidate', 'predecessor']):
            # J3 alone has a 25 GB allowance. Other journeys retain their 15 GB
            # overlay budget, including in a full qualification invocation.
            if not stage(phase + '-install', phase, ['J1', 'J2'], args.setup_snapshot, seal=True):
                return combine(args, out, sources)
            installed = out / (phase + '-install') / 'snapshot'
            if installed.exists():
                if not stage(phase + '-setup', phase, ['J3'], installed, seal=True):
                    return combine(args, out, sources)
            snapshot = out / (phase + '-setup') / 'snapshot'
            if snapshot.exists():
                remaining = ['J4', 'J5', 'J6'] if phase == 'predecessor' else ['J4', 'J8']
                if args.session == 'hosted':
                    remaining = ['J4', 'J5', 'J6', 'J7', 'J8']
                if not stage(phase, phase, remaining, snapshot):
                    return combine(args, out, sources)
            else:
                lane = 'hosted-windows' if args.session == 'hosted' else 'vm-windows'
                sources[phase] = preparation_failure(lane, 'Setup did not produce a stopped checkpoint')
    return combine(args, out, sources)


if __name__ == '__main__':
    sys.exit(main())
