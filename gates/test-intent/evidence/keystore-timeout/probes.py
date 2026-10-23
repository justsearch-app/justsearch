import os, subprocess, pathlib, time, json
p = pathlib.Path(os.environ['TMPDIR'])
e = p / 'evidence'
cp = (e / 'test-classpath.txt').read_text()
java = pathlib.Path((e / 'test-java.txt').read_text())
javac = java.with_name('javac')
probe = p / 'probe-classes'
probe.mkdir(exist_ok=True)
subprocess.run([str(javac), '-J-Djava.io.tmpdir=' + str(p), '-cp', cp, '-d', str(probe), str(p/'TimeoutProbe.java')], check=True)
rel = pathlib.Path('modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java')
results = []
for name, source, delay, expected, message in [
    ('before-slow', p/'before'/rel, 4000, 1, 'timed out after 30 seconds'),
    ('after-slow', pathlib.Path.cwd()/rel, 4000, 0, None),
    ('after-hung-child', pathlib.Path.cwd()/rel, 60000, 1, 'password publication step 1 did not pause within 30 s'),
    ('after-whole-timeout', pathlib.Path.cwd()/rel, 15000, 1, 'timed out after 120 seconds'),
]:
    work = p/name
    work.mkdir(exist_ok=True)
    text = source.read_text().replace('public static void main(String[] args) throws Exception {', 'public static void main(String[] args) throws Exception {\n    Thread.sleep(' + str(delay) + ');')
    if name.startswith('before'):
        text = text.replace('Files.writeString(args, "-XX:+EnableDynamicAgentLoading\\n-cp', 'Files.writeString(args, "-Djava.io.tmpdir=' + str(p) + '\\n-XX:+EnableDynamicAgentLoading\\n-cp')
    src = work/'KeystoreDurabilityTest.java'
    src.write_text(text)
    classes = work/'classes'
    classes.mkdir(exist_ok=True)
    subprocess.run([str(javac), '-J-Djava.io.tmpdir=' + str(p), '-cp', cp, '-d', str(classes), str(src)], check=True)
    started = time.monotonic()
    with (e/(name+'.log')).open('w') as log:
        r = subprocess.run([str(java), '-Djava.io.tmpdir=' + str(p), '-XX:+EnableDynamicAgentLoading', '-cp', os.pathsep.join([str(classes),str(probe),cp]), 'TimeoutProbe'], stdout=log, stderr=subprocess.STDOUT, timeout=150)
    elapsed = time.monotonic() - started
    output = (e/(name+'.log')).read_text()
    ok = r.returncode == expected and (message is None or message in output)
    if name == 'after-slow':
        ok = ok and output.count('start-to-pause=') == 8 and elapsed > 30
    if name == 'after-hung-child':
        ok = ok and 'not paused; elapsed=' in output and elapsed < 45
    results.append(dict(name=name, childDelayMs=delay, exitCode=r.returncode, elapsedSeconds=elapsed, expectedMessage=message, matched=ok))
    (e/'probes.json').write_text(json.dumps(results, indent=2)+'\n')
    print(results[-1], flush=True)
    if not ok: raise SystemExit('Probe did not match: '+name)
