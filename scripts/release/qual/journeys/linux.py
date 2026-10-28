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
    if result.returncode:
        output = (result.stdout + result.stderr).lower()
        message = f'apt-get {args[0]} failed with exit {result.returncode}; see apt log'
        # Exit 100 alone says nothing about the package. Attribute known network,
        # repository and host resource failures by their observed cause.
        if any(cause in output for cause in ('failed to fetch', 'could not resolve', 'temporary failure resolving',
                'could not connect', 'connection refused', 'connection timed out', 'network is unreachable',
                'unable to acquire the dpkg frontend lock', 'could not get lock', 'no space left on device')):
            raise Infrastructure(message)
        if any(cause in output for cause in ('dependency problems', 'pre-installation script',
                                             'post-installation script', 'trying to overwrite')):
            raise AssertionError(message)
        raise Infrastructure(message + '; package cause not established')


def installed_version(run):
    return run.target.run(['dpkg-query', '-W', '-f=${Version}', 'just-search']).stdout.strip()


def require_candidate(run):
    if installed_version(run) != run.require('initialVersion'):
        raise Unavailable('Candidate is not installed; predecessor observations cannot qualify its desktop integration')


def package_install(run):
    candidate = run.args.assets / run.args.package
    run.target.copy(candidate, run.work + '/' + candidate.name)
    run.facts['initialVersion'] = run.target.run(['dpkg-deb', '-f', run.work + '/' + candidate.name, 'Version']).stdout.strip()
    initial = run.args.predecessor if run.args.phase == 'predecessor' else candidate
    if not initial.is_file():
        raise Infrastructure('Initial package missing: ' + str(initial))
    target = run.work + '/' + initial.name
    if initial != candidate:
        run.target.copy(initial, target)
    run.facts['initialPackage'] = {'name': initial.name, 'sha256': digest(initial)}
    apt(run, 'install', target)
    paths = run.target.run(['dpkg-query', '-L', 'just-search']).stdout.splitlines()
    binaries = [p for p in paths if p.endswith('/JustSearch') and '/bin/' in p]
    assert len(binaries) == 1, 'Package has no unique installed executable'
    run.binary = binaries[0]
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


def add_folder_api(run):
    run.require('ui')
    corpus = run.require('corpus')
    run.api('/api/indexing/roots', {'path': corpus, 'collection': 'default'})
    roots = wait_for(lambda: (v if corpus in json.dumps(v) else None)
                     if (v := run.api('/api/indexing/roots')) else None,
                     'Production API added watched folder', seconds=45)
    run.facts['folder'] = roots
    return roots


def viewer_fixture(rows, fixture):
    return next((r for r in rows if Path(r['exe']).name == 'mousepad'
                 and fixture in r['argv'] + r['fds']), None)


def receiving_viewer(run):
    rows = run.target.json("""
import json,os,sys
from pathlib import Path
rows=[]; marker=('QUAL_JOURNEY_RUN='+json.loads(sys.argv[1])).encode()
for p in Path('/proc').glob('[0-9]*'):
 try:
  if p.stat().st_uid != os.getuid() or marker not in (p/'environ').read_bytes().split(b'\\0'):continue
  exe=str((p/'exe').resolve())
  if Path(exe).name!='mousepad':continue
  fds=[]
  for fd in (p/'fd').iterdir():
   try:fds.append(os.readlink(fd))
   except OSError:pass
  rows.append({'pid':int(p.name),'ticks':(p/'stat').read_text().split(') ',1)[1].split()[19],
               'exe':exe,'argv':(p/'cmdline').read_bytes().decode().strip('\\0').split('\\0'),'fds':fds})
 except (OSError,IndexError):pass
print(json.dumps(rows))
""", run.run_id)
    run.evidence('J2-receiving-viewer-processes', rows)
    return viewer_fixture(rows, run.require('corpus') + '/' + OPEN_FILE)


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
    viewer = wait_for(lambda: receiving_viewer(run),
                      'Receiving viewer opened exact fixture (cmdline or fd)', seconds=30)
    run.native.shot('J2-receiving-viewer')
    run.target.stop([viewer])
    run.desktop_spawn([run.binary], run.work + '/return-from-viewer.log')
    run.facts['search'] = True
    return {'search': result, 'receivingViewerProcess': viewer}


def install_ai(run):
    run.require('ui')
    run.require('folder')
    run.nav('AI Brain')
    preview = run.api('/api/ai/install/plan-preview')
    run.evidence('J3-default-plan', preview)
    manifest = run.api('/api/ai/install/manifest')
    run.evidence('J3-production-manifest', manifest)
    resumed = run.facts.get('resumedSetup') is True
    if not resumed:
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
        if resumed:
            runtime = run.api('/api/ai/runtime/status')
            run.evidence('J3-resume-runtime', runtime)
            features = {f['id']: f for f in runtime.get('onnxFeatures', [])}
            # installedFully can briefly be true before reboot initialization
            # publishes activation_required. Only activation/runtime evidence
            # settles a cached setup; an ambiguous idle state must keep waiting.
            return status if status.get('phase') == 'activation_required' or all(
                features.get(name, {}).get('modelActive') is True for name in ('embed', 'splade')) else None
        return status if (status.get('state') in ('completed', 'installed', 'succeeded')
                          or status.get('installedFully') is True) else None
    status = wait_for(settled, 'Default CPU AI install', seconds=900 if resumed else 1800, interval=5,
                      error=Infrastructure if resumed else AssertionError)
    assert preview.get('downloadProfile') == 'CPU', 'Default plan did not select CPU'
    run.evidence('J3-downloaded', status)
    memory_samples = []
    def sample_memory():
        sample = run.target.json("""
import json,time
from pathlib import Path
m={k:int(v.split()[0]) for k,v in (line.split(':',1) for line in Path('/proc/meminfo').read_text().splitlines()) if k in ('MemTotal','MemAvailable','SwapTotal','SwapFree')}
print(json.dumps(dict(m,epochSeconds=time.time(),usedKiB=m['MemTotal']-m['MemAvailable'])))
""", desktop=False)
        memory_samples.append(sample)
        run.evidence('J3-activation-memory', memory_samples)
    sample_memory()
    if not resumed or status.get('phase') == 'activation_required':
        activate_downloaded(run)
    else:
        run.evidence('J3-activation-consent', {'restoredActivatedSetup': True,
            'reason': 'Development rerun; original UI action belongs to the snapshot source report'})
    activation_samples = []
    def activated():
        sample_memory()
        install = run.api('/api/ai/install/status')
        runtime = run.api('/api/ai/runtime/status')
        ui = run.wd.text()
        outcomes = run.wd.execute("return all(document,'jf-operation-outcome-detail').map(e=>e.operationKey).filter(Boolean)")
        history = [run.api('/api/operation-history/' + key) for key in outcomes]
        activation_samples.append({'install': install, 'runtime': runtime, 'ui': ui, 'operations': history})
        run.evidence('J3-activation-observations', activation_samples)
        for operation in history:
            if operation.get('state') in ('failed', 'cancelled'):
                raise AssertionError('Model activation failed: ' + json.dumps(operation))
        features = {f['id']: f for f in runtime.get('onnxFeatures', [])}
        return {'install': install, 'runtime': runtime} if install.get('installedFully') is True and all(
            features.get(name, {}).get('modelActive') is True for name in ('embed', 'splade')) else None
    active = wait_for(activated, 'Downloaded default CPU search encoders activated', seconds=900, interval=5)
    status = active['install']
    # Acquisition stages belong to this download session, not rebooted caches.
    if not resumed:
        assert 'retrieval-core' in status.get('readyCapabilities', []), 'Installed CPU search capability is not ready'
    runtime = run.api('/api/ai/runtime/status')
    run.evidence('J3-runtime', runtime)
    components = preview.get('components', [])
    chat = next((c for c in components if c.get('id') == 'chat'), None)
    assert chat and chat.get('state') == 'unavailable', 'CPU default plan does not declare chat unavailable'
    assert any(c.get('id') != 'chat' and c.get('state') != 'unavailable' for c in components), 'CPU plan has no available search components'
    run.nav('Search')
    run.require('folder')
    retrieval = semantic_search(run)
    run.nav('AI Brain')
    text = run.wd.text()
    assert (chat.get('unavailableReason') or 'Chat is not supported here.') in text, 'CPU chat unavailable explanation is not shown'
    run.native.shot('J3-cpu-search-without-chat')
    # RerankerConfig documents lazy initialization on a retrieval query. Check
    # all reported default models after exercising retrieval, not before it.
    def models_active():
        observed = run.api('/api/ai/runtime/status')
        run.evidence('J3-runtime', observed)
        features = {f['id']: f for f in observed.get('onnxFeatures', [])}
        return observed if all(features.get(name, {}).get('modelActive') is True
                               for name in ('embed', 'splade', 'reranker', 'citation_scorer')) else None
    try:
        runtime = wait_for(models_active, 'All default retrieval models active after vector query', seconds=120, interval=3)
    except AssertionError as exc:
        observed = run.api('/api/ai/runtime/status')
        features = {f['id']: f for f in observed.get('onnxFeatures', [])}
        flags = {name + '.modelActive': features.get(name, {}).get('modelActive')
                 for name in ('embed', 'splade', 'reranker', 'citation_scorer')}
        diagnostic = {'flags': flags, 'retrievalTrace': retrieval.get('searchTrace', {}), 'runtime': observed}
        run.evidence('J3-model-activity-failure', diagnostic)
        raise AssertionError(str(exc) + '; ' + ', '.join(
            name + '=' + json.dumps(value) for name, value in flags.items())
            + '; retrieval trace=' + json.dumps(diagnostic['retrievalTrace'])) from exc
    run.facts['ai'] = status
    return {'installed': status, 'runtime': runtime, 'cpuPlan': preview, 'activeRetrieval': retrieval, 'uiText': text}


def activate_downloaded(run):
    run.wd.click('button[aria-label="Activate downloaded models"]')
    # The installed version/policy may accept this UI action immediately or
    # request typed authorization. Observe what it actually presents.
    consent = wait_for(lambda: run.wd.execute("""
const typed=all(document,'input[data-testid="authorization-typed-input"]').some(e=>{
 const r=e.getBoundingClientRect();return r.width>0 && r.height>0;
});
const operationKeys=all(document,'jf-operation-outcome-detail').map(e=>e.operationKey).filter(Boolean);
return typed || operationKeys.length ? {typed,operationKeys} : null;
"""), 'Activation authorization or accepted operation visible', seconds=30, error=Infrastructure)
    run.evidence('J3-activation-consent', consent)
    if consent['typed']:
        run.wd.keys('input[data-testid="authorization-typed-input"]', 'core.activate-installed-models')
        run.wd.click('button[aria-label="Approve"]')


def semantic_search(run):
    def probe():
        result = run.api('/api/knowledge/search', {'query': 'flower coastal navigation tower', 'mode': 'vector', 'limit': 20})
        run.evidence('J3-vector-query', result)
        trace = result.get('searchTrace', {})
        degradation = trace.get('degradation', {})
        stages = trace.get('stages', [])
        return result if OPEN_FILE in json.dumps(result.get('results', [])) and trace.get('effectiveMode') == 'VECTOR' and not degradation.get('hybridFallback') and not degradation.get('vectorBlocked') and any(
            s.get('id') == 'dense-retrieval' and s.get('status') == 'executed' for s in stages) and any(
            s.get('id') == 'cross-encoder' and s.get('status') == 'executed' for s in stages) and not any(
            s.get('id') == 'sparse-retrieval' and s.get('status') == 'executed' for s in stages) else None
    return wait_for(probe, 'Semantic fixture retrieval without keyword fallback', seconds=240, interval=3)


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
    # Carry only measured control geometry across the normal restart. No DOM
    # automation is attached to the persistence observation itself.
    run.facts['bookmarkGeometry'] = run.wd.execute("""
const button=all(document,'button[aria-label="View bookmarks"]')[0].getBoundingClientRect();
const popup=all(document,'jf-bookmarks-popover').find(e=>e.hasAttribute('open'));
const list=popup.shadowRoot.querySelector('[role="listbox"]').getBoundingClientRect();
return {button:{x:button.x+button.width/2,y:button.y+button.height/2},
 popup:{x:Math.floor(list.x),y:Math.floor(list.y),width:Math.ceil(list.width),height:Math.ceil(list.height),
 viewportWidth:innerWidth,viewportHeight:innerHeight}};
""")
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
    run.evidence('J4-retained-state', state)
    run.native.shot('J4-normal-restart')
    geometry = run.require('bookmarkGeometry')
    wait_for(run.native.app_window, 'Normal restarted app focused', seconds=30, error=Infrastructure)
    # Engine readiness precedes restored UI state. Do not send Escape before
    # the Settings modal we left open has actually mounted in the normal UI.
    wait_for(lambda: 'Settings' in run.native.app_text('J4-restored-settings'),
             'Restored Settings visible before dismissal', seconds=30, error=Infrastructure)
    run.native.key('escape')
    wait_for(lambda: 'Settings' not in run.native.app_text('J4-settings-closed'),
             'Restored Settings closed before bookmarks click', seconds=30, error=Infrastructure)
    before = run.native.app_text('J4-before-bookmarks', geometry['popup'])
    if 'Library' in before:
        raise Infrastructure('Bookmark observation region already contains Library before clicking')
    interaction_observation(run, 'J4-bookmarks-click')
    window = run.native.app_window()
    if not window:
        raise Infrastructure('Normal restarted app lost focus before bookmark click')
    run.native.click(window['rect']['x'] + geometry['button']['x'],
                     window['rect']['y'] + geometry['button']['y'])
    text = wait_for(lambda: (s if 'Library' in s else None)
                    if (s := run.native.app_text('J4-restarted-bookmark', geometry['popup'])) else None,
                    'Authored Library bookmark visible in normal restarted popup', seconds=30)
    run.evidence('J4-native-bookmark', {'before': before, 'after': text, 'geometry': geometry,
                                       'automationAttached': False})
    run.native.shot('J4-restarted-bookmark')
    run.native.key('escape')
    return state


def interaction_observation(run, name):
    status = run.api('/api/status')
    indexing = status.get('operating', {}).get('indexing', {})
    state = indexing.get('state')
    worker = status.get('worker', {})
    observed = {'secondsSinceWindowShown': round(time.monotonic() - run.require('windowShownAt'), 3),
                'windowShownEvidence': run.require('windowShownEvidence'),
                'indexingRunning': state in ('RUNNING', 'SLOWED', 'PAUSING') if state else None,
                'indexing': indexing,
                'worker': {k: worker.get(k) for k in ('core', 'migration', 'enrichment', 'visualExtraction')},
                'statusObservedAt': status.get('observed_at')}
    run.evidence(name, observed)
    return observed


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
    if run.args.phase != 'predecessor':
        raise Infrastructure('J5 requires its own fresh predecessor overlay')
    predecessor = run.work + '/' + run.args.predecessor.name
    expected = run.target.run(['dpkg-deb', '-f', predecessor, 'Version']).stdout.strip()
    assert installed_version(run) == expected, 'Predecessor package was not installed for J5'
    verify = run.target.run(['dpkg', '--verify', 'just-search'], check=False)
    assert verify.returncode == 0 and not verify.stdout.strip(), 'Predecessor installed payload differs from its package'
    run.facts['predecessorPackage'] = {'name': run.args.predecessor.name,
                                     'sha256': digest(run.args.predecessor), 'version': expected}
    run.evidence('J5-predecessor-package', run.facts['predecessorPackage'])
    run.target.vm.mirror('ubuntu', str(run.args.mirror), False, False)
    run.start_ui()
    open_update_offer(run, 'J5-update-entry')
    run.native.shot('J5-update-offered')
    run.facts['updateOffered'] = True


def open_update_offer(run, evidence):
    # Begin where the user sees the offer. The banner's own action navigates
    # to Settings; it does not directly install the update. Engine recovery
    # recreates Settings at Appearance, so do not reuse its former category.
    started = time.monotonic()
    close = run.wd.execute("return all(document,'button[aria-label=\"Close Settings\"]').some(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0})")
    if close:
        run.wd.click('button[aria-label="Close Settings"]')
    banner = wait_for(lambda: run.wd.execute("return all(document,'jf-app-update-banner').filter(e=>!e.hidden).map(e=>e.shadowRoot.textContent).join(' ').trim()"),
                      'Update banner visible', seconds=45, error=Infrastructure)
    expected_candidate = run.require('initialVersion')
    assert expected_candidate in banner, 'Update banner does not name the candidate version'
    run.native.shot(evidence + '-banner')
    action = run.wd.execute("return all(document,'button[aria-label=\"Open app update settings\"]').some(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0})")
    if action:
        run.wd.click('button[aria-label="Open app update settings"]')
    else:
        run.nav('Settings')
    run.wd.click('button[data-settings-category="desktop"]')
    run.wd.element('button[aria-label="Install update"]:not(:disabled)', seconds=45)
    run.evidence(evidence, {'banner': banner, 'action': 'banner: Review and install' if action else 'Settings fallback: banner has no visible action',
                          'secondsToControlsReady': round(time.monotonic() - started, 3),
                          'readinessSignal': 'Banner names candidate; enabled Install update visible in Desktop settings'})


def cancel_update(run):
    run.require('updateOffered')
    old_version = installed_version(run)
    run.wd.click('button[aria-label="Install update"]')
    prompt = wait_for(lambda: (v if 'authenticat' in v.lower() or 'password' in v.lower() else None)
                      if (v := run.native.screen_text('J5-polkit-cancel-prompt', psm=6)) else None,
                      'PolicyKit prompt', seconds=180, error=Infrastructure)
    run.native.key('escape')
    run.ready()
    assert installed_version(run) == old_version, 'Cancelled update changed the installed version'
    open_update_offer(run, 'J5-cancel-reoffer-entry')
    state = assert_state(run)
    run.facts['updateCancelled'] = True
    return {'prompt': prompt, 'resumedVersion': old_version, 'state': state}


def update_success(run):
    run.require('updateCancelled')
    open_update_offer(run, 'J5-success-entry')
    old_shells = [p for p in run.target.owned() if p['exe'] == run.binary]
    run.wd.click('button[aria-label="Install update"]')
    wait_for(lambda: (v if 'authenticat' in v.lower() or 'password' in v.lower() else None)
             if (v := run.native.screen_text('J5-polkit-accept-prompt', psm=6)) else None,
             'PolicyKit prompt', seconds=180, error=Infrastructure)
    run.native.type(run.target.vm.config()['password'])
    run.native.key('enter')
    expected = run.args.package.removeprefix('JustSearch_').split('_')[0]
    wait_for(lambda: installed_version(run) == expected, 'Candidate installed by updater', seconds=420)
    # No WebDriver relaunch: the production updater must perform this itself.
    run.wd = None
    run.facts.pop('ui', None)
    run.ready()
    run.observe_window('updater-relaunch')
    shells = wait_for(lambda: [p for p in run.target.owned() if p['exe'] == run.binary
                               and not any(p['pid'] == old['pid'] and p['ticks'] == old['ticks'] for old in old_shells)],
                      'Updater relaunched a new installed shell', seconds=60)
    # A retained old process can execute an unlinked predecessor inode even
    # after dpkg reports the candidate. Observe the actual running executable.
    for shell in shells:
        same = run.target.py('import json,os,sys;from pathlib import Path;a=json.loads(sys.argv[1]);print(os.path.samestat(Path("/proc/"+str(a[0])+"/exe").stat(),Path(a[1]).stat()))',
                             [shell['pid'], run.binary]).strip()
        assert same == 'True', 'Relaunched shell is not executing the installed candidate binary'
    state = assert_state(run)
    run.native.shot('J5-normal-updater-relaunch')
    verify = run.target.run(['dpkg', '--verify', 'just-search'], check=False)
    assert verify.returncode == 0 and not verify.stdout.strip(), 'Updated installed payload checksum mismatch'
    run.facts['updated'] = True
    return {'installedVersion': installed_version(run), 'state': state, 'relaunchedShells': shells}


def update_lock(run):
    run.require('updateCancelled')
    open_update_offer(run, 'J5-lock-entry')
    # Use the real dpkg lock protocol, not flock (apt uses POSIX record locks).
    source = """
import fcntl,json,os,time
from pathlib import Path
root=Path(__file__).parent
with open('/var/lib/dpkg/lock-frontend','a') as lock:
 fcntl.lockf(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 (root/'lock-ready.json').write_text(json.dumps({'pid':os.getpid(),'ticks':Path('/proc/self/stat').read_text().split(') ',1)[1].split()[19]}))
 end=time.monotonic()+900
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
                 if (v := run.native.screen_text('J5-lock-polkit', psm=6)) else None,
                 'PolicyKit lock-test prompt', seconds=180, error=Infrastructure)
        run.native.type(run.target.vm.config()['password'])
        run.native.key('enter')
        # The packaged updater specifies DPkg::Lock::Timeout=300. Do not shorten
        # the product timeout or replace its apt process in qualification.
        time_started = time.monotonic()
        # Recovery may recreate Settings at Appearance. Observe the banner's
        # own re-offer first, then follow it back to the install control.
        wait_for(lambda: run.wd.execute("return all(document,'jf-app-update-banner').some(e=>!e.hidden && e.shadowRoot.textContent.includes(arguments[0]))", [run.require('initialVersion')]),
                 'Update re-offered after dpkg lock timeout', seconds=390)
        assert time.monotonic() - time_started >= 290, 'Update re-offered before the real lock timeout'
        run.ready()
        assert installed_version(run) == old, 'Lock-timeout update changed the installed version'
        open_update_offer(run, 'J5-lock-reoffer-entry')
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
    run.observe_window('cold-deep-link')
    run.ready()
    cold = wait_for(lambda: (s if 'orchid' in s.lower() and 'Search v3' in s else None)
                    if (s := run.native.app_text('J7-deep-link-cold')) else None,
                    'Cold OS deep link search destination', seconds=45)
    interaction_observation(run, 'J7-warm-dispatch')
    run.desktop_spawn(['xdg-open', 'justsearch://surface/core.library-surface'], run.work + '/warm-deep-link.log')
    warm = wait_for(lambda: native_library_destination(run),
                    'Warm OS deep link Library destination', seconds=45)
    return {'cold': cold, 'warm': warm}


def native_library_destination(run):
    # The top bar names the current surface. Body OCR depends on layout and
    # can miss the Add Folder button despite correct navigation.
    window = run.native.app_window()
    if not window:
        raise Infrastructure('Deep-link receiving app is not focused')
    rect = window['rect']
    region = {'x': 160, 'y': 0, 'width': min(400, rect['width'] - 160), 'height': 40,
              'viewportWidth': rect['width'], 'viewportHeight': rect['height']}
    text = run.native.app_text('J7-deep-link-warm', region)
    return {'topBar': text, 'window': window} if re.search(r'\bLibrary\b', text) else None


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
        marker = run.work + '/shortcut-dispatched.json'
        wrapper = run.work + '/shortcut-dispatch.sh'
        script = ('#!/bin/sh\n'
                  + "printf '{\"pid\":%s}\\n' \"$$\" > " + shlex.quote(marker) + ' || exit 1\n'
                  + 'exec env ' + shlex.quote('QUAL_JOURNEY_RUN=' + run.run_id) + ' '
                  + shlex.quote(run.binary) + ' --focus-search\n')
        run.target.py('import json,sys;from pathlib import Path;v=json.loads(sys.argv[1]);'
                      'Path(v[0]).unlink(missing_ok=True);p=Path(v[1]);p.write_text(v[2]);p.chmod(0o700)',
                      [marker, wrapper, script])
        command = shlex.quote(wrapper)
        # GNOME's supported desktop settings store; disposable guest only.
        path = '/org/gnome/settings-daemon/plugins/media-keys/custom-keybindings/justsearch-qual/'
        run.target.run(['gsettings', 'set', 'org.gnome.settings-daemon.plugins.media-keys', 'custom-keybindings', "['" + path + "']"])
        schema = 'org.gnome.settings-daemon.plugins.media-keys.custom-keybinding:' + path
        # Super+Shift+Space is GNOME's default backwards input-source binding.
        # Use a chord unbound in the clean guest rather than shadowing that action.
        for key, value in [('name', 'JustSearch qualification'), ('command', command), ('binding', '<Control><Super>j')]:
            run.target.run(['gsettings', 'set', schema, key, value])
    else:
        assert re.search(r'\bActive\b', section), 'X11 global shortcut is not active: ' + section
    run.native.key('escape')
    run.nav('Search')
    # Focus another real application first. Waiting for the foreground change
    # avoids typing into a window while GNOME is still dispatching the binding.
    run.desktop_spawn(['mousepad', run.require('corpus') + '/' + OPEN_FILE], run.work + '/shortcut-viewer.log')
    wait_for(lambda: (w if w and 'Mousepad' in w['title'] else None)
             if (w := run.native.focused_window()) else None,
             'Receiving viewer focused before shortcut', seconds=30, error=Infrastructure)
    run.native.key('win+ctrl+j' if wayland else 'alt+shift+space')
    if wayland:
        def dispatched():
            invocation = run.target.json('import json,sys;from pathlib import Path;'
                'p=Path(json.loads(sys.argv[1]));print(p.read_text() if p.exists() else "null")', marker)
            run.evidence('J7-shortcut-dispatch', {'dispatched': invocation is not None,
                'invocation': invocation, 'command': command, 'binding': '<Control><Super>j',
                'compositorSession': {key: run.target.env.get(key) for key in
                    ('XDG_SESSION_TYPE', 'WAYLAND_DISPLAY', 'XDG_CURRENT_DESKTOP')},
                'focusedWindow': run.native.focused_window()})
            return invocation
        wait_for(dispatched, 'GNOME custom shortcut binding dispatch (wrapper invocation absent)',
                 seconds=30, error=Infrastructure)
    wait_for(lambda: shortcut_receiver(run),
             'Physical shortcut foregrounded search', seconds=30,
             error=AssertionError if wayland else Infrastructure)
    run.native.key('ctrl+a')
    query = QUERY + ' shortcut receiver'
    run.native.type(query)
    observed = wait_for(lambda: shortcut_receiver(run, query),
                        'Physical shortcut focuses search', seconds=30,
                        error=AssertionError if wayland else Infrastructure)
    run.native.shot('J7-physical-shortcut-search')
    return {'settings': section, 'physicalInput': observed}


def shortcut_receiver(run, query=None):
    window = run.native.app_window()
    if not window:
        run.evidence('J7-shortcut-receiver', {'appFocused': False,
                     'focusedWindow': run.native.focused_window(), 'expectedQuery': query})
        return None
    state = run.wd.execute("""
let active=document.activeElement;
while(active?.shadowRoot?.activeElement)active=active.shadowRoot.activeElement;
const input=all(document,'textarea[data-testid="sv3-composer-input"]').find(e=>e.getBoundingClientRect().height>0);
return {documentFocused:document.hasFocus(),searchFocused:!!input && active===input,value:input?.value};
""")
    run.evidence('J7-shortcut-receiver', {'window': window, 'dom': state, 'expectedQuery': query})
    return {'window': window, 'dom': state} if state['documentFocused'] and state['searchFocused'] and (query is None or state['value'] == query) else None


def ocr(run):
    require_candidate(run)
    run.require('folder')
    run.require('ready')
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
    'folder-api': add_folder_api,
    'search-open': search_open, 'cpu-ai': install_ai, 'author-state': author_state,
    'restart': restart, 'update-offer': update_prepare, 'update-cancel': cancel_update,
    'update-success': update_success, 'update-lock': update_lock, 'reinstall': reinstall, 'remove-retain-reinstall': remove_reinstall,
    'desktop-entry': desktop_entry, 'deep-links': deep_links, 'shortcut': shortcut,
    'ocr': ocr, 'mcp': mcp, 'mcp-metadata': mcp_metadata,
}
