---
title: "ADR-0046: Local API trust boundary"
type: decision
status: stable
description: "The local HTTP API's trust boundary is the owning OS user: an owner-only credential file and authentication on every non-public production route, alongside loopback bind, Host allowlist, MCP Origin validation and desktop CORS."
date: 2026-09-02
probes:
  - adr-0046-loopback-bind-literal
  - adr-0046-no-wildcard-bind
  - adr-0046-token-fails-closed
  - adr-0046-token-refusal-asserted
  - adr-0046-token-route-removed
  - adr-0046-production-route-walk
  - adr-0046-owner-only-manifest
last_reviewed: 2026-10-20
---

# ADR-0046: Local API trust boundary

## Status

Accepted (2026-09-02).

Written retroactively. The controls below shipped incrementally across tempdocs 633, 655 and 834;
what did not exist was a decision record naming *what they collectively defend against and what
they deliberately do not*. Root `CLAUDE.md` hard invariant #2 said "Local API binds to 127.0.0.1
only" — true, but a description of one control rather than of the posture. This ADR is the posture;
the invariant line now points here.

## Context

JustSearch's Head process serves a local HTTP API (Javalin) consumed by three kinds of caller:

1. the desktop shell (Tauri webview, origin `tauri://localhost`);
2. external MCP clients — native processes such as the MCPB bridge
   (`packaging/mcpb/server/index.js`) and `scripts/prod/justsearch-mcp/discovery.mjs`;
3. developer tooling (dev-runner, jseval, ui-shot) in dev mode.

The adversaries that are actually reachable on a single-user desktop are narrow, and naming them is
what makes the control set legible:

- **A remote host on the network.** Barred entirely by the bind address.
- **A malicious web page in the user's browser**, using the browser as a confused deputy —
  cross-origin `fetch` and DNS rebinding.
- **Another local OS account**, which can reach loopback but cannot read the credential file.
- **Another native process running as the owning user.**

The third one is the important admission: a same-user native process can already read the data
directory and the runtime manifest, which is exactly where the desktop shell itself obtains the
session token (`modules/shell/src-tauri/src/binding.rs:116`). No HTTP-layer control can exclude a
caller that can read the token off disk. `docs/reference/security/threat-model.md` (Elevation of
privilege section) records this residual with its verified caller inventory.

## Decision

**The trust boundary is the owning OS user. The owner-only runtime manifest holds the per-boot
credential. Other accounts and browser origins cannot obtain it through HTTP. The network cannot
reach the loopback service. Same-user native processes can read it and remain inside the boundary.**

Six controls implement that boundary. The first is the bind address itself, in
`modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java`; four HTTP controls live in
`modules/ui/src/main/java/io/justsearch/ui/api/ApiSecurityFilters.java` and are installed as
before-filters, in a fixed order, by `install(Javalin)` (`:138-145`) — which the server calls
immediately before it binds (`LocalApiServer.java:579`).

| # | Control | Site | What it excludes |
|---|---|---|---|
| 1 | **Loopback bind** | `LocalApiServer.java:582` — `this.app.start("127.0.0.1", bindPort)` | Every remote host. The address is a literal, not a setting: there is no configuration path to `0.0.0.0`. |
| 2 | **Host-header allowlist** | `setupHostValidation` (`:193-207`), predicate `isAllowedHost` (`:685`) | DNS rebinding. After a rebind the request is *same-origin* with the attacker's domain, so CORS no longer applies — but the attacker's domain is still in the `Host` header, and only `127.0.0.1` / `localhost` / `::1` are accepted. Applies to every method, including token-exempt GETs. |
| 3 | **MCP Origin validation** | `setupMcpOriginValidation` (`:238-241`) via `enforceLoopbackOrigin` (`:248`), predicate `isAllowedMcpOrigin` (`:295`) | A browser reaching `/mcp`. Absent `Origin` is admitted (native MCP hosts are not browsers); a present `Origin` is parsed as a URI and its host component compared for equality, so `http://127.0.0.1.evil.com` does not pass. This is the MCP Streamable-HTTP spec's own MUST, and is deliberately independent of `prodMode`. |
| 4 | **CORS pinned to the shell origin** | `setupCors` (`:370-397`), `resolveAllowedOrigin` (`:708`) | A cross-origin page *reading* a response. In prod only the desktop origins (`tauri://localhost`, `http(s)://tauri.localhost`) are granted. CORS is not a request-blocker, which is why control 2 exists. |
| 5 | **Per-boot token on every non-public route** | `ApiSecurityFilters.requiresSessionToken` and `setupSessionTokenEnforcement` | Every production request requires `X-JustSearch-Session`, including reads and SSE streams, except `OPTIONS` and `GET`/`HEAD` on the six public paths listed below. An unlisted future route inherits authentication. A restart replaces the token; stale tokens receive 401. |
| 6 | **Owner-only canonical credential file** | `RuntimeManifestPublisher.writeManifest`, `OwnerOnlyFile.createFresh` | Other local accounts cannot read `runtime/manifest.json`. The fresh temporary file is created with POSIX 0600, or made empty and assigned a Windows ACL allowing only the owner and SYSTEM before writing the token. Every publish replaces the canonical file, including upgrades over readable files and leftover temporary files. |

The six public reads are exactly `/api/health`, `/api/runtime/live`, `/api/runtime/ready`,
`/api/status`, `/api/runtime/manifest`, and `/.well-known/justsearch/manifest.json`.
Their responses never include the token. REST, SSE, MCP and historical-instance responses use
redacted projections even when reading history written by older releases. Newly written history
omits the token; existing history is left untouched.

**`GET /api/mcp/token` is removed.** In production it receives 401 without a valid token and 404
with one. No HTTP route dispenses the credential, and HeadlessApp does not print it. The desktop
shell and native bridges read `head.sessionToken` from the owner-only canonical manifest. Bridges
prove the Engine's identity before sending the credential or tool arguments; discovery probes
remain unauthenticated public reads. An explicit port override uses only
`JUSTSEARCH_SESSION_TOKEN` and fails with a message naming it if absent.

Browser event streams use the shared fetch-based transport to send the token in a header.
Credentials never appear in URLs. Reads and streams re-resolve the token when the Engine restarts;
a token not yet published at cold start remains retryable. Development mode stays token-free.

`ProductionRouteTokenWalkTest#walksEveryRegisteredRouteOnTheRealProductionEngine` boots the real
production Engine, enumerates its registered routes and exercises the authentication boundary.
The ADR probes name this test but do not execute it; a probe pass is not runtime evidence.

**Token enforcement fails closed.** In prod mode with a null or blank session token, the server
refuses to install its filters and start, and raises a lifecycle reason code. Before 2026-09-02 this
branch logged `TOKEN_ENFORCEMENT_DISABLED` and continued serving with authentication silently switched off. That was fail-open on the control that matters most, and the fact
that no shipped launch path could reach it (`HeadlessApp.java:364-365` pairs `prodMode` with
`generateSessionToken()`; dev-runner, jseval and ui-shot never set prod mode) made it *unreachable*,
not *safe* — a future launch path would have inherited a silent downgrade. Refusing to start is the
only behaviour whose failure is loud.

**The session token is authentication, not authorization.** It answers "may this caller reach the
API at all". Whether a specific action is approved is the trust lattice's separate `GateBehavior`
ceremony (`AUTO` / `INLINE_CONFIRM` / `TYPED_CONFIRM` / `DENY`). Holding the token satisfies neither
confirm gate, and clearing a confirm gate does not substitute for the token. Two independent layers,
by design.

## Consequences

**Positive**

- The posture is one document, so a reviewer can ask "what is inside the boundary" and get an
  answer, rather than reconstructing it from five filter methods.
- The failure mode of every control is explicit: 403 (Host / Origin), 401 (token), refuse-to-start
  (missing token in prod). None of them degrade silently.
- The accepted residual is written down, so a future reviewer does not spend the same afternoon
  rediscovering that a same-user process can read the token off disk.

**Negative (accepted)**

- **A same-user native process is fully trusted.** It can read the token from the runtime manifest
  and drive every API the shell can drive, including `justsearch_ingest`. This is not mitigated; it
  is the boundary.
- **The token is per-boot and stored in the owner-only canonical manifest.** There is no revocation, rotation or expiry short of a
  restart. Acceptable while a boot is the unit of session.
- **CORS' contribution depends on the browser enforcing it.** Control 2 is what actually blocks the
  rebinding case; control 4 is defense in depth on top of it.
- **Fail-closed can turn a misconfiguration into a non-starting app.** That is the intended
  trade: a Head that will not start is a bug report, a Head serving mutations without a token is a
  vulnerability nobody notices.

## Alternatives Considered

### Unix-socket / named-pipe transport instead of loopback TCP
Removes the browser as a reachable caller entirely — no port, no Host header, no CORS. **Rejected**
for now: the Tauri webview and the external MCP client inventory both speak HTTP over loopback, and
Windows named-pipe support across that inventory is unproven. It remains the strongest available
upgrade and is named as a reassess trigger below.

### OS-level per-caller authentication (peer credentials / process identity)
Verify the calling process rather than a shared secret. **Rejected** — it would not change the
boundary. Every caller worth admitting *and* every caller worth excluding runs as the same user, so
peer credentials cannot separate them. It would add machinery without moving the residual.

### Persist the token so clients survive a restart
Would spare external MCP clients a re-fetch after a Head restart. **Rejected** — the per-boot token
is what makes a stale client fail closed. Persisting it converts a self-healing failure into a
long-lived credential on disk for no benefit the manifest re-read does not already give.

### Keep token enforcement fail-open and rely on the launch path
The status quo before this ADR: the only production launch path always supplies a token, so the
fail-open branch is unreachable. **Rejected** — "unreachable today" is a property of one caller, not
of the code. The next launch path (a service wrapper, a test harness promoted to prod, an installer
variant) inherits a silent downgrade.

## Reassess When

- **The API binds anything other than `127.0.0.1`.** Probe `adr-0046-loopback-bind-literal` fails
  and this ADR is void until re-derived — every control above assumes the network cannot reach the
  port.
- **Credential file ownership or ACL semantics change.** Other accounts must remain unable to
  read the canonical manifest and authenticate to non-public routes.
- **Any token-bearing path becomes reachable from a browser** — a new route serving the token
  or a shell that fetches it over HTTP instead of reading the owner-only manifest.
- **A transport with OS-level access control becomes viable** for the full caller inventory (see
  the first alternative), which would let the boundary shrink from "same user" to "named callers".

## Cross-references

- `docs/reference/security/threat-model.md` — the STRIDE analysis, the per-control mitigation table,
  and the verified external-caller inventory this ADR summarises.
- [ADR-0030](0030-policy-on-operations-vs-mcp-hints.md) — what the MCP surface is allowed to
  express; this ADR governs who may reach it.
- Root `CLAUDE.md` hard invariant #2 — the always-loaded one-line projection of this decision.
