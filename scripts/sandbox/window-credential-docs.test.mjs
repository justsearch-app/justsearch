import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

const read = relative => fs.readFileSync(new URL(relative, import.meta.url), 'utf8');
test('S9 documents packaged manifest-token limits, exposed headless approvals and accepted residuals', () => {
  const threat = read('../../docs/reference/security/threat-model.md');
  const headless = read('../../docs/how-to/headless-runtime.md');
  assert.match(threat, /manifest token is not the operator/);
  assert.match(threat, /read\/search and untrusted\s+requests/);
  assert.match(threat, /app window's separate per-boot credential/);
  assert.match(threat, /Headless: token-as-operator remains exposed/);
  assert.match(threat, /same-user process can edit saved grants or the plugin allowlist/);
  assert.match(threat, /shell and webview memory/);
  assert.match(threat, /release devtools/);
  assert.match(threat, /stdout pipe through `\/proc` could race/);
  assert.match(headless, /Curl changes that require approval need a click in the app/);
  assert.match(headless, /retains \*\*token-as-operator\*\*/);
});
