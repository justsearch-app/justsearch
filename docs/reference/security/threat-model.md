---
title: Threat Model
type: reference
status: stable
description: "STRIDE threat model for local processing, outbound connections, update trust, and the loopback API boundary."
---

# Threat Model

This doc answers: *"What is JustSearch's attack surface, what is the privacy guarantee, and what
mechanically enforces it?"* It is a STRIDE-style threat model over the local-first architecture and the
basis for the README's privacy description. It is an NLnet-M2 deliverable.

> **Scope.** JustSearch runs entirely on the user's machine: a desktop shell (Tauri webview), a loopback
> HTTP API, one Engine JVM whose index half owns Lucene, and a local inference server (`llama-server`). There is no
> server-side component, no account, and no cloud processing of user documents. The guarantee here is
> local processing for search and built-in inference. Update checks, AI downloads, optional trace export,
> and user-selected integrations cross the device boundary as described below.
> AI answers can be wrong — always check a citation. See the project's `NON-GOALS` at the repository root.

## Assets

1. **The user's documents** (indexed content, snippets, embeddings) — processed locally. Connected MCP clients and tools receive the data passed to them; enabled trace export can carry paths and excerpts (see below).
2. **Search queries and AI conversations** — reveal user intent; handled locally by the built-in assistant. Connected external clients and tools receive the inputs passed to them.
3. **The local API** — the control surface for retrieval, ingest, and RAG (`modules/ui`).
4. **The model files** — integrity matters (a tampered model could degrade or mislead); see [first-run](#first-run-and-supply-chain).

## Trust boundaries

| Boundary | Description |
|---|---|
| **Webview ↔ Head** | The Tauri webview talks to the loopback HTTP API. Confined by CSP + Host/Origin/token checks. |
| **Other local processes ↔ Head** | Any process (or a malicious web page the user visits) can attempt to reach `127.0.0.1:<port>`. The key inbound boundary. The decision of *where* that boundary is drawn — and what is deliberately inside it — is [ADR-0046: Local API trust boundary](../../decisions/0046-local-api-trust-boundary.md); this document is its threat analysis. |
| **Head <-> index half / Inference** | The Head and the index half share one Engine JVM and meet at direct in-process calls - no socket, no listener, no IPC (ADR-0049; the retired gRPC channel and MMF bus no longer exist). `llama-server` remains a separate process on loopback. Not network-exposed. |
| **Device ↔ Internet** | AI downloads, launch-time update checks in desktop builds with an embedded release URL, optional OTLP trace export, and user-selected integrations. The webview CSP does not constrain native Rust or Engine HTTP clients. |

## The privacy guarantee and its mechanical anchors

Local indexing, retrieval, and built-in inference are anchored in the Engine and local
`llama-server`. The following controls and exceptions define the network boundary:

1. **Frontend request confinement.** The desktop Content-Security-Policy pins `connect-src` to
   `'self' ipc: http://ipc.localhost http://127.0.0.1:*`, with `script-src 'self'`
   (`modules/shell/src-tauri/tauri.conf.json`). Frontend `fetch`/XHR/WebSocket cannot target public
   hosts. `scripts/docs/check-privacy-claims.mjs` checks this directive and the absence of common
   analytics SDKs; it does not prove the entire application has no egress.
2. **No built-in usage analytics service; tracing is opt-in.** API and index tracing both default to
   `none`. Enabling either writes `<data>/telemetry/traces.ndjson`. Only when tracing is enabled **and**
   `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` or `OTEL_EXPORTER_OTLP_ENDPOINT` is configured does the Engine
   also export OTLP/HTTP spans. The endpoint is not restricted to loopback or HTTPS. Spans can contain
   paths and bounded document excerpts (up to 16 documents × 1024 characters on search spans).
   See `TracingBootstrap`, the initialization gates in `HeadlessApp` and `KnowledgeServer`, and
   [trace configuration](../configuration/environment-variables.md#trace-output-and-optional-otlp-export).
   An OTLP endpoint alone does not enable tracing. The development agent-analytics sink under
   `scripts/agent-analytics/` is a separate tool.
3. **AI downloads and automatic update checks.** Install AI downloads from `github.com` and
   `huggingface.co` with consent; repair and upgrades can download again. On Windows with supported
   NVIDIA hardware, when downloads and GPU acceleration are permitted by policy, it also downloads a CUDA `llama-server` build and selects it automatically unless
   the user configured an executable. Desktop builds with an embedded release URL check it by default
   about 30 seconds after chrome mount. CI-built installers embed the repository's configured GitHub
   release URL, including branch candidates and pre-releases. Discovery fetches `release.v1.json`,
   `release.v1.json.sig` and `latest.json`; installation rechecks `latest.json` and downloads the signed
   installer only on request. Requests follow GitHub redirects to download/CDN hosts. Those hosts
   receive the request IP and time; these updater requests send no document content or queries.
   Set `JUSTSEARCH_AUTO_UPDATE_CHECK=false` before starting a fresh desktop process to suppress only
   the automatic check; Settings and recovery manual checks remain available. To forbid all new
   updater requests, set `{"schemaVersion": 1, "appUpdatesEnabled": false}` in `policy.v1.json`: Windows machine policy is
   `%PROGRAMDATA%\JustSearch\policy.v1.json`, and user policy is in the shell's app-data directory,
   passed to the Engine as `JUSTSEARCH_HOME`. User policy can only further restrict machine policy.
   Rust enforces this without the Engine, including qualification autorun and recovery. An invalid,
   unreadable or nonregular machine policy fails closed; an absent file is permissive. Broken user
   policy is reported by `/api/policy/effective` and ignored, matching the Engine's existing fallback.
   Policy is read at the start of each discovery and install, and again before artifact download.
   A discovery already in flight may complete its descriptor, signature and latest-metadata requests;
   changes apply to the next operation and the install's artifact download. No in-flight request is
   cancelled. Disabling checks retains durable upgrade intents, accepted sequences and local Engine
   reconciliation. The key is optional in schemaVersion 1, independent of AI flags; older releases
   ignore it. The first enforcing release will be named in its release notes. These controls do not
   disable AI downloads, trace export or external integrations. Builds without a configured release
   URL do not contact an update server; debug builds can use a runtime-provided URL.
   See `startAppUpdateMonitor`, `updater.rs::check_release`, and [supply chain](#first-run-and-supply-chain).
4. **Connected clients and tools.** MCP clients receive retrieved paths and passages. The optional
   MCP host (`JUSTSEARCH_MCP_HOST_CONFIG` / `justsearch.mcp.host.config`) launches user-chosen programs;
   their network behavior is outside this boundary. Links opened in the system browser also leave
   this application's request boundary.

## STRIDE analysis

### Spoofing / Tampering / Information disclosure — the inbound loopback surface ★

The main inbound threat is access to the loopback API by software other than the legitimate webview.
"Loopback-only" means *private from the internet*; it does **not** by itself mean *isolated from other local software*.

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
The API and index half run in **one Engine JVM**, joined through in-process ports (ADR-0049).
These module boundaries provide no process isolation: a compromised API shares the index owner's
address space and can read or corrupt its data. The separate `llama-server` and extraction children
remain process boundaries; they do not isolate the index from the API. The MCP tool surface separates
read tools (`justsearch_search`/`answer`/`browse`/`status`) from the privileged write tool
(`justsearch_ingest`), with mutations gated by the session token in production mode.

## First-run and supply chain

Install AI downloads models and runtimes with consent; repair and upgrades can download them again.
The desktop updater is another supply-chain path. Integrity + availability considerations:

- **Integrity.** Each model package pins a `sha256` in `model-registry.v2.json`; a tampered download
  fails verification. Every model/runtime `downloadUrl` must use HTTPS and resolve from an allowlisted
  public host.
- **Update authenticity and rollback protection.** The shell fetches `release.v1.json` and its
  signature over HTTPS, verifies the descriptor against the build's pinned metadata root, rejects
  a sequence below its persisted highest accepted sequence, and checks that Tauri's `latest.json`
  names the same artifact and signature. The artifact digest and signature are verified before
  installation. See `updater.rs::check_release`, `verify_metadata_signature`, and
  `require_closed_set`. GitHub availability and the release-signing keys are part of
  this trust chain.
- **Availability / single points of failure.** Project-controlled model assets resolve from the
  project's own releases repo. The packaged chat GGUF and mmproj currently resolve from
  `huggingface.co/bartowski/...`, and the `llama.cpp` binaries resolve from `github.com/ggml-org/...`;
  those upstream locations remain availability dependencies until the registry points at a
  project-controlled mirror. *(Drift guard: `ModelRegistryLoaderTest` asserts every `downloadUrl`
  resolves from an allowlisted public host, and `scripts/docs/check-privacy-claims.mjs` fails if future
  mirror wording conflicts with the current registry URLs.)*

## What this model deliberately does not claim

- **Not** that the app makes zero network connections. Downloads, launch-time update checks,
  and optional trace export are described above. Local inference does not
  require cloud processing; enabling off-device trace export can send paths and excerpts.
- **Not** that a connected MCP client keeps the data local. `justsearch_search` and `justsearch_answer`
  return file paths and passage text as tool results to whichever client the user connected (see
  [`mcp-production-server.md`](../mcp-production-server.md) § Data exposure). A client running a
  cloud-hosted model forwards those results to its provider; that egress is the client's, chosen by the
  user, and outside this model's boundary. The built-in assistant and a local-model client keep the
  loop on-device.
- **Not** infallibility — AI answers can be wrong; verify their citations.
- **Not** protection against a fully compromised host OS or a malicious local user with disk access.

## See also
- [`mcp-production-server.md`](../mcp-production-server.md) — the MCP endpoint surface.
- [`api-contract-map.md`](../api-contract-map.md) — the HTTP and in-process port contract sources.
- `ApiSecurityFilters.java` — the request-filter security plumbing (CORS / Host / token / capability gates).
