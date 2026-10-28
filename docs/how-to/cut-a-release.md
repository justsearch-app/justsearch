---
title: "Cut a JustSearch release"
type: how-to
status: draft
description: "Build one immutable Windows/Linux draft candidate, qualify its bytes, and publish only on the owner's instruction."
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

## Downgrade and recovery policy

Releases are **fix-forward only**: repair a broken release by installing a newer release through
the updater, including its dead-Engine update path. The updater does not provide a downgrade path.
Installing an older build over Lane F data has these effects:

| Store | Effect |
|---|---|
| `ui/settings.json` | The older build quarantines the newer settings file as `settings.json.corrupt-*` and resets settings to defaults. Lane F preserves a pre-migration copy as `settings.v<old>.bak.json`. |
| `jobs.db` | The older build refuses the future schema; this derived database rebuilds. |
| Index (`state.json` and generation manifest) | The older build may open the index or rebuild it. |
| `operations.db` | The older build ignores it; it remains untouched and unused, so operation history is orphaned. |
| `runtime/*` | Ephemeral files reset by policy. |

To restore settings after installing an older build:

1. Stop JustSearch.
2. In the settings directory, copy `settings.v<old>.bak.json` over `settings.json`.
3. Start JustSearch.

The desktop app sets `JUSTSEARCH_HOME` to its data folder, so settings are at
`%APPDATA%\io.justsearch.shell\ui\settings.json` on Windows or
`~/.local/share/io.justsearch.shell/ui/settings.json` on Linux
(`$XDG_DATA_HOME/io.justsearch.shell/ui/settings.json` if set).
For headless/dev launches, the file is `$JUSTSEARCH_HOME/ui/settings.json` when set. Otherwise,
it is `%USERPROFILE%/AppData/Roaming/justsearch/ui/settings.json` on Windows,
`~/Library/Application Support/justsearch/ui/settings.json` on macOS, or
`~/.config/justsearch/ui/settings.json` on Linux. These locations follow
[`UiSettingsStore.resolveSettingsFile()`](../../modules/app-services/src/main/java/io/justsearch/app/services/settings/UiSettingsStore.java);
[`JUSTSEARCH_HOME`](../reference/configuration/environment-variables.md#envregistry-keys-complete)
selects the home root.

Lane F also ships a `data-version.json` marker and a newer-data notice. A build from Lane F onward
that finds a marker written by a newer build shows a notice naming that version and the preserved
files. Releases before Lane F (0.3.x and earlier) do not read the marker, so for them the table
above is the whole behavior.

## Release candidate and publication

The owner decides when to cut a release. An agent prepares and lands the release PR only after
that instruction: bump `version` in `gradle.properties`, run
`node scripts/ci/sync-version.mjs` (Windows and Linux), and add the user-facing `CHANGELOG.md`
section. Keep known security or data-loss defects out of a release. Other issues block only when
they affect every user and prevent general use; list remaining known issues in the draft notes.

1. Tag the release PR's **merge commit on main** as `vX.Y.Z`. Pushing the tag starts
   `build-installer.yml`. No balance input, provider-portal visit, or environment approval is
   needed. The production descriptor URL is compiled as
   `https://github.com/justsearch-app/justsearch/releases/latest/download/release.v2.json`;
   release builds retain the runtime-override rejection.
2. The workflow builds the SSL.com-signed Windows NSIS installer and Ubuntu 24.04 x86_64 `.deb`,
   runs the Windows packaged verifier and Linux installed-Engine/help-search smoke, then creates
   **one draft** containing both platforms. Nothing becomes public. It includes both installers
   and their Tauri signatures, `justsearch-mcp.mcpb`, signed `release.v2.json`, one `latest.json`,
   `candidate.json` (commit/run identity and inventory), the compatibility registers, both packaged
   check reports, `provenance.sigstore.json`, and `SHA256SUMS`.
   Every file except `SHA256SUMS` itself is checksummed. The saved GitHub build-provenance bundle
   attests every payload, including the candidate inventory; the bundle is checksummed and cannot
   attest itself. See [GitHub CLI verification](https://cli.github.com/manual/gh_attestation_verify).
3. Qualify and finally validate **those downloaded draft bytes**, recording their SHA-256 values
   and evidence in the draft notes. Fill the `Not tested` and `Known issues` placeholders; retain
   the hidden candidate-identity marker. Never replace an asset after qualification. Changes to
   main during this work cannot alter this candidate. The existing Sandbox silent-install and
   update-preserves-models lanes remain available for qualification.
4. After qualification and the owner's explicit word, dispatch `publish-release.yml` **on main**
   with input `tag=vX.Y.Z`. It checks the complete asset set, SHA256SUMS, updater signatures, and
   saved provenance against the original tag and commit. It also checks the published sequence
   floor, then promotes the draft as latest. It never rebuilds or re-signs anything.

A failed candidate is abandoned. Prepare the **next patch version**; never move or reuse its tag.
A draft accepts only the same commit and workflow run. If upload fails, use **Re-run failed jobs**
on the same run: signed packages and the assembled candidate are retained as immutable workflow
outputs for that retry. Existing uploaded assets must match byte for byte; there is no clobber.
A platform/check failure creates no release. An upload failure can leave a partial draft of that
single candidate; it remains private. A failed publication can be retried by dispatching the same
tag; a completed promotion is idempotent. Workflow artifacts last 14 days; after expiry abandon
an incomplete candidate rather than re-signing it. Successful draft uploads preserve the evidence
and provenance with the draft, independently of Actions retention.

Sequence is `max(sequence from every published release.v1.json/release.v2.json) + 1`, with the
existing floor of 41. Drafts do not advance it. Unreadable published descriptors and inconsistent
predecessor compatibility fail closed. If another candidate publishes first and makes a draft's
sequence stale, abandon the stale candidate and cut the next patch.

## Manual Windows builds and daily smoke

`build-installer.yml` still supports branch `workflow_dispatch` for existing Windows qualification
uses. It calls `build-installer-windows.yml`, retaining `sandboxTestMode`, `candidateVersion`
(qualification only). Manual builds, including Sandbox qualification, are unsigned and use
generated test trust pins. Signed candidates come only from `v*` tags. A qualification
build is forbidden on a tag. Release publication has moved entirely to `publish-release.yml`.
The existing `windows-installer` artifact name remains available to qualification consumers.

`packaging-smoke.yml` runs daily on main at 04:23 UTC (and can be dispatched on main). It compares
packaging-relevant paths with the last successful run and skips both builds when they have not
changed. Otherwise both hosted platforms build in release configuration with test pins and no
production signing secrets. Windows runs `installer_verify`; Ubuntu installs the `.deb`, launches
its installed Engine with its bundled JVM and isolated data, waits for Engine readiness, and
searches the bundled keyboard-shortcuts help. Failure is a red Actions run; no issue is posted.

## One-time repository settings (admin)

Apply these once; these instructions do not change repository settings:

- Create environment **`release-signing`**. Under **Deployment branches and tags**, choose
  **Selected branches and tags**, add a **Tag** rule matching **`v*`**, and add **no branch
  rules**. Remove any existing branch rules or broader tag rules. Under **Deployment protection
  rules**, leave **Required reviewers empty/disabled** and **Wait timer disabled**; add no custom
  approval rules. Tags sign unattended; branch, PR and daily-smoke jobs cannot access this
  environment. Every job reading a production signing or updater key binds this environment.
- Move all production signing **secrets** into `release-signing`:
  `JUSTSEARCH_CODESIGN_MODE` (`command`), `JUSTSEARCH_CODESIGN_COMMAND` (the SSL.com template
  below), `JUSTSEARCH_CODESIGN_PFX_B64`, `JUSTSEARCH_CODESIGN_PFX_PASSWORD`,
  `JUSTSEARCH_CODESIGN_TIMESTAMP_URL`, `JUSTSEARCH_CODESIGN_THUMBPRINT` (the latter four only
  if used by the selected signing mode), `JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY`,
  `JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY_PASSWORD` (empty only for an unencrypted key), and
  `JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PEM` (Ed25519). **Delete their repository-scoped
  copies** and any copies in other environments; remove any organization-secret repository
  access granting these credentials outside `release-signing`. Do not leave a fallback copy.
- Public **variables remain repository-scoped** so unsigned builds and publication can read them:
  `JUSTSEARCH_RELEASE_METADATA_PUBLIC_KEY_PEM`, `JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY`
  (base64 raw Ed25519, same key as the PEM), `JUSTSEARCH_RELEASE_METADATA_ROOT_KEY_ID`,
  `JUSTSEARCH_UPDATE_ARTIFACT_PUBLIC_KEY` (Tauri base64 Minisign public key), and
  `JUSTSEARCH_UPDATE_ARTIFACT_KEY_ID`. No variable needs moving into the environment.
  The private/public pairs must match. Delete `JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED` from both
  repository and environment variables for production. The production descriptor URL is fixed
  in the workflows; the old `JUSTSEARCH_RELEASE_DESCRIPTOR_URL` variable is no longer consulted.
- Tag ruleset for **`v*`**: restrict creation to authorized release operators/agents and prohibit
  updates and deletion (including failed candidates). Keep main's existing merge checks. Limit
  who may dispatch publication to the owner's authorized operators through repository access.
- Enable Actions and GitHub artifact attestations for this repository/plan; permit the job-scoped
  `contents: write` for draft creation/publication and `id-token: write` + `attestations: write`
  for candidate attestation. All builds use hosted runners. Repository/default token permissions
  can remain read-only.

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

**Credential scope.** Release jobs and the bulk mirror producer read production keys only from
`release-signing`, whose deployment policy allows only `v*` tags and has no required reviewers.
Branch, PR and daily-smoke builds use generated test keys. See the one-time settings above
before the first tag build.

**eSigner (SSL.com) via `command` mode — validated template.** Sandbox-validated end-to-end
(2026-07-22, CodeSignTool 1.3.3, demo credentials; tempdoc 760): signs in place, non-interactive,
distinct nonzero exit codes on failure (fail-closed semantics hold through the `.cmd` wrapper).
`build-installer-windows.yml` installs CodeSignTool into `%RUNNER_TEMP%\CodeSignTool` only when the command
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
> path and positive ceiling before any command/vendor invocation; bulk mirror dispatches additionally supply provider-authoritative remaining budget.
> Installer runs enforce the existing 12-signature per-run ceiling without a balance input.

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
verification succeeds. The workflow summary reports both counts; the mirror producer separately checks provider allocation before bulk signing.

### Per-pin-bump signed-mirror procedure

The bundler signs **every** unsigned bundled PE — ~93 third-party (Tesseract + llama.cpp) + ~8 own
≈ ~100 signatures per release. Because the third-party PEs are pin-stable, sign them **once per
upstream pin bump** and re-host the signed archives, dropping per-release signings from ~100 to
**~8**. Per pin bump:

1. **Dispatch the `Sign Vendored Mirrors` workflow**
   (`.github/workflows/sign-vendored-mirrors.yml` — `workflow_dispatch` only, because it *spends*
   metered signings):

   ```bash
   gh workflow run sign-vendored-mirrors.yml --ref vX.Y.Z -f signLlama=true -f signTesseract=true -f providerRemainingSignatures=<portal-value>
   ```

   Use a protected `v*` tag containing the reviewed pins; branch dispatches cannot access
   `release-signing`. Both inputs default to `true`; set one to `false` to re-sign a single payload
   when only one pin moved. The workflow reads the pins from their existing authorities rather than restating them
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
      It fails (exit 1) listing any of `OrtCudaHelper.ortNativeDllSet()` the archive lacks for the current platform —
      on Windows: `onnxruntime.dll`, `onnxruntime4j_jni.dll`, `onnxruntime_providers_shared.dll`,
      `onnxruntime_providers_cuda.dll`. Library IDs come from the Java authority and filenames
      from the native-target contract (Linux uses the corresponding `.so` names), not a second list. A pack
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
| v0.3.0 | 2026-09-07 | No additional Sandbox verdict recorded here | Published release; in-place update from 0.2.0 remains unverified in its changelog. | [release](https://github.com/justsearch-app/justsearch/releases/tag/v0.3.0), [changelog](../../CHANGELOG.md#030---2026-09-07) |

## See also

- MCPB bundle + registry: `packaging/mcpb/README.md`
- Owner funnel: `docs/m1-operator-checklist.md`
- Workflow: `.github/workflows/build-installer.yml`
