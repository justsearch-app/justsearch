---
schema: test-intent.v1
task: WP5.8
author-role: builder
author-session: uiux-source-gone-builder
---

# A cited source that is no longer held says so

## Builder findings at the assigned baseline

- `modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.ts:516–518` treated every 404 as absence and rendered other failures as bare HTTP status. The existing friendly missing-source behavior was reusable.
- `modules/ui/src/main/java/io/justsearch/ui/api/PreviewController.java:259–265` returned 404 for every null/not-found slice, including unreadable content.
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerSearchService.java:868–894` used `found=false` both for missing content and exceptions. It cannot establish absence. Existing strict `IndexCountOps.countByFieldOrThrow` and `WorkerIngestService.captureRebuildGeneration` supply membership and an idle-generation witness without a disk probe or new owner.
- `modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.ts:2794–2822` already mounted the shared citations panel for Ask and Delegate. There was no current-membership observation. The existing Search v3 view owns conversation identity and the resolved Engine address.

Line numbers above refer to `bfd625c7eea6591250e5a3d231c25e7884aa4312`.

## Changes and the rung used

| File (under `modules/` unless stated) | Change and reason |
| --- | --- |
| `app-api/.../DocumentService.java` | Three-state presence and a 64-ID bound; default unknown keeps existing service implementations compatible. No persisted field. |
| `app-services/.../worker/SearchServiceCalls.java` | One in-process observation with a default unknown result; reuses the existing Worker port. |
| `app-services/.../worker/KnowledgeClient.java` | Dispatches that observation using the existing STANDARD call machinery. |
| `app-services/.../worker/RemoteDocumentService.java` | Validates batches and uses the existing captured-client executor/lease. Never infers membership from fetch results. |
| `app-services/.../conversation/LazyDocumentService.java` | Resolves and forwards through the existing per-operation owner. |
| `app-engine/.../WorkerSearchCalls.java` | Forwards the observation through the existing Engine bridge. |
| `worker-services/.../services/WorkerSearchService.java` | Strict count per ID, refresh failure propagated into unknown, per-source read failures isolated; idle-generation checks before/after reject transitional or replaced views. No stored-content or source-filesystem read. |
| `worker-services/.../server/DefaultWorkerAppServices.java` | Wires the existing strict idle-generation authority in both normal composition paths. Text-only candidate views remain unknown. |
| `ui/.../api/PreviewController.java` | Bounded observation route and independent membership diagnosis after a failed preview. Only confirmed absence returns 404/sourceState=absent; unknown returns 503/sourceState=unknown. The observation never records activity. |
| `ui/.../api/routes/AiRoutes.java` | Registers the existing controller as an OBSERVATION. |
| `ui/.../api/ObservationJustifications.java` | Names the actual source-panel consumer. |
| `ui/.../api/RouteContractPolicy.java` | Declares the internal route, response schema and error contracts. |
| `ui/.../api/RouteCohorts.java` | Places the exact route in the knowledge cohort. |
| `ui/.../api/SchemaController.java` | Serves the new declared response schema through the existing allowlist. |
| `SSOT/schemas/document-presence-response.v1.json` (repository root) and `ui/src/main/resources/SSOT/schemas/document-presence-response.v1.json` | Matching bounded three-state response schema and normal packaged mirror. |
| `docs/reference/api-contract-map.md` (repository root) | Documents request, response, unknown behavior and read-only semantics. |
| `ui-web/src/shell-v0/components/chat/CitationsPanel.ts` | Search v3 opt-in asynchronous 64-ID batches, announced absence text, forced-color/wrapping styles. Abort plus request/source/conversation/Engine-address identity guards discard late replies. Existing disclosure lifecycle triggers fresh observations. Evidence objects remain untouched. |
| `ui-web/src/shell-v0/views/search-v3/Sv3Main.ts` | Supplies stable source references, conversation identity and resolved Engine address. Forwards already-known absence for either card or inline citation opening. |
| `ui-web/src/shell-v0/views/search-v3/SearchV3View.ts` | Passes existing owner state down. Lit's installed `keyed` directive forces a fresh Head read when a confirmed-absent source is selected even if its pane was already open. No new state owner. |
| `ui-web/src/shell-v0/components/documentPane/DocumentPane.ts` | Reuses the friendly unavailable error, adds full last-known path and shared copy/reveal controls, replaces bare HTTP errors with readable unknown errors, rejects stale loads including hide/reveal transitions. Completed present previews retain existing behavior. |
| `worker-services/.../WorkerDocumentPresenceTest.java` | Four Worker boundary checks: real Lucene counts without content/disk, removal/return, per-source I/O failure, generation fences and bounds. |
| `app-services/.../RemoteDocumentPresenceTest.java` | Two checks of captured-client forwarding, release, lazy resolution, unknown defaults and bounds. |
| `app-services/.../CitedConversationReadOnlyTest.java` | Real file-store Ask/Delegate load/list byte preservation; passes before and after. |
| `ui/.../DocumentPresenceHttpTest.java` | Real Javalin request/response boundary, bounds, requested-ID filtering, per-source states and no observation activity. |
| `ui/.../PreviewReadFailureHttpTest.java` | Real Javalin unreadable preview remains unknown; assertion fails on baseline 404 and passes with 503. |
| `ui-web/.../CitationsPanel.availability.test.ts`, `DocumentPane.availability.test.ts`, `Sv3Main.availability.test.ts` | Nineteen component checks; the raw six-file comparison also includes existing source/mark/pane preservation checks. Component checks are not claimed as installed-system checks. |
| Existing `DocumentPane.test.ts`, `PreviewControllerTest.java`, `PreviewSliceHttpTest.java`, `VduRequestActivityTest.java` | Missing-source fixtures encode independent membership; existing behavioral assertions retained. |
| Existing `DeclaredRequestActivityTest.java`, `SchemaControllerTest.java` | Extend exhaustive inventories for the added observation/schema; preserve existing cases and assertions. Test-intent entries above explain each adjustment. |

## Scenario evidence

`ui-before-results.json` and `ui-after-results.json` are unedited Vitest reports; `collect-ui.py` validates identical selection, assertion-only fail-before and no skips. `ui-summary.json` records **108 passes / 12 assertion failures before, 120 passes after**. `ui-before.json` and `ui-after.json` are separately generated by the repository's stock evidence producer in private shared clones, with matching check source digests. They are not hand-edited. Their `failBefore` is null because the mandated before checkout is a shared clone, not the producer's worktree mode; the raw reports and summary supply the actual before/after comparison. Java summaries are machine-generated from actual JUnit XML by `collect-java.py`; copied XML preserves decisive failures and preservation results.

| Scenario | Check and observed comparison |
| --- | --- |
| S58.1 | Source-panel announced-absence component check fails before and passes after. Installed Engine/browser checks cover saved Ask and Delegate, next disclosure and reload. ARIA live/status is asserted; audible screen-reader output is unverified. |
| S58.2 | Path/copy/reveal and readable-error component checks fail before and pass after. Real HTTP unreadable-source regression fails before (404) and passes after (503). Installed browser copies the exact last-known path and exposes Reveal. Native OS reveal is not invoked because the desktop is off limits. |
| S58.3 | Component checks exercise unknown, failed, malformed, unrecognized and slow replies, bare/unconfirmed 404 and HTTP failures. The repaired distinctions fail before and pass after; safe neutral cases pass both. Worker checks isolate count I/O failure and reject missing/changed/transition generation authority. Installed Engine stopped yields unknown in both saved conversations. Startup/rebuild/switch/migration/store-lock combinations are boundary/component checks only, not a full installed transition matrix. |
| S58.4 | Real Lucene test counts a record with no extracted text and no disk file as present. Installed browser additionally renames the actual disk source while its indexed preview remains readable/present. Baseline preview/mark preservation checks pass; the newly introduced count interface has no executable baseline counterpart. |
| S58.5 | All existing citation/mark/pane checks in the focused six-file comparison pass before and after, including retained completed previews. Installed browser observes the existing mark and indexed preview for both answer shapes. |
| S58.6 | Deferred first-paint/131-source check fails before and passes after (64/64/3 batches). Late conversation/source/Engine-address and hide/reveal tests fail before and pass after. Real HTTP observation activity is zero; exhaustive route activity checks retain prior paths. |
| S58.7 | Panel removal/reconnect/restoration check fails before and passes after. Real Lucene delete/re-add and installed folder re-add return present on next load. |
| S58.8 | Installed browser screenshots cover light, dark, forced colors and width 360 for both shapes; actual clipboard copy works at narrow width. Before-state component checks have no absence text/path/control affordance; after checks pass. Visual review and screenshots apply only to the candidate. |
| N58.1 | Real FileConversationStore Ask/Delegate byte test passes before and after (`conversation-bytes-before.xml`, `conversation-bytes-after.xml`). Installed browser hashes all four saved metadata/message files unchanged. |
| N58.2 | Existing mark/anchor/verification checks in the focused comparison pass before and after; full candidate UI suite passes. No verification calculation or saved evidence is changed. |
| N58.3 | Existing old-window panel/pane checks pass before and after. Explicit opt-out check sends no presence request and passes both. |
| N58.4 | Real route activity, route-contract/schema and inventory checks exercise the registered route; inventories were adapted as documented above. Full Java/UI and governance outcomes are recorded below. |

The browser fixture uses private HOME/data/loopback services, a real indexed folder and real saved Ask/Delegate records citing it. It uses the allowed saved-conversation alternative; no model inference or llama-server was started. The web bundle is served by an owned Vite preview proxying the installed Engine because the Engine API does not serve `/`. The final installed run passed all 10 checks: [browser-evidence.json](../evidence/source-no-longer-held/browser-evidence.json), [browser-final.log](../evidence/source-no-longer-held/commands/browser-final.log). Its 12 screenshots (light, dark, forced-colors narrow, Engine stopped) were reviewed but are not kept in the repository. Candidate source/bundle hashes and saved before/after hashes are machine-recorded.

## Commands and results

Every Gradle invocation uses `env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew` through a clean-environment wrapper. The wrapper sets `CUDA_VISIBLE_DEVICES=-1`, strips inherited `JUSTSEARCH_*`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `JAVA_OPTS`, `_JAVA_OPTIONS`, and preserves `GRADLE_OPTS`. There were no slot exit-75 retries. Build outputs and node_modules are private to each checkout. `.agents/preflight` does not exist.

| Command | Observed result |
| --- | --- |
| Plain `npm ci` at repository root and `modules/ui-web` | Both exited 0. Repeated in the private before/evidence clones, with separate installations. No symlink/hard-link node_modules. |
| `npm run typecheck` in `modules/ui-web` | Exit 0 after final Engine-address wiring. |
| `npm run lint` in `modules/ui-web` | Exit 0 after final wiring. |
| `npm run test:unit:run -- --configLoader runner --maxWorkers=4` | Final: 655 files, 8,316 tests passed; exit 0. |
| `npm run test:unit:lockdown -- --configLoader runner` | Final: 3 files, 18 tests passed; exit 0. |
| Focused six-file `npx vitest run ... --configLoader runner --maxWorkers=4 --reporter=json` | Before: 108 passed, 12 assertion failures, 0 skipped; exit 1. After: 120 passed, 0 failed/skipped; exit 0. Selection includes the three new availability files and existing CitationsPanel, DocumentPane and Sv3Main citation files. |
| Stock `node scripts/governance/gates/test-intent/run-evidence.mjs --out ... --check ...` | Separately ran the three new UI files in private shared clones: baseline failed, candidate 19 tests passed; source digests recorded in producer output. |
| `:modules:app-api:test -PskipWebBuild=true` | Exit 0: 468 tests, 0 failures, 5 skipped. |
| `:modules:app-services:test -PskipWebBuild=true` | Exit 0: 4,560 tests, 0 failures, 11 skipped. Additional real conversation-byte test: 1 pass before and 1 pass after in separate focused calls. |
| `:modules:app-engine:test -PskipWebBuild=true` | Exit 0: 774 tests, 0 failures, 9 skipped. |
| `:modules:worker-services:test -PskipWebBuild=true` | Exit 1: JUnit XML has 2,409 tests, 4 failures, 24 skipped (console counts 2,408/4/23 because of a skipped container; passes agree). New membership tests: 4 passes. All four failures reproduce on baseline: LinuxRootMountInfoTest `/dev/disk/by-uuid` environment assumption and three WatcherUnavailabilityScopeTest assertions. Copied before/after XML preserves them. |
| `:modules:ui:test -PskipWebBuild=true` | Initial run: 2,222 tests, 6 failures, 1 skipped. Fixed the five membership-related fixture/schema-registration defects without removing assertions. Focused repaired classes then passed except the schema inventory count, which was corrected to require all 22 names. Final full run: exit 1, 2,222 tests, 1 failure, 1 skipped (2,220 passes); only NvidiaSmiAvailabilityHttpTest fails, matching the full baseline. `java-ui-full-candidate.json` and paired copied failure XML preserve the exact result. |
| Baseline `:modules:ui:test -PskipWebBuild=true` | Exit 1: 2,220 tests, 1 failure, 1 skipped. Only failure is NvidiaSmiAvailabilityHttpTest installed-tool availability assertion; same source and failure on candidate. Isolated baseline and candidate NVIDIA runs both passed, so no product fix is inferred from that intermittent behavior. |
| `compileTestJava compileIntegrationTestJava pmdAll --continue -PskipErrorProneTests=false -PskipWebBuild=true` | Final exit 0, 244 tasks. Initial run found one unnecessary qualification in KnowledgeClient; fixed that implementation defect and reran the full command. |
| `node scripts/governance/run.mjs --mode gate` | Exit 1: 28 evaluated, 26 pass, 1 skip (`test-efficacy`), 1 fail (`test-intent`: independent acceptance missing). All six modified existing tests covered. Uses assigned `bfd625c7ee` via merge-base. Baseline: 28 evaluated, 0 failures (skips are not claimed as passes). |
| `node scripts/governance/gates/test-intent/cli.mjs check --base origin/main` | Exit 1. Explicit moving base was `2950147c7e42db172c71194e1041de8cdd289e6f` on this run; 32 flagged items include unrelated upstream additions/deletions. They are not changes made by this builder. |
| Diagnostic `node scripts/governance/gates/test-intent/cli.mjs check --base bfd625c7ee` | Exit 1 solely for independent acceptance missing; 6 existing modified tests covered and 8 isolated new test sources exempt. The author does not write an acceptance record. |
| `npm run knip:report`; `node scripts/docs/generate-runtime-config-matrix.mjs`; plain `npm ci` in `scripts/wire-contract` | Exit 0; produced normal required governance inputs. No dependency changes. Advisory producer initially returned 403; the valid baseline report was reused only after confirming identical lockfile/input hashes. The final npm-audit gate passes. |
| `:modules:ui:installDist` | Exit 0: 84 tasks; current web bundle built successfully. Final private installed-Engine/headless run: exit 0, 10 checks, 12 screenshots. Engine and managed preview exited on owned stop markers; final ports are closed (`cleanup.json`). |
| `git diff --check` | Exit 0. No commit, push or publish. The stock evidence producer could not write source Git objects, so it was run only in the mandated private shared clones. |

Earlier validation attempts and their terminal counts are retained in `validation-attempts.json`; final acceptance commands and raw output are in `commands/`. An initial full UI run caught an extra update after panel disconnection; guarding the empty-set reset repaired that existing unsubscribe assertion. The full 8,316-test run was repeated after the final Engine-address change and passed.

## Limits and hand-back

The scenario choice remains agent-adopted under the quoted delegation. The separate verifier must judge this uncommitted candidate and author any test-intent acceptance. Skips and the pre-existing Java failures are not passes. The explicit origin/main drift needs the orchestrator's normal integration handling; no rebase or upstream test changes were attempted.

Native reveal and audible screen-reader output are unverified because desktop access is prohibited. Installed startup/rebuild/switch/migration/locked-store combinations are not all exercised; their conservative unknown branches have narrower Worker/component evidence. The new Java count interface cannot execute against the baseline without compatibility scaffolding; this is not represented as a baseline pass. Decisive repaired UI behavior and the unreadable Head preview do have actual assertion fail-before evidence.

Shared resources touched: Gradle/npm caches and the global Gradle scheduling slot; required NVIDIA tests query the installed driver/tool status without GPU inference. Early private Engine attempts encountered the shared HotSpot perfdata path, then used `-XX:-UsePerfData` and a private temporary directory. No owner data, dev stack, desktop or model inference was used. Logs were kept under the assigned temporary folder, never `/tmp/js-linux-port`.

A first owned Vite preview on loopback port 34385 survived its launcher session's interrupt. Its PID is invisible from the available command process namespaces, so it cannot safely be signalled by recorded identity here. Native host/orchestrator cleanup is required for that one listener. Later Engine/preview children are supervised by their own recorded PID/start time and stopped by owned markers. Headless Chromium profiles/TMPDIRs are removed in finally blocks.

## Test-intent classifications

### Round 2: focus announces confirmed absence

The repair at baseline `64af7c4a286d7b54e23c90e4fee673f51a2fef29` adds a conditional
`aria-description="No longer available"` to the existing source button. Its
accessible name, existing polite status, presence observation and batching stay
unchanged. Present, unknown and observation-disabled sources receive no description.
The original package evidence above remains historical; this round refreshes only
the changed source-panel check.

- Focused unit comparison: **12 passed / 1 assertion failure before; 13 passed
  after**, no skips. The failure is the absent button's missing description.
  [Before](../evidence/source-no-longer-held/accessibility-unit-before.json) and
  [after](../evidence/source-no-longer-held/accessibility-unit-after.json) are raw
  Vitest reports. The three new parameterized cases preserve the existing name
  attributes and description for present/unknown, and require the description for
  absence while keeping the same single polite status node.
- [Headless Chromium runner](../evidence/source-no-longer-held/accessibility-browser.mjs)
  loads the production Search v3 bundle from its own `vite preview`, mounts real
  Ask and Delegate source disclosures with fixture turns, and stubs only the
  document fixture and presence route. It checks Chromium's computed accessibility
  tree and Playwright's button role lookup after tabbing onto each source.
  [Before](../evidence/source-no-longer-held/accessibility-browser-before.json):
  four preservation checks pass; both absent-source checks fail because name and
  description omit the state. [After](../evidence/source-no-longer-held/accessibility-browser-after.json):
  all six pass, with `No longer available` in both absent buttons' descriptions.
  Each panel has one meaningful polite-status mutation, including after an
  unrelated rerender. An initial probe compared unfocused and focused names;
  the final comparison uses keyboard focus in both states because the existing
  hover/focus preview contributes to the name. Audible screen-reader output is
  unverified.
- Typecheck and lint exit **0**. Whole unit suite: **655 files / 8,319 tests
  passed**, exit **0**. Lockdown: **2 files passed / 1 failed; 17 tests passed /
  1 failed**, exit **1** in the builder's run (the unchanged
  `ConfirmDialog.viewport-lockdown.test.ts` S53.7 case exceeded its 5,000 ms timeout; see the
  re-run below).
  [Validation output](../evidence/source-no-longer-held/accessibility-validation.txt)
  retains all four commands' output and the producer's diagnostic.
- [Generated evidence](../evidence/source-no-longer-held/ui-accessibility.json):
  `run-evidence.mjs --before 64af7c4a286d7b54e23c90e4fee673f51a2fef29` (orchestrator, no copied
  dependencies) records the candidate passing and the before failing by assertion (the absent
  button's description is null). The lockdown suite, re-run by the orchestrator on the same tree,
  passed 3 files / 18 tests; the earlier ConfirmDialog viewport timeout did not recur.

`.agents/preflight` is absent. Both production builds pass. The new browser check
used private loopback ports, fresh profiles and short `/tmp/jsv-*` TMPDIRs, with
displays removed and `CUDA_VISIBLE_DEVICES=-1`. Its owned previews and browsers
exited and their profiles were removed. The required lockdown suite inherited the
assigned task TMPDIR and ran its existing browser fixture unchanged. No Engine,
model, desktop or owner data was used. The whole suite's existing proxy checks
also logged refused connections to localhost:3000; no service answered there.
Dependency copies were private; source Git
metadata was not written. The builder leaves the repair uncommitted for independent
verification. No known-good revision for this repair was supplied.

The scenarios are agent-adopted under the owner's delegation, not owner-agreed.
The bounded Worker count, Head preview and Search v3 panel implement DL1/DL2 and
S58.1–S58.8 without changing saved evidence. New isolated tests are exempt from
intent entries; their assertion fail-before and preservation evidence is recorded
below. A separate verifier still judges the candidate.

The existing tests keep their behavioral assertions. Their missing-source fixtures now
carry the independent absence fact required by DL2: a bare fetch miss/404 is no
longer sufficient evidence that the index has no record.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/chat/CitationsPanel.availability.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A focused confirmed-absent source button exposes No longer available through its explicit name or description and keeps one polite status node. Present and unknown sources keep the same explicit name and description. All prior availability assertions remain intact.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task WP5.8 request",
          "delegationScope": "Area-5 designs and settled WP5.8 scenarios S58.1–S58.8, N58.1–N58.4 and decisions DL1/DL2 from /home/elias/dev/justsearch/tmp/verify/agreed/area5.md lines 316–378",
          "choice": "S58.1 round-2 orchestrator repair: a screen-reader user focusing a confirmed-absent source button hears No longer available through its name or description, while its appearance remains politely announced once. Present and unknown sources keep their current name and description.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/source-no-longer-held/ui-accessibility.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/chat/CitationsPanel.availability.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The confirmed-missing response fixture adds sourceState: absent. The shared helper forwards that field. The assertion that a confirmed-missing source says it is no longer in the index, every other assertion, and every test condition are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task WP5.8 request",
          "delegationScope": "Area-5 designs and settled WP5.8 scenarios S58.1–S58.8, N58.1–N58.4 and decisions DL1/DL2 from /home/elias/dev/justsearch/tmp/verify/agreed/area5.md lines 316–378",
          "choice": "DL2 and S58.2/S58.3: only independently confirmed index absence produces the missing-source diagnosis. An unconfirmed 404 remains unknown.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/PreviewControllerTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The always-not-found DocumentService fixture still returns null content and now independently confirms ABSENT via checkPresence. The opaque-ID and not-found response assertions, activity expectations and all other tests remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task WP5.8 request",
          "delegationScope": "Area-5 designs and settled WP5.8 scenarios S58.1–S58.8, N58.1–N58.4 and decisions DL1/DL2 from /home/elias/dev/justsearch/tmp/verify/agreed/area5.md lines 316–378",
          "choice": "DL2 and S58.3 separate not-in-index from unreadable/no-extracted-text. The confirmed-absence preview still returns 404; unknown is not absence.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/PreviewSliceHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VduRequestActivityTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The single-runtime slicing fixture supplies its immutable idle-generation witness so the real Worker can count the missing ID. The activity fixture explicitly confirms pending.png ABSENT. All pre-existing response, paging, capture-release, cancellation, and activity assertions and parameterizations are retained.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task WP5.8 request",
          "delegationScope": "Area-5 designs and settled WP5.8 scenarios S58.1–S58.8, N58.1–N58.4 and decisions DL1/DL2 from /home/elias/dev/justsearch/tmp/verify/agreed/area5.md lines 316–378",
          "choice": "S58.3 and DL2 require independent index membership to separate confirmed absence from unreadable content; preservation fixtures must encode the independent absence fact without changing their existing HTTP or activity assertions.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Every registered route retains its declared user-input semantics: observations cannot create user activity, and deliberate operations retain their activity behavior.",
      "whySurvives": "The real-router iteration and activity-version equality assertion remain intact. The new observation route is added to the explicit observation inventory and receives its valid empty docIds body. No pre-existing path, request body, classification, assertion, or selection is changed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task WP5.8 request",
          "delegationScope": "Area-5 designs and settled WP5.8 scenarios S58.1–S58.8, N58.1–N58.4 and decisions DL1/DL2 from /home/elias/dev/justsearch/tmp/verify/agreed/area5.md lines 316–378",
          "choice": "S58.6: the bounded source-membership check is not user activity. POST /api/documents/presence is an unattended observation, with no change to any existing route classification.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/SchemaControllerTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "The served schema inventory is exactly the named expected schemas, and every declared route schema is served as JSON.",
      "whySurvives": "Every prior named schema assertion and the nonexistent-schema rejection remain. The new document-presence-response.v1.json name is explicitly required and the exact count increases from 21 to 22 solely for that one added schema. The loop that fetches every declared route schema is unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task WP5.8 request",
          "delegationScope": "Area-5 designs and settled WP5.8 scenarios S58.1–S58.8, N58.1–N58.4 and decisions DL1/DL2 from /home/elias/dev/justsearch/tmp/verify/agreed/area5.md lines 316–378",
          "choice": "N58.4 and S58.6 require the new bounded membership observation route to be fully registered, including its served response schema.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a17f4c-65c1-73f3-9ace-10e79b97c743",
  "verdict": "accept",
  "digest": "sha256:7030ee6e114bbd5a3a52128955ea1705fa36f715f13a2a38af32627d2627e0c0",
  "note": "Codex recheck-1 (separate session) on eea6f7cb96: focus repair before 4 fail / after 16 pass, merge integrity, full checks; accepts sha256:7030ee6e…e0c0"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1801a-b4af-7d13-93b9-312a0b0adda8",
  "verdict": "accept",
  "digest": "sha256:2cf46934f711c57ad1d76c22e04fb97b9b029fd3b47b4e6792d6ecbcba2e4f19",
  "note": "Codex acceptor-3 (separate session) on 663492faa0: six entries satisfy their classes; CitationsPanel New/repaired fails before by assertion, 13 pass after; 123 frontend / 70 JVM tests pass"
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "01a1801a-a8dd-7fe2-a698-ea88c9e37fac",
  "verdict": "reject",
  "digest": "sha256:2cf46934f711c57ad1d76c22e04fb97b9b029fd3b47b4e6792d6ecbcba2e4f19",
  "note": "Codex auditor-3: the revised accessibility oracle still rejects valid names/descriptions and accepts an overridden description; the other five entries satisfy their classes"
}
```
