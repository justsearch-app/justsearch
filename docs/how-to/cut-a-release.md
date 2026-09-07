---
title: "Cut a JustSearch release"
type: how-to
status: draft
description: "The release loop (build -> clean-Sandbox whole-product verification -> fix -> converge -> finalize), the hash-consistent asset set the build produces, and the per-release index. The single durable home for how a release is cut."
---

# Cut a JustSearch release

## What this does

Describes how a JustSearch release is produced and published. It is the durable "how";
per-release *results* (the round-by-round Sandbox convergence for a given candidate) live in
that release's own working notes, and the shipped record lives in the GitHub Release. This page
is the single living surface — it does not accrete per release (that was the failure mode of the
old rolling packaging log).

> **Status.** The Sandbox verification *loop* has been exercised across many candidates.
> `build-installer.yml` has been dispatched successfully against both `main` and a worktree branch
> (2026-07-15) — dispatching it does **not** require a `v*` tag or create/update a GitHub Release
> unless the ref dispatched against is one. The first stable release, **v0.2.0**, was published on
> 2026-08-13 with Authenticode signatures (publisher **Elias Justus**). The exact published-installer
> updater qualification tracked by tempdoc 617 remains a separate, still-open evidence tier.

## The release loop

A release **candidate** is qualified before its number is finalized:

1. **Build** the installer via `build-installer.yml` (`gh workflow run build-installer.yml --ref
   main`, or any branch ref — no tag needed for a validation candidate), then `gh run download
   <run-id>` to fetch the artifact locally.
   The protected `release-signing` Environment normally admits only `main` and `v*`. For a branch
   candidate, temporarily add that exact branch to the Environment's deployment policy, verify the
   pending run's head SHA, approve the one run, and remove the temporary policy as soon as the job
   starts. Here “unsigned” means no provider-signer invocation or spend, not absence of secret access:
   approval makes the Environment's referenced Authenticode secrets available to the reviewed job,
   and the workflow also needs Environment-held updater-signing material.
   **Do not attempt a local build via `package-installer-win.ps1` on a dev machine with Windows
   Smart App Control enforcing** (`Windows Security > App & browser control > Smart App Control`).
   SAC blocks unsigned cargo build-scripts, so the Tauri/Rust build fails partway through with
   `os error 4551` — the script's own preflight catches this and refuses to start (rather than
   burning 10-30 minutes on a build that cannot succeed), and `JUSTSEARCH_SKIP_SAC_CHECK=1` only
   bypasses the *warning*, not the actual OS-level block, so it still fails later. A local build is
   viable only on a machine with SAC off; **the CI path above works regardless and is the default**
   — it has no SAC restriction and produces the identical installer. (First hit + diagnosed
   2026-07-16, tempdoc 734 round-6 prep — this was previously undocumented and each Sandbox round
   independently rediscovered it.)
   CPU-only by default; models + GPU variants land on the user machine via the in-app "Install AI"
   flow (the full model set exceeds the NSIS 32-bit limit).
2. **Verify in a clean Windows Sandbox.** Install the candidate in a fresh Sandbox and let an
   independent agent run a **whole-product** verification pass (search, chat/RAG, MCP, Install
   AI, restart cycles — not just the changed slice). The clean Sandbox avoids dev-machine model
   pollution. Every qualifying round is launched with a **charter** (`sandbox-launch.py
   --charter`): the round's purpose and each open blocker's needs-round / needs-dig
   classification, staged for the verifier and read against the round's debrief at finalize
   (tempdoc 750 Part B).
   **Every charter watch item must state what the signal looks like when the build is HEALTHY,
   not only what it looks like when broken.** A watch item that names only the broken signature
   hands the round a symptom, and symptoms are shared by working code; what distinguishes a
   defect is a *discriminator*. Worked example (round 8, 2026-07-31): the charter said the log
   line `Combined backfill: docs=N (embed=0,splade=0,chunks=0)` "repeating at high frequency …
   is the livelock; it must not appear." It appeared **143 times, six of them within 142 ms —
   and was not the livelock**: the lines carried real progress, the run terminated on its own,
   enrichment completed, and a 60-second idle window afterwards produced zero new lines. A round
   following that charter literally would have filed a false HIGH against a working build. The
   discriminator was never the signature but **non-termination** — the signature still firing
   while every coverage counter is static and ingest jobs are starved. So write each watch item
   as a pair: *healthy looks like X (bounded, terminating, counters advancing); the defect is Y
   (X's signature persisting with no progress)*, and give the round the observation that
   separates them.
   **A BROKEN criterion must name the defect *class*, not one instance of it.** Round 11
   (tempdoc 734 round-11 section): the charter's BROKEN criterion was scoped to search ("any
   bare HTTP 401 on search"). Search passed cleanly, so read literally the criterion was
   satisfied -- but the actual class it existed to catch (round-10's F7: *any shipped-UI control
   whose mutating call 401s*) had resurfaced on a different control (chat-encryption
   unlock/lock), and a round following the charter's letter rather than its intent could have
   reported the item a clean pass and moved on. Name the class the criterion is really standing
   in for ("any shipped-UI control whose mutating call 401s", not "401 on search") so a round
   generalizes past the one instance that happened to be tested last time.
   **Watch items are written for the success path — say what happens when the install
   itself fails.** Round 16 (2026-08-12) specified healthy and broken enrichment signatures
   in careful detail and had no branch for "Install AI does not complete"; when 5 of 7
   packages failed, the round held precise expectations for strings that could not yet exist
   and no guidance on whether to treat the failure as blocking, retry, or environmental, so it
   had to decide the round's shape itself. That is the right call to leave to the round — the
   charter should *say* so: add one line stating that if the install does not complete, the
   round's shape is the round's call, and the failure itself is the finding to characterise.
   Round modes cover the supported **arrival states**, not only the empty
   machine: first and final qualifying rounds run `fresh-install`, and the qualifying set must
   include at least one `upgrade-from-release` round (previous public release installed first,
   candidate installed over it — tempdoc 750 Part C).
   **The qualifying set also includes one owner/human gestalt-tier manual pass per release
   candidate, performed WITHOUT API access.** The reviewer must experience the surface, not
   compensate for it: an agent with the API, the tempdocs and the source conventions resolves an
   unclear surface by reading `/api/…` and correctly files nothing, which is exactly the
   competence that disqualifies it as the observer for this class. Findings are recorded and then
   agent-verified against the running build, each claim tagged with its provenance. Origin: the
   0.2.0 round-14 manual pass (2026-08-05/06) — run immediately after a round that passed
   coverage 26/26, golden parity, token health and a full screenshot reader pass with no blocking
   defect — produced **15 findings**, the whole-screen and design-debt classes no per-item
   existence gate can see (tempdocs 809 verbatim, 810 triage). *What* to cover is not remembered — it is
   **derived from what the candidate ships:**
   `scripts/sandbox/sandbox-launch.py` generates a per-candidate `coverage-brief.md` from the
   committed surface artifacts against `governance/sandbox-coverage.v1.json` and **fails closed**
   if the build ships a surface not yet classified there (so a new endpoint/panel like `/mcp` can't
   be silently forgotten). The round runs with request tracing on
   (`JUSTSEARCH_HEAD_TRACING_LEVEL=detailed`) and `collect-evidence.ps1` captures the API ladder,
   the `/mcp` Inspector check, and a copy of `traces.ndjson` into the mapped evidence folder. At
   finalize, on the **host** (the sandbox has no Python), against the persisted evidence dir:
   `python scripts/sandbox/check_coverage.py --manifest <share>/coverage-manifest.json --traces
   <share>/evidence/traces.ndjson --evidence-dir <share>/evidence` — a non-zero exit means a
   required surface was not exercised.
   **A qualifying round must also leave six process artifacts in the evidence dir**, each
   separately fail-closed by that same check: `retrospective.md` (the round-vs-charter debrief,
   with its time-accounting section), `evidence-review.v1.json` (a reader examined every
   credit-eligible screenshot), `findings.md` (added 2026-08-12, tempdoc 823 — the round's defect
   report as its own artifact, each finding carrying a severity, an evidence pointer and a
   regression home; round 16 filed five findings and left no findings file, scattering them
   across three artifacts written for other purposes, and a round that genuinely found nothing
   says so explicitly), and — added 2026-08-05, tempdoc 808 — `mustwatch-verdicts.v1.json`
   (a verdict for every must-watch id in the round's brief; `unobservable` needs a reason,
   `observed-fail` prints loudly but routes through findings rather than flipping the exit),
   `session-analysis.md` (what the harness/charter made hard, what was off-charter and why, what
   the next round should change — round 12's unprompted version of this yielded ~11 adopted harness
   fixes), and `mutating-probe.v1.json` (written by `collect-evidence.ps1`; a `fail` means every GET
   rung read green while the product's whole mutating surface was dead — the round-10 false-green,
   which until now reached no exit code). The round also captures golden-query search responses
   (`evidence/golden/<queryId>.json`), checked at finalize by `check_golden_parity.py` against
   the per-candidate baseline generated from the dev stack on the same build — parity-with-dev,
   not absolute quality, since the Sandbox has no `jseval`. That check fails closed (exit 1, not a
   reported ranking delta) on a model-identity mismatch, an under-staged corpus, or a skipped
   dense-retrieval leg, rather than surfacing any of those as a phantom search-quality regression.
   The durable harness method lives in `scripts/sandbox/*.md`;
   each candidate's round-by-round results live in that release's own convergence tempdoc (linked
   from the Release index below).
3. **Fix and rebuild at the *same* candidate number.** Major findings are reported back, fixed
   in code, and the installer is rebuilt under the same number. Every confirmed regression
   should become a regression test/gate before the next round, so the loop converges instead of
   re-finding the same class.
   **Round-scheduling gate (tempdoc 750 Part B):** after a DO-NOT-QUALIFY, classify each open
   blocker **needs-round** (only a clean install / real GUI / real external client can answer
   it) or **needs-dig** (source-level or dev-stack investigation). Schedule a new round only
   when at least one blocker is needs-round, or the round is the final qualifying round — a
   round is the most expensive verification tier and must not be spent re-confirming a
   needs-dig blocker (0.2.0 rounds 5→6 did exactly that; the classification goes in the
   charter). A needs-dig classification carries an owner and a timebox so the gate cannot
   stall a release indefinitely; by-catch from past confirmatory rounds is real but is
   measured in the debrief (on-opportunity share), not used to justify the next one.
   **Campaign hygiene: audit the charter's do-not-refile list once per campaign** for entries
   that have drifted from *known residual* into *accepted behaviour*. The list is necessary — it
   is what stops a round re-filing a known-correct signature as a false HIGH — but every entry
   permanently converts an observation into a non-observation, and nothing expires it. Re-read
   each entry and ask whether it is still a residual someone intends to fix, or has quietly
   become the product's behaviour by default.
4. **Finalize on zero findings.** Only when a Sandbox round finds no blocking issues is the
   number finalized and the release published.

**Finalize criterion:** a clean whole-product Sandbox round (independent verifier, not the
committer) with no blocking findings — or with every remaining blocking finding explicitly
downgraded to a **known issue** per the policy below. This is not a route to silently lowering the
bar (`structural-defects-no-repeat` still applies to the defect itself once picked up) — it
requires the owner naming the specific finding and its tracking tempdoc, not an agent's unilateral
call, and it does not excuse the finding from eventually needing a real fix with a regression test.

### Known issues at release

A confirmed, reproducible finding may ship as a documented known issue instead of blocking finalize
when **all** of:

1. **Explicit owner decision**, dated and recorded in the release's convergence tempdoc (not an
   agent's or round's own call) — e.g. "owner decision YYYY-MM-DD: finding N ships as a known
   issue, tracked in tempdoc NNN."
2. **A dedicated tracking tempdoc exists** for the finding, with the problem statement, root-cause
   understanding so far, and a suggested fix shape — so the fix has a durable home separate from
   the release record, and isn't lost once the release ships.
3. **The GitHub Release's notes carry a Known Issues section** linking to that tempdoc, so the
   limitation is disclosed to users at the point of install, not buried in internal docs.
4. **The convergence tempdoc's finding is reclassified in place** (not deleted or silently
   dropped) — the routing column points at the new tracking tempdoc, and the release verdict
   records that this specific finding no longer blocks finalize, while any *other* still-open
   blocking findings continue to.

This exists so one non-core defect doesn't hold an entire release hostage, while keeping every
known defect visible, owned, and headed toward an actual fix rather than indefinite tolerance.

## `build-installer.yml` dispatch inputs

`workflow_dispatch` accepts four inputs (definitions in the workflow's `on.workflow_dispatch.inputs` block):

| Input | Type | Default | What it does | When to set it |
|---|---|---|---|---|
| `sign` | boolean | `false` | Authenticode-signs the build with the configured `JUSTSEARCH_CODESIGN_*` credential. Off by default because eSigner signings are metered (~8 per build with the signed mirrors); a `v*` tag ref always signs regardless of this input. | Set `true` for a branch dispatch that needs a real signature (e.g. a signed rehearsal or the signed-mirror procedure); leave `false` for an ordinary unsigned validation build. |
| `sandboxTestMode` | boolean | `false` | Compiles the shell with `JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE=1`, permitting loopback HTTP feeds and runtime trust-root overrides in `updater.rs` (`pinned_release_value` / `ensure_https`). The workflow refuses this input on a `v*` tag ref — a qualification build must never be published. | Set `true` together with `candidateVersion` to produce the unsigned qualification candidates the in-app N->N+1 updater lane needs — tempdoc 617 §9 item 2 calls for two updater-capable candidate versions (or an equivalent previous-source build plus target) so that upgrade path can actually be exercised in the Sandbox. |
| `candidateVersion` | string | `''` (unset) | Overrides the built version (e.g. `0.2.1-qual.1`) instead of reading `gradle.properties`. Only honoured together with `sandboxTestMode`. | Set alongside `sandboxTestMode` to produce a second, differently-versioned candidate for the N->N+1 lane. A real release cut leaves this unset and takes its version from `gradle.properties` as usual. |
| `providerRemainingSignatures` | string | `''` (unset) | The provider portal's remaining signing allocation immediately before a production-signed run; required for a `v*` tag or `sign=true` unless the self-signed rehearsal variable is on. | Read the provider portal value and pass it whenever dispatching a signed build (see step 6 below). |

## The asset set the build produces

One build produces a complete, hash-consistent asset set in `dist/installer/`:

| Asset | Source | Notes |
|---|---|---|
| `JustSearch_<version>_x64-setup.exe` | NSIS build (Tauri) | The installer. |
| `JustSearch_<version>_x64-setup.exe.sig` | Tauri updater signing | Minisign signature verified by both Tauri and the authenticated descriptor builder. |
| `release.v1.json` | `scripts/release/app-release-assets.mjs` | Canonical, metadata-signed descriptor containing the sequence, artifact digest/signature, and durable-store compatibility register. |
| `release.v1.json.sig` | `scripts/release/app-release-assets.mjs` | Ed25519 signature over the exact canonical descriptor bytes. |
| `latest.json` | `scripts/release/app-release-assets.mjs` | Tauri updater metadata; its URL and signature must match `release.v1.json`. |
| `justsearch-mcp.mcpb` | built from source by `scripts/ci/pack-mcpb.mjs` | The one-click MCP bundle; a deterministic STORED zip of `manifest.json` + `server/**` (not committed). |
| `SHA256SUMS` | generated by `build-release-assets.ps1` | `sha256sum -c`-compatible manifest over the complete release asset set. |

For a tag build, the workflow first creates or reuses a **draft** GitHub Release, uploads the
complete set, downloads it again, verifies every checksum and the authenticated updater closed
set, and only then publishes. A verification failure leaves the Release in draft state.

**Consistency invariant (fail-closed).** The MCPB's SHA-256 is a *published contract*: it lives
in `packaging/mcpb/server.json` (`fileSha256`, read by the Official MCP Registry publish), in the
release `SHA256SUMS`, and in the bundle bytes. A wrong `fileSha256` silently breaks MCP installs.
Because the bundle is **built deterministically from source**, `scripts/ci/check-mcpb-consistency.mjs`
re-packs from source and fails the build if the fresh hash `!= server.json.fileSha256` (run every PR)
— this catches both integrity *and* freshness (an edit to `manifest.json`/`server/**` without a
re-sync). On a real cut (`--release-version`) it also fails if `server.json`'s version/URL don't match
the tag. After editing the source, run `node scripts/ci/pack-mcpb.mjs --sync` (the `mcpb-repack-hint`
reminds you). See `packaging/mcpb/README.md`.

## Cutting a release (operator)

In-repo steps (an agent can prepare these; the branch must be pushed and green):

1. Prepare the changelog section, then validate its structure:

   ```bash
   node scripts/release/release-changelog.mjs prepare --version <version> --date YYYY-MM-DD --write
   node scripts/release/release-changelog.mjs check
   ```

   This moves the current `[Unreleased]` entries under one exact, non-empty
   `## [<version>] - YYYY-MM-DD` heading and leaves a new, possibly empty `[Unreleased]` section.
   The tag workflow rechecks that exact version before it creates or edits a draft Release.
2. Bump `gradle.properties` `version` to the release version (e.g. `0.2.1`).
   `sync-version.ps1` propagates it to Tauri/Cargo/npm; `-RequireReleaseSemver` accepts
   `x.y.z[-alpha.N]` and rejects `SNAPSHOT`.
   > **Strict release semver is now load-bearing at tag time.** The next `v*` tag dispatch is
   > the **first** that actually runs `-VerifyReleaseVersion` (a latent flag-binding bug hid it
   > until tempdoc 760's hashtable-splat fix — rehearsal run `29909495558`; it had never been
   > exercised because no tag dispatch had run since the flag was added). So `gradle.properties`
   > must already be a clean `x.y.z[-pre.N]` before you tag — a `SNAPSHOT`/malformed version now
   > fails the build at dispatch instead of slipping through.
3. `sync-version.ps1` also stamps `server.json`'s `version` + release-asset URL from the
   gradle version. If the MCPB source changed since the last release, also run
   `node scripts/ci/pack-mcpb.mjs --sync` and commit `server.json` (its `fileSha256`).
   Verify: `node scripts/ci/check-mcpb-consistency.mjs --release-version <version>`.

Owner-only steps (require repo permissions):

4. Configure the updater trust inputs:
   - secret `JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY` (and
     `JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY_PASSWORD` when applicable);
   - secret `JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PEM`;
   - variables `JUSTSEARCH_UPDATE_ARTIFACT_PUBLIC_KEY`,
     `JUSTSEARCH_UPDATE_ARTIFACT_KEY_ID`,
     `JUSTSEARCH_RELEASE_METADATA_PUBLIC_KEY_PEM`,
     `JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY`,
     `JUSTSEARCH_RELEASE_METADATA_ROOT_KEY_ID`, and
     `JUSTSEARCH_RELEASE_DESCRIPTOR_URL`.

   The artifact key may rotate between releases because its public key is authenticated inside
   `release.v1.json`. The v1 metadata root is deliberately a long-lived offline root; replacing it
   safely requires a separately designed bridge/dual-root release and must not be attempted as an
   ordinary variable change.
5. Push, tag `v<version>` (a bare `vX.Y.Z` auto-resolves to **non-prerelease** → becomes
   `/releases/latest`; `-alpha/-beta/-rc` tags are prereleases).
6. Read the provider portal's remaining-signatures value, then dispatch
   `build-installer.yml` against the tag ref with `providerRemainingSignatures` set to that value.
   The tag path requires signing and refuses to begin packaging unless the value covers the
   reviewed per-run ceiling (currently 12). The tag version must also exactly match the single
   `version=` value in `gradle.properties`; a mismatched tag is rejected before packaging:

   ```bash
   gh workflow run build-installer.yml --ref v<version> -f providerRemainingSignatures=<portal-value>
   ```

   The job runs under the protected `release-signing` Environment, which has a required-reviewer
   rule: every dispatch pauses at "Waiting for review" on the run's Actions page, and no step
   executes until the owner approves it there. Dispatch, then watch the run and approve it.

   It builds, runs
   `build-release-assets.ps1`, round-trip verifies the draft asset set, and publishes the Release
   only after verification succeeds.

   > **The separate `installer_verify` job cannot hold the release in draft.** It runs `needs:
   > build-windows-installer`, i.e. after that job (including its publish step) has already
   > finished, so a red `installer_verify` always follows a release that is already public. Treat
   > a failure there as a post-publish signal: check that job's run log for what specifically
   > failed, then ship a follow-up patch release, or manually un-publish
   > (`gh release edit <tag> --draft`) if the defect is severe enough to pull the asset.
   >
   > **Where the release sequence comes from.** The sequence shipped inside `release.v1.json`
   > is derived by `scripts/ci/derive-release-sequence.mjs`: `max(sequence` over every published
   > release's `release.v1.json` asset`) + 1`, never below a checked-in floor of **41** (v0.2.0's
   > published `40`, plus one). The tag being cut is excluded from the max, so re-running a
   > dispatch against an already-published tag re-derives the same value instead of drifting.
   > A release without the asset is skipped with a warning; an asset that exists but cannot be
   > fetched or parsed is a hard error — the build fails rather than guessing, because
   > `updater.rs` persists `highest_accepted_sequence` and permanently refuses any descriptor
   > below it, with no in-client recovery.
   >
   > This replaced `GITHUB_RUN_NUMBER`, which GitHub scopes to the workflow *file*: renaming,
   > moving, or delete-and-recreating `build-installer.yml` reset the counter to 1 and would have
   > bricked update acceptance for every client that had accepted a release — a trap that locked
   > in with the first stable tag (v0.2.0, 2026-08-13; tempdoc 801 D14.b). Restructuring the
   > workflow file is now safe. Raise the floor only to stay above the highest sequence any
   > *published* release has carried; never lower it.
7. Set the repo homepage, enable Discussions if desired, and take/replace hero screenshots
   (see `docs/m1-operator-checklist.md`).
8. **Post-cut — update the README to the published asset.** `README.md`'s installer size + SHA-256
   line and the `v<version>` download link describe the *published* release asset, which is why
   they deliberately still read the previous release's figures (853 MB, the v0.1.0 SHA) until a cut
   lands. After the Release exists, bump the size, the `SHA256SUMS` value, and the
   `releases/download/v<version>/…` link together to the new asset.

> Do **not** advertise MCP against a release whose installer predates the `/mcp` endpoint. The
> shipped **v0.1.0** app has no MCP endpoint (its backend is 2026-04-28 jars); the MCPB bundle
> and the README's MCP sections are meaningful only from the next release onward.

## Pre-release verification (sandbox silent-install)

Beyond the whole-product Sandbox round in the release loop, a narrow automated check confirms the
two facts the distribution-readiness audit flagged as never empirically tested: `/S` **silent**
install lands the app at the real per-user default path, and `/S` **silent** uninstall removes it
cleanly (install dir, `HKCU:\…\Uninstall\JustSearch`, shortcuts, no leaked processes).

Run from a GUI-capable Windows machine with Windows Sandbox enabled (the harness needs an
interactive GUI session — it cannot be driven headless):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ci\package-installer-win.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release\sandbox-silent-install-test.ps1
```

The first stages the newest installer into the Sandbox share; the second stages the guest script,
generates the `.wsb`, and launches Windows Sandbox by double-click. Results land as
`silent-test-result-<timestamp>.json` (schema `justsearch.sandbox-silent-install-test.v1`) in the
share, ending in a literal `PASS`/`FAIL`. `-GenerateOnly` prepares the `.wsb` without launching.

> **Only ever run this inside a disposable Windows Sandbox — never directly on a host with a real
> JustSearch install.** The guest script's registry/filesystem lookups are host-global; run outside
> Sandbox they will find and silently `/S`-uninstall a real install (this happened once during
> authoring — tempdoc 760). The guest header carries the same warning.

## Pre-release verification (update preserves models)

Run this **before publishing**, once a candidate installer exists. Tempdoc 617 D2 allows
monolithic full-installer updates only because the user's models — up to ~9 GB — are reused in
place, and a monolithic update runs uninstall-then-install over the existing install. The
`check-update-preserves-models` script only checks the *declared* packaging surface; this lane
checks a built one.

```bash
# Gate the candidate you are about to publish against the current latest release.
gh workflow run update-preserves-models.yml \
  -f base_release_tag=<previous vX.Y.Z> \
  -f candidate_source=run-artifact \
  -f candidate_run_id=<the build-installer.yml run id from step 6>
```

It installs the previous release silently, seeds model files under
`%APPDATA%\io.justsearch.shell\models`, installs the candidate over it, and asserts every seeded
byte is unchanged — then uninstalls and asserts they are *still* unchanged (ADR-0024). Download the
`update-preserves-models-evidence` artifact and keep it with the release evidence. Full reference,
including what the lane does not cover: `docs/how-to/verify-update-preserves-models.md`.

Unlike the Sandbox harness above, this one runs unattended on a hosted runner and needs no GUI.

## Release-note authority

Do not maintain a second “next cut” list here. Human-authored release notes live in
`CHANGELOG.md`. The tag workflow composes the immutable release body from the download-trust
notice, that tag's exact changelog section, and GitHub's generated notes. Re-running a failed tag
dispatch updates an existing draft to the same composed body before asset publication.

## Code signing

Authenticode signing is live: v0.2.0 shipped signed by **Elias Justus**. Branch rehearsals may still
be unsigned or explicitly self-signed, but every `v*` tag requires a configured credential and the
full `-Release` contract. `scripts/ci/sign-windows.ps1` selects a credential mode via
`JUSTSEARCH_CODESIGN_MODE` (default `pfx`), all fail-closed under
`JUSTSEARCH_REQUIRE_SIGNING`:

| Mode | Secrets it reads | Use |
|---|---|---|
| `pfx` | `JUSTSEARCH_CODESIGN_PFX_B64` + `JUSTSEARCH_CODESIGN_PFX_PASSWORD` + `JUSTSEARCH_CODESIGN_TIMESTAMP_URL` | A PFX cert file (base64). |
| `store` | `JUSTSEARCH_CODESIGN_THUMBPRINT` | Cert from the Windows cert store — how USB-token / HSM CSP-backed non-exportable keys present locally (`signtool /sha1`). |
| `command` | `JUSTSEARCH_CODESIGN_COMMAND` (a template with a `{file}` placeholder) | Any vendor CLI (Azure Trusted Signing, eSigner, …) — pluggable with zero further repo changes. |

**Environment setup (current).** The protected GitHub Environment `release-signing` exists
(created 2026-09-03), enforces a required-reviewer rule on every dispatch, and holds
`JUSTSEARCH_CODESIGN_MODE` (`command`) and `JUSTSEARCH_CODESIGN_COMMAND`. The repo variable
`JUSTSEARCH_RELEASE_DESCRIPTOR_URL` points at
`https://github.com/justsearch-app/justsearch/releases/latest/download/release.v1.json`.
Repository-scoped copies of the same two secret names still exist alongside the Environment
copies — the migration step from the original one-time setup was not completed; treat the
Environment copies as authoritative and the repo-scoped ones as a pending cleanup, not a second
source of truth.

**eSigner (SSL.com) via `command` mode — validated template.** Sandbox-validated end-to-end
(2026-07-22, CodeSignTool 1.3.3, demo credentials; tempdoc 760): signs in place, non-interactive,
distinct nonzero exit codes on failure (fail-closed semantics hold through the `.cmd` wrapper).
`build-installer.yml` installs CodeSignTool into `%RUNNER_TEMP%\CodeSignTool` only when the command
template references it. The shared bootstrap verifies the downloaded archive against the committed
SHA-256 before extraction and writes a provenance receipt. During vendor execution the signer
removes unrelated updater, release-metadata, and GitHub secrets from the child environment. Set
`JUSTSEARCH_CODESIGN_COMMAND` to:

```text
%RUNNER_TEMP%\CodeSignTool\CodeSignTool.bat sign -username=<eSigner login> -password="<password>" -totp_secret="<TOTP secret captured at 2FA enrollment>" -credential_id=<credential id> -input_file_path="{file}" -override="true"
```

Notes from the validation: each signature takes ~9-12 s end-to-end (an unmirrored ~99-file build
adds ~15-20 min of signing; post-mirrors ~8 files ≈ 1.5 min); check the eSigner **malware-scan /
Malware Blocker** setting on the credential before the first dispatch (if enabled it can block
signing of flagged files); `get_credential_ids -username=... -password=...` prints the credential
id if it wasn't captured from the dashboard; the eSigner **30-day unlimited-signing trial window**
is the right time to run the per-pin-bump signed-mirror procedure below (the ~93 one-time mirror
signatures are then free).

> **`JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED` is REHEARSAL-ONLY and must NEVER be set for a production
> release or `command`-mode/vendor signing.** It relaxes the post-sign check from `signtool verify
> /pa` to a hash-valid-Authenticode presence check, so a chain-untrusted (self-signed) signature is
> accepted — exactly what a real release must reject. Both release admission and the shared signer
> reject the combination with `command` mode. The shared signer also requires a run-local ledger
> path and positive ceiling before any command/vendor invocation; every paid workflow dispatch must
> additionally supply provider-authoritative remaining budget.

**Rehearsal (no paid cert needed).** The full CI signing path can be exercised before any purchase:
generate a throwaway self-signed certificate, configure it through `pfx` or `store` mode, set
`JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED=1`, and dispatch a branch build. Never put an eSigner/vendor
command in this rehearsal lane. This is how tempdoc 760's signing-rehearsal campaign reached green
(run `29914075529`) with zero paid signings spent; remove the rehearsal credential/variable
afterward. The signing script itself is separately rehearsable via
`scripts/ci/test-sign-windows.ps1`.

Each signing run keeps two credential-free journals. The append-only attempt journal reserves a
slot before every vendor invocation, so failures, retries, and interrupted calls still consume the
reviewed ceiling. The verified-signature ledger is appended only after local Authenticode
verification succeeds. The workflow summary reports both counts; neither is a substitute for the
provider portal's authoritative remaining allocation.

### Per-pin-bump signed-mirror procedure

The bundler signs **every** unsigned bundled PE — ~93 third-party (Tesseract + llama.cpp) + ~8 own
≈ ~100 signatures per release. Because the third-party PEs are pin-stable, sign them **once per
upstream pin bump** and re-host the signed archives, dropping per-release signings from ~100 to
**~8**. Per pin bump:

1. **Dispatch the `Sign Vendored Mirrors` workflow**
   (`.github/workflows/sign-vendored-mirrors.yml` — `workflow_dispatch` only, because it *spends*
   metered signings):

   ```bash
   gh workflow run sign-vendored-mirrors.yml --ref main -f signLlama=true -f signTesseract=true -f providerRemainingSignatures=<portal-value>
   ```

   Both inputs default to `true`; set one to `false` to re-sign a single payload when only one pin
   moved. The workflow reads the pins from their existing authorities rather than restating them
   (llama from `modules/ui/build.gradle.kts`, Tesseract from
   `packaging/runtime/tesseract-windows.v1.json`), sha256-verifies each upstream download before
   signing anything, repacks the Tesseract self-extracting NSIS installer into a layout-preserving
   zip, and runs `scripts/release/sign-vendored-payload.ps1` on each archive (extract → sign every
   unsigned inner PE → deterministic re-zip → sha256 out). Budget ~9-12 s per PE: a full two-payload
   run is ~20 min of signing. The same script can be run locally with the
   `JUSTSEARCH_CODESIGN_*` env set and `-ProviderRemainingSignatures <portal-value>`, if you would
   rather not go through CI.
2. **Download the run's `signed-vendored-mirrors` artifact** (`gh run download <run-id>`): the two
   `-signed.zip` files and their `.sha256` sidecars. The sidecar lines are also printed near the end
   of the run log, ready to paste.
3. **Upload both `-signed.zip` files to the `justsearch-releases` repo** and note each URL.
   (Tesseract has a licensing obligation here — see below.)
4. **Regenerate the Tesseract per-file manifest pins** (Tesseract only). The override is
   *archive*-level, but `packaging/runtime/tesseract-windows.v1.json` also pins each staged file by
   sha256 + size in `files[]`, and signing rewrites those bytes:

   ```bash
   node scripts/release/regen-tesseract-manifest.mjs <tesseract-...-signed.zip>
   # verify instead of write:
   node scripts/release/regen-tesseract-manifest.mjs <tesseract-...-signed.zip> --check
   ```

   It rewrites only the affected `sha256`/`sizeBytes` values in place (key order and formatting are
   preserved) and leaves the manifest's `sourceUrl`/`sourceSha256` — the *upstream* archive pin —
   alone. Entries with their own `sourceUrl` (today `tessdata/eng.traineddata`) are skipped: they
   are downloaded separately and copied over the extraction, so signing cannot have touched them.
   Skipping this step is not silent — `verifyTesseractRuntime` fails with a pointer back to this
   command.
5. **Commit the URL + sha256 pairs into `packaging/signed-mirrors.v1.json`** (`llama-cpu` and/or
   `tesseract` entry), together with the regenerated manifest, as one PR. The build reads that file
   and applies the matching gradle override pair; the pins are committed there — not in repo
   variables — so each bump is PR-reviewed supply-chain history. If a payload entry is absent, the
   build uses the byte-identical default pinned upstream download; a
   half-complete entry (url without sha256, or vice versa) fails the build loudly before it starts.

**Notes:**
- The Tesseract mirror is a **zip** even though the manifest's `sourceUrl` names a `.exe`: the build
  saves whatever that URL returns under a fixed `...-setup-<ver>.exe` filename and runs `7z x` on
  it, and 7-Zip picks its handler by content signature, so a zip under that name extracts to the
  same layout. What must be preserved is the *extracted layout* — the `files[]` paths resolve
  against the extraction root.
- Hosting a signed Tesseract mirror on `justsearch-releases` **redistributes GPL-2.0-obligated
  binaries** from that repo — that repo's `THIRD_PARTY_NOTICES.txt` needs the corresponding GPL
  notice / source-offer treatment. **Flagged as a required step; do it before hosting** (not covered
  by this doc).

## ORT native version bump — coupling checklist

The onnxruntime jar version and the GPU ORT-CUDA pack are **coupled** (tempdoc 772 §J): a bump to
one that misses the others makes GPU users **silently lose ORT CUDA** (embeddings fall back to CPU
speed). When bumping the ORT version, all of the following must move together:

- [ ] The **onnxruntime jar** version (Gradle dependency).
- [ ] The re-built **`ort-native-cuda12-v<ver>.zip`** pack asset (uploaded to `justsearch-releases`).
      **Before pinning its sha, assert its contents:**
      ```bash
      node scripts/release/check-ort-native-asset.mjs <path-to>/ort-native-cuda12-v<ver>.zip
      ```
      It fails (exit 1) listing any of `OrtCudaHelper.ORT_NATIVE_DLL_SET` the archive lacks —
      `onnxruntime.dll`, `onnxruntime4j_jni.dll`, `onnxruntime_providers_shared.dll`,
      `onnxruntime_providers_cuda.dll` — read from the Java authority, not a second list. A pack
      missing one does **not** fail loudly at runtime: ORT falls back to CPU and every ONNX encoder
      runs at CPU speed while the status surfaces still report a GPU variant (tempdoc 734 R11-F3).
      The registry pin then makes the verified bytes the only accepted bytes, so this is the one
      moment the contents can be wrong.
- [ ] The **registry entry** — `filename` / `sha256` / `sizeBytes` for that asset — in
      `model-registry.v2.json` (`modules/configuration/src/main/resources/ai/`).
- [ ] The in-zip **`ort-native-version.txt`** marker inside the pack archive.
- [ ] **`OrtCudaHelper.EXPECTED_ORT_NATIVE_VERSION`** (`modules/ort-common/…/OrtCudaHelper.java`).

If the marker or `EXPECTED_ORT_NATIVE_VERSION` disagree, the pack is treated as
`VERSION_MISMATCH` and CUDA is not activated.

## Release index

One row per release (this table is the only living per-release record here; detail lives in
each release's convergence tempdoc + its GitHub Release):

| Version | Date | Sandbox verdict | Notes | Links |
|---|---|---|---|---|
| v0.1.0 | 2026-06-25 | (pre-pipeline) | Prerelease/alpha. **No MCP endpoint.** Installer built locally, not via CI. | [release](https://github.com/justsearch-app/justsearch/releases/tag/v0.1.0) |
| v0.2.0 | 2026-08-13 | published after the 0.2.0 convergence campaign; tempdoc 617's exact updater N→N+1 lanes remain open | First stable cut with the MCP endpoint, authenticated update assets, and Authenticode-signed installer (publisher Elias Justus). | [release](https://github.com/justsearch-app/justsearch/releases/tag/v0.2.0), tempdoc 734, tempdoc 823, tempdoc 617 |

## See also

- MCPB bundle + registry: `packaging/mcpb/README.md`
- Owner funnel: `docs/m1-operator-checklist.md`
- Workflow: `.github/workflows/build-installer.yml`
