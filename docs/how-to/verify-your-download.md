---
title: "Verify your JustSearch download"
type: how-to
status: stable
description: "Check Windows and Linux downloads with SHA256SUMS, GitHub build attestations and Windows publisher identity."
audience: user
related: docs/how-to/cut-a-release.md; README.md
---

# Verify your JustSearch download

JustSearch installers are **Authenticode-signed as of v0.2.0**; the expected publisher is
**Elias Justus**. Releases provide a Windows x86_64 NSIS installer and a Linux x86_64 `.deb`
for Ubuntu 24.04 or newer. Check the release hash and GitHub build attestation on either platform,
and the Authenticode signature on Windows. You can also build from source. A valid signature proves publisher identity and byte integrity; it is not a claim that the
software is bug-free or that Windows reputation warnings can never appear.

## 1. Check the SHA-256 hash

Download the package for your platform, `SHA256SUMS`, and `provenance.sigstore.json` from
**the same release**. Example package names:

| Platform | Asset |
|---|---|
| Windows x86_64 | `JustSearch_<version>_x64-setup.exe` |
| Linux x86_64 | `JustSearch_<version>_amd64.deb` |

`SHA256SUMS` covers every release asset except itself, including both packages, updater metadata
and signatures, MCP bundle, packaged check reports and the saved provenance bundle. The
[two-platform candidate producer](../../scripts/release/release-candidate.mjs) generates it from
the exact draft bytes later promoted for publication.

**On Windows**, from the folder you downloaded to:

```powershell
certutil -hashfile JustSearch_<version>_x64-setup.exe SHA256
```

Compare the printed hash (case-insensitive) against the matching line in the release's
`SHA256SUMS`. They must match exactly.

**On Linux, git-bash or WSL**, if you downloaded the installer and `SHA256SUMS` into the same folder:

```bash
sha256sum --ignore-missing -c SHA256SUMS
```

Confirm the package you downloaded has a matching line and says **OK**. `SHA256SUMS` also lists the MCP bundle and updater assets;
`--ignore-missing` skips files you did not download.

A matching hash checks consistency with `SHA256SUMS`. Verify provenance as well to establish
which repository and workflow produced those bytes.

## 2. Verify GitHub build provenance (Windows and Linux)

With [GitHub CLI](https://cli.github.com/manual/gh_attestation_verify) installed, run the command
for your downloaded package (replace `<version>` with the release version):

```powershell
gh attestation verify JustSearch_<version>_x64-setup.exe --bundle provenance.sigstore.json --repo justsearch-app/justsearch --signer-workflow justsearch-app/justsearch/.github/workflows/build-installer.yml
```

```bash
gh attestation verify JustSearch_<version>_amd64.deb --bundle provenance.sigstore.json --repo justsearch-app/justsearch --signer-workflow justsearch-app/justsearch/.github/workflows/build-installer.yml
```

Require successful verification and check that the reported source ref is the intended `vX.Y.Z`
tag and its commit. This verifies the package hash against signed GitHub build provenance from
this repository's release workflow. Stop if the checksum or provenance check fails. These checks
establish origin and integrity; they do not establish that the software is bug-free.

## 3. Check the signature and understand OS warnings

In PowerShell:

```powershell
$signature = Get-AuthenticodeSignature .\JustSearch_<version>_x64-setup.exe
$signature.Status
$signature.SignerCertificate.Subject
```

For a published build from v0.2.0 onward, `Status` must be `Valid` and the subject must identify
Elias Justus. Stop if the signature is absent, invalid, or names an unexpected publisher even when
the SHA-256 matches.

### SmartScreen ("Windows protected your PC")

Windows may still show a SmartScreen reputation warning for a newly signed application or
certificate. That warning is separate from Authenticode validity: verify both the hash and the
publisher rather than treating “Run anyway” as the verification step. A specific malware detection
is a different signal and should not be bypassed. If the checks above do not match, stop and use the
build-from-source path below.

### Windows Smart App Control (SAC)

Smart App Control is a stricter Windows 11 policy and may block software that its trust evaluation
does not accept. The signed v0.2.0 installer removes the old “necessarily unsigned” condition, but
only a clean-machine observation can establish the current Windows reputation experience. Do not
disable a security control merely to make an unexplained signature or publisher mismatch disappear.

## 4. Build from source instead

If you'd rather not run a downloaded binary at all, JustSearch builds from source with **JDK 25**
and **Node.js** — no GPU, no Rust toolchain, and no model download needed just to build:

```bash
git clone https://github.com/justsearch-app/justsearch && cd justsearch
node scripts/lib/tool-invoker.cjs gradle build
```

To produce a Windows installer locally, use
[`scripts/ci/package-installer-win.ps1`](../../scripts/ci/package-installer-win.ps1) on Windows with
Node.js 24, JDK 25, MSVC C++ Build Tools, and the Rust toolchain installed:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ci/package-installer-win.ps1
```

The local build fails its preflight if Windows Smart App Control is enforcing: SAC blocks unsigned
cargo build-scripts (`os error 4551`). Use a separate build machine where SAC is not enforcing;
do not disable a security control to bypass a signature mismatch. A local build is not signed with
the project's publisher certificate. It lets you build the source you inspect; matching the release
hash is not expected, especially because signing changes the bytes.

**Maintainers with repository write access** can instead dispatch the build workflow:

```bash
gh workflow run build-installer.yml --ref main
gh run download <run-id>
```

These manual branch builds are unsigned and use test trust pins. Production signing runs only
on protected `v*` tags in `release-signing`, without per-release reviewer approval. Maintainers
should follow [Cut a release](cut-a-release.md) for the complete candidate sequence.

## See also

- [`cut-a-release.md`](cut-a-release.md) — the full release pipeline, including where
  `SHA256SUMS` and the MCPB bundle come from.
- [Threat model](../reference/security/threat-model.md) — what JustSearch does and doesn't send
  over the network, independent of the installer's signing status.
