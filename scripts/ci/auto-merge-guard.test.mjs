import assert from 'node:assert/strict';
import test from 'node:test';
import guardAutoMerge from './auto-merge-guard.cjs';

async function exercise({ head = 'a'.repeat(40), eventHead = 'a'.repeat(40),
  enabledAt = '2026-10-21T12:00:00Z', pushedAt = '2026-10-21T12:01:00Z', queryError,
  missingPr = false, missingRequest = false, missingEnabledAt = false, mutationError } = {}) {
  const context = { repo: { owner: 'owner', repo: 'repo' },
    payload: { pull_request: { number: 9, node_id: 'event-pr-id', updated_at: pushedAt, head: { sha: eventHead } } } };
  const mutations = [];
  const messages = [];
  const github = {
    graphql: async (query, args) => {
      if (query.includes('mutation')) {
        assert.match(query, /disablePullRequestAutoMerge/);
        mutations.push(args);
        if (mutationError) throw new Error(mutationError);
        return {};
      }
      if (queryError) throw new Error(queryError);
      assert.match(query, /autoMergeRequest\s*{\s*enabledAt/);
      assert.deepEqual(args, { ...context.repo, number: 9 });
      return { repository: { pullRequest: missingPr ? null : { id: 'pr-id', headRefOid: head,
        autoMergeRequest: missingRequest ? undefined : enabledAt === null ? null : missingEnabledAt ? {} : { enabledAt } } } };
    },
  };
  // Any anonymous metadata request would fail these cases, including rate-limit failures.
  const fetch = test.mock.method(globalThis, 'fetch', () => { throw new Error('Unexpected anonymous request'); });
  try {
    await guardAutoMerge({ github, context, core: { info: message => messages.push(message) } });
    assert.equal(fetch.mock.callCount(), 0);
  } finally { fetch.mock.restore(); }
  return { mutations, messages };
}
test('disables an enqueue older than this push on the event head', async () => {
  const { mutations, messages } = await exercise();
  assert.deepEqual(mutations, [{ id: 'pr-id' }]);
  assert.match(messages[0], /Disabled auto-merge for PR #9/);
});
test('a queued synchronize run leaves an enqueue for a later head alone', async () => {
  const { mutations, messages } = await exercise({ head: 'b'.repeat(40) });
  assert.deepEqual(mutations, []);
  assert.match(messages[0], /head has changed again/);
});
test('a delayed run preserves a re-enqueue after the push, even before the run started', async () => {
  const { mutations, messages } = await exercise({ enabledAt: '2026-10-21T12:01:01Z' });
  assert.deepEqual(mutations, []);
  assert.match(messages[0], /enabled after this push.*keeping the new enqueue/);
});
test('an already disabled PR needs no mutation', async () => {
  const { mutations, messages } = await exercise({ enabledAt: null });
  assert.deepEqual(mutations, []);
  assert.match(messages[0], /already disabled/);
});
for (const override of [
  { enabledAt: 'invalid' }, { missingEnabledAt: true },
  { enabledAt: '2026-02-30T12:01:00Z' }, { pushedAt: null }, { pushedAt: '' },
  { pushedAt: 'invalid' }, { pushedAt: '1' }, { pushedAt: '2026-10-21T12:00:00Z' },
  { missingRequest: true }, { head: null }, { eventHead: null },
  { head: 'invalid', enabledAt: '2026-10-21T12:02:00Z' },
]) {
  test(`uncertain or equal metadata disables auto-merge: ${JSON.stringify(override)}`, async () => {
    const { mutations } = await exercise(override);
    assert.deepEqual(mutations, [{ id: 'pr-id' }]);
  });
}
for (const override of [{ queryError: 'HTTP 503' }, { missingPr: true }]) {
  test(`unreadable PR metadata uses the trusted event node ID: ${JSON.stringify(override)}`, async () => {
    const { mutations } = await exercise(override);
    assert.deepEqual(mutations, [{ id: 'event-pr-id' }]);
  });
}
test('a failure to disable is reported', async () => {
  await assert.rejects(exercise({ mutationError: 'HTTP 403' }), /HTTP 403/);
});
