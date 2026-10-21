import os, subprocess, pathlib, time, json
p = pathlib.Path(os.environ['TMPDIR'])
e = p / 'evidence'
cp = (e / 'test-classpath.txt').read_text()
java = pathlib.Path((e / 'test-java.txt').read_text())
javac = java.with_name('javac')
probe = p / 'replica-probe-classes'
probe.mkdir(exist_ok=True)
subprocess.run([str(javac), '-J-Djava.io.tmpdir=' + str(p), '-cp', cp, '-d', str(probe), str(p/'ReplicaTimeoutProbe.java')], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
rel = pathlib.Path('modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreReplicaRepairTest.java')
results = []
for name, source, delay, expected, message in [
    ('replica-before-slow', p/'replica-before'/rel, 7000, 1, 'timed out after 30 seconds'),
    ('replica-after-slow', pathlib.Path.cwd()/rel, 7000, 0, None),
    ('replica-after-hung-child', pathlib.Path.cwd()/rel, 60000, 1, 'password publication step 2 did not pause within 30 s'),
    ('replica-after-whole-timeout', pathlib.Path.cwd()/rel, 23000, 1, 'timed out after 90 seconds'),
]:
    work = p/'replica-probes-clean'/name
    work.mkdir(parents=True, exist_ok=True)
    text = source.read_text()
    if name.startswith('replica-before'):
        needle = r'Files.writeString(args, "-XX:+EnableDynamicAgentLoading\n-cp'
        replacement = r'Files.writeString(args, "-Djava.io.tmpdir=' + str(p) + r'\n-XX:+EnableDynamicAgentLoading\n-cp'
        assert needle in text
        text = text.replace(needle, replacement)
    src = work/'KeystoreReplicaRepairTest.java'
    src.write_text(text)
    child = (p/'replica-before'/rel.with_name('KeystoreDurabilityTest.java')).read_text()
    needle = 'public static void main(String[] args) throws Exception {'
    assert needle in child
    child = child.replace(needle, needle + chr(10) + '    Thread.sleep(' + str(delay) + ');')
    child_src = work/'KeystoreDurabilityTest.java'
    child_src.write_text(child)
    classes = work/'classes'
    classes.mkdir(exist_ok=True)
    subprocess.run([str(javac), '-J-Djava.io.tmpdir=' + str(p), '-cp', cp, '-d', str(classes), str(src), str(child_src)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    started = time.monotonic()
    with (e/(name+'.log')).open('w') as log:
        r = subprocess.run([str(java), '-Djava.io.tmpdir=' + str(p), '-XX:+EnableDynamicAgentLoading', '-cp', os.pathsep.join([str(classes),str(probe),cp]), 'ReplicaTimeoutProbe'], stdout=log, stderr=subprocess.STDOUT, timeout=150)
    elapsed = time.monotonic() - started
    output = (e/(name+'.log')).read_text()
    ok = r.returncode == expected and (message is None or message in output)
    if name == 'replica-after-slow':
        ok = ok and output.count('start-to-pause=') == 4 and elapsed > 30
    if name == 'replica-after-hung-child':
        ok = ok and 'not paused; elapsed=' in output and elapsed < 45
    results.append(dict(name=name, childDelayMs=delay, exitCode=r.returncode, elapsedSeconds=elapsed, expectedMessage=message, matched=ok))
    (e/'replica-probes.json').write_text(json.dumps(results, indent=2)+chr(10))
    print(results[-1], flush=True)
    if not ok: raise SystemExit('Probe did not match: '+name)
