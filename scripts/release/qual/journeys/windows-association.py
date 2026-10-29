#!/usr/bin/env python3
"""Set the disposable user's text viewer through Windows' native Default Apps UI."""
import argparse
import json
import ntpath
import os
from pathlib import Path
import time
import traceback

from runner import Infrastructure, wait_for
from windows import Target, Native, PROCESSES, ARGV, ps_json, ps_value, default_viewer, viewer_identity


def same_image(left, right):
    return ntpath.normcase(ntpath.normpath(left)) == ntpath.normcase(ntpath.normpath(right))


def association_matches(expected):
    observed = default_viewer()
    return observed if same_image(observed['executable'], expected) else None


def register_viewer():
    import winreg
    image = str(Path(os.environ['WINDIR']) / 'System32/WindowsPowerShell/v1.0/powershell_ise.exe')
    if not Path(image).is_file():
        raise Infrastructure('The disposable Windows image has no PowerShell ISE text editor')
    command = '"' + image + '" -NoProfile -File "%1"'
    # Advertise this qualification-owned ProgID. Windows itself writes UserChoice
    # through Settings below; do not synthesize or delete its protected hash.
    for key, value in ((r'Software\Classes\.txt', 'JustSearchQualText'),
                       (r'Software\Classes\JustSearchQualText\shell\open\command', command)):
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, key) as handle:
            winreg.SetValueEx(handle, '', 0, winreg.REG_SZ, value)
    metadata = ps_json('$f=Get-Item -LiteralPath ' + ps_value(image) +
        ';@{label=$f.VersionInfo.FileDescription;sha256=(Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}')
    path = Path('C:/Qual/tools.json')
    tools = json.loads(path.read_text())
    tools.update(viewer=image, viewerLabel=metadata['label'], viewerSha256=metadata['sha256'])
    path.write_text(json.dumps(tools))
    return tools


def configure(target, native, tools, out, verify=False):
    expected = tools['viewer']
    association = association_matches(expected)
    if association:
        return {'verified': True, 'method': 'already-selected', 'association': association}
    if verify:
        raise Infrastructure('Default text viewer did not survive reboot: ' + repr(default_viewer()))
    before = ps_json(PROCESSES, array=True)
    windows_dir = Path(os.environ['WINDIR'])
    settings_image = str(windows_dir / 'ImmersiveControlPanel/SystemSettings.exe')
    frame_image = str(windows_dir / 'System32/ApplicationFrameHost.exe')
    chooser_image = str(windows_dir / 'System32/OpenWith.exe')
    if any(same_image(p['exe'], settings_image) for p in before):
        raise Infrastructure('Viewer setup refuses an existing Settings session')
    client, module = native.automation()
    recorded = []

    def save(name, value):
        (out / (name + '.json')).write_text(json.dumps(value, indent=2))

    def new_processes(image):
        rows = ps_json(PROCESSES, array=True)
        found = [p for p in rows if same_image(p['exe'], image)
                 and (same_image(image, frame_image) or not any(p['pid'] == old['pid'] and p['created'] == old['created'] for old in before))]
        for p in found:
            if (not any(p['pid'] == old['pid'] and p['created'] == old['created'] for old in before)
                    and not any(p['pid'] == old['pid'] and p['created'] == old['created'] for old in target.roots)):
                target.roots.append(p)
                recorded.append(p)
        return found

    def find_window(image, klass):
        candidates = new_processes(image)
        elements = client.GetRootElement().FindAll(module.TreeScope_Children, client.CreateTrueCondition())
        for i in range(elements.Length):
            element = elements.GetElement(i)
            process = next((p for p in candidates if p['pid'] == element.CurrentProcessId), None)
            if (process and element.CurrentClassName == klass and element.CurrentNativeWindowHandle
                    and (klass != 'ApplicationFrameWindow' or element.CurrentName == 'Settings')):
                window = {'pid': process['pid'], 'process': process, 'class': klass,
                          'handle': element.CurrentNativeWindowHandle}
                native.element(window)
                save('window-' + klass.replace(' ', '-'), window)
                return window

    def control(window, process, predicate):
        def probe():
            if not target.alive([process]):
                raise Infrastructure('Default Apps process identity changed')
            elements = [e for e in native.descendants(window) if e.CurrentProcessId == process['pid']
                        and e.CurrentIsEnabled and not e.CurrentIsOffscreen and predicate(e)]
            if len(elements) > 1:
                raise Infrastructure('Default Apps control is ambiguous')
            return elements[0] if elements else None
        return wait_for(probe, 'Owned Default Apps control', seconds=45, error=Infrastructure)

    def invoke(window, process, element):
        if not target.alive([process]):
            raise Infrastructure('Default Apps action process identity changed')
        native.element(window)
        element.GetCurrentPattern(module.UIA_InvokePatternId).QueryInterface(module.IUIAutomationInvokePattern).Invoke()

    target.spawn(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command',
                  "Start-Process 'ms-settings:defaultapps'"], out / 'settings-launch.log')
    window = wait_for(lambda: find_window(frame_image, 'ApplicationFrameWindow'),
                      'New Default Apps Settings frame', seconds=60, error=Infrastructure)
    settings = wait_for(lambda: next(iter(new_processes(settings_image)), None),
                        'New Settings process', seconds=30, error=Infrastructure)
    field = control(window, settings, lambda e: e.CurrentControlType == 50004
                    and e.CurrentName == 'Enter a file type or link type')
    native.element(window)
    value = field.GetCurrentPattern(module.UIA_ValuePatternId).QueryInterface(module.IUIAutomationValuePattern)
    value.SetValue('.txt')
    if value.CurrentValue != '.txt':
        raise Infrastructure('Default Apps file-type search did not accept .txt')
    button = control(window, settings, lambda e: e.CurrentControlType == 50000 and e.CurrentName.startswith('.txt, '))
    invoke(window, settings, button)
    chooser = wait_for(lambda: find_window(chooser_image, 'Open With'),
                       'New Windows default-app chooser', seconds=45, error=Infrastructure)
    app = control(chooser, chooser['process'], lambda e: e.CurrentControlType == 50007
                  and e.CurrentName.split(',')[0].strip() == tools['viewerLabel'])
    native.element(chooser)
    app.GetCurrentPattern(module.UIA_SelectionItemPatternId).QueryInterface(module.IUIAutomationSelectionItemPattern).Select()
    button = control(chooser, chooser['process'], lambda e: e.CurrentAutomationId == 'OpenWith_SingleButton'
                     and e.CurrentControlType == 50000 and e.CurrentName == 'Set default')
    invoke(chooser, chooser['process'], button)
    association = wait_for(lambda: association_matches(expected), 'Windows selected the exact viewer image',
                           seconds=30, error=Infrastructure)
    save('selected-association', association)
    # Prove the normal OS opener, not a direct launch of the intended viewer.
    fixture = out / 'viewer-probe.txt'
    fixture.write_text('JustSearch qualification viewer setup probe.\n')
    before_viewer = ps_json(PROCESSES, array=True)
    os.startfile(str(fixture.resolve()))
    def receiving():
        rows = ps_json(ARGV + '$rows=' + PROCESSES + r''';
@($rows | ForEach-Object {$_.argv=if($_.command){[QualArgv]::Split($_.command)}else{@()};$_})
''', array=True)
        return viewer_identity(rows, native.windows(), str(fixture.resolve()), expected, before_viewer)
    viewer = wait_for(receiving, 'Default viewer opened exact setup file with UIA main window',
                      seconds=60, error=Infrastructure)
    target.roots.append(viewer)
    save('receiving-viewer', viewer)
    target.stop([viewer])
    return {'verified': True, 'method': 'native-default-apps', 'association': association,
            'viewer': viewer, 'processes': recorded}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, default=Path('C:/Qual/viewer-setup'))
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)
    target = Target()
    result = {'verified': False}
    code = 1
    try:
        target.prepare('hosted-windows' if os.environ.get('GITHUB_ACTIONS') == 'true' else 'vm-windows',
                       'viewer-setup-' + str(time.time_ns()))
        target.process_evidence = args.evidence / 'processes.json'
        target.observe()
        tools = register_viewer()
        result = configure(target, Native(target, args.evidence), tools, args.evidence, args.verify)
        code = 0
    except Exception:
        result['error'] = traceback.format_exc()
    finally:
        try:
            target.observer_done.set()
            if target.observer:
                target.observer.join()
            target.stop(target.owned())
        except Exception:
            result.update(verified=False, cleanupError=traceback.format_exc())
            code = 1
        (args.evidence / ('verify.json' if args.verify else 'configure.json')).write_text(json.dumps(result, indent=2))
    return code


if __name__ == '__main__':
    raise SystemExit(main())
