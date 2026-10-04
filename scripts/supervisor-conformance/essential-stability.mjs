// Shared actuator proof: the test controls only the fake Engine's reported index readiness.
import fs from 'node:fs';
import path from 'node:path';

export async function proveEssentialStability({ dataDir, statePath, policy, io }) {
  const problems = [];
  const current = () => io.readJsonIfPresent(statePath);
  await io.waitFor(() => current()?.state === 'running' && current()?.incarnation === 2,
    { timeoutMs: 15000, what: '503-responsive second incarnation' });
  const control = (ready, epoch = 0) => {
    const target = path.join(dataDir, 'fake-essential-ready.json');
    const temporary = `${target}.tmp`;
    fs.writeFileSync(temporary, JSON.stringify({ ready, epoch }));
    fs.renameSync(temporary, target);
  };
  const requireCount = (expected, phase) => {
    const state = current();
    if (state?.state !== 'running' || state?.incarnation !== 2 || state?.restartCount !== expected) {
      problems.push(`${phase}: expected running incarnation 2 with budget ${expected}, got ${JSON.stringify(state)}`);
    }
  };
  await io.sleep(policy.stabilityWindowMs + 2 * policy.hangPollIntervalMs);
  requireCount(1, '503 is live while index readiness is absent');
  control(true);
  await io.sleep(policy.stabilityWindowMs / 2);
  requireCount(1, 'a partial ready window does not reset');
  control(false);
  await io.sleep(3 * policy.hangPollIntervalMs);
  requireCount(1, 'loss of index readiness interrupts the window');
  control(true);
  await io.sleep(policy.stabilityWindowMs * 0.7);
  requireCount(1, 'the new ready window must start over');
  control(true, 1);
  await io.sleep(policy.stabilityWindowMs * 0.7);
  requireCount(1, 'READY with a different epoch restarts the stability window');
  await io.waitFor(() => current()?.restartCount === 0,
    { timeoutMs: policy.stabilityWindowMs + 4 * policy.hangPollIntervalMs, what: 'continuous essential readiness budget reset' });
  requireCount(0, 'essential readiness resets despite optional AI being unavailable');
  return problems;
}
