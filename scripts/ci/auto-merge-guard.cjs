/** Invalidate an enqueue for this synchronize event, without undoing a newer enqueue. */
module.exports = async function guardAutoMerge({ github, context, core, sleep = ms => new Promise(resolve => setTimeout(resolve, ms)) }) {
  const eventPr = context.payload.pull_request;
  const number = eventPr.number;
  const retry = async operation => {
    for (let attempt = 0; ; attempt++) {
      try { return await operation(); } catch (error) {
        if (attempt === 2) throw error;
        core.info(`PR #${number}: retrying auto-merge operation after ${error.message}`);
        await sleep(250 * (attempt + 1));
      }
    }
  };
  let pr;
  try {
    const result = await retry(() => github.graphql(`
      query($owner: String!, $repo: String!, $number: Int!) {
        repository(owner: $owner, name: $repo) {
          pullRequest(number: $number) { id headRefOid autoMergeRequest { enabledAt } }
        }
      }`, { ...context.repo, number }));
    pr = result?.repository?.pullRequest;
  } catch (error) {
    core.info(`PR #${number}: cannot read auto-merge state (${error.message}); disabling it conservatively.`);
  }
  const validHead = value => typeof value === 'string' && /^[a-f0-9]{40}$/i.test(value);
  if (validHead(pr?.headRefOid) && validHead(eventPr.head?.sha) && pr.headRefOid !== eventPr.head.sha) {
    core.info(`PR #${number}: head has changed again; a later synchronize run handles it.`);
    return;
  }
  if (pr?.autoMergeRequest === null) {
    core.info(`PR #${number}: auto-merge is already disabled.`);
    return;
  }
  const timestamp = value => {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value)) return NaN;
    const parsed = Date.parse(value);
    // Date.parse normalizes impossible dates such as February 30; reject those too.
    return Number.isFinite(parsed) && new Date(parsed).toISOString().slice(0, 19) === value.slice(0, 19) ? parsed : NaN;
  };
  // updated_at comes from this push's payload, not a later API read or the time a
  // delayed runner starts. No anonymous API request (or Actions permission) needed.
  const pushedAt = timestamp(eventPr.updated_at);
  const enabledAt = timestamp(pr?.autoMergeRequest?.enabledAt);
  if (validHead(pr?.headRefOid) && validHead(eventPr.head?.sha)
      && Number.isFinite(pushedAt) && Number.isFinite(enabledAt) && enabledAt > pushedAt) {
    core.info(`PR #${number}: auto-merge was enabled after this push; keeping the new enqueue.`);
    return;
  }
  // Missing metadata must never preserve an enqueue for an unvalidated head.
  // The trusted event's node_id also lets us disable when the state query fails.
  const id = pr?.id || eventPr.node_id;
  if (!id) throw new Error(`PR #${number}: auto-merge is still on; cannot disable it: missing pull request node ID.`);
  try {
    await retry(() => github.graphql(`mutation($id: ID!) {
    disablePullRequestAutoMerge(input: {pullRequestId: $id}) { pullRequest { number } }
  }`, { id }));
  } catch (error) {
    throw new Error(`PR #${number}: auto-merge is still on; disabling it failed after 3 attempts: ${error.message}`);
  }
  core.info(`Disabled auto-merge for PR #${number}: its head changed or enqueue metadata is uncertain; run enqueue again.`);
};
