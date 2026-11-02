// Plants each form of a jseval-only PYTHONPATH in a scratch checkout and expects the check to
// name it; the forms that name both packages or use the helper, and history files, must pass.
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const check = fileURLToPath(new URL('./check-pythonpath.mjs', import.meta.url));
const repo = path.resolve(path.dirname(check), '../..');

function scratch(t, files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'check-pythonpath-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  execFileSync('git', ['init', '--quiet', root]);
  for (const [file, text] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    fs.writeFileSync(path.join(root, file), text);
  }
  return spawnSync(process.execPath, [check, root], { encoding: 'utf8' });
}

const planted = {
  'scripts/a.sh': '(cd scripts/jseval && PYTHONPATH=scripts/jseval python -m jseval run)\n',
  '.github/workflows/b.yml': '        env:\n          PYTHONPATH: ${{ github.workspace }}/scripts/jseval\n',
  'docs/how-to/c.md': '```bash\nexport PYTHONPATH="$PWD/scripts/jseval"\n```\n',
  'scripts/d.mjs': "const env = { PYTHONPATH: path.join(root, 'scripts', 'jseval') };\n",
  'scripts/e.bat': 'set "PYTHONPATH=%WT%\\scripts\\jseval"\n',
  'scripts/f.py': 'env["PYTHONPATH"] = str(root / "scripts" / "jseval")\n',
  // jsharness named later on the line, outside the value, must not hide the error.
  'scripts/g.sh': 'PYTHONPATH=scripts/jseval python -c "from jsharness.readiness import wait_pipeline_complete"\n',
};

test('every planted jseval-only PYTHONPATH is named', t => {
  const result = scratch(t, planted);
  assert.equal(result.status, 1, result.stdout + result.stderr);
  for (const file of Object.keys(planted)) assert.match(result.stderr, new RegExp(`${file.replace(/[.\\/]/g, '\\$&')}:\\d+:`), file);
  assert.match(result.stderr, /\(7\)/);
});

test('both packages, the helper, a script path after the value, and history pass', t => {
  const result = scratch(t, {
    'scripts/a.sh': 'PYTHONPATH=scripts/jseval:scripts/jsharness python -m jseval run\n'
      + '(cd "$repo/scripts/jseval" && PYTHONPATH=$pythonpath python -m jseval run)\n'
      + 'PYTHONPATH=$(js_pythonpath "$repo") python scripts/jseval/x.py\n',
    'scripts/b.mjs': "const env = { PYTHONPATH: pythonPath(root) };\n",
    'docs/c.md': 'PYTHONPATH=$(node scripts/lib/python-path.cjs .) python scripts/jseval/x.py\n'
      + 'jseval refuses when a PYTHONPATH names only scripts/jseval.\n',
    'docs/tempdocs/old.md': 'PYTHONPATH=scripts/jseval python -m jseval run\n',
    'docs/design/x/evidence/run.md': 'PYTHONPATH=scripts/jseval python -m jseval run\n',
    'scripts/jseval/782-run/driver.cmd': 'set PYTHONPATH=F:\\wt\\scripts\\jseval\n',
  });
  assert.equal(result.status, 0, result.stdout + result.stderr);
});

test('this checkout passes', () => {
  const result = spawnSync(process.execPath, [check, repo], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stdout + result.stderr);
});
