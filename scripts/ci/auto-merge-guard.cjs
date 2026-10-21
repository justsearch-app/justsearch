/** Invalidate an enqueue for this synchronize event, without undoing a newer enqueue. */
module.exports = async function guardAutoMerge({ github, context, core, fetchImpl = globalThis.fetch }) {
  const number = context.payload.pull_request.number;
  // Public run metadata needs no Actions permission. Do not send the write token.
  const response = await fetchImpl(`${context.apiUrl}/repos/${context.repo.owner}/${context.repo.repo}/actions/runs/${context.runId}`, {
    headers: { Accept: 'application/vnd.github+json' }, signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) throw new Error(`Auto-merge guard cannot read this run's creation time: HTTP ${response.status}`);
  const run = await response.json();
  const { repository: { pullRequest: pr } } = await github.graphql(`
    query($owner: String!, $repo: String!, $number: Int!) {
      repository(owner: $owner, name: $repo) {
        pullRequest(number: $number) { id headRefOid autoMergeRequest { enabledAt } }
      }
    }`, { ...context.repo, number });
  if (pr.headRefOid !== context.payload.pull_request.head.sha) {
    core.info(`PR #${number}: head has changed again; a later synchronize run handles it.`);
    return;
  }
  if (!pr.autoMergeRequest) {
    core.info(`PR #${number}: auto-merge is already disabled.`);
    return;
  }
  const createdAt = Date.parse(run.created_at);
  const enabledAt = Date.parse(pr.autoMergeRequest.enabledAt);
  if (!Number.isFinite(createdAt) || !Number.isFinite(enabledAt)) {
    throw new Error('Auto-merge guard cannot compare the run creation and enqueue timestamps.');
  }
  if (enabledAt > createdAt) {
    core.info(`PR #${number}: auto-merge was enabled after this run was created; keeping the new enqueue.`);
    return;
  }
  await github.graphql(`mutation($id: ID!) {
    disablePullRequestAutoMerge(input: {pullRequestId: $id}) { pullRequest { number } }
  }`, { id: pr.id });
  core.info(`Disabled auto-merge for PR #${number}: its head changed; run enqueue again.`);
};
