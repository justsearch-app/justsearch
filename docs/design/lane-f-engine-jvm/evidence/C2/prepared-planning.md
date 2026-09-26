# C2-3 preparation before orchestrator approval

September13, based on852916d16. The dispatcher and backend intent router now expose
preparation without accepting or executing an operation. Agent/workflow consumers
are the next connected item; this prerequisite does not close C2-3.

The implementation extracts the existing private InvocationPlan construction and
reuses its runner key scope and operations.db preparation. The public server-side
result is a recorded receipt or a stable key/nonce with optional bounded display.
It contains no frozen execution payload and grants no authority. Router continuation
uses a backend overload, leaving ShellAddress/public argument JSON unchanged.
Current provenance/hard-stop validation and public identity lookup precede preparation;
final dispatch repeats authorization. No acceptance, effect or capsule operation is
performed by planning. A non-display plan and a receipt bypass the handler projection.

The named regression demonstrates why routing first is unsafe: a LOW operation has
an AUTO enforcement gate, while the agent's WATCH mode can require approval. Negative1010
temporarily calls dispatchAttempt for LOW before planning; its LOW case observes one
effect where zero is required. The MEDIUM case passes. Eight cases in five suites
are represented in the task (the focused two-case fixture plus six always-included
contract cases), with exactly one failure. The original source is restored before1011.

Final1011 passes358 cases in49 suites, zero failures/errors/skips; both test tasks
execute (app-agent-api240, services118). Affected PMD and UI integration-test compilation
pass. The tests cover no acceptance/effect before approval, stable unknown key and
nonce, frozen target after SQLite reopen, conflicting pending/terminal public input,
receipt/display bypass, current hard-stop denial, and exact router reference delivery
without a navigation broadcast. The router refuses non-invocation continuation,
inconsistent transport and an unkeyed nonce.

Command:

```text
gradlew.bat :modules:app-services:test --tests '*PreparedOperationDispatchTest' --tests '*OperationExecutorImplTest' --tests '*BackendIntentRouterImplTest' :modules:app-agent-api:test :modules:app-services:pmdMain :modules:app-services:pmdTest :modules:app-agent-api:pmdMain :modules:app-agent-api:pmdTest :modules:ui:compileIntegrationTestJava --continue -PtestParallelism=1 --max-workers=4 --console=plain
```

The preceding full1009 build at852916d16 also passes in16m07s:10252 represented cases,
zero failures/errors and35 inherited skips. Seventeen test tasks execute8178 cases;
21 tasks reuse unchanged successful inputs. This is the integrated boundary before
the planning API, not full-suite proof of this diff. See
[latest full summary](latest-full-run-summary.json). Hosted34742373815 remains pending
at the recorded check; no hosted/live/model success is inferred.

Raw logs, XML and counts are under
`F:/justsearch-public/.claude/worktrees/lane-F-A/tmp/c2-3-preparation-planning-negative1010*`,
`.../c2-3-preparation-planning1011*` and `.../c2-3-preparation-full1009*`.
The [verification record](prepared-planning-verification.json) pins source/log hashes
and exact counts. Retain these accessible artifacts through stage acceptance plus30
days; export before releasing the worktree. Agent/workflow preparation, point-to-point
preview delivery, nested gate reattachment and remaining C2 ingress are still required.
