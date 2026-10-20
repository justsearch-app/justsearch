---
title: Threat Model
type: reference
status: stable
description: "STRIDE threat model for the local-first, loopback-only architecture; the provable-privacy story and its mechanical anchors."
---

# Threat Model

This doc answers: *"What is JustSearch's attack surface, what is the privacy guarantee, and what
mechanically enforces it?"* It is a STRIDE-style threat model over the local-first architecture and the
basis for the README's **"nothing leaves your machine"** claim. It is an NLnet-M2 deliverable.

> **Scope.** JustSearch runs entirely on the user's machine: a desktop shell (Tauri webview), a loopback
> HTTP API, one Engine JVM whose index half owns Lucene, and a local inference server (`llama-server`). There is no
> server-side component, no account, and no cloud processing of user documents. The guarantee here is
> **privacy** (your files and queries stay local), **not** infallibility of AI answers — always check a
> citation. See the project's `NON-GOALS` (at the repository root in the public release).

## Assets

1. **The user's documents** (indexed content, snippets, embeddings) — the primary asset; JustSearch never sends it off the device. (An MCP client the user connects receives passages as tool results — see [what this model does not claim](#what-this-model-deliberately-does-not-claim).)
2. **Search queries and AI conversations** — reveal user intent; local-only.
3. **The local API** — the control surface for retrieval, ingest, and RAG (`modules/ui`).
4. **The model files** — integrity matters (a tampered model could degrade or mislead); see [first-run](#first-run-and-supply-chain).

## Trust boundaries

| Boundary | Description |
|---|---|
| **Webview ↔ Head** | The Tauri webview talks to the loopback HTTP API. Confined by CSP + Host/Origin/token checks. |
| **Other local processes ↔ Head** | Any process (or a malicious web page the user visits) can attempt to reach `127.0.0.1:<port>`. The key inbound boundary. The decision of *where* that boundary is drawn — and what is deliberately inside it — is [ADR-0046: Local API trust boundary](../../decisions/0046-local-api-trust-boundary.md); this document is its threat analysis. |
| **Head <-> index half / Inference** | The Head and the index half share one Engine JVM and meet at direct in-process calls - no socket, no listener, no IPC (ADR-0049; the retired gRPC channel and MMF bus no longer exist). `llama-server` remains a separate process on loopback. Not network-exposed. |
| **Device ↔ Internet** | The only intended egress is the one-time model download. Everything else stays local. |

## The privacy guarantee and its mechanical anchors

The "nothing leaves your machine" claim rests on three enforced properties, each with a source-of-truth
anchor (so the claim is *checkable*, not promised):

1. **The webview cannot egress to the public internet.** The Content-Security-Policy pins
   `connect-src` to loopback only — `connect-src 'self' ipc: http://ipc.localhost http://127.0.0.1:*`,
   with `script-src 'self'` and no external `img-src`/`script-src` origins
   (`modules/shell/src-tauri/tauri.conf.json`, the `csp` field). A compromised or buggy frontend cannot
   `fetch`/`XHR`/`WebSocket` to an external host. *(Drift guard: `scripts/docs/check-privacy-claims.mjs`
   fails the build if the CSP stops pinning loopback.)*
2. **No product telemetry exporter exists.** The shipped code contains no analytics/telemetry SDK
   (no PostHog/Segment/Sentry/Mixpanel/Amplitude). The only "telemetry" in the codebase is **internal**
   Lucene/runtime metrics that are never exported off-device. **Do not confuse** this with the
   *development-time* agent-analytics OTLP sink (`scripts/agent-analytics/otlp-sink.py`) — that is a
   dev-only tool, binds `127.0.0.1:4318`, writes under `tmp/`, and is **not part of the shipped app**.
3. **The only intended egress is the one-time model download** (from GitHub Releases + Hugging Face).
   After first run, the app operates fully offline; a user can confirm with a network monitor. See
   [First-run and supply chain](#first-run-and-supply-chain).

## STRIDE analysis

### Spoofing / Tampering / Information disclosure — the inbound loopback surface ★

The most important threat is **not** outbound egress (the CSP confines that) but **inbound** access to
the loopback API by software other than the legitimate webview. "Loopback-only" means *private from the
internet*; it does **not** by itself mean *isolated from other local software*.

- **DNS rebinding.** A malicious web page the user visits can, after a short DNS TTL, re-resolve its own
  domain to `127.0.0.1` and issue requests to the local API from the user's browser. This is the dominant
  2025–2026 attack class against local services (multiple MCP-server CVEs — e.g. CVE-2026-11624,
  CVE-2026-42559; the Python SDK fix in 1.23.0; the TypeScript SDK shipping protection off by default),
  with the **Ollama DNS-rebinding CVE-2024-28224** as the exact precedent: a rebound page read and
  exfiltrated arbitrary files a local LLM server could reach.
- **Cross-process access.** Any local process can attempt to call `127.0.0.1:<port>`.

**Mitigations (defense in depth), all in `modules/ui/src/main/java/io/justsearch/ui/api/ApiSecurityFilters.java`:**

| Control | What it stops | Note |
|---|---|---|
| **Host-header allowlist** (`isAllowedHost` / `setupHostValidation`) | DNS rebinding. After rebinding the request is *same-origin* with the attacker domain, so CORS no longer applies — but the server still sees the attacker's domain in the `Host` header and returns **403**. Only loopback hosts (`127.0.0.1`/`localhost`/`::1`) are accepted. | The canonical DNS-rebinding defense; applies to **all** methods, incl. token-exempt GET reads. |
| **CORS Origin allowlist** (`resolveAllowedOrigin`) | A normal cross-origin page from reading API responses. In prod, only the desktop origins (`tauri://localhost`, `http(s)://tauri.localhost`) are allowed. | Protects response-reading; insufficient alone vs. rebinding (hence the Host check). |
| **Lifecycle and retry-header CORS visibility** | For an already-allowed browser origin, `Access-Control-Expose-Headers` permits JavaScript to read `Deprecation`, `Sunset`, `Link`, and `Retry-After` when a route emits them. A foreign Origin receives no CORS grant, so this does not expose those headers to a foreign page. | Response visibility only; it does not broaden the loopback bind, Host allowlist, MCP Origin check, session token, or CSP. |
| **MCP endpoint Origin check** (`isAllowedMcpOrigin` / `setupMcpOriginValidation`) | A request to `/mcp` carrying a non-loopback `Origin` — rejected with **403**, on every method that endpoint serves. Absent `Origin` is allowed (native MCP hosts are not browsers); the value is parsed as a URI and its **host component** compared for equality, so lookalikes like `http://127.0.0.1.evil.com` do not pass. | The MCP Streamable-HTTP spec's own MUST ("Servers MUST validate the `Origin` header on all incoming connections to prevent DNS rebinding attacks"). Distinct from the CORS row: CORS only withholds a response-reading grant, whereas the spec requires an explicit rejection. The token bootstrap endpoint has been removed. |
| **Session token on non-public routes** (`setupSessionTokenEnforcement`) | All production requests require `X-JustSearch-Session`, including reads and streams, except `OPTIONS` and `GET`/`HEAD` on `/api/health`, `/api/runtime/live`, `/api/runtime/ready`, `/api/status`, `/api/runtime/manifest`, and `/.well-known/justsearch/manifest.json`. The per-boot token is read from the owner-only canonical runtime manifest; missing or stale credentials receive 401. Production without a token refuses to start. | Future routes inherit authentication. Dev mode stays token-free. |
| **Owner-only credential file** (`OwnerOnlyFile`, `RuntimeManifestPublisher`) | Another local OS account cannot read the canonical manifest. Fresh temporary files receive POSIX 0600 or a Windows owner-plus-SYSTEM ACL before secret bytes are written. Every publish replaces older readable files. | New history omits the token. Older history remains on disk but is redacted in HTTP/MCP/SSE responses. |
| **Loopback bind** (Hard Invariant #2) | Remote network access entirely — the API binds `127.0.0.1`, never `0.0.0.0`. | Necessary baseline; not sufficient alone. |
| **Production capabilities are live-derived** (`InfraPhase`) | A production launch from replacing the `/infra/capabilities` handshake with a file-backed development fixture. | `app.api.fake_capabilities` and `APP_API_FAKE_CAPABILITIES` are honored only outside production; `HeadAssemblyTest.productionModeIgnoresFileBackedCapabilitiesOverride` pins the boundary. |

Together: the owning OS user can read the credential and use non-public APIs. Other local accounts
and web origins cannot bootstrap through HTTP. Host, MCP Origin and CORS checks remain in force;
remote access is barred by the loopback bind.

The generated Node runtime client applies the same boundary in the outbound direction. Its async
factory accepts only an `http://` loopback origin with no credentials, path, query, or fragment;
every built-in Fetch request sets `redirect: "error"`, so a loopback server cannot redirect the SDK
to a non-loopback destination. The factory also reads the public runtime manifest and verifies its
Runtime Contract version before returning any operation methods. An injected Fetch implementation is
a trusted host-integration seam and must honor the supplied redirect policy.

**No HTTP credential bootstrap.** `/api/mcp/token` is removed: 401 without a token in production,
404 with a valid token. The headless Engine no longer prints the credential. Native bridges read
it from the same canonical manifest whose pid, instance and port they validate, and only send it
after proving that the connection belongs to that Engine. Public discovery probes send no token
or tool arguments. Explicit port overrides require `JUSTSEARCH_SESSION_TOKEN`.

**Accepted residual:** another native process running as the owning OS user can read the file.
That user is the trust boundary; another local account is outside it. See ADR-0046 and
`ProductionRouteTokenWalkTest` for the registered-route authentication check. Windows ACL and
second-account access require platform verification in addition to source probes.

**This token is deliberately independent of the trust lattice's per-action consent gate (tempdoc
655) — a different axis, not a substitute.** The session token answers "is this caller allowed to
reach the API at all" (authentication); the trust lattice's `GateBehavior`
(`AUTO`/`INLINE_CONFIRM`/`TYPED_CONFIRM`/`DENY`, consumed via a pending-authorization + capsule/
durable-grant ceremony) answers "is this specific action approved" (authorization) — holding the
token satisfies neither `INLINE_CONFIRM` nor `TYPED_CONFIRM` on its own, and vice versa. Two
independent layers an attacker (or a misbehaving MCP client) must clear, by design.

### Repudiation
Single-user local app; no multi-tenant identity. Out of scope — there is no shared server to repudiate to.

### Information disclosure at rest — the action-ledger audit journal
The action-ledger audit journal (grant, gate and operation records — including operation subjects and
scan roots) persists **as plaintext at rest** under the data dir. This is an accepted, dated owner
decision (2026-08-06, tempdoc 812): accept-and-document, not an oversight.

Two facts bound what that exposure is. First, the journal holds **metadata, not content** — which
folders were scanned, which grant was given, which gate fired and when; it does not carry document
text. A reader with disk access learns *what you pointed the app at*, not *what is in those files*.
Second, the encrypted-store catalog **deliberately excludes it**: a catalog entry without a real
cipher behind it would be a false claim of sealing, and this model would rather name an unsealed
store than list one that only looks sealed. Neither fact is a defence against the threat the model
already declines to cover — a malicious local user with disk access (see *What this model
deliberately does not claim*).

Revisit this if audit rows ever start carrying **content** rather than metadata — for example a query
string, a snippet, a document title, or an answer excerpt. At that point the decision changes
category, because the "metadata, not content" bound is the whole reason plaintext is acceptable here.

### Denial of service
A local process could spam the loopback API; impact is confined to the user's own machine (no remote
amplification, no shared service). Deny logging is rate-limited (`maybeRecordHostDeny`/`maybeRecordTokenDeny`)
to avoid log floods during a probe.

### Elevation of privilege
The API delegates index IO to the Worker over IPC (Hard Invariant #1 — Head never touches Lucene), so a
compromised Head cannot directly corrupt the index. The MCP tool surface follows least-privilege in
spirit: read tools (`justsearch_search`/`answer`/`browse`/`status`) vs. the privileged write tool
(`justsearch_ingest`), with mutations gated by the session token.

## First-run and supply chain

First run downloads models once. Integrity + availability considerations:

- **Integrity.** Each model package pins a `sha256` in `model-registry.v2.json`; a tampered download
  fails verification. Every model/runtime `downloadUrl` must use HTTPS and resolve from an allowlisted
  public host.
- **Availability / single points of failure.** Project-controlled model assets resolve from the
  project's own releases repo. The packaged chat GGUF and mmproj currently resolve from
  `huggingface.co/bartowski/...`, and the `llama.cpp` binaries resolve from `github.com/ggml-org/...`;
  those upstream locations remain availability dependencies until the registry points at a
  project-controlled mirror. *(Drift guard: `ModelRegistryLoaderTest` asserts every `downloadUrl`
  resolves from an allowlisted public host, and `scripts/docs/check-privacy-claims.mjs` fails if future
  mirror wording conflicts with the current registry URLs.)*

## What this model deliberately does not claim

- **Not** that the app makes zero network connections — it downloads models on first run, and runs a
  local `llama-server`. The claim is that **your documents and queries never leave the device**.
- **Not** that a connected MCP client keeps the data local. `justsearch_search` and `justsearch_answer`
  return file paths and passage text as tool results to whichever client the user connected (see
  [`mcp-production-server.md`](../mcp-production-server.md) § Data exposure). A client running a
  cloud-hosted model forwards those results to its provider; that egress is the client's, chosen by the
  user, and outside this model's boundary. The built-in assistant and a local-model client keep the
  loop on-device.
- **Not** infallibility — AI answers can be wrong; the guarantee is privacy, not correctness.
- **Not** protection against a fully compromised host OS or a malicious local user with disk access.

## See also
- [`mcp-production-server.md`](../mcp-production-server.md) — the MCP endpoint surface.
- [`api-contract-map.md`](../api-contract-map.md) — the HTTP and in-process port contract sources.
- `ApiSecurityFilters.java` — the request-filter security plumbing (CORS / Host / token / capability gates).
