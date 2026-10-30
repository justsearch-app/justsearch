---
title: "Cut a JustSearch release"
type: how-to
status: stable
description: "Prepare one immutable Windows/Linux draft, qualify its bytes, and publish after the owner's final validation."
---

# Cut a JustSearch release

This is the single living procedure. Put candidate identities, evidence and results in the
draft's notes and release working record, never here. All times below are estimates.

The owner's instructions (2026-10-27):

> "for each release, owner attention should only be necessary, and thats for final validation."
>
> "the release workflow doesnt need to have every issue fixed before being shipped, but only the critical ones every user would experience and that would block using justsearch in general"
>
> "i dont want for agents to automatically decide when its time for a new release, i should do that for now"
>
> "i think we will just skip the GPU tests on windows"

## 0. Keep packaging smoke running on main

[packaging-smoke.yml](../../.github/workflows/packaging-smoke.yml) runs daily at 04:23 UTC
and can be dispatched on main. It builds unsigned Windows and Linux packages when packaging
inputs changed. It uses test keys, spends no production signing balance and publishes nothing.
Inspect its results alongside the full CI run; a skipped build is not a packaging pass.
Estimate: 20–60 minutes when builds run, plus queue time.

```bash
gh run list --workflow packaging-smoke.yml --branch main --limit 5
# Only if a fresh smoke is needed:
gh workflow run packaging-smoke.yml --ref main
```

## 1. Prepare one release PR after the owner says to release

Agents never choose the release date. Choose the newest main commit with a completed full
push CI run whose **Build (no model blobs)**, **License and notices**, **Secret scan** and
packaging checks passed. Inspect every other failure against the blocker rule below;
do not require every advisory check to be green. Estimate: 15–30 minutes plus CI/merge time.

```bash
gh run list --workflow ci.yml --branch main --event push --status completed --limit 30 \
  --json databaseId,headSha,createdAt,conclusion
gh run view "$RUN" --json headSha,event,status,jobs
```

`RUN` is the inspected full run, not a PR run with unselected lanes. Record its SHA and the
packaging evidence. Selection and triage are agent work; `release-candidate.mjs admit`
checks release identity and keys, not this CI selection policy.

Create the release branch at that SHA. Edit only the canonical `version` in
`gradle.properties`, then sync the app, Cargo/npm app lock identities, MCPB manifest,
registry version, URL and bundle hash. Root/frontend npm package versions identify tooling,
not the shipped application. Add user-facing entries under `CHANGELOG.md`'s Unreleased section.
For example, with `VERSION` set to the new `X.Y.Z`:

```bash
node scripts/ci/sync-version.mjs --require-release-semver
node scripts/ci/sync-version.mjs --check
node scripts/release/release-changelog.mjs prepare --version "$VERSION" --date "$(date -u +%F)" --write
node scripts/release/release-changelog.mjs check
node scripts/ci/check-mcpb-consistency.mjs --release-version "$VERSION"
```

Merge one PR containing the version projections and changelog through the normal main checks.
Use its actual merge commit as the candidate; do not tag the PR branch head.

## 2. Tag the merge commit; build a private draft

After the release PR merges, set `MERGE_COMMIT` to that commit on main:

```bash
export REPO=justsearch-app/justsearch
export TAG="v$VERSION"
git tag "$TAG" "$MERGE_COMMIT"
git push origin "$TAG"
gh run list --workflow build-installer.yml --event push --limit 5
```

[build-installer.yml](../../.github/workflows/build-installer.yml) is the release workflow.
It calls [build-installer-windows.yml](../../.github/workflows/build-installer-windows.yml)
and [build-installer-linux.yml](../../.github/workflows/build-installer-linux.yml).
Keep all three: packaging smoke also calls both platform builders.
Tag pushes alone enter production signing. Manual branch dispatch produces unsigned
Windows qualification builds; `sandboxTestMode`/`candidateVersion` are test-build inputs,
never release inputs. A signing rehearsal uses a real tag on a **throwaway patch version**;
**never publish** its draft, and use a higher patch next time.

Estimate: 30–90 minutes plus queues. The workflow creates one private GitHub **draft** containing:
SSL.com-signed NSIS, Ubuntu 24.04 x86_64 `.deb`, both updater artifact signatures,
MCPB, signed descriptor v2, `latest.json`, compatibility registers, packaged smoke reports,
`candidate.json`, saved build attestations (`provenance.sigstore.json`) and `SHA256SUMS`.
The attestations cover the payloads and candidate inventory; the provenance bundle cannot
attest itself. Every asset except `SHA256SUMS` itself is checksummed.

The workflow's **Actions artifacts**, including the signed installers and candidate assets,
are downloadable from this public repository before promotion, until they expire. The GitHub
release stays a private draft; nothing is promoted or put on the update feed before
`publish-release.yml`.

A failed candidate is never re-tagged. Fix on main and use `vX.Y.(Z+1)`.
For an upload-only failure, **Re-run failed jobs** on the same run reuses immutable signed
outputs; existing assets must match byte for byte. A partial draft remains private.
Artifacts expire after 14 days; abandon an incomplete candidate after expiry.

## 3. Qualify the draft's exact bytes, without rebuilding

Download into a new directory, check hashes before use, and keep the tagged source and
registry alongside the evidence. Recheck hashes after copying to each lane. Never replace
qualified assets. Estimate: hosted lanes 30–120 minutes; clean VMs 1–3 hours each, sequential;
GPU 1–3 hours including a relevant 60-minute soak. Downloads and infrastructure can add time.

```bash
# QUAL is a new absolute scratch directory; ASSETS is its draft-assets subdirectory.
mkdir -p "$ASSETS"
gh release view "$TAG" --repo "$REPO" --json isDraft,tagName,targetCommitish
gh release download "$TAG" --repo "$REPO" --dir "$ASSETS"
(cd "$ASSETS" && sha256sum --check SHA256SUMS)
git worktree add --detach "$QUAL/source" "$TAG"
git show "$TAG":packaging/mcpb/server.json > "$QUAL/server.json"
gh workflow run qualify-release-windows.yml --ref main -f tag="$TAG"
gh workflow run qualify-release-linux.yml --ref main -f tag="$TAG"
```

Require `isDraft=true` and candidate identity matching the tag/merge commit. The two hosted
workflows have the single input `tag` and read-only release access; they upload observations
on every outcome. They do not publish. Watch each run to completion, including failures/stalls.

Keep the test list short:

- **J1–J8:** install/launch; add a folder, index, search and open; default Install AI;
  restart/persistence; update/recovery; uninstall/reinstall retention; desktop integration; MCP.
- **GPU:** private Linux host lane with the draft's unchanged registry and production pack URLs.
  J9 is a 60-minute soak only when indexing, enrichment or inference changed.
- **Exploration:** one exploring agent per clean VM, using its screen, at most 45 minutes each.
  Record surprises, screenshots and reproducible steps.

Follow the [VM harness README](../../scripts/release/qual/README.md) for ownership, resource
limits and screen control, and the [journey README](../../scripts/release/qual/journeys/README.md)
for tools and evidence inputs. Their test-key/rebuild procedure is **development-only**;
release qualification uses the production-trusting packages and mirror below. Do not rebuild,
re-sign, generate test signing keys or enable `JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE`.

Both full VM lanes (Ubuntu Wayland and Windows) need a **lower-version, production-pinned,
production-signed predecessor** for J5. Use the previous published release when it speaks
descriptor v2 on both targets. For the first release after 0.3.0, use the **never-published
signing-rehearsal draft** instead: for example, rehearsal `v0.3.1`, real candidate `v0.3.2`.
Keep its original packages and signing/build evidence; never publish the rehearsal or use
0.3.0 as the v2 predecessor. Download the selected predecessor separately and hash-check it:

```bash
# PREDECESSOR_TAG is the selected published v2 release or never-published rehearsal tag.
mkdir -p "$QUAL/predecessor-assets"
gh release download "$PREDECESSOR_TAG" --repo "$REPO" --dir "$QUAL/predecessor-assets"
(cd "$QUAL/predecessor-assets" && sha256sum --check SHA256SUMS)
# Set PREDECESSOR_DEB and PREDECESSOR_NSIS to those checked package paths.
```

Before running J5, check these constraints against the predecessor's tagged source/build
evidence and the candidate's unchanged metadata:

- The candidate version in `release.v2.json` and `latest.json` must match and be strictly
  higher than the installed predecessor (the updater uses SemVer ordering).
- [Sequence derivation](../../scripts/ci/derive-release-sequence.mjs) ignores drafts, so the
  rehearsal and candidate may share a sequence. In the [updater](../../modules/shell/src-tauri/src/updater.rs),
  a fresh predecessor install starts with `highest_accepted_sequence=0`; `validate_sequence`
  rejects only a **lower** sequence, so equality is accepted even after an earlier check.
  Use fresh predecessor state, not state from an unrelated newer update.
- The predecessor must pin the production descriptor URL ending in `release.v2.json`,
  metadata root public key and metadata key ID that authenticate the candidate. Keep
  `OFFLINE_LONG_LIVED_V1`. The root-signed descriptor delegates each artifact's public key
  and key ID; the updater uses that public key to verify the installer signature. Check the
  candidate artifact key/public-key fields against its production build pins and signatures;
  do not substitute test pins. Changing Actions variables cannot change an installed root.
- The descriptor's `targets` and `latest.json`'s `platforms` must both contain
  `windows-x86_64` (NSIS) and `linux-x86_64` (`.deb`). For each selected target, version,
  artifact URL and signature must match across both files; the downloaded package must
  match its descriptor hash/size. Its compatibility entries must also cover the predecessor's
  embedded durable-store register, including owner, recovery strategy and readable format.

These code rules permit the rehearsal path; they do not certify a particular package pair.
If a pin, target, compatibility entry or sequence prevents the update, report J5 blocked
with that exact mismatch; never edit signed metadata to make it pass.

Populate `MIRROR="$QUAL/mirror"` from the **candidate draft's own hash-checked downloads**.
Use their exact filenames and the paths in the signed descriptor's production URLs:

```text
mirror/
  justsearch-app/justsearch/releases/latest/download/release.v2.json
  justsearch-app/justsearch/releases/latest/download/release.v2.json.sig
  justsearch-app/justsearch/releases/latest/download/latest.json
  justsearch-app/justsearch/releases/download/vX.Y.Z/JustSearch_X.Y.Z_x64-setup.exe
  justsearch-app/justsearch/releases/download/vX.Y.Z/JustSearch_X.Y.Z_amd64.deb
```

```bash
# DEB and NSIS are absolute paths to the candidate packages in ASSETS.
MIRROR="$QUAL/mirror"
LATEST="$MIRROR/justsearch-app/justsearch/releases/latest/download"
VERSIONED="$MIRROR/justsearch-app/justsearch/releases/download/$TAG"
mkdir -p "$LATEST" "$VERSIONED"
cp "$ASSETS/release.v2.json" "$ASSETS/release.v2.json.sig" "$ASSETS/latest.json" "$LATEST/"
cp "$DEB" "$NSIS" "$VERSIONED/"
# The sources passed SHA256SUMS; require byte-identical copies before use.
for name in release.v2.json release.v2.json.sig latest.json; do
  cmp "$ASSETS/$name" "$LATEST/$name" || exit 1
done
cmp "$DEB" "$VERSIONED/$(basename "$DEB")" || exit 1
cmp "$NSIS" "$VERSIONED/$(basename "$NSIS")" || exit 1
```

The VM's generated test CA stands in **only for github.com TLS**. The mirror serves the
unchanged production-signed metadata and packages; no release private key is needed.
Run the lanes sequentially with separately prepared Linux/Windows tauri-driver tooling
from the journey README (`LINUX_DRIVER`/`WINDOWS_DRIVER` below):

```bash
export QUAL_OWNER=release-qualification
python3 scripts/release/qual/journeys/vm-run.py \
  --session wayland --assets "$ASSETS" --package "$(basename "$DEB")" \
  --registry "$QUAL/server.json" --predecessor "$PREDECESSOR_DEB" --mirror "$MIRROR" \
  --driver "$LINUX_DRIVER" --evidence "$QUAL/wayland-evidence"
# Then repeat J1 on X11; this lane needs no predecessor or mirror.
python3 scripts/release/qual/journeys/vm-run.py \
  --session x11 --assets "$ASSETS" --package "$(basename "$DEB")" \
  --registry "$QUAL/server.json" --driver "$LINUX_DRIVER" --evidence "$QUAL/x11-evidence"
windows_inputs=( \
  --assets "$ASSETS" --package "$(basename "$NSIS")" --registry "$QUAL/server.json" \
  --predecessor "$PREDECESSOR_NSIS" --mirror "$MIRROR" --driver "$WINDOWS_DRIVER" )
python3 scripts/release/qual/journeys/vm-windows.py "${windows_inputs[@]}" \
  --mode prepare --evidence "$QUAL/windows-tools-snapshot"
python3 scripts/release/qual/journeys/vm-windows.py "${windows_inputs[@]}" \
  --setup-snapshot "$QUAL/windows-tools-snapshot" --evidence "$QUAL/windows-evidence"
```

Use `gpu-host.py --source` with the exact tagged checkout and `--last-release` naming the
last **published** release (local tags can include abandoned drafts):

```bash
PREVIOUS_TAG=$(gh release view --repo "$REPO" --json tagName --jq .tagName)
# Add --source "$QUAL/source" --last-release "$PREVIOUS_TAG" to the README's GPU command.
```

**Never pass `--development-pack-mirror`** for release qualification. The runner decides
J9 relevance from that source's release diff. Development subsets/resume results are not full acceptance.

**Coverage limits:** the journey implementations remain experimental; their READMEs record
unqualified paths. Hosted crash coverage can cap an otherwise successful run at pending
validation (exit 2). Report passed, failed, skipped by decision and unavailable separately.
Chat on a **Windows GPU is not tested**, by owner decision. Hosted/VM lanes do not prove GPU
chat. Do not turn an unavailable check or infrastructure failure into a product pass.

## 4. Agents triage and fix forward

A defect blocks only if it breaks a normal path for **most users of a supported target**,
with **no reasonable workaround**: install, launch, add a folder, index to completion,
search, open a result, or the default Install AI plan (search everywhere; chat on a supported GPU).
Always block known security or data-loss defects and invalid release artifacts: missing
packages, bad signatures or broken update metadata.

Non-blockers include uncommon hardware, file types or configurations; GPU-only failures
where CPU search works; uninstall leftovers that lose no data; cosmetic/wording issues;
and automatic 0.3.0 upgrade repairs (0.3.0 users install the new version over the old one).
Record these as known issues. Agents make the triage decision without asking the owner.

Fix blockers on main, merge the next patch release PR and produce a new draft. Rerun the
journeys the fix can affect plus J1–J2; keep evidence of unaffected journeys and its candidate
identity explicit. The existing runners label subsets incomplete: retain that limit when
assembling the overall qualification evidence. Repairs take as long as the defect requires.

## 5. Prepare the owner's final validation

Prepare both clean OS environments with the candidate installed and the corpus ready, then
open them visibly on the owner's monitor. The current harness supports **one VM at a time**:
use these commands sequentially, stopping your VM before preparing the other.

```bash
export QUAL_OWNER=owner-release-round
python3 scripts/release/qual/vm.py prepare-owner ubuntu --package "$DEB" --corpus "$CORPUS"
# Open JustSearch through the VM screen, add/index the corpus, and present it visibly.
# After that OS's validation:
python3 scripts/release/qual/vm.py stop
python3 scripts/release/qual/vm.py prepare-owner windows --package "$NSIS" --corpus "$CORPUS"
```

`DEB`/`NSIS` must name the hash-checked draft packages. `prepare-owner` installs and copies the
corpus to Documents, then boots the same overlay visibly; **it does not start the app or index
the corpus**. Finish those actions through the screen before inviting validation. There is
currently no automated restore of qualified application snapshots into this owner view.

Give the owner one page: version, what changed, journey results, what was not tested and
known issues. Update the draft's notes from that summary, filling `Not tested` and `Known issues`;
retain the hidden candidate-identity marker. The owner tries the result for about **10 minutes**
and replies **"publish"** or names what is wrong. Agents handle preparation, evidence and
triage. Estimate: 15–30 minutes preparation per VM plus indexing/download time.

## 6. Publish only after "publish", then watch for a week

```bash
gh workflow run publish-release.yml --ref main -f tag="$TAG"
gh run list --workflow publish-release.yml --limit 5
```

[publish-release.yml](../../.github/workflows/publish-release.yml) checks the closed asset set,
hashes, updater signatures, saved provenance, original tag/commit and published sequence floor,
then promotes the existing draft as latest. It **never rebuilds or re-signs**. The production
feed is `https://github.com/justsearch-app/justsearch/releases/latest/download/release.v2.json`.
Confirm the latest release/feed resolves to the promoted bytes. Estimate: 5–15 minutes plus queue.
Publication of the same candidate is idempotent; a failed promotion can be retried on the same tag.

An agent watches new issues for a week, triages using the same blocker rule and fixes forward.
Arrange that watch explicitly; there is no automatic week-long issue watcher in these workflows.

## Recovery and downgrade policy

Never delete or edit a **published** release. Repair with a higher version and sequence,
through the updater including its dead-Engine update path, or by installing the new package.
Sequence is the maximum from published v1/v2 descriptors plus one, with floor 41; drafts do
not advance it. Unreadable descriptors fail closed. If another publication makes a draft's
sequence stale, abandon it and prepare the next patch candidate.

A failed update reconciles ownership/state on restart on Windows; Linux follows the same
recovery contract. The documented fallback is installing the new package over the old one.
On Linux, `apt remove just-search` preserves settings, index, downloaded models and authored
records under `~/.local/share/io.justsearch.shell` (or `$XDG_DATA_HOME`); reinstall reuses them.
Indexed source files are not removed. The updater has no downgrade path.

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

## Owner duties and one-time settings

Per release, the owner says **when to release** and performs the final validation. Agents do
all other recurring work. Rare duties: renew the code-signing certificate and signing balance;
agents remind the owner a month ahead. There is no automated renewal reminder in this repository.

Set these once in the **source repository's Settings → Environments / Secrets and variables → Actions**:

| Location | Setting |
|---|---|
| Environment `release-signing` → Deployment branches and tags | Selected branches and tags: **Tag** rule matching **`v*`** and **Branch** rule matching **`main`** only. Keep main protected. Required reviewers empty/disabled, wait timer disabled, no custom approval rule. |
| Environment secrets: Authenticode | `JUSTSEARCH_CODESIGN_MODE=command`; `JUSTSEARCH_CODESIGN_COMMAND` with the SSL.com template below. The workflows also read `JUSTSEARCH_CODESIGN_PFX_B64`, `JUSTSEARCH_CODESIGN_PFX_PASSWORD`, `JUSTSEARCH_CODESIGN_TIMESTAMP_URL` and `JUSTSEARCH_CODESIGN_THUMBPRINT`; these are needed only for the corresponding PFX/store mode, not SSL.com command mode. Release admission currently requires the command credential. |
| Environment secrets: updater / metadata | `JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY`, `JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY_PASSWORD` (empty only for an unencrypted key), `JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PEM` (Ed25519 metadata root). |
| Environment secret: Linux pack producer | `JUSTSEARCH_RELEASES_PACK_TOKEN`: fine-grained PAT restricted to **`eliasjustus/justsearch-releases`**, **Contents: read and write**, mandatory Metadata read. Used only by `build-linux-gpu-packs.yml` to create its private draft; the source repo's `GITHUB_TOKEN` cannot write there. |
| Repository Actions variables (public) | `JUSTSEARCH_RELEASE_METADATA_PUBLIC_KEY_PEM`, `JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY` (base64 raw Ed25519 matching the PEM), `JUSTSEARCH_RELEASE_METADATA_ROOT_KEY_ID`, `JUSTSEARCH_UPDATE_ARTIFACT_PUBLIC_KEY` (Tauri base64 Minisign), `JUSTSEARCH_UPDATE_ARTIFACT_KEY_ID`. Private/public pairs must match. |
| Repository/environment variables | Delete `JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED` for production. The old `JUSTSEARCH_RELEASE_DESCRIPTOR_URL` variable is unused; workflows set the production URL directly. |
| Repository Actions permissions | Enable Actions and artifact attestations; permit job-scoped `contents: write` for draft/publish and `id-token: write` + `attestations: write` for provenance. Default token permissions may remain read-only. Builds use hosted runners. |
| Repository tag ruleset `v*` / access | Restrict tag creation to authorized release operators; prohibit update/deletion, including failed tags. Restrict publication dispatch through operator access. |

Keep production signing secrets **only** in `release-signing`: remove repository copies, other
environment copies and organization-secret access that exposes the same credentials outside it.
No per-release balance input, provider-portal visit or environment approval is required.

The SSL.com command template (secret; substitute credential values once) is:

```text
%RUNNER_TEMP%\CodeSignTool\CodeSignTool.bat sign -username=<eSigner login> -password="<password>" -totp_secret="<TOTP secret>" -credential_id=<credential id> -input_file_path="{file}" -override="true"
```

The Windows builder installs the hash-pinned CodeSignTool when the template references it.
The signer verifies Authenticode and enforces its run-local attempt ledger and 12-signature
installer ceiling. Keep the certificate current and provider balance sufficient.

One-time local duties: retain an **offline backup of the metadata root key**, and arrange the
Windows test VM licence using Microsoft's Windows 11 Enterprise evaluation (90-day expiry;
see the [VM README](../../scripts/release/qual/README.md)). Artifact signing keys are delegated
and replaceable through root-signed metadata. Root-key loss needs the offline backup and a
written bridge procedure; **no operational root-key bridge procedure exists in this repository**.
Do not assume changing Actions variables can update clients' compiled trust roots.

## Maintenance when dependency pins change

Ordinary app releases reuse immutable signed mirrors and Linux GPU packs. Agents handle pin
maintenance, rather than adding owner chores to each release.

For Windows vendored mirrors, the retained producer signs pinned inputs once per pin bump
(estimate 20–40 minutes); it requires the provider's authoritative remaining allocation:

```bash
gh workflow run sign-vendored-mirrors.yml --ref main \
  -f signLlama=true -f signTesseract=true -f providerRemainingSignatures=<portal-value>
gh run download <run-id> -n signed-vendored-mirrors
node scripts/release/regen-tesseract-manifest.mjs <tesseract-signed.zip>
```

Upload the signed ZIPs to the payload repository, then PR the exact URL/SHA256 pairs in
`packaging/signed-mirrors.v1.json` and regenerated Tesseract per-file pins together. Preserve
upstream source pins and extracted layout. Tesseract redistribution requires its GPL notice
and source-offer treatment in the payload repository. No partial URL/hash entry is accepted.

### Linux GPU pack procedure

For Linux packs, [build-linux-gpu-packs.yml](../../.github/workflows/build-linux-gpu-packs.yml)
accepts only a new immutable `linux-cuda12-<positive integer>` tag on main (estimate 1–6 hours):

```bash
gh workflow run build-linux-gpu-packs.yml --ref main -f tag=linux-cuda12-<N>
```

It builds pinned inputs, audits glibc/dependency closure and creates a private draft in
`eliasjustus/justsearch-releases`, never publishing. Validate the exact ZIPs using the
[private Install AI journey](test-gpu-locally.md#host-only-install-ai-check-with-a-registry-override),
then obtain the owner's final validation before publishing that pack draft. Merge its generated
`model-registry.v2.json` pins only after publication; never commit placeholder URLs/hashes.

When ORT changes, keep its Gradle JAR version, rebuilt GPU pack, in-pack
`ort-native-version.txt`, registry filename/hash/size and
`OrtCudaHelper.EXPECTED_ORT_NATIVE_VERSION` aligned. Before pinning an archive:

```bash
node scripts/release/check-ort-native-asset.mjs <ort-native-pack.zip>
```

See also [download verification](verify-your-download.md),
[MCPB packaging](../../packaging/mcpb/README.md) and the
[supplementary model-retention check](verify-update-preserves-models.md).
