"""Linux implementation of the shared journey definition; no product test hooks."""
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import tarfile
import time
import zipfile

from corpus import generate, OPEN_FILE, QUERY, OCR_QUERY
from runner import HERE, Infrastructure, Unavailable, digest, wait_for


def apt(run, *args):
    if run.target.vm:
        result = run.target.run(['sudo', '-S', '-p', '', 'env', 'DEBIAN_FRONTEND=noninteractive',
                                 'apt-get', '-y', *args],
                                input=run.target.vm.config()['password'] + '\n', timeout=900, check=False)
    else:
        # Only for disposable hosted machines, never the release owner's host.
        result = run.target.run(['sudo', '-n', 'env', 'DEBIAN_FRONTEND=noninteractive',
                                 'apt-get', '-y', *args], timeout=900, check=False)
    (run.out / f'apt-{time.time_ns()}.log').write_text(result.stdout + result.stderr)
    assert result.returncode == 0, f'apt-get {args[0]} failed with exit {result.returncode}; see apt log'


def installed_version(run):
    return run.target.run(['dpkg-query', '-W', '-f=${Version}', 'just-search']).stdout.strip()


def require_candidate(run):
    if installed_version(run) != run.require('initialVersion'):
        raise Unavailable('Candidate is not installed; predecessor observations cannot qualify its desktop integration')


def package_install(run):
    initial = run.args.assets / run.args.package
    if not initial.is_file():
        raise Infrastructure('Initial package missing: ' + str(initial))
    target = run.work + '/' + initial.name
    run.target.copy(initial, target)
    run.facts['initialPackage'] = {'name': initial.name, 'sha256': digest(initial)}
    apt(run, 'install', target)
    paths = run.target.run(['dpkg-query', '-L', 'just-search']).stdout.splitlines()
    binaries = [p for p in paths if p.endswith('/JustSearch') and '/bin/' in p]
    assert len(binaries) == 1, 'Package has no unique installed executable'
    run.binary = binaries[0]
    run.facts['initialVersion'] = installed_version(run)
    expected = run.target.run(['dpkg-deb', '-f', target, 'Version']).stdout.strip()
    assert installed_version(run) == expected, 'Installed package version differs from the selected bytes'
    verify = run.target.run(['dpkg', '--verify', 'just-search'], check=False)
    assert verify.returncode == 0 and not verify.stdout.strip(), 'Installed payload differs from dpkg checksums'
    has_java = run.target.run(['sh', '-c', 'command -v javac'], check=False).returncode == 0
    assert not has_java, 'Clean install inherited a developer JDK'
    bridge = run.work + '/release.mcpb'
    run.target.copy(run.args.assets / run.args.mcpb, bridge)
    run.target.py("""
import json,stat,sys,zipfile
from pathlib import Path,PurePosixPath
a=json.loads(sys.argv[1]); root=Path(a['out']);root.mkdir()
with zipfile.ZipFile(a['bundle']) as z:
 assert len(z.infolist())<100 and sum(e.file_size for e in z.infolist())<5000000
 for e in z.infolist():
  p=PurePosixPath(e.filename)
  assert not p.is_absolute() and '..' not in p.parts and not stat.S_ISLNK(e.external_attr>>16)
 z.extractall(root)
""", {'bundle': bridge, 'out': run.work + '/mcpb'})
    with tempfile.TemporaryDirectory(prefix='journey-corpus-') as temp:
        corpus = Path(temp) / 'corpus'
        hashes = generate(corpus)
        if run.target.vm:
            archive = Path(temp) / 'corpus.tar'
            with tarfile.open(archive, 'w') as tar:
                tar.add(corpus, arcname='corpus')
            run.target.copy(archive, run.work + '/corpus.tar')
            run.target.run(['tar', '-xf', run.work + '/corpus.tar', '-C', run.work])
        else:
            import shutil
            shutil.copytree(corpus, run.work + '/corpus')
    run.facts['corpus'] = run.work + '/corpus'
    run.evidence('corpus-sha256', hashes)
    return {'installedVersion': expected, 'binary': run.binary, 'package': run.facts['initialPackage'],
            'bundledJVM': [p for p in paths if p.endswith('/runtime/bin/java')]}


def first_run(run):
    if not run.binary:
        raise Unavailable('Package installation did not complete')
    run.normal_launch()
    text = wait_for(lambda: (v if 'JustSearch' in v or 'What are you looking for?' in v else None)
                    if (v := run.native.screen_text('J1-normal-launch')) else None,
                    'Packaged window visible', seconds=60, error=Infrastructure)
    run.evidence('J1-normal-ready', run.ready())
    run.start_ui()
    wait_for(lambda: run.wd.execute("return all(document,'jf-shell').length > 0"),
             'Packaged web UI mounted', seconds=60, error=Infrastructure)
    # Dismiss only the actual first-run welcome, if the candidate has one.
    buttons = run.wd.execute("return all(document,'button').map(e=>e.textContent.trim());")
    if 'Get started' in buttons:
        run.wd.click('button', 'Get started')
    if run.wd.execute("return all(document,'jf-walkthrough-card').some(e=>!e.hidden)"):
        run.wd.click('button[aria-label="Dismiss"]')
    wait_for(lambda: 'Search' in run.native.screen_text('J1-first-run-visible'),
             'Packaged UI visible after mounting', seconds=30, error=Infrastructure)
    run.native.shot('J1-first-run')
    return {'normalLaunch': True, 'webDriver': 'installed executable', 'buttons': buttons}


def add_folder(run):
    run.require('ui')
    run.nav('Library')
    run.wd.click('button[aria-label="Add Folder"]')
    try:
        # The actual GtkFileChooserNative is driven outside WebDriver.
        wait_for(lambda: run.native.picker_visible('J2-picker'),
                 'Native folder picker visible', seconds=20, error=Infrastructure)
        run.native.key('ctrl+l')
        run.native.type(run.require('corpus'))
        run.native.key('enter')
        time.sleep(1)
        if run.native.picker_visible('J2-picker-location'):
            # Some chooser backends navigate from the location field before
            # accepting. Open/Select are both the default native action.
            run.native.key('enter' if run.target.vm else 'alt+o')
        run.native.shot('J2-picker-selected')
        # The production Library intentionally stages a picker selection for
        # review. Confirm the exact returned path, then activate its real Add.
        selected = wait_for(lambda: run.wd.execute("return all(document,'input[aria-label=\"Folder path\"]').find(e=>e.value===arguments[0]) || null;", [run.require('corpus')]),
                            'System picker returned the exact corpus path', seconds=30, error=Infrastructure)
        run.wd.click('button[aria-label="Add"]')
        roots = wait_for(lambda: (v if run.require('corpus') in json.dumps(v) else None)
                         if (v := run.api('/api/indexing/roots')) else None,
                         'System picker added watched folder', seconds=45)
    except BaseException:
        run.native.key('escape')
        raise
    run.facts['folder'] = roots
    return roots


def search_api(run, query=QUERY, filename=OPEN_FILE):
    def probe():
        result = run.api('/api/knowledge/search', {'query': query, 'mode': 'text', 'limit': 20})
        run.evidence('search-last', result)
        return result if filename in json.dumps(result.get('results', [])) else None
    return wait_for(probe, 'Indexed fixture returned: ' + filename, seconds=240)


def search_open(run):
    run.require('folder')
    run.require('ui')
    result = search_api(run)
    run.nav('Search')
    # Search input is distinct from the AI composer. The selector is scoped to
    # native input controls, including open shadow roots, and is probed below.
    run.wd.click('button[aria-label="Mode: Search"]')
    run.wd.click('button[data-tier="search"]')
    selector = 'textarea[data-testid="sv3-composer-input"]'
    run.wd.keys(selector, QUERY + '\ue007')
    wait_for(lambda: OPEN_FILE in run.wd.text(), 'Fixture visible in search UI', seconds=45)
    # WebDriver's real secondary mouse click opens the result's context menu.
    hit = run.wd.element('[data-testid="search-result-row"]', seconds=5)
    hit_key = hit['element-6066-11e4-a52e-4f735466cecf']
    run.wd.call(f'/session/{run.wd.session}/actions', {'actions': [{'type': 'pointer', 'id': 'mouse',
        'parameters': {'pointerType': 'mouse'}, 'actions': [
            {'type': 'pointerMove', 'duration': 0, 'origin': {'element-6066-11e4-a52e-4f735466cecf': hit_key}, 'x': 0, 'y': 0},
            {'type': 'pointerDown', 'button': 2}, {'type': 'pointerUp', 'button': 2}]}]})
    run.wd.click('button,[role="menuitem"]', 'Open file')
    # Receiving-viewer evidence, not an IPC response or process-spawn assertion.
    wait_for(lambda: (v if all(word in v for word in ('File', 'Edit', 'Document')) else None)
             if (v := run.native.screen_text('J2-receiving-viewer-opening')) else None,
             'Default text viewer visible', seconds=30, error=Infrastructure)
    # Mousepad's initial window truncates the absolute path in its title bar.
    # Maximize the actual foreground viewer before asserting the filename.
    run.native.key('alt+f10')
    text = wait_for(lambda: (v if OPEN_FILE.lower() in v.lower() else None)
                    if (v := run.native.screen_text('J2-receiving-viewer')) else None,
                    'Filename visible in receiving viewer', seconds=30, error=Infrastructure)
    assert OPEN_FILE.lower() in text.lower(), 'Viewer does not show the unique filename'
    run.native.key('alt+f4')
    run.desktop_spawn([run.binary], run.work + '/return-from-viewer.log')
    run.facts['search'] = True
    return {'search': result, 'receivingViewerText': text}


def install_ai(run):
    run.require('ui')
    run.require('folder')
    run.nav('AI Brain')
    preview = run.api('/api/ai/install/plan-preview')
    run.evidence('J3-default-plan', preview)
    manifest = run.api('/api/ai/install/manifest')
    run.evidence('J3-production-manifest', manifest)
    run.wd.click('button[aria-label="Install AI"]')
    run.wd.click('button[aria-label="Accept and download"]')
    samples = []
    def settled():
        status = run.api('/api/ai/install/status')
        samples.append(status)
        run.evidence('J3-install-observations', samples)
        raw = json.dumps(status)
        if status.get('state') in ('failed', 'error'):
            if any(x in raw.lower() for x in ('connection reset', 'timed out', 'timeout', 'resolve host', 'http 503', 'http 502', 'http 429')):
                raise RuntimeError('Production AI download failed; see J3-install-observations.json')
            raise AssertionError('Install AI failed: ' + raw)
        return status if status.get('state') in ('completed', 'installed', 'succeeded') or status.get('installedFully') is True else None
    status = wait_for(settled, 'Default CPU AI install', seconds=1800, interval=5)
    assert preview.get('downloadProfile') == 'CPU', 'Default plan did not select CPU'
    assert 'retrieval-core' in status.get('readyCapabilities', []), 'Installed CPU search capability is not ready'
    runtime = run.api('/api/ai/runtime/status')
    run.evidence('J3-runtime', runtime)
    components = preview.get('components', [])
    chat = next((c for c in components if c.get('id') == 'chat'), None)
    assert chat and chat.get('state') == 'unavailable', 'CPU default plan does not declare chat unavailable'
    assert any(c.get('id') != 'chat' and c.get('state') != 'unavailable' for c in components), 'CPU plan has no available search components'
    run.nav('Search')
    run.require('folder')
    search_api(run)
    run.nav('AI Brain')
    text = run.wd.text()
    assert (chat.get('unavailableReason') or 'Chat is not supported here.') in text, 'CPU chat unavailable explanation is not shown'
    run.native.shot('J3-cpu-search-without-chat')
    run.facts['ai'] = status
    return {'installed': status, 'runtime': runtime, 'cpuPlan': preview, 'uiText': text}


def snapshot(run):
    settings = run.api('/api/settings/v2')
    roots = run.api('/api/indexing/roots')
    # Observe owned authored/settings/model files; never modify saved state to
    # fabricate a persistence fixture. Exclude derived/live index and log bytes.
    files = run.target.json("""
import hashlib,json,sqlite3,sys
from pathlib import Path
root=Path(json.loads(sys.argv[1])); rows={}
for name in ('ui/settings.json',):
 p=root/name
 if p.is_file():rows[name]=hashlib.sha256(p.read_bytes()).hexdigest()
for p in root.rglob('*'):
 if p.is_file() and p.suffix in ('.onnx','.gguf','.safetensors'):
  rows[str(p.relative_to(root))]=hashlib.sha256(p.read_bytes()).hexdigest()
authored=[]
for db in (root/'localstorage').glob('*.localstorage'):
 with sqlite3.connect(db.as_uri()+'?mode=ro',uri=True) as conn:
  for key,value in conn.execute('SELECT key,value FROM ItemTable'):
   if key == 'justsearch.userState.v2':
    if isinstance(value,bytes):value=value.decode('utf-16-le' if 0 in value[:8] else 'utf-8')
    state=json.loads(value)
    for profile in state.get('profiles',{}).values():authored.extend(profile.get('savedViews',[]))
print(json.dumps({'files':rows,'savedViews':authored}))
""", run.data, timeout=180)
    return {'settings': settings, 'roots': roots, 'savedFiles': files,
            'search': search_api(run)}


def author_state(run):
    run.require('ui')
    run.require('folder')
    run.nav('Library')
    run.wd.click('button[aria-label="Bookmark this view"]')
    run.wd.click('button[aria-label="View bookmarks"]')
    text = bookmark_text(run)
    assert 'Library' in text, 'Authored bookmark is not visible'
    run.native.shot('J4-authored-bookmark')
    run.native.key('escape')
    run.nav('Settings')
    run.wd.click('button[data-settings-category="appearance"]')
    # One user setting, authored through the real control.
    run.wd.click('button[role="radio"]', 'Dark')
    wait_for(lambda: run.api('/api/settings/v2').get('ui', {}).get('theme') == 'dark',
             'UI-authored theme persisted', seconds=20)
    run.facts['authored'] = True
    run.facts['beforeState'] = snapshot(run)
    return run.facts['beforeState']


def assert_state(run):
    before = run.require('beforeState')
    after = snapshot(run)
    assert before['savedFiles'] == after['savedFiles'], 'Settings, authored records or model bytes changed'
    assert after['savedFiles']['savedViews'], 'No persisted authored bookmark was observed'
    assert before['settings'].get('ui') == after['settings'].get('ui'), 'Persisted UI settings changed'
    assert run.require('corpus') in json.dumps(after['roots']), 'Watched folder disappeared'
    assert OPEN_FILE in json.dumps(after['search']['results']), 'Indexed fixture disappeared'
    return after


def restart(run):
    run.require('beforeState')
    run.normal_launch()
    state = assert_state(run)
    run.native.shot('J4-normal-restart')
    # Reattach in a fresh automation process only after the normal-launch check.
    run.start_ui()
    run.wd.click('button[aria-label="View bookmarks"]')
    assert 'Library' in bookmark_text(run), 'Saved bookmark is retained on disk but missing from the restarted UI'
    run.native.shot('J4-restarted-bookmark')
    run.native.key('escape')
    return state


def bookmark_text(run):
    return wait_for(lambda: run.wd.execute("""
const popup=all(document,'jf-bookmarks-popover').find(e=>e.hasAttribute('open'));
if (!popup || !popup.shadowRoot) return null;
const content=popup.shadowRoot.querySelector('[role="listbox"],.empty');
if (!content) return null;
const r=content.getBoundingClientRect();
return r.width>0 && r.height>0 ? content.textContent : null;
"""), 'Saved-views popover visibly opened', seconds=30, error=Infrastructure)


def update_prepare(run):
    if not run.args.predecessor or not run.args.mirror:
        raise Unavailable('Update requires --predecessor and --mirror with the same test pins')
    run.require('beforeState')
    # J1-J4 must qualify the candidate on this desktop too. Prepare the parent
    # independently for J5, preserving the candidate's already-checked state in
    # our private run folder; never open newer saved state with an older app.
    run.stop_app()
    run.target.run(['mv', run.data, run.work + '/candidate-state-before-J5'])
    apt(run, 'remove', 'just-search')
    predecessor = run.work + '/' + run.args.predecessor.name
    run.target.copy(run.args.predecessor, predecessor)
    apt(run, 'install', predecessor)
    expected = run.target.run(['dpkg-deb', '-f', predecessor, 'Version']).stdout.strip()
    assert installed_version(run) == expected, 'Predecessor package was not installed for J5'
    verify = run.target.run(['dpkg', '--verify', 'just-search'], check=False)
    assert verify.returncode == 0 and not verify.stdout.strip(), 'Predecessor installed payload differs from its package'
    run.facts['predecessorPackage'] = {'name': run.args.predecessor.name,
                                     'sha256': digest(run.args.predecessor), 'version': expected}
    for key in ('folder', 'search', 'ai', 'authored', 'beforeState', 'ui', 'ready'):
        run.facts.pop(key, None)
    outer = run.out
    prep = outer / 'J5-predecessor'
    prep.mkdir()
    run.out = run.native.evidence = prep
    try:
        for journey in run.definition['journeys']:
            if journey['id'] not in ('J1', 'J2', 'J3', 'J4'):
                continue
            for check in journey['checks']:
                if check['id'] == 'install' or run.args.lane not in check['lanes']:
                    continue
                value = CHECKS[check['id']](run)
                run.evidence(journey['id'] + '-' + check['id'], value)
    finally:
        run.out = run.native.evidence = outer
    run.evidence('J5-predecessor-package', run.facts['predecessorPackage'])
    run.target.vm.mirror('ubuntu', str(run.args.mirror), False, False)
    run.start_ui()
    run.nav('Settings')
    run.wd.click('button[data-settings-category="desktop"]')
    run.wd.click('button[aria-label="Check for updates"]')
    run.wd.element('button[aria-label="Install update"]', seconds=60)
    run.native.shot('J5-update-offered')
    run.facts['updateOffered'] = True


def cancel_update(run):
    run.require('updateOffered')
    old_version = installed_version(run)
    run.wd.click('button[aria-label="Install update"]')
    prompt = wait_for(lambda: (v if 'authenticat' in v.lower() or 'password' in v.lower() else None)
                      if (v := run.native.screen_text('J5-polkit-cancel-prompt')) else None,
                      'PolicyKit prompt', seconds=180, error=Infrastructure)
    run.native.key('escape')
    run.ready()
    assert installed_version(run) == old_version, 'Cancelled update changed the installed version'
    run.wd.element('button[aria-label="Install update"]', seconds=45)
    state = assert_state(run)
    run.facts['updateCancelled'] = True
    return {'prompt': prompt, 'resumedVersion': old_version, 'state': state}


def update_success(run):
    run.require('updateCancelled')
    run.wd.click('button[aria-label="Install update"]')
    wait_for(lambda: (v if 'authenticat' in v.lower() or 'password' in v.lower() else None)
             if (v := run.native.screen_text('J5-polkit-accept-prompt')) else None,
             'PolicyKit prompt', seconds=180, error=Infrastructure)
    run.native.type(run.target.vm.config()['password'])
    run.native.key('enter')
    expected = run.args.package.removeprefix('JustSearch_').split('_')[0]
    wait_for(lambda: installed_version(run) == expected, 'Candidate installed by updater', seconds=420)
    # No WebDriver relaunch: the production updater must perform this itself.
    run.wd = None
    run.facts.pop('ui', None)
    run.ready()
    state = assert_state(run)
    run.native.shot('J5-normal-updater-relaunch')
    verify = run.target.run(['dpkg', '--verify', 'just-search'], check=False)
    assert verify.returncode == 0 and not verify.stdout.strip(), 'Updated installed payload checksum mismatch'
    run.facts['updated'] = True
    return {'installedVersion': installed_version(run), 'state': state}


def update_lock(run):
    run.require('updateCancelled')
    # Use the real dpkg lock protocol, not flock (apt uses POSIX record locks).
    source = """
import fcntl,json,os,time
from pathlib import Path
root=Path(__file__).parent
with open('/var/lib/dpkg/lock-frontend','a') as lock:
 fcntl.lockf(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 (root/'lock-ready.json').write_text(json.dumps({'pid':os.getpid(),'ticks':Path('/proc/self/stat').read_text().split(') ',1)[1].split()[19]}))
 end=time.monotonic()+420
 while time.monotonic()<end and not (root/'release-lock').exists():time.sleep(.25)
"""
    script = run.work + '/hold-dpkg.py'
    run.target.py('import json,sys;from pathlib import Path;a=json.loads(sys.argv[1]);Path(a[0]).write_text(a[1])', [script, source])
    starter = "import subprocess,json,sys;a=json.loads(sys.argv[1]);subprocess.Popen(['python3',a[0]],stdin=subprocess.DEVNULL,stdout=open(a[1],'w'),stderr=subprocess.STDOUT,start_new_session=True)"
    run.target.run(['sudo', '-S', '-p', '', 'env', 'QUAL_JOURNEY_RUN=' + run.run_id,
                    'python3', '-c', starter, json.dumps([script, run.work + '/dpkg-lock.log'])],
                   input=run.target.vm.config()['password'] + '\n')
    try:
        identity = wait_for(lambda: run.target.json('import json,sys;from pathlib import Path;print((Path(json.loads(sys.argv[1]))/"lock-ready.json").read_text())', run.work),
                            'Isolated dpkg lock acquired', seconds=15, error=Infrastructure)
        run.evidence('J5-held-dpkg-lock', identity)
        old = installed_version(run)
        run.wd.click('button[aria-label="Install update"]')
        wait_for(lambda: (v if 'authenticat' in v.lower() or 'password' in v.lower() else None)
                 if (v := run.native.screen_text('J5-lock-polkit')) else None,
                 'PolicyKit lock-test prompt', seconds=180, error=Infrastructure)
        run.native.type(run.target.vm.config()['password'])
        run.native.key('enter')
        # The packaged updater specifies DPkg::Lock::Timeout=300. Do not shorten
        # the product timeout or replace its apt process in qualification.
        time_started = time.monotonic()
        run.wd.element('button[aria-label="Install update"]', seconds=390)
        assert time.monotonic() - time_started >= 290, 'Update re-offered before the real lock timeout'
        run.ready()
        assert installed_version(run) == old, 'Lock-timeout update changed the installed version'
        return {'version': old, 'state': assert_state(run), 'heldLock': identity}
    finally:
        run.target.py('import json,sys;from pathlib import Path;(Path(json.loads(sys.argv[1]))/"release-lock").touch()', run.work)
        wait_for(lambda: run.target.py('import json,sys;from pathlib import Path;p=Path(json.loads(sys.argv[1]));m=json.loads((p/"lock-ready.json").read_text());print(not Path("/proc/"+str(m["pid"])).exists())', run.work).strip() == 'True',
                 'Own lock holder exited', seconds=15, error=Infrastructure)


def reinstall(run):
    run.require('beforeState')
    run.stop_app()
    candidate = run.work + '/' + run.args.package
    run.target.copy(run.args.assets / run.args.package, candidate)
    apt(run, 'install', '--reinstall', candidate)
    run.normal_launch()
    return assert_state(run)


def remove_reinstall(run):
    run.require('beforeState')
    run.stop_app()
    apt(run, 'remove', 'just-search')
    retained = run.target.py('import json,sys;from pathlib import Path;print((Path(json.loads(sys.argv[1]))/"ui/settings.json").is_file())', run.data).strip()
    assert retained == 'True', 'apt remove deleted user settings'
    apt(run, 'install', run.work + '/' + run.args.package)
    run.normal_launch()
    return assert_state(run)


def desktop_entry(run):
    require_candidate(run)
    run.require('ready')
    entry = run.target.run(['cat', '/usr/share/applications/JustSearch.desktop']).stdout
    assert 'MimeType=x-scheme-handler/justsearch;' in entry, 'Desktop entry lacks the deep-link handler'
    handler = run.target.run(['xdg-mime', 'query', 'default', 'x-scheme-handler/justsearch']).stdout.strip()
    assert handler == 'JustSearch.desktop', 'OS has not registered the installed deep-link handler'
    run.stop_app()
    run.desktop_spawn(['gtk-launch', 'JustSearch'], run.work + '/menu-launch.log')
    run.ready()
    run.native.shot('J7-menu-entry-launch')
    return {'entry': entry, 'osHandler': handler}


def deep_links(run):
    require_candidate(run)
    run.require('folder')
    run.stop_app()
    run.desktop_spawn(['xdg-open', 'justsearch://query?q=orchid%20lighthouse'], run.work + '/cold-deep-link.log')
    run.ready()
    cold = wait_for(lambda: (s if 'orchid' in s.lower() else None)
                    if (s := run.native.screen_text('J7-deep-link-cold')) else None,
                    'Cold OS deep link search destination', seconds=45)
    run.desktop_spawn(['xdg-open', 'justsearch://surface/core.library-surface'], run.work + '/warm-deep-link.log')
    warm = wait_for(lambda: (s if 'Add Folder' in s else None)
                    if (s := run.native.screen_text('J7-deep-link-warm')) else None,
                    'Warm OS deep link Library destination', seconds=45)
    return {'cold': cold, 'warm': warm}


def shortcut(run):
    require_candidate(run)
    run.start_ui()
    run.nav('Settings')
    run.wd.click('button[data-settings-category="desktop"]')
    # Settings explain the native registration result on the actual session.
    section = wait_for(lambda: (text if text and 'Loading shortcut status' not in text else None)
                       if (text := run.wd.execute("return all(document,'[data-testid=\"settings-search-shortcut\"]').map(e=>e.textContent).join(' ')")) else None,
                       'Shortcut registration status observed', seconds=30, error=Infrastructure)
    run.evidence('J7-shortcut-status', {'session': run.args.lane, 'text': section})
    wayland = run.args.lane.endswith('wayland')
    if wayland:
        assert '--focus-search' in section, 'Wayland did not explain the desktop shortcut alternative'
        command = run.binary + ' --focus-search'
        # GNOME's supported desktop settings store; disposable guest only.
        path = '/org/gnome/settings-daemon/plugins/media-keys/custom-keybindings/justsearch-qual/'
        run.target.run(['gsettings', 'set', 'org.gnome.settings-daemon.plugins.media-keys', 'custom-keybindings', "['" + path + "']"])
        schema = 'org.gnome.settings-daemon.plugins.media-keys.custom-keybinding:' + path
        for key, value in [('name', 'JustSearch qualification'), ('command', command), ('binding', '<Super><Shift>space')]:
            run.target.run(['gsettings', 'set', schema, key, value])
    else:
        assert re.search(r'\bActive\b', section), 'X11 global shortcut is not active: ' + section
    run.normal_launch(['--focus-search'])
    wait_for(lambda: 'Search v3' in run.native.screen_text('J7-before-shortcut'),
             'Normal search UI mounted before shortcut', seconds=60, error=Infrastructure)
    # Focus another real application first. Waiting for the foreground change
    # avoids typing into a window while GNOME is still dispatching the binding.
    run.desktop_spawn(['mousepad', run.require('corpus') + '/' + OPEN_FILE], run.work + '/shortcut-viewer.log')
    wait_for(lambda: 'orchid' in run.native.screen_text('J7-shortcut-viewer-focused').lower(),
             'Receiving viewer focused before shortcut', seconds=30, error=Infrastructure)
    run.native.key('win+shift+space' if wayland else 'alt+shift+space')
    wait_for(lambda: 'Search v3' in run.native.screen_text('J7-shortcut-foreground'),
             'Physical shortcut foregrounded search', seconds=30, error=Infrastructure)
    run.native.key('ctrl+a')
    run.native.type(QUERY)
    observed = wait_for(lambda: (s if 'orchid' in s.lower() else None)
                        if (s := run.native.screen_text('J7-physical-shortcut-search')) else None,
                        'Physical shortcut focuses search', seconds=30, error=Infrastructure)
    return {'settings': section, 'physicalInput': observed}


def ocr(run):
    require_candidate(run)
    run.require('folder')
    run.ready()
    return search_api(run, OCR_QUERY, 'scanned-delivery.pdf')


def mcp(run):
    run.require('ready')
    run.require('folder')
    version = installed_version(run)
    manifest = run.target.json('import json,sys;from pathlib import Path;print((Path(json.loads(sys.argv[1]))/"manifest.json").read_text())', run.work + '/mcpb')
    # The initial app may still be the predecessor when J5 fails. Do not certify
    # the candidate's bridge against that version as though the update succeeded.
    if manifest['version'] != version:
        raise Unavailable('Candidate is not installed; cannot qualify its bridge against the predecessor')
    assert {'linux', 'win32'} <= set(manifest['compatibility']['platforms']), 'MCPB platforms incomplete'
    node = run.target.run(['node', '--version']).stdout.strip()
    if int(node.removeprefix('v').split('.')[0]) < 22:
        raise Unavailable('Pinned MCP Inspector requires Node >=22; provision the qualification client')
    observations = {}
    for name, args in [('list', ['--method', 'tools/list']),
                       ('status', ['--method', 'tools/call', '--tool-name', 'justsearch_status']),
                       ('search', ['--method', 'tools/call', '--tool-name', 'justsearch_search', '--tool-arg', 'query=' + QUERY])]:
        result = run.target.run(['npx', '--yes', '@modelcontextprotocol/inspector@0.21.1', '--cli',
                                 'node', run.work + '/mcpb/server/index.js', *args], timeout=180, check=False)
        (run.out / ('J8-inspector-' + name + '.log')).write_text(result.stdout + result.stderr)
        if result.returncode:
            if any(v in result.stderr for v in ('npm ERR', 'npm error', 'ENOTFOUND', 'ECONNRESET')):
                raise Infrastructure('Pinned Inspector download failed; see inspector log')
            raise AssertionError(f'Release MCP bridge {name} failed: {result.stdout[-2000:]} {result.stderr[-2000:]}')
        try:
            parsed = json.loads(result.stdout)
        except ValueError as exc:
            raise Infrastructure('Inspector returned non-JSON output') from exc
        observations[name] = parsed
        assert not parsed.get('isError'), 'MCP tool returned isError: ' + json.dumps(parsed)
    assert 'justsearch_search' in json.dumps(observations['list']), 'tools/list omits search'
    assert OPEN_FILE in json.dumps(observations['search']), 'MCP search did not return fixture'
    return {'manifest': manifest, 'inspector': '0.21.1', 'node': node, 'observations': observations}


def mcp_metadata(run):
    with zipfile.ZipFile(run.args.assets / run.args.mcpb) as bundle:
        manifest = json.loads(bundle.read('manifest.json'))
    if not run.args.registry:
        raise Unavailable('Provide --registry server.json from the draft tag to check S21 registry metadata')
    registry = json.loads(run.args.registry.read_text())
    version = run.target.run(['dpkg-deb', '-f', run.work + '/' + run.args.package, 'Version'], check=False).stdout.strip()
    if not version:
        # The initial package is the predecessor in update lanes.
        version = subprocess.check_output(['dpkg-deb', '-f', str(run.args.assets / run.args.package), 'Version'], text=True).strip()
    assert manifest['version'] == registry['version'] == version, 'App, MCPB and registry versions differ'
    assert {'linux', 'win32'} <= set(manifest['compatibility']['platforms']), 'MCPB platforms incomplete'
    package = registry['packages'][0]
    assert package['identifier'] == f'https://github.com/justsearch-app/justsearch/releases/download/v{version}/justsearch-mcp.mcpb', 'Registry release URL differs'
    assert package['fileSha256'] == digest(run.args.assets / run.args.mcpb), 'Registry bundle checksum differs'
    return {'manifest': manifest, 'registry': registry}


CHECKS = {
    'install': package_install, 'first-run': first_run, 'folder-picker': add_folder,
    'search-open': search_open, 'cpu-ai': install_ai, 'author-state': author_state,
    'restart': restart, 'update-offer': update_prepare, 'update-cancel': cancel_update,
    'update-success': update_success, 'update-lock': update_lock, 'reinstall': reinstall, 'remove-retain-reinstall': remove_reinstall,
    'desktop-entry': desktop_entry, 'deep-links': deep_links, 'shortcut': shortcut,
    'ocr': ocr, 'mcp': mcp, 'mcp-metadata': mcp_metadata,
}
