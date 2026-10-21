import assert from 'node:assert/strict';
import test from 'node:test';
import guardAutoMerge from './auto-merge-guard.cjs';

const context = { apiUrl: 'https://api.github.com', repo: { owner: 'owner', repo: 'repo' }, runId: 123,
  payload: { pull_request: { number: 9, head: { sha: 'a'.repeat(40) } } } };
async function exercise({ head = context.payload.pull_request.head.sha,
  enabledAt = '2026-10-21T12:00:00Z', createdAt = '2026-10-21T12:01:00Z', status = 200 } = {}) {
  const mutations = [];
  const messages = [];
  const github = {
    graphql: async (query, args) => {
      if (query.includes('mutation')) {
        assert.match(query, /disablePullRequestAutoMerge/);
        mutations.push(args);
        return {};
      }
      assert.match(query, /autoMergeRequest\s*{\s*enabledAt/);
      assert.deepEqual(args, { ...context.repo, number: 9 });
      return { repository: { pullRequest: { id: 'pr-id', headRefOid: head,
        autoMergeRequest: enabledAt === null ? null : { enabledAt } } } };
    },
  };
  await guardAutoMerge({ github, context, core: { info: message => messages.push(message) },
    fetchImpl: async (url, options) => {
      assert.equal(url, 'https://api.github.com/repos/owner/repo/actions/runs/123');
      assert.deepEqual(options.headers, { Accept: 'application/vnd.github+json' }, 'the public read sends no token');
      return { ok: status === 200, status, json: async () => ({ created_at: createdAt }) };
    },
  });
  return { mutations, messages };
}
test('disables an enqueue older than this synchronize run on the event head', async () => {
  const { mutations, messages } = await exercise();
  assert.deepEqual(mutations, [{ id: 'pr-id' }]);
  assert.match(messages[0], /Disabled auto-merge for PR #9/);
});
test('a queued synchronize run leaves an enqueue for a later head alone', async () => {
  const { mutations, messages } = await exercise({ head: 'b'.repeat(40) });
  assert.deepEqual(mutations, []);
  assert.match(messages[0], /head has changed again/);
});
test('a queued synchronize run leaves a later re-enqueue of the same head alone', async () => {
  const { mutations, messages } = await exercise({ enabledAt: '2026-10-21T12:02:00Z' });
  assert.deepEqual(mutations, []);
  assert.match(messages[0], /keeping the new enqueue/);
});
test('an already disabled PR needs no mutation', async () => {
  const { mutations, messages } = await exercise({ enabledAt: null });
  assert.deepEqual(mutations, []);
  assert.match(messages[0], /already disabled/);
});
for (const override of [{ enabledAt: 'invalid' }, { createdAt: null }, { createdAt: 'invalid' }]) {
  test(`unreadable timestamps are reported: ${JSON.stringify(override)}`, async () => {
    await assert.rejects(exercise(override), /cannot compare/);
  });
}

test('an unavailable run timestamp reports the HTTP failure', async () => {
  await assert.rejects(exercise({ status: 503 }), /creation time: HTTP 503/);
});
