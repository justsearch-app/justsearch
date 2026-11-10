# Context window: one home

Baseline: `611a433d52b83eda253c96cc690d03a2d3d1cfa2` (the task-start `origin/main`).
`unit.json` is regenerated execution evidence for both current test files: seventeen default
Vitest checks and two real-Chromium lockdown checks pass on the candidate. Both files fail
with assertions against the pinned baseline; the browser cases fail at the missing Settings
link. `before-observations.json` retains the original detailed unit observations: eleven
new-behavior failures and six preservation passes. No preservation expectation was changed.

| Contract | Checks / evidence |
| --- | --- |
| P2-1 | `ContextWindow.home.test.ts`: one editor and cold/mounted arrival. `ContextWindow.home-lockdown.test.ts`: real Shell/router/Settings dialog, cold and actual Memory-tab arrivals, runtime address, dialog closure, keyboard focus, viewport visibility, running then finished highlight, no focus/highlight replay on ordinary return. |
| P2-2 | Saved `0`, `512`, `196608`; minimum `512` and narrow `llm.contextWindow` save; preservation passes before and after. |
| P2-3 | Saved/running mismatch, pending Automatic after override, derived Automatic, unknown/offline/stale readouts. |
| P2-4 | HTTP 500 and 409 refusal, visible error and previous saved value; preservation passes before and after. |
| P2-5 | Both search terms find the retained register anchor; agent-autonomy anchor remains; full Settings suites in default Vitest run. |

The browser check is now part of the existing lockdown suite, discovered by
`vitest.config.lockdown.ts` and run in `.github/workflows/ci.yml` by the frontend lockdown step.
The standalone `scripts/ci/check-context-home-browser.mjs` was deleted. Reproduce from
`modules/ui-web`:

```
npx vitest run --config vitest.config.lockdown.ts src/shell-v0/views/ContextWindow.home-lockdown.test.ts --maxWorkers=2
```

It starts a private Vite server with `configFile: false` and a temporary cache directory,
stubs API responses, uses installed headless Chromium with GPU disabled, and closes its own
browser/context/server in teardown. Each test has a fresh browser context. There is no
browser installation or shared dev stack. The old `chromium-*.txt` files are historical
standalone-run observations; `unit.json` is the current reproducible browser evidence.

For `unit.json`, the canonical evidence runner was copied, with its imports, beneath `$TMPDIR`.
`shared-clone-runner.patch` records the temporary adaptation: root is the invocation cwd;
before-state creation uses `git clone --shared --no-checkout` and detached checkout rather
than registering a worktree in the source repository; cleanup removes only that clone.
Dependencies were copied as ordinary files with `--copy-node-modules`, never linked to another
checkout. Both trees use their own dependency/build caches. Invocation from the repository root:

```
node "$TMPDIR/s6-evidence-runner/scripts/governance/gates/test-intent/run-evidence.mjs" \
  --out gates/test-intent/evidence/s6-context/unit.json \
  --check modules/ui-web/src/shell-v0/views/ContextWindow.home.test.ts \
  --check modules/ui-web/src/shell-v0/views/ContextWindow.home-lockdown.test.ts \
  --before 611a433d52b83eda253c96cc690d03a2d3d1cfa2 --copy-node-modules
```

The producer digest records that adaptation; no repository runner was changed. The current
changeset cites this evidence for both new files. Independent acceptance remains the verifier's
responsibility. The retired suite guarded the removed Settings editor; its replacement covers
the surviving semantics in AI Brain. Conflict search:
`rg -n -i 'context.window|contextLength|Reading memory' docs/decisions`.
ADR-0047 requires Automatic derivation and explicit overrides honored or failing loud, with
no requirement for a duplicate Settings editor.

## Final-tree checks

Commands ran after the final test migration. No production code changed during this finishing
assignment. `.agents/preflight` is absent.

| Command | Observed result |
| --- | --- |
| UI `npm run typecheck` | Exit 0. |
| UI `npx vitest run --maxWorkers=4` | Exit 0; 748 files, 9,488 tests passed; 123.44 seconds. The previous twenty failures all pass in the full run. |
| UI `npm run lint` | Exit 0. |
| Root `node scripts/ci/run-ui-web-gates.mjs` | Exit 0; 31/31 passed. |
| UI `npm run knip:report` | Exit 0; report produced (the underlying knip analysis reports findings with exit 1). |
| Root `node scripts/governance/run.mjs --gate dead-code --mode gate` | Exit 0; pass, seven informational findings. |
| UI `npm run check:component-vocabulary` | Exit 0; 163 components match. The requested root invocation reports Missing script (exit 1); this command exists only in the UI package. |
| UI `npx vitest run --config vitest.config.lockdown.ts --maxWorkers=2` | Exit 0; 26 files, 486 tests passed; 417.07 seconds. |
| Root `node scripts/governance/gates/test-intent/cli.mjs --base origin/main` | Exit 1; all three task test items covered; nineteen unrelated differences against the advanced main plus missing independent acceptance. Full output in `test-intent.txt`. |
| `gitleaks git --staged --no-banner --redact` | Exit 0; no leaks. All changes staged in a private temporary index, then unstaged. |

The explicit base resolved to `3e160cbc34303d00c36b998f1a1e4992543bb304`; no fetch was run.
The gate's nonblocking reconsider-rate finding is 13 of 49 audited PRs (26.5%) with reviewer
disagreement or a tie-break over 28 days. This assignment does not alter those base differences
or supply its own independent acceptance.

The first targeted Vitest invocation was inadvertently made at the repository root, where no
local Vitest is installed; npx attempted a registry lookup and failed writing its read-only npm
cache. Re-running from the UI package used the installed dependencies and passed. No dependency
installation or cache clearing was performed.


`full-suites.txt` retains unedited stdout/stderr from the two final full-suite commands.
The default suite logs refused localhost:3000 probes as well as its passing summary; no shared
dev stack was started or used. The browser tests used their own headless Chromium processes
and private Vite servers. No shared running application or dependency tree was used by the
before-state checks. No cache was cleared and no browser installation was run.

The source Git metadata is outside the sandbox's writable roots. Staging therefore used a
private temporary Git index and object directory under `$TMPDIR`, with the repository object
store as a read-only alternate. Gitleaks scanned the complete staged diff. `git read-tree HEAD`
then unstaged it, and both temporary and source indexes were checked for an empty staged diff.
No candidate was committed, pushed, fetched or rebased. The prior uncommitted work is preserved.
