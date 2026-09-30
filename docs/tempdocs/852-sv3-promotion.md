---
number: 852
title: The window cutover — promoting Search v3 to the one interaction surface
status: "IN PROGRESS — S0 (merged, #493); S1 (merged, #495); S2 (merged, #503); S3 (merged, #505). S2 and S3 both CONFIRMED LIVE 2026-08-19 (see each slice's live-round section); S4 PARTIAL (this PR — the Q1-independent half); S4's remainder + S5-S11 pending"
created: 2026-08-19
updated: 2026-08-19
scope-of-this-file: S0, S1, S2, S3 and S4-partial. The full program charter (target end state, the parity ledger,
  the slice DAG S1-S11, the open questions) lives with the orchestrator and lands here as the
  program's later slices land. This file exists so each slice's code has its design of record
  in-repo rather than only in a PR body.
forcing-function: "`check-window-cutover` (shipped with 851) WARNs until 2026-11-30 (moved from 2026-09-30 by owner decision 2026-09-30; see 851) and FAILS after, keyed on (a) `core.search-v3-surface` audience USER in CorePlugin.ts and (b) the `governance/window-cutover.done` marker the program's final slice writes."
related: "847 (citation correctness — S1-S3 shipped in #488; 852-S1 is sequenced strictly behind 847-S3, which owns `Sv3Turn.recordId` and the identity-keyed merge), 848 (reasoning parity), 846 (markdown substrate), 849 (evidence reader), 822 (the Search v3 window itself: Phases F1-F10), 610 (the context set), 513 (stable message ids + branch), 629/734 (the conversation-store lock)"
---

## Status

| Slice | What | State |
|---|---|---|
| **S0** | FE↔Java surface-parity leg on `check-surface-composition` | **implemented (merged, #493)** |
| **S1** | Turn identity + the `/history` companion load. No UI. | **implemented (merged, #495)** |
| **S2** | The tempdoc-610 context set (floor / clear / compact / summary-edit / message-exclude) | **implemented (merged, #503) · CONFIRMED LIVE 2026-08-19** |
| **S3** | Branch + version pager + edit/retry/resend + cascade-aware delete | **implemented (merged, #505) · CONFIRMED LIVE 2026-08-19** |
| **S4 (partial)** | Composer MODE control + light-theme wiring (ledger 14) + `jf-control` adoption (ledger 11) | **implemented (this PR)** |
| S4 (rest) | The extract tier (ledger 2) + `deriveAffordance` / intent-tier vocabulary adoption | pending — **Q1-gated** |
| S5-S7 | Retrieve tier, flip prerequisites | pending |
| S8-S11 | The flip, the sweep, the marker, the renames | pending |

S0's second half — recording that record-attribute hydration lives in
`modules/ui-web/src/shell-v0/components/chat/` (adopting 847-S1's location) — is a decision
addressed to the 847 and 848 implementers and carries no code. It is noted here and not
duplicated as an entry above.

## S0 — the FE↔Java surface-parity leg

### Why this exists

Three facts, each fine alone, compose into one specific hole:

1. The `interaction-surface` gate parses `new Surface(...)` declarations out of
   `modules/app-observability/src/main/java/io/justsearch/app/observability/surface/CoreSurfaceCatalog.java`
   **only** (`governance/interaction-surfaces.v1.json` `scan`). Its FE leg scans for a second
   `registerViewFactory` mount, and its `feMirror` is `coreInteractionShapes.ts` — a *shape*
   mirror, not a surface/audience mirror.
2. `check-window-cutover.mjs` keys "the promotion is complete" on
   `registeredAudience(CorePlugin.ts)` plus the marker file (`:79-88`, `:129-130`) — i.e. it reads
   **only** the TypeScript side.
3. `check-surface-composition.mjs` already read *both* files, but only to resolve dangling
   member refs against the merged id set. Its own comment recorded the limit: the RAIL set was
   "Java-only (the only placement this static gate authoritatively knows)".

Composed: **an implementer who flips `audience` in `CorePlugin.ts` alone ships two USER/RAIL
interaction windows, passes every gate, and satisfies the 2026-09-30 forcing function.** The gate
meant to prevent the outcome cannot see the FE registration; the gate enforcing the deadline reads
nothing else. Neither is wrong on its own.

`core.search-v3-surface` has **no entry in `CoreSurfaceCatalog.java` at all** today, so the
`interaction-surface` gate is currently blind to search-v3 on every leg — it is not "allowing" a
second window, it cannot see one. The safety S8 (the flip) depends on is created *by* S8 unless
something arms it earlier. That is what S0 is.

### What shipped

A second leg on `scripts/ci/check-surface-composition.mjs` — a leg on plumbing the gate already
had, not a new gate:

> Any surface id declared in **both** `CorePlugin.ts` and `CoreSurfaceCatalog.java` must agree on
> `audience` and `placement`. A disagreement fails the build.

- **Comment-stripped first**, both sources, using the same technique and for the same reason as
  `check-window-cutover.mjs` `stripComments` — a commented-out or merely discussed registration is
  not a declaration. (This also hardened leg 1's Java parse, which previously matched
  `Placement.*` inside comments; leg 1's result on `main` is byte-identical either way —
  `4 host(s), 7 member(s)` before and after.)
- **One-sided surfaces are out of scope.** FE-only (`core.search-v3-surface`,
  `core.memory-surface`, `core.command-palette`) and Java-only (`core.ask-surface`,
  `core.system-surface`, `core.free-chat-surface`, `core.extract-surface`) ids are not compared:
  there is no second declaration to disagree with. 14 of the 21 ids are compared today.
- **The failure message names both files, both values, and the rationale**, because a gate whose
  message does not explain the hazard gets "fixed" by editing whichever side is nearer.
- The script was refactored to export pure functions (`parseJavaSurfaces`,
  `parseCorePluginSurfaces`, `checkComposition`, `checkParity`, `run`) so both legs are testable
  without a repo on disk. Leg 1's rules and messages are unchanged.

### The pre-existing drift this leg found

Arming the leg immediately surfaced two live disagreements that predate it:

| Surface | `CorePlugin.ts` | `CoreSurfaceCatalog.java` |
|---|---|---|
| `core.health-surface` | `audience: 'OPERATOR'` | `Audience.USER` |
| `core.activity-surface` | `audience: 'OPERATOR'` | `Audience.USER` |

The FE re-declaration wins in the shell (`CorePlugin.ts:45-53` states this for `core.help-surface`
in as many words: "this FE re-declaration would otherwise override the wire's placement"), so the
wire catalog is currently wrong about who can see Health and Activity. `core.logs-surface`, the
third System-hub member, agrees at OPERATOR on both sides — which is what makes these two look
like drift rather than a rule.

**Which side is right is a product decision about who sees Health and Activity, not a gate fix**,
and it is outside S0's scope. So they are recorded rather than silently tolerated, in the script's
`KNOWN_PARITY_DRIFT` ledger, with three properties that keep it from becoming an escape hatch:

1. Each entry **pins the exact pair of values**. The pinned pair warns.
2. **Any change to that pair fails** — settled (delete the stale entry) or drifted further (that is
   new drift, and the ledger does not exempt it).
3. **An entry naming a pair no longer declared in both files fails** as residue.

`core.search-v3-surface` and `core.unified-chat-surface` are deliberately not in the ledger —
those are the ids S0 exists to protect. Logged to the observations inbox as a pre-existing issue.

### Mutation probe — evidence the leg bites

A gate armed slices before the thing it protects is the shape that ships inert, so the bite was
verified against the real tree, not only against fixtures. `core.search-v3-surface` cannot serve as
the probe subject (it has no Java entry), so `core.unified-chat-surface` — the shipped USER/RAIL
window, and the closest analogue of the flip — was mutated on the FE side only:

| # | Mutation (`CorePlugin.ts` only) | Result |
|---|---|---|
| 0 | none | **PASS** (exit 0) — `4 host(s), 7 member(s); 14 surface(s) … checked, 2 recorded pre-existing disagreement(s)` |
| 1 | `audience: 'USER'` → `'DEVELOPER'` | **FAIL** (exit 1) — `core.unified-chat-surface declares audience 'DEVELOPER' in …/CorePlugin.ts but 'USER' in …/CoreSurfaceCatalog.java. … a ONE-SIDED flip ships a broken or duplicated USER window while every other gate stays green …` |
| 2 | `placement: 'RAIL'` → `'DEEPLINK'` | **FAIL** (exit 1) — same message shape, on `placement` |
| 3 | reverted | **PASS** (exit 0), output identical to #0; `git diff` shows no change to `CorePlugin.ts` |

A second probe checked **test precision** rather than gate bite: replacing `stripComments` with the
identity function turned exactly the four comment-handling assertions red
(`FAIL (4 of 28)`) and nothing else — so those tests pass because stripping works, not because the
fixture happened to parse. Reverted.

### Tests

`scripts/ci/check-surface-composition.test.mjs` (new, 28 assertions, the bare-node style of
`check-window-cutover.test.mjs`): parser behaviour; agreement passes; audience mismatch fails;
placement mismatch fails; both fields disagreeing yields one failure per field; a one-sided
search-v3 promotion fails on both fields; FE-only and Java-only surfaces are not compared; four
comment cases (line-commented FE field, block-commented FE field, a commented-out whole FE
registration placed *after* the live one so it cannot pass by overwrite order, commented-out Java
field); the ledger's warn / drifted-further / settled / residue paths; and leg 1's four composition
rules plus the 578-Option-A merged-id-set resolution, so the refactor cannot silently regress them.

### Routing, and one limitation left open

The leg adds a second **subject** to this gate: `CoreSurfaceCatalog.java`. The gate's existing
wiring — the `ui-web-gates` recipe in `governance/consult-register.v1.json`, pushed by the consult
hook on any `modules/ui-web/src/**` edit — only covers the FE side, so a Java-only edit would not
have reached it.

The first attempt added `check-surface-composition` to the CLAUDE.md pre-merge row for
`CoreSurfaceCatalog.java`. **`check-always-loaded-budget` rejected it** — 31 B over CLAUDE.md's
ratchet ceiling — and its failure message names the correct remedy: migrate the addition out to
the consult-register. So the routing lives in a new `surface-catalog-parity` region
(`pathIncludes: ["CoreSurfaceCatalog.java"]`) whose recipe names both this gate and
`--gate surface-altitude`; `regionFor()` was probed against the catalog's real path and resolves
it. `governance/surface-composition.v1.json`'s description was updated to describe both legs
rather than only the first. `check-premerge-table` validates the register's script refs
(50 refs, all resolving) and `check-always-loaded-budget` is green with CLAUDE.md unchanged.

**Open:** neither this gate nor its test is wired into `.github/workflows/ci.yml` — that is the
pre-existing arrangement for this gate and is unchanged here, so the leg is enforced by the
pre-merge/consult path, not by hosted CI. Recorded rather than fixed: adding a gate to CI is a
workflow change with its own review surface, and S8 lists `check-surface-composition` explicitly
in its required-gate set regardless.

## S1 — turn identity and the two-record load

### The corrected premise

The first draft claimed `Sv3Turn` had no backend message identity. That was **refuted** during
review: a record-projected `Sv3Turn.id` already *is* the `ConversationStore` id of the turn's user
message (`FileConversationStore.enrichMessage:213-219` mints it, `loadHistory:159-165` back-fills
`idx-N` for legacy rows, `InteractionThreadController.chatTurn:260-262` uses it verbatim as the
thread-event id, `unifiedThreadProjection.ts` preserves it, `sv3-record.ts` opens each turn on it),
and after 847-S3 `Sv3Turn.recordId` carries it too. So S1 must **not** mint a second, differently
named id field — that would fork the id space in the one place this codebase has kept it single.

Three real gaps remained, and those are what shipped:

### (a) The assistant message id was discarded

`projectSv3RecordTurns` emitted `activity: agent ? turn.activity : []` — for an **ask** turn the
activity list, and with it every assistant item id, was thrown away. The rendering rule behind that
drop is sound (a one-item activity list would be a second way to draw the same paragraph), so the
fix preserves the **id**, not the list: `Building.assistantId` tracks the turn's last assistant
item and is emitted as `Sv3Turn.assistantRecordId`. Last-wins, the same rule `evidence` follows and
for the same reason — the terminal assistant message is the answer.

### (b) One accessor for a turn's two message ids

`sv3TurnMessageIds(turn) → { userMsgId, assistantMsgId }`, reusing 847-S3's `recordId` for the user
half and (a) for the assistant half. No new identity field.

**What it guarantees, and why the rule is provenance rather than a list of ids to reject.**
`GET /api/thread/{id}` interleaves two planes (`InteractionThreadController.java:66-73`): the chat
turns, which ARE conversation-store rows, and every agent run's events, projected read-time from
`AgentRunStore` and written as messages nowhere. The run plane mints its own user messages
(`${runId}:user`, `AgentRunQueryService.java:346-350`), assistant messages
(`${conversationId}:assistant:${stamp}`, `AgentInteractionMapper.java:69`), workflow node outputs
and search events — and `chatTurn` has a fallback of its own for a row with no usable id
(`${conversationId}:chat:${msg.hashCode()}`, `:260-262`). The first draft of this slice filtered
only the last of those, which is a list one entry behind the next event kind anybody adds.

So the accessor reports an id only when **both** hold:

1. **Provenance** — the record opened the turn on a `user` item (`Sv3Turn.recordOpenedByUser`), for
   the user half. The projection opens a turn on whatever arrives before any user item, and a sealed
   first line becomes a `role:"locked"` placeholder that `chatTurn` drops, so a thread can legitimately
   open on a *stored assistant* row — whose id is a perfectly good store UUID and is still not the
   user message a branch point means. Shape alone cannot see that.
2. **The store's own mints** — a UUID from `FileConversationStore.enrichMessage` (`:213-219`) or the
   `idx-N` back-fill `loadHistory` applies on read (`:159-165`). An allowlist rather than a denylist,
   so a new run-plane id family fails CLOSED (an affordance unavailable) instead of mis-targeting.

The same store test is applied while projecting, so a turn's `assistantRecordId` holds the last
**stored** assistant message rather than the last assistant item — on a turn that has both, a plain
last-wins would let the run's projected message displace the addressable one.

A **live** turn is null on both halves by construction: it carries the positional
`${sessionId}#t${n}` handle and `recordId: null` until the record speaks.

`sv3TurnByMessageId(turns, messageId)` is the companion lookup, because every `/history` field names
a MESSAGE while the window renders TURNS.

### (c) The `/history` companion load

The shipped window reads both records at adjacent lines (`UnifiedChatView.ts:2048-2049`); search-v3
read only `GET /api/thread/{id}`. `SearchV3View.refreshHistory` now calls the shared
`resumeConversation` on **session open** (the per-tab restore and a sidebar claim — not per turn:
these are properties of a conversation, and re-asking at every terminal would spend a round trip per
answer), and `applySv3History` parks the result on `Sv3Session.history` as `Sv3SessionHistory`:
`parentSessionId` · `branchPointMessageId` · `parentFirstUserMessage` · `contextFloor` ·
`contextFloorSummary` · `excludedMessageIds` · `excludedSourceIds` · `locked`.

`messages` is deliberately **not** carried: `/history` has a transcript too, and taking it would
give this window a second answer to "what happened in this conversation". The canonical record is
the one authority for that.

**The read does not claim.** `resumeConversation` claimed the app-wide active conversation as a side
effect of any successful read — right for the shipped window's open path, its only caller until now,
and wrong for a companion load: a slow read landing after the reader moved on (to another v3
conversation, to New session, or into the *other* window, which claims the same shared pointer) would
re-point the product at the conversation they walked away from. It now takes `{ claim }`, defaulting
to today's behaviour, and search-v3 passes `claim: false` — it already claims at open. Removing the
side effect beats compensating for it afterwards: a restore-the-pointer heuristic cannot tell a
cross-window claim from its own. A superseded load is still discarded.

**Nothing renders any of it yet.** S2/S3 are its consumers and are the reason it is loaded now
rather than later; S1 stays a substrate slice with no UI to audit.

**Obligation this places on S2/S3:** every one of these fields is mutable by an affordance those
slices ship — setting or clearing a floor, compacting, excluding a message or a source. Each write
must be followed by a re-load, or the window renders a floor the backend no longer holds. The
`history: null` tri-state supports that directly: a lazy re-load is the same call.

### Verification

The design's live probe was re-aimed into unit-level regression tests (`sv3-sessions.test.ts`,
`SearchV3View.record.test.ts`), because static reading settles the id space and does not settle
these two:

1. **Id-vs-index pairing across `role:"locked"` placeholders.** `/history` returns every row it read
   including the placeholder a sealed line becomes (`FileConversationStore.java:149-157`), while
   `chatTurn` returns `null` for every role that is not user/assistant (`:247-259`) — so the two
   arrays differ in length and position, and turns group messages besides. The test constructs the
   skew and asserts the id answer, naming the index answer that would look entirely plausible (the
   first turn's assistant message sits at history position 1). A merge leg asserts the record's
   evidence and message ids land on the turn bearing the matching `recordId` when the record's
   order disagrees with the local one.
2. **The `idx-N` backfill, the run plane, and the `hashCode()` fallback.** `idx-N` is a real store id
   and is reported; a run turn's `${runId}:user` + `${conversationId}:assistant:${stamp}` pair, a
   turn opened on a search event, a turn opened on a stored *assistant* row, and the `:chat:`
   fallback are each constructed and asserted `null`. **The fallback assertion's premise, recorded
   because it is what makes that half a guard rather than a live expectation:** a store-backed
   conversation cannot produce it — `enrichMessage` mints a UUID before every write, `loadHistory`
   back-fills `idx-N` on read, and a sealed line becomes `idx-N-locked`.

Every new case was confirmed to bite by mutation: reverting (a) fails 5; dropping the store-mint
allowlist fails 3; dropping the `recordOpenedByUser` condition fails the stored-assistant-row case
(the one shape alone cannot decide); letting the projection take the last assistant item regardless
of provenance fails the mixed-turn case; disabling the `refreshHistory` calls fails the arrival case;
and restoring the claiming read fails both pointer cases.

Green: `npm run typecheck` clean; `npm run test:unit:run` 419 files / 5227 tests passed. ui-web gate
set + the six kernel gates + `execution-surface` + `register-guard-resolution` pass, except reds
already red on `main` in files this PR does not touch (`check-theme-token-closure`,
`check-accent-as-text`, `strip-token-fallbacks --check`, and `check-controls-a11y`'s
`UnifiedChatView.ts` title-on-disabled finding). One unrelated flake was seen once in a parallel
suite run (`EnvelopeStream.test.ts`'s 70 ms watchdog, green in isolation and on the final run) and
is logged to the observations inbox.

### Deviation from the design's letter

The design put the `resumeConversation` call inside `refreshRecord()`. `refreshRecord` also runs at
every turn terminal (`onDone`, `concludeRun`), which would have made the companion load per-turn;
it is wired to the two session-open sites instead, which is what "lazy, on session open" asks for.

## S2 — the tempdoc-610 context set in Search v3

### What the port is, and what it is not

Tempdoc 610 gave the shipped window five acts over what the NEXT prompt contains: set the
effective-context floor, clear it, compact everything above it into a summary, edit that summary,
and hide a single message from the prompt while it stays in the transcript. All five are live
backend endpoints (`ChatController.java:287-531`) behind five shared store functions
(`state/conversationListStore.ts:575-681`), and search-v3 imported none of them.

So this slice adds **no backend, no request and no second store**: it is the window's affordances
over authorities that already exist, plus the two readouts 610 pairs with them (the context meter
and the shared context inspector). What is genuinely new is the derivation between them —
`views/search-v3/sv3-context.ts`, the pure half — because A's unit is a MESSAGE and this window's
unit is a TURN.

### The unit change, and the two rules it inherits from S1

- **The floor names the turn's QUESTION** (`floorMessageId = messageIds[0]`), so "reset context to
  here" keeps the turn the reader pointed at *in* context. A turn the record opened on a stored
  assistant row falls back to that row, which is still a store message the endpoint accepts.
- **An exclusion hides BOTH of a turn's messages.** Leaving the question in the prompt while
  dropping the answer would send the model a question it never answered. A turn counts as hidden
  when *either* is excluded — a half-excluded turn is one the prompt is missing part of, and
  reporting it as fully present would be the more comfortable lie.

Both run on S1's accessor and on nothing else:

1. **Ids come from `sv3TurnMessageIds` only.** A turn reports an id only when the conversation store
   minted it, so an affordance can never be pointed at an agent-run-plane id (`${runId}:user`,
   `${conversationId}:assistant:${stamp}`) that `{floorMessageId}` and
   `POST …/messages/{id}/exclude` would reject. **A turn that names no store message renders no ⋯
   trigger at all** — the honest null, not a control that fails when pressed. That is agent turns
   and live turns, and it is asserted for both.
2. **Message → turn resolution is by id** (`sv3TurnByMessageId`), never by position: `/history`
   counts rows `/api/thread` never emits, so a floor resolved by index would attach to a
   neighbouring turn and look entirely plausible on screen.

### The F4 obligation, discharged

S1 recorded it: *"every one of these fields is mutable by an affordance S2/S3 ship — each write must
be followed by a re-load, or the window renders a floor the backend no longer holds."*

Every act routes through one tail (`settleContextWrite`): **re-load `/history`, then report a
refusal**. The reload is UNCONDITIONAL — a partially-applied bulk exclusion is exactly when the
window's own idea of the ledger is least trustworthy — and nothing is patched locally, so the
backend stays the single authority for the floor, the summary and the ledger. Seven of the fifteen
integration cases fail when that one `await` is removed (mutation-probed).

### Where the affordances live

| Surface | What it carries |
|---|---|
| The turn's tail, in the transcript | A ⋯ `jf-control` opening the product's ONE context-menu primitive, with the four per-turn acts. It **rests** rather than hiding until hover: L14 allows exactly one thing in the row to yield (the copy action, 818 §6b), and hiding otherwise-unreachable capabilities behind a pointer would make them discoverable by accident only. |
| The floor divider, above the floor turn | The two forms of the boundary (rewind vs compacted), the summary disclosure, its editor, and Restore. |
| `jf-sv3-context-bar`, between the transcript and the composer | The occupancy meter (the shared `projectContextHorizon`, so the product's two context meters cannot disagree about what 80% means) and the hidden-turn aggregate with its bulk undo. It renders NOTHING when the conversation reported no occupancy and hides no turn. |
| The shell's context inspector | Opened from the meter; the projection drops out-of-context and hidden turns, so what it lists and what the transcript dims are one derivation. |

Every control this slice adds is born on **`jf-control`** (parity-ledger row 11) rather than adding
more hand-rolled buttons for S6 to convert.

### The concurrency defect independent review caught (F1)

The exclusion acts first fired their toggles through `Promise.all`. The endpoint behind them is a
**read-modify-write over one shared document with no lock**: `FileConversationStore.toggleStringInMeta`
reads `meta.json`, adds or removes the id, and calls `writeMetaAtomic` (`:503-527`) — the WRITE is
atomic, the SEQUENCE is not, and each POST is served on its own HTTP thread. Two toggles in flight
together therefore race on one snapshot and the loser's id is dropped.

What that costs is specific and invisible: a turn's two ids go out together, one lands, and the turn
is left **half excluded** — the next prompt carries a question whose answer was dropped, while the
transcript dims the turn either way (`hasExcluded` is true on one id as on two). Both sites now
serialize through one `excludeInTurn` helper, which is what the shipped window already does
(`views/UnifiedChatView.ts:1767-1776`).

**This is `green-masked-destructive`, and it shaped the test.** The fake backend cannot lose an
update, so the original green was environmental — and an ORDER assertion would not have caught it
either, since `Promise.all` over `ids.map(...)` issues in array order too. The fake now measures
what actually matters: it counts exclusion writes in flight and records the high-water mark, so
`peakConcurrentExcludes === 1` witnesses the serialization. Reverting to `Promise.all` fails 2 cases
with `expected 2 to be 1`.

### Two more from the same review

- **F2 — the trigger's gate was one scope too narrow.** The menu's entries are withheld window-wide
  while a prompt is in flight, but the trigger was gated on the TURN's own status — so during any
  stream every settled turn rendered a ⋯ that opened nothing: "a control that fails when pressed",
  the exact alternative this slice's honest-null rule refuses. The window's `streaming` flag is now
  handed to the region and gates the trigger too.
- **F3 — `role="separator"` was pruning its own controls.** That role is children-presentational: a
  conforming screen reader hides everything inside the node carrying it, which on the divider's
  control row meant Restore — the only way back from a floor — was unreachable to assistive tech,
  with every gate green (no gate models role inheritance). The role now sits on an empty hairline
  and the control row is a labelled `role="group"`. Asserted, because nothing else can see it.

### One defect the critical-analysis pass caught

The summary editor first closed on the SAVE PRESS. That discards the reader's correction exactly
when it is hardest to reproduce — the write was refused. It now answers to the store: the editor
closes when the saved text comes back from `/history`, clears when the summary changes underneath it
(another conversation, a re-compaction, a restore), and otherwise stays open with the correction in
it while the toast names the act that failed. Asserted, and mutation-probed.

### Deviations from the design's letter

1. **Source-exclusion is not here.** The parity ledger puts the source-exclusion ledger +
   `SourcesPane` in **row 8, slice S6**; S2's row is row 7 — the five message-side functions.
   `excludedSourceIds` is loaded (S1) and rendered by S6.
2. **The meter is stored per CONVERSATION** (`Sv3Session.contextUsage`), not per window as the
   shipped window keeps it. A window-level reading would follow the reader into a conversation whose
   prompt it never measured. It is fed by the `done` terminal, which this window was already
   receiving and discarding (`sv3-ask.ts` registered no `onDone` handler at all).

### Named limits and next-slice items (from independent review, not implemented here)

Recorded the way S1 recorded its `resumeConversation` limit — as facts about what this slice does
NOT do, so the next one inherits them rather than rediscovers them.

- **F4 — the turn projection is LOSSY, and the bar counts turns.** `projectSv3TurnContexts` walks
  the turns on screen, so an excluded message id that maps to no rendered turn (a message the record
  does not project, a turn scrolled out of a truncated record) is invisible to both the hidden-turn
  count and to **Include all** — which therefore cannot un-hide it. The label says "turns" and means
  it, so nothing on screen is false; what is missing is any way to see or reach an orphaned
  exclusion. A `/history`-side count (ledger length vs resolved turns) would surface the gap.
- **F5 — `refreshHistory` has no request-ordering guard.** It is guarded on the SESSION
  (`activeId !== conversationId` discards a superseded load) but not on ORDER, so two reloads of the
  SAME conversation can land out of order and leave the older answer standing. The record half
  already has the shape of the fix — `refreshRecord`'s `AbortController` — and the next slice should
  give the companion load the same.
- **F6 — an unresolvable floor renders nothing while the backend still truncates.** When
  `history.contextFloor` names a message no turn on screen carries, the divider does not render and
  no turn dims — yet the backend keeps truncating the prompt at it. Consider rendering the bar line
  plus Restore whenever a floor is SET but unresolved, so the state is at least visible and
  reversible.
- **F8-F10 (nits, logged not fixed):** the four menu entries share one `history` icon and could be
  differentiated; the rest are logged to the observations inbox.

### The live leg (folds into the program's next stack session, as CONFIRMATION)

This slice is FE-only and verified against a fake backend, so the live pass is confirmation of a
contract already exercised, not discovery. Procedure, on the next dev-stack window:

1. Open a conversation with at least two settled ask turns.
2. **Set a floor** on the second turn, then reload the window: the divider is above the same turn
   and `GET /api/chat/conversations/{id}/history` reports `contextFloor` = that turn's USER message.
3. **Compact** to the same turn: `contextFloorSummary` is present in `/history` and the divider says
   "compacted", not "reset".
4. **Exclude a turn**, then reload: `excludedMessageIds` holds **BOTH** of that turn's ids, and
   `meta.json` on disk holds both — the F1 assertion at the layer the unit test cannot reach.
5. **Include all** from the bar: the ledger empties, and the dimming clears on reload.

### Verification

- `npm run typecheck` clean; `npm run test:unit:run` **427 files / 5378 tests** pass.
- The ui-web gate set + the six kernel gates + `execution-surface` + `register-guard-resolution`
  pass, except reds already red on `main` in files this PR does not touch
  (`check-theme-token-closure` + `strip-token-fallbacks --check` on `RecentsMenu.ts` /
  `ActionLedgerView.ts`, `check-accent-as-text` on `ActionLedgerView.ts` — the first two named in
  `expected-state.v1.json` — and `check-controls-a11y`'s `UnifiedChatView.ts:2137` finding).
  `gen-component-vocabulary` was regenerated for the new `jf-sv3-context-bar` tag.
- **Mutation probes** (each reverted): dropping the post-write `/history` reload fails 7 cases;
  removing the store-id gate on the ⋯ trigger fails the agent-turn case; excluding only one of a
  turn's two messages fails 2; flooring on the answer instead of the question fails 3; letting the
  inspector ignore the floor fails 2; dropping the `done`-payload occupancy read fails 2; closing
  the summary editor on the press fails the refused-edit case.
- New tests: `sv3-context.test.ts` (13 cases, the pure derivation) and
  `SearchV3View.context.test.ts` (15 cases, the five acts round-tripping against a stateful fake
  backend holding the 610 endpoints). Every case fails before this slice — the window imported none
  of the five store functions and rendered no context affordance.
- ~~**Not verified live.**~~ **Verified live 2026-08-19** — see the live round below. The S2 gate
  row is discharged.

### The live round, run 2026-08-19 — S2 CONFIRMED

Dev stack from this worktree's own dist (`distFrom`), compact chat profile, 4096-token context,
corpus `docs/{explanation,reference,how-to}` = 111 documents / 1149 chunks, enrichment 100% on all
four stages. The five steps were driven through the real window in Playwright — every act via the
turn's ⋯ and the context bar, never the endpoint — and each reload was a full `page.goto` plus a
re-entry from the sidebar, so nothing survived in memory.

Conversation `uc-9e69e1dc-e2b2-4f89-a2a6-d32d2079eddd`, three settled ask turns, message ids in
transcript order `[06f4bb38(u), b6732866(a), 73a4b1a9(u), 1768c116(a), 54e82fdb(u), 3b5fe1b4(a)]`.

| Step | Expected | Observed |
|---|---|---|
| 2. Floor on the second turn, reload | divider above that turn; `contextFloor` = its USER message | divider read `Context reset — the assistant no longer sees the turns above this line`; `/history.contextFloor` = **`73a4b1a9`** = the second turn's USER id, not its answer |
| 3. Compact to the same turn | `contextFloorSummary` present; divider says "compacted" | divider read `Context compacted — …summarized for the assistant`; `contextFloorSummary` a real generated summary; `contextFloor` unchanged |
| 4. Exclude the third turn, reload | `excludedMessageIds` holds **BOTH** ids, in `/history` **and** in `meta.json` | `["54e82fdb", "3b5fe1b4"]` — the turn's user AND assistant id — identical in `/history` and in `…/index/conversations/uc-9e69e1dc…/meta.json` on disk; bar read `1 turn hidden from context` |
| 5. Include all from the bar | ledger empties, dimming clears on reload | `excludedMessageIds` `[]`; the hidden-count badge gone after reload |

**Step 4 is the F1 assertion the unit test cannot reach, and it holds at the layer of truth.** The
concurrency defect independent review caught was that a turn's two messages could be written as one;
`meta.json` on disk carries both ids, so the serialization fix is confirmed where it actually
matters rather than against a fake backend that could have agreed for the wrong reason.

**One harness-side false pass worth recording, because it nearly became evidence.** The first run
parsed the conversation list with `convs.get("conversations", …)`; the endpoint's key is
`sessions`, so the id silently resolved to `None`, every `/history` field came back `null`, and the
output *looked* like four clean negatives sitting beside four passing UI assertions. The UI half was
real; the API half was vacuous. Fixed by keying `convs["sessions"]` and asserting the id is
non-null — a `KeyError` where there had been a silent `null`. Generalisable: a probe whose failure
mode is a plausible-looking `null` must assert its own preconditions, or it will confirm whatever
you hoped.

## S3 — branch, the version pager, edit / retry, and cascade-aware delete

### The premise, and the one thing it turns on

Parity-ledger rows 5 and 6 read as two capabilities. They are ONE backend act with three callers:
`POST …/branch?fromMsgId=` (`conversationListStore.branchConversation`, `ChatController.java:612-637`).
There is no edit endpoint and no retry endpoint — the shipped window builds both from *branch, then
re-dispatch* (`views/UnifiedChatView.ts:1471-1497`, `branchAndResend`), and inventing an endpoint
here would have been inventing a contract the backend does not hold.

What that reduces the slice to is ARITHMETIC: which message id each of the three acts names. The
answer is not one id per turn, and this is the whole defect surface:

- **Branch to new thread** forks at the turn's **own answer** — the new thread inherits the exchange
  the reader is standing in and continues past it (`branchHere`, `:5610-5619`).
- **Edit** and **Retry** fork at the **previous turn's answer**, so the re-sent question is the FIRST
  divergent message (`:1471-1487`). At the head of the conversation there is no preceding message and
  the id is `EMPTY_PREFIX_SENTINEL` — a real id the backend understands, not a null. (`ConversationStore.java:46`
  pins that sentinel's FE producer to "the FE producer (TS: `UnifiedChatView` edit/retry of the first
  turn)"; §3.3.b asked for that pointer to be repointed at whichever v3 site owns the act, and this is
  that site.)

Both are store-minted ids on the same turn and `?fromMsgId=` accepts either. Forking an edit at the
turn's own answer produces a branch that reads as *the old answer, then the new question*: plausible
on screen, and wrong. `sv3-branch.ts` carries both, named for what they do (`branchFromId` /
`forkKey`), and the pure test asserts them against each other on one turn.

### Where the ids come from, and what a turn that has none gets

`sv3TurnMessageIds` (S1) and nowhere else — which is what makes the honest null hold one level deeper
than S2 needed it. S2's acts need a turn's OWN ids; an edit needs the id of the turn BEFORE it. So a
settled, store-minted turn sitting after an agent turn can be branched from and **cannot be edited**:
its own ids are fine, the message it would fork before belongs to the run plane. Two acts, two gates,
on the two ids they actually use; the alternative is one "is this turn controllable" verdict that
would offer an Edit which 404s when pressed.

Inherited turns are refused entirely (`sv3FirstOwnTurnIndex`) — the reference's rule
(`canTurnControl`): those messages belong to the parent, and re-forking them from the child forks the
wrong conversation. A branch point **no turn on screen carries** resolves to "nothing here is
inherited" rather than "everything is": the record this window renders is not obliged to include the
parent's prefix, and reading a failed lookup as inheritance would withhold every control on a
conversation that is entirely its own.

### The version pager

`siblingSessionsAt` is a pure read over the already-loaded conversation list, so the pager costs no
endpoint. It needs the store's own `parentSessionId` / `branchPointMessageId`, which
`mergeStoreConversations` deliberately does not carry onto `Sv3Session` — so the window now holds the
store's ROWS as well as its projection (`SearchV3View.conversations`), rather than copying two
pointers onto a second shape that would then have to be kept in step.

A fork is visible from both ends and the projection reports both (the reference's Case A / Case B):
from the BASE its continuation is version 1, and from the BRANCH its first own turn is version N. One
consequence is worth stating because it looks like a bug and is not: a branch created and not yet
asked in has **no own turn**, so its divergence point is not on screen and neither is a pager. The
reference window ends in exactly the same place and for the same reason — both gate the pager on a
turn the conversation OWNS. Pressing Next from the base and landing on a pager-less branch is
therefore parity, not a regression; if it is wrong, it is wrong in both windows.

Paging is an OPEN, not a write: the target is a real conversation and it routes through the same
claim-and-load path a sidebar click takes. An end of the pager is `jf-control`'s typed
**unavailable-with-a-reason**, not a silently inert button.

### Order is the whole of edit

`branchInto` opens and CLAIMS the branch **before** re-dispatching, because `runAsk` appends to
whatever conversation is active. Sending first appends the rewrite to the conversation the reader was
replacing — the exact failure the act exists to avoid, and invisible on screen. Mutation-probed:
swapping those two lines fails 4 cases.

A refused branch changes nothing and says so. There is no local fallback; a window that "continued
anyway" in the current conversation would do the one thing the reader did not ask for.

### The editor answers to the transcript

S2's own defect, headed off before it could recur: the question editor does not close on the SEND
press. It closes when the turn it was editing LEAVES the transcript — which is what an accepted edit
does, since the branch forks from before that turn — so a refused branch leaves the editor open with
the reader's rewrite in it while the toast names the act that failed. Asserted; the same rule closes
it when the reader claims another conversation.

### Cascade-aware delete (§3.3.b's rider)

Slices 515/516 made orphaning a branch impossible: the store REFUSES to delete a conversation others
were forked from, with `409` + the children's ids. This window called the plain `deleteConversation`,
which reports that refusal as a bare `false` — so **the row vanished from the list and the
conversation stayed on disk, with nothing said**. That is not a missing feature; it is a delete that
silently does not delete, which is why the design called it a correctness behavior rather than chrome.

It now routes through `deleteConversationWithCascade` and NAMES the branches in the confirm — ids from
the refusal, labels from the list the window already holds. Three outcomes, and only one is silent:

| Outcome | What the reader gets |
|---|---|
| Declined | Nothing deleted, no toast — they were asked and said no |
| Consented, cascade landed | The children's rows dropped with the parent's |
| Refused outright, or cascade broke halfway | `DELETE_FAILED`, and a re-list puts the row back |

The store function's return cannot tell the last two from the first (`{ok:false, childIds}` covers two
of them), so the reader's own answer is tracked at the call site instead of inferred from it.

### F5, discharged

S2 recorded it: *"`refreshHistory` has no request-ordering guard… the next slice should give the
companion load the same [`AbortController`]."* Branch and edit multiply the reload rate — every act is
a write followed by a reload, and an edit is two acts in a row — so it is discharged here.
`refreshHistory` now supersedes its predecessor exactly as `refreshRecord` does, and both are aborted
on disconnect. The SESSION guard S1 shipped stays: the two catch different staleness and neither
subsumes the other.

**Honest limit:** `resumeConversation` accepts no signal, so the superseded REQUEST is not cancelled —
only its answer is discarded. That is what F5 named (ordering); the in-flight request costs a round
trip and changes nothing.

The test needed the fake backend to be able to lose a race it cannot actually lose, so `/history` now
**snapshots at serve time** and one read can be held past another. Without the snapshot a held read
would "catch up" to the writes that landed while it waited and the stale answer could not exist —
`green-masked-destructive`, caught while writing the case rather than after it passed.

### Deviations from the design's letter

1. **`raiseBudget` is not here.** The brief flagged it as possibly S3's; the ledger places it in **S6**
   (§3.3.b's row, alongside `jf-control` adoption). Left there.
2. **Retry and Branch live in the ⋯; Edit renders inline** — the reference's §13.1 split ("Edit is the
   user turn's defining action and renders INLINE on the turn"), not S2's everything-in-the-menu shape.
   One ⋯ per turn carries both sets: they are two derivations because they are gated on different ids,
   but a second overflow beside the first would be a second place to look for "what can I do with this
   turn".

### Named limits and next-slice items

- **F11 — the pager is invisible on a fresh branch** (above). Parity with the reference, recorded
  because it will read as a bug to anyone who has not read this section.
- **F12 — the pager is bounded by the conversation LIST, and that is an honesty limit before it is a
  perf one.** `siblingSessionsAt` reads `state.conversations`, which `loadConversations` fetches as
  `?limit=20` — so a fork whose sibling is not among the 20 most recently active conversations is
  **invisible**: the pager silently under-counts, or does not render at all, and nothing on screen
  says a version was omitted. A reader with an older fork is told there is one version when there are
  two. (The perf note is the lesser half: the projection is O(turns × conversations) per render, fine
  at 20 and worth a memo if the limit grows.) The fix is a count the BACKEND reports, the same shape
  S2's F4 needs for orphaned exclusions — both are "the window can only count what it happened to
  load".
- **F13 — the cascade prompt names branches by label, and a fresh branch's label is its parent's
  opening question** until it is renamed or asked in — so several branches of one conversation can
  list identically. What is deleted are the ids, and those are right; only the naming is ambiguous.
- **F14 — the cascade deletes ONE level and refuses deeper lineages.** `deleteConversationWithCascade`
  recurses into each child *without* the consent callback, so a child that has children of its own
  answers `409`, the cascade aborts, and nothing is deleted. That is the safe behaviour and it is
  kept; what this slice fixed is the COPY, which used to promise "those branches too". A reader who
  actually wants a three-level delete must currently remove the deepest fork first.
- **F15 — the inherited turn's rendered ABSENCE is not pinned at the DOM level** (review L2). The pure
  tier asserts the derivation (`canEdit === false`, no menu entries) and the live tier asserts the
  absence for a turn whose fork point is a RUN-PLANE id, but no case mounts a BRANCH and asserts that
  its inherited turns render no edit pencil and no branch entries. The two are different refusals —
  one is "this id is not a store message", the other is "this message belongs to the parent" — and a
  regression that dropped only the second would leave the pure tier green. The gap is coverage, not
  behaviour; the honest form of the limit is that the inherited refusal is derivation-tested only.
- **F16 — "consented, then broke halfway" is untested** (review L3). The production comment in
  `SearchV3View.deleteThroughStore` explains that the local `declined` flag exists because the store
  function's return cannot separate a declined cascade from a consented one that failed partway
  (`{ok:false, childIds}` is both). The declined branch and the plain-refusal branch are both
  covered; the third — consent given, one child deleted, the next child refused — is not, so the
  flag's whole reason for existing is asserted at two of its three corners. Reaching it needs a fake
  that refuses a SPECIFIC child mid-cascade.
- **S2's F4 and F6 are untouched.** They are context-set limits, not branch limits, and nothing here
  makes either better or worse. F4 and F12 are the same shape and want the same backend-side count.

### What the independent review changed (PR #505)

Verdict: APPROVE-WITH-FIXES. The production code passed every constructed attack — the fork
arithmetic was verified against `FileConversationStore.java:113`'s INCLUSIVE prefix semantics, and
`EMPTY_PREFIX_SENTINEL` was confirmed a real backend sentinel (`ConversationStore.java:49`,
`FileConversationStore.java:99-104/:317`), retiring the live-404 risk. **Every required fix was test
coverage**, which is the interesting part: three of them were greens that could not fail.

- **M1 — no fixture exceeded two turns**, so "the previous turn's answer" and "the FIRST turn's
  answer" were the same message and mutating `turns[index - 1]` → `turns[0]` passed EVERY test. The
  one piece of arithmetic this module exists for was mutation-invisible. Three-turn fixtures added to
  both files; the mutation now fails 2 cases. `sv3FirstOwnTurnIndex` above 1 is asserted too.
- **M2 — the grandchild shape had zero coverage**, so reversing the load-bearing Case-A-before-Case-B
  precedence failed nothing. A conversation that is both a branch and a base now pins it; reversing
  the blocks fails 1 case.
- **M3 — the pager's end-of-range case asserted only that nothing moved**, which a silently-inert
  control also satisfies, while its own comment claimed the reason renders. It now asserts
  `aria-disabled` + the reason text, and that the OTHER end is available in the same render.
- Folded in: the rewrite is typed before the stream starts (so "survives the wait" is about the
  reader's text), the keyboard path is asserted to agree with the button, `toEqual([])` assertions
  gained positive anchors, `openBranch` clears `renamingId`, the edit's Ctrl/⌘+Enter path is refused
  by the same expression the button explains, and the sidebar row now leaves **only when the store
  says it left** — asking "delete this and its branches?" about a row that has already vanished
  behind the dialog was the optimistic-removal defect.

### Verification

- `npm run typecheck` clean; `npm run test:unit:run` **429 files / 5441 tests** pass, with S2's
  `SearchV3View.context.test.ts` and `sv3-context.test.ts` unmodified and green.
- The ui-web gate set + the six kernel gates pass, except reds already red on `main` in files this PR
  does not touch (`check-theme-token-closure`, `strip-token-fallbacks --check`, `check-accent-as-text`
  on `RecentsMenu.ts` / `ActionLedgerView.ts`; `check-controls-a11y` on `UnifiedChatView.ts:2137`).
  Only the first and third are in `expected-state.v1.json`; the other two are logged to the
  observations inbox.
- **Mutation probes** (each reverted): removing the `/history` order guard fails the ordering case and
  only that one; forking an edit at the turn's own answer instead of the previous one fails 9 cases
  across both new files; re-dispatching before opening the branch fails 4; **`turns[index - 1]` →
  `turns[0]` fails 2** (the review's M1 — it failed 0 before the three-turn fixtures); **reversing the
  pager's two cases fails 1** (M2 — 0 before the grandchild fixture); removing the row at the press
  instead of at the store's answer fails 1.
- New tests: `sv3-branch.test.ts` (19 cases, the pure arithmetic) and `SearchV3View.branch.test.ts`
  (18 cases, all four capabilities round-tripping against a stateful fake backend that really mints
  branches with inherited prefixes and really refuses a parent delete with 409). Every case fails
  before this slice — the window imported `branchConversation` nowhere, rendered no pager, had no
  editor, and called the non-cascade delete.
- ~~**Not verified live.**~~ **Verified live 2026-08-19** — see the live round below. The S3 gate
  row is discharged.

### The live round, run 2026-08-19 — S3 CONFIRMED

Same stack and corpus as S2's live round. All four capabilities were driven through the real window.

**Branch, and the inherited prefix.** Parent `uc-498789ab` (three settled turns), branched from
turn 1's ⋯. The store minted `uc-7c1e3980` with `parentSessionId` = the parent and
`branchPointMessageId` = `0e8e30da` — the parent's turn-1 ANSWER, which is the previous turn's
answer relative to the fork, exactly the arithmetic the M-probe pinned. The branch opened carrying
the two inherited turns verbatim and nothing else; the parent's six message ids were byte-identical
before and after, so the fork copied rather than moved.

**The version pager, from both ends — the two cases `versionsAt` distinguishes, each observed.**

- **Case B (this conversation is the BASE).** Reopening the parent, the pager renders on turn
  index 2 — the turn whose fork key is the branch point — reading **`1 / 2`**. It renders on that
  turn and no other.
- **Case A (this conversation IS a branch).** A branch shows no pager while it has only inherited
  turns, which is correct and was the first live reading: `canEdit` and `versions` both require
  `index >= firstOwn`, and a fresh branch has no own turn yet. After asking one question in the
  branch, the pager appeared on that first own turn reading **`2 / 2`**, with both direction
  controls present.

  Recorded because it briefly looked like a defect: the pager and the inline Edit are *absent by
  construction* on an inherited turn. An audit that stopped at "no pager rendered" would have filed
  a false regression against correct code.

**Edit is branch-then-resend, and the old turn is excluded.** Rewriting a question through the
inline editor produced a NEW sibling conversation whose history is `[inherited prefix…, the NEW
question]` — the replaced turn is absent from it, the parent's message ids are unchanged, and the
pager on the resulting turn reads `2 / 2`.

**Cascade-aware delete, and the 409, at both layers.** `DELETE /api/chat/conversations/{parent}`
with a live branch answers **409** `BRANCHES_PREVENT_DELETION`, body naming
`childSessionIds: ["uc-7c1e3980…"]`, and the parent is still listed afterwards. Pressing delete on
that row in the window surfaces the refusal as the consent dialog rather than swallowing it:

> Delete this conversation and its branches? · "Citation Scorer in Grounding" has 1 branch forked
> from it: • … · Deleting it deletes that branch too. A branch that has been forked again is not
> deleted — nothing is removed and this will say so. · [Cancel] [Delete all (2)]

**The optimistic-removal defect stays fixed live**: the sidebar row count was unchanged while the
dialog stood open — the row does not leave at the press. Declining removed nothing (parent and
child both still present), which is the one deliberately silent case.

## S4 (partial) — the composer mode control, the light-theme seam, and `jf-control` adoption

**What this slice is NOT.** The design splits S4 in two, and only the Q1-independent half ships
here: the composer's **tier control** (§3.2), plus two parity-ledger wiring rows (14 — light theme;
11 — `jf-control` adoption). The `deriveAffordance` / intent-tier **vocabulary** adoption waits on
Q1, because the authority's default return is `'retrieve'` (`agencyPosture.ts:99`) — the tier whose
existence Q1 decides. Nothing here touches routing or the shape vocabulary: the two tiers the
control offers are the two the window already dispatched. The **extract tier** (ledger row 2), also
part of S4's full scope, is not in this slice either.

### The mode control (ledger row 12)

Delegate has been live since Phase F2 and reachable **only** by chord —
`submit(ctrlKey||metaKey ? 'delegate' : 'ask')` (`Sv3Composer.ts:931` pre-change), with the send
button hardcoded to `'ask'` and the only statement of the routing in that button's `aria-label`. A
capability whose sole discovery path is a chord nobody was told about is not an affordance.

- **A second `composer-control` in the `.controls` row**, on the effort menu's exact grammar:
  `aria-haspopup="menu"`, `aria-expanded`, an accessible name carrying both halves ("Mode: Ask"),
  glyph + label + chevron, and a `role="menu"` of `menuitemradio` rungs with a badged default and a
  one-line description each. It is NOT in the primary-action slot (that slot early-returns exactly
  one control), and unlike `modelLabelFact()` beside it, it takes the full button treatment because
  it is a real control rather than a fact.
- **One renderer for both triggers** (`controlTrigger`). The two MENUS are still written twice,
  because their option rows differ in an attribute *name* — `data-effort` is read by the ui-shot
  harness (`scripts/jseval/jseval/ui_check.py:1539`) — and a lit template cannot parametrise that.
- **The window owns the value.** `Sv3ComposerTier` moves to `sv3-run.ts` (the module that already
  owns the send-routing vocabulary) and is re-exported from `Sv3Composer.ts`; the composer announces
  `sv3-tier-change` and the window validates it with `isSv3Tier` before keeping it, exactly as it
  does for the effort rung.
- **The keyboard is unchanged.** `Ctrl/⌘+Enter` still delegates from either mode — the modifier is
  read first, the chosen tier second — so the accelerator is not a toggle. Plain Enter and the send
  control both route at the chosen tier, which is `ask` in a window whose reader never touched the
  control.
- **Two consequences the affordance forces, both honesty-shaped.** (1) The send control's routing
  hint follows the tier (`SV3_DELEGATE_SEND_HINT`), because a send that delegates while its label
  says "Enter asks" is chrome that lies; `sv3PrimaryAction` takes an OPTIONAL `tier`, so every
  existing caller words the sentence it worded before. (2) The composer's availability NOTICE follows
  the chosen tier. Before the control, showing the ask tier's reason was right — Enter always asked.
  Now Enter can route two ways, and a delegate-mode window displaying ask's refusal would refuse a
  send for a reason nothing on screen states. The two gates stay two projections
  (`askUnavailableReason` / `delegateUnavailableReason`); only which one is DISPLAYED follows the mode.

### The light-theme seam (ledger row 14) — audit F-06

`sv3-tokens.css.ts:333` has carried a complete authored light palette behind `:host([theme='light'])`
since slice 1 and **nothing ever set the attribute**, so the window painted its dark set inside a
light app. The 2026-08-19 measured closure audit recorded that as **F-06** (the unwired sv3 light
seam; the orchestrator's brief carries its measured 1.08:1 pair). No palette values are authored
here — the set existed; the wire did not.

- `themeState.ts` gains the READ side of the decision it already owns as writer:
  `getAppearanceMode()` and `subscribeAppearanceMode()` (one `MutationObserver` on
  `documentElement`'s `data-theme`, fanned out — the "one matchMedia, fanned out" precedent the file
  already establishes). It observes the ATTRIBUTE rather than hooking `applyAppearance`, because the
  attribute has writers the settings path never goes through: the pre-paint inline script in
  `index.html`, and the OS-preference change while `system` is active.
- `SearchV3View` mirrors it onto a reflected `theme` property — read in the CONSTRUCTOR (so the
  first paint is already right), re-read on connect (a retained instance re-attaches into the mode
  the app is in now), and unsubscribed on disconnect. **One-way**: this window has no theme control
  of its own and must not grow one.
- Swept: three comments that asserted the window is dark by construction or carries no theme
  attribute (`Sv3Main.ts`, `sv3-tokens.css.ts`, `Sv3Main.imports.test.ts`).

### `jf-control` adoption (ledger row 11)

The ledger's row — "598-line primitive, 28 B call sites, 0 uses" — was written against the window as
it stood before S2/S3. **That count is stale**: S2 and S3 already put their own controls on the
primitive (24 uses in `Sv3Main.ts`, 4 in `Sv3ContextBar.ts`), including the typed-availability arm
(the version pager's ends, the edit Send while a stream is in flight). What remained hand-rolled was
28 buttons, of which this slice moves the **nine plain commands**:

| Adopted | Where |
|---|---|
| six run-gate decisions (budget raise / finalize / stop, context continue / summarize / stop) | `Sv3Main.ts` |
| locked-history remedy | `Sv3Main.ts` |
| corpus remedy · degradation remedy | `Sv3Composer.ts` |

Each keeps its `data-testid` on the host and its accessible name; the skin moves from `.x { … }` to
`jf-control.x::part(control) { … }` and each site drops its own `:focus-visible` rule, because the
primitive brings one.

**The nineteen that stay hand-rolled, and why** — a list, not an omission:

1. **Menu triggers** (mode, effort) — `aria-haspopup` + `aria-expanded`; the primitive renders a
   fixed-shape button and expresses neither.
2. **Menu rungs** (mode ×2, effort ×3) — `role="menuitemradio"` + `aria-checked`.
3. **Disclosures** (turn sources, degradation detail) — `aria-expanded` + `aria-controls`.
4. **The primary slot** (send ×2, stop, answer) — the empty-draft send is natively `disabled` with
   no `title` **by design** (a browser suppresses a tooltip on a disabled element), and the slot's
   whole discipline is one control with a derived reason in its own label.
5. **The session row** (row button + rename / pin / delete) — the row itself is the `aria-current`
   row pattern with its own dblclick/keydown; the three acts are one trio that must move together,
   and `delete` is pressed directly by an **S3** test this slice is constrained not to touch.
6. **Grips** (sidebar, pane) — drag separators driven by `pointerdown`, not command buttons.
7. **Pane backdrop** — a dismiss layer; its keyboard path is Escape (the `controls-a11y` gate's own
   category for exactly this).
8. **Topbar palette trigger** and **sidebar new/collapse** — plain always-available commands whose
   names and keyboard paths are already correct, so adoption would buy nothing; the palette trigger
   is additionally the element the palette restores focus to.

**Two findings the "wiring, not rewrite" claim understates**, both verified in source:

- **`jf-control` does not delegate focus** (no `delegatesFocus` in `components/Control.ts` or
  `primitives/JfElement.ts`), so `host.focus()` on an adopted control focuses nothing. The
  run-prompt focus in `SearchV3View.onComposerAnswer` had to be re-aimed through the control's
  shadow root. Any adopted control that is a programmatic focus target needs the same at its caller.
- **Adoption is visible to every test that presses the control.** A click on the HOST reaches no
  handler, so five press sites in three suites moved to a `press`/`pressControl` helper that goes
  through the inner button — the same helper S3's branch suite already carries. S2's and S3's suites
  are untouched and green.

`check-controls-a11y` gained no new finding from the adoption: it accepts a native button, so this
was a capability upgrade, not a gate fix — as the ledger says.

### Verification

- `npm run typecheck` clean; `npm run test:unit:run` **433 files / 5505 tests** pass, with S2's
  `SearchV3View.context.test.ts` / `sv3-context.test.ts` and S3's `SearchV3View.branch.test.ts` /
  `sv3-branch.test.ts` unmodified and green.
- The ui-web gate set + the six kernel gates pass, except reds already red on `main` in files this
  PR does not touch: `check-theme-token-closure` and `strip-token-fallbacks --check`
  (`RecentsMenu.ts`, `ActionLedgerView.ts`), `check-accent-as-text` (`ActionLedgerView.ts`), and
  `check-controls-a11y` (`UnifiedChatView.ts:2137`).
- **Mutation probes** (each applied and reverted, with the suite re-run): hardcoding the send
  button's tier back to `'ask'` fails 3 cases; making plain Enter ignore the mode fails 1; dropping
  the tier from `sv3PrimaryAction`'s input fails 1; showing the ask tier's reason regardless of mode
  fails 1; removing the menus' mutual exclusion fails 1; removing the appearance subscription fails
  2; dropping `reflect` on `theme` fails 5; renaming the light block's selector fails 2; reverting
  one adopted control to a hand-rolled button fails 2.
- New tests: `SearchV3View.tier.test.ts` (13 cases — the affordance, the equality probe between the
  control and the chord, the unchanged keyboard, the routing hint, the tier-following notice, and the
  row's one-open-menu rule), `SearchV3View.theme.test.ts` (7 — mount, both directions, a runtime OS
  flip while on Follow OS, remount, the one-way mirror, and the palette's own polarity inversion
  computed from the token graph), `SearchV3View.controls.test.ts` (7 — the adoption contract and the
  exception list). Every case fails before this slice: the mode control did not exist, the host
  carried no `theme` attribute, and the nine controls were native buttons.
- **Not verified live.** FE-only. A dev-stack pass — switch the app to light and read the window,
  send at each mode, and re-measure F-06's pair — is left for the live leg, together with a fresh
  `jseval ui-shot` of the composer in both themes.

### S4 remainder — named limits carried out of the independent review (PR #509)

Neither item is a defect this slice introduced; both are things the slice's own reading made legible,
recorded here so the S4 remainder has them rather than a future reader rediscovering them.

**R1 — the cross-tier silent refusal, and the containment it depends on.** `Sv3Composer.submit`
refuses a send whose tier is gated by a bare `return` (`Sv3Composer.ts:1025-1028`, unchanged from
`main`): the draft survives, but nothing is said. That is currently invisible in ONE direction only —
Ctrl/⌘+Enter from ask mode forces `delegate`, and the notice on screen is ask's — and it is
unreachable **today** purely because delegate's gate conditions are a strict SUBSET of ask's:
`projectAvailability` applies the connecting / not-live / starting / `capabilities.chat` gates to
every affordance (`state/availability.ts:96-133`) and adds the zero-documents gate for `documents`
alone (`:139-146`). So a window whose ask tier is open cannot have a closed delegate gate, and the
chord cannot hit the silent branch.

**The dependency is undeclared and unenforced.** The moment `agent` grows a gate of its own — an
agent-specific capability flag, a tool-permission precondition, a per-affordance budget — the chord
from ask mode becomes a silent no-op: the reader presses it, the draft stays, and the only reason on
screen is the *other* tier's (empty). Two fix shapes, either sufficient:

1. **Assert the containment** where it is relied upon — a test (or a derivation) pinning that
   `projectAvailability('agent', s)` is unavailable only when `projectAvailability('documents', s)`
   is too, so a new `agent`-only gate fails a test instead of silently changing what a chord does.
2. **Route the refusal through the one message channel** — surface the refusing tier's reason on the
   ephemeral toast the way `jf-control` does for a blocked activation (`Control.ts:452-458`), which
   makes the refusal legible regardless of which gate closed.

The second is the better end state (it removes the dependency rather than watching it); the first is
the cheaper guard and is what the containment argument above actually needs to stay true.

**R2 — `jf-control` should delegate focus.** The adoption in this slice had to teach one caller to
reach through the primitive's shadow root (`SearchV3View.onComposerAnswer`) because `Control` sets no
`delegatesFocus` (`components/Control.ts`, `primitives/JfElement.ts` — neither declares
`shadowRootOptions`). Every future adopter of a control that is a programmatic focus target inherits
that coupling. The reviewer's recommendation, adopted as an S4-remainder item: a ~3-line
`static shadowRootOptions = { ...super.shadowRootOptions, delegatesFocus: true }` (or an explicit
`focus()` override) on the primitive deletes the reach-through for every consumer at once. It is
**not** done here because it changes focus behaviour for ~30 existing `jf-control` call sites app-wide
and belongs in a change whose blast radius is the primitive, not this window.
