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
> HTTP API (Head), a Lucene-owning Worker, and a local inference server (`llama-server`). There is no
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
| **Head ↔ index half / Inference** | The Head and the index half share one Engine JVM and meet at direct in-process calls — no socket, no listener, no IPC (ADR-0049; the gRPC channel and the MMF bus are deleted). `llama-server` remains a separate process on loopback. Not network-exposed. |
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
| **Lifecycle and retry-header CORS visibility** | For an already-allowed browser origin, `Access-Control-Expose-Headers` permits JavaScript to read `Deprecation`, `Sunset`, `Link`, and `Retry-After` when a route emits them. A foreign Origin receives no CORS grant, so this does not expose those headers to a foreign page. | Response visibility only; it does not broaden the loopback bind, Host allowlist, MCP Origin check, mutation token, or CSP. |
| **MCP endpoint Origin check** (`isAllowedMcpOrigin` / `setupMcpOriginValidation`) | A request to `/mcp` **or `/api/mcp/token`** carrying a non-loopback `Origin` — rejected with **403**, on every method those paths serve. Absent `Origin` is allowed (native MCP hosts are not browsers); the value is parsed as a URI and its **host component** compared for equality, so lookalikes like `http://127.0.0.1.evil.com` do not pass. | The MCP Streamable-HTTP spec's own MUST ("Servers MUST validate the `Origin` header on all incoming connections to prevent DNS rebinding attacks"). Distinct from the CORS row: CORS only withholds a response-reading grant, whereas the spec requires an explicit rejection. See the token-route note below for why the second path is in scope. |
| **Session token on mutations** (`setupSessionTokenEnforcement`) | A foreign caller from performing `POST`/`PUT`/`DELETE` in prod — and, regardless of method, from reaching the run family under `RunRoutes.PATH_PREFIX` (`/api/chat/runs`), whose journal carries prompts, answers and retrieved passage text. `OPTIONS` is exempt everywhere, since a CORS preflight cannot carry the header. The token is generated **per Head boot** (`HeadlessApp.java:364-365`) and delivered to the UI via the Tauri bridge — a web origin cannot obtain it. A backend restart mints a **new** token, so a client still holding the previous one fails **closed** (401), never open. **The control itself also fails closed:** prod mode with a null/blank token makes the Head refuse to construct the API and exit, rather than serving with mutation-gating silently off (tempdoc 884 item 23; before 2026-09-02 this branch logged `TOKEN_ENFORCEMENT_DISABLED` and continued). | Covers `POST`/`DELETE /mcp` tool calls (`LocalApiServer.java:644-645`) and `justsearch_ingest`. |
| **Loopback bind** (Hard Invariant #2) | Remote network access entirely — the API binds `127.0.0.1`, never `0.0.0.0`. | Necessary baseline; not sufficient alone. |
| **Production capabilities are live-derived** (`InfraPhase`) | A production launch from replacing the `/infra/capabilities` handshake with a file-backed development fixture. | `app.api.fake_capabilities` and `APP_API_FAKE_CAPABILITIES` are honored only outside production; `HeadAssemblyTest.productionModeIgnoresFileBackedCapabilitiesOverride` pins the boundary. |

Together: mutations and the MCP retrieval backend (`POST /mcp`) are token-protected; token-exempt GET
reads are protected by the Host-allowlist; `/mcp` additionally enforces the MCP spec's Origin check
on every method; remote access is barred by the loopback bind.

The generated Node runtime client applies the same boundary in the outbound direction. Its async
factory accepts only an `http://` loopback origin with no credentials, path, query, or fragment;
every built-in Fetch request sets `redirect: "error"`, so a loopback server cannot redirect the SDK
to a non-loopback destination. The factory also reads the public runtime manifest and verifies its
Runtime Contract version before returning any operation methods. An injected Fetch implementation is
a trusted host-integration seam and must honor the supplied redirect policy.

**`GET /api/mcp/token` — the bootstrap, and why it is Origin-guarded too (reviewed 2026-08-13).**
This route is token-**exempt** by construction: it is how a legitimate client obtains the token in
the first place, so it cannot itself demand one. That makes it the softest point of the token
scheme, which is why it was reviewed separately once `/mcp` gained its Origin check.

*Residual read paths, after the Host allowlist and CORS:*

- **A rebound browser page** — blocked by the Host allowlist: rebinding leaves the attacker's domain
  in `Host`, so the request 403s before any handler runs.
- **A normal cross-origin page** — the `fetch` is *sent* (CORS is not a request-blocker) and the
  handler *runs*; CORS only withholds the grant to **read** the response. Nothing is mutated, so the
  practical exposure was already nil — but "the response was produced and merely not readable" is a
  thinner margin than the rest of this table, and it depends on the browser enforcing it.
- **A native local process** — can read the token, and always could. Out of scope: a local process
  with the user's privileges can read the data dir and the runtime manifest directly (which is where
  the desktop shell itself gets the token). Nothing at this layer can change that.

*Decision: guard it, on the same allowlist as `/mcp`.* The check costs no legitimate caller
anything, because **no legitimate caller is a browser**. Verified caller inventory: the MCPB bridge
(`packaging/mcpb/server/index.js:101`) and `scripts/prod/justsearch-mcp/discovery.mjs:98` are Node
processes; the sandbox and installer probes are PowerShell (`scripts/sandbox/*.ps1`,
`scripts/ci/verify-installer-nsis-win.ps1:673`) — all send no `Origin` and are admitted unchanged.
The desktop shell never calls this route at all: it reads `head.sessionToken` from the runtime
manifest (`modules/shell/src-tauri/src/binding.rs:116`). `modules/ui-web` contains no reference to
it outside the generated route manifest. So the second-bullet path closes for the cost of one
before-filter, and the remaining exposure is the native-process one that no HTTP-layer control can
address. Regression-tested in `McpOriginValidationTest` (foreign Origin → 403 with the token absent
from the body; absent/loopback/`tauri://` Origin → 200).

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
