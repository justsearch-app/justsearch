# Record-kind policy fixture correction — September 13

Hosted CI at 8ead6de3b, [run 34722656207](https://github.com/justsearch-app/justsearch/actions/runs/34722656207),
failed Public claims at the policy-axis liveness self-test. Its literal component
list still contained nine fields after OperationPolicy gained recordKind. The
record-order assertion correctly detected the mismatch. Other hosted jobs passed.

The fixture now appends OperationKind recordKind in its actual tenth position.
The exact component-name/order assertion and all positional/comment regression
checks remain intact; no production gate or validation was removed. Local
`node scripts/ci/check-policy-axis-liveness.test.mjs` passes all 12 assertions and
`node scripts/ci/check-policy-axis-liveness.mjs` passes all 10 policy axes, each
with a production reader. The failure log and workflow classification are retained
at worktree `tmp/c2-2-kind-hosted-8ead-failure.txt` and
`tmp/c2-2-kind-hosted-signals.txt` through lane acceptance plus 30 days.

This is a separate follow-up to the declared-kind item. Child acceptance remains
the active C2-2 item and is not included in this correction commit. A successful
successor hosted run is still required.
