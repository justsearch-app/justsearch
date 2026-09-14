# C2-9a hosted prepared-context assertion correction

## Hosted finding (2026-09-14)

CI [34839403298](https://github.com/justsearch-app/justsearch/actions/runs/34839403298)
at `27246acd6cd6278bf73c0170c78bc834805b55c1` failed only Unit tests (app-ui),
job103960747333; the other12 jobs passed. CLA34839398932 passed. Earlier basis
revision43fa7b3ac CI34838573308 was cancelled by the subsequent push, not verified.

PreparedDispatchAdmissionTest's two parameters still expected exact original
EngineContext without the C2-9a server-selected grant reference. The real handler
correctly received the original frozen context plus `jsa1:capsule` and its exact
work id. Hosted retries produced six reports of the same assertion failure at
line83. This is expectation drift against the already-selected one-field transition,
not a permission to relax frozen attribution, work ownership or provenance checks.
The correction expects that explicit capsule basis and preserves equality of every
other context field and the existing real admission/lifetime assertions.

Independent read-only triage fetched exact hosted source and logs. Accessible
artifacts are `tmp/1642-hosted-run.json`, `tmp/1642-hosted-job-103960747333.log`,
`tmp/1642-hosted-failure-snippets.txt`, `tmp/1642-hosted-PreparedDispatchAdmissionTest.java`
and `tmp/1642-hosted-OperationExecutorImpl.java` under
`F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/`.
Retain through final lane reconciliation plus30 days, at least2026-10-14.


## Local correction proof

Root-owned Windows Gradle1643 executed both parameterized cases successfully,
with UI PMD and repository formatting passing. The tested tree was27246acd6 plus
the shared-authority composition diff and this assertion correction. The latter is
committed separately; the shared-authority change does not affect this directly
constructed executor fixture. Logs/counts/XML are preserved at `tmp/1643*` under
the same worktree and retention. A successful subsequent hosted run remains owed.
