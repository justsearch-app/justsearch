# C2-4 effect identity correction

Implemented 2026-09-13 from8508bc5d0. The independent reader review found that
arbitrary Effect ids could hide a later operation:<key> in the globally deduplicated
ledger. Ingress now reserves fe-effect and rejects server namespaces before publication.
Follow-up review identified fresh-client local counters aliasing at1. New entries
persist a UUIDv4 ledgerId in the existing frontend journal; local numeric ids retain
undo/causation ownership. Legacy entries keep their former numeric wire id on restore.
This avoids duplicate historical entries on upgrade, without claiming to repair old
clients' preexisting collisions. [Decision](C2-4-plan.md#client-effect-identity-boundary).

Negative1163 fails both namespace regressions before the server fix. Negative1169
fails the two-fresh-clients identity assertion before UUID assignment. Final1172
passes33 represented cases:19 UI and2 system integration execute;12 observability
reuse successful1164 inputs. Zero failures/errors/skips; PMD and integration compilation
pass. The actual isolated-Engine integration fixture uses a valid legacy id and
retains its idempotency assertion. Focused frontend1170 passes90 cases; full1171
passes6510 and fails one unrelated EnvelopeStream watchdog real-timer assertion.
Unchanged isolated watchdog1174 passes24; full rerun1175 passes all6511/484 files.
The first failure is retained, not hidden; its timing diagnosis remains a separate item.
Typecheck passes. All27 UI gates pass1174; affected UI-shot analysis1171 selects no
visual steps for either changed production file. Docs regeneration and all linked
canonical checks pass1174. UI gate runner emits its existing Node DEP0190 advisory.

Managed live1173 passes14 HTTP exchanges: reject forged operation identity; accept
and deduplicate a UUID effect; record an accepted failed operation; read its history,
outcome and ledger identity; controlled restart; same keyed retry/outcome and one
operation ledger entry. Both managed stops confirm ports closed. This is a dev-mode
HTTP/restart proof, not production-token enforcement, physical crash, real-model
behavior, or SSE proof. Numeric compatibility was separately exercised live1165.

Independent reviewer c2_reader_review rechecks the final source overlay and reports
no residual actionable identity finding: mint/persistence/fallback in effects/index.ts,
all three ActionLedgerClient projections, ingress-before-broadcast and integration
fixture. It ran no builds. Root owns1172/1173/1175 results. The manifest binds tested
source bytes to the unchanged files committed with this record; commands were run
on the dirty overlay, not after the final commit, so no post-commit execution is claimed.

[Commands, source hashes, counts and artifact inventory](effect-identity-verification.json)
point to accessible raw evidence in the lane worktree tmp. Retain until lane acceptance
plus30 days; export before worktree release. Completion consumer, atomic SSE,
coherent full build, installed-v5 and hosted proof remain required. No stage closure.
