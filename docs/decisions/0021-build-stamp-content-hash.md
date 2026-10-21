---
title: "ADR-0021: Build-Stamp Content-Hash Design"
type: decision
status: accepted - startup injection gap; owner review open
description: "The Engine distribution content hash is generated, but normal startup does not inject it into the running JVM; hot reload sets it. The dev-runner uses a separate mtime-based head stamp for startup freshness. Owner review of the content-hash startup gap remains open."
date: 2026-04-06
probes: none - normal startup does not inject the build-stamp hash; jseval skips the comparison when the stamp is missing and warns without aborting on a mismatch.
last_reviewed: 2026-09-02
---

# ADR-0021: Build-Stamp Content-Hash Design

## Status
Historically accepted; owner review of the startup injection gap remains open.
Lane F stage A item A13 moved `generateBuildStamp` to `modules/ui`
(`build/install/ui/build-stamp.txt`, finalized by `installDist`). The deleted `WorkerSpawner`
was not replaced as a startup injector of `justsearch.build.stamp`; the current setter is in
`DevReloadManager` after hot reload. With no property set, the status mapper sets `buildStamp` to null
and jseval skips its content-hash comparison. The dev-runner instead checks a separate,
mtime-based `justsearch.head.stamp` at startup. Read the historical body below against
this implementation gap.

## Historical Context (pre-ADR-0049)

During development, the Worker JVM may be running stale bytecode after a recompile. The developer discovers the mismatch only when new code doesn't behave as expected — which looks like a code bug, not a deployment issue. In the 366 tempdoc, this caused multiple debugging cycles where correctly implemented code appeared broken because the running JVM was serving stale classes.

The `reload` command supports hot-reload via JDWP for method-body changes, but structural changes (new record fields, new methods, new classes) require a full restart. Nothing warned that a restart was needed.

Research found that stale-JVM detection is an unsolved gap in the Java ecosystem. No tool, framework, or JDK feature addresses "tell me whether the running JVM matches what's on disk." Build-info plugins (`gradle-git-properties`, Spring `build-info`) embed git SHA or timestamp in artifacts but provide no comparison logic. The solution must be custom-built.

The forces at play:
- `installDist` produces a distribution of ~175 JARs in `lib/` — this is the Worker's classpath.
- Gradle's UP-TO-DATE checking is content-based — a stamp mechanism must not break this.
- The stamp must capture uncommitted changes (git SHA alone is insufficient).
- Multiple consumers need the stamp: MCP reload tool, jseval, `/api/status`.

## Historical Decision (pre-ADR-0049)

Use a SHA-256 content hash of the Worker distribution (`build/install/indexer-worker/lib/`), truncated to 16 hex characters:

1. **Hash computation**: Project JARs (SNAPSHOT) are content-hashed (full file bytes) for exact change detection. Third-party JARs use name+size only (they are immutable, and content-hashing the 371 MB `onnxruntime_gpu` JAR would be wasteful). The hash changes if and only if the distribution changes.
2. **Gradle task**: `generateBuildStamp` is finalized by `installDist`, ensuring the stamp is generated for all launch paths (`runHeadless`, `runHeadlessEval`, `assemble`, `build`). The task is Gradle UP-TO-DATE compatible — same inputs produce the same hash, so unchanged distributions skip the task.
3. **Injection**: `WorkerSpawner` reads `build-stamp.txt` from the distribution root and passes `-Djustsearch.build.stamp=<hash>` to the Worker process.
4. **Exposure**: The stamp is surfaced via `StatusResponse.build_stamp` (proto field 12) through the existing `IndexStatus` RPC, and appears in the `/api/status` REST response.
5. **Reload integration**: The MCP reload tool compares the running stamp against the on-disk stamp. When JDWP hot-swap fails due to structural changes, the tool sets `structuralChangeDetected: true` and emits `"RESTART REQUIRED"`. On successful hot-swap, the stamp file is propagated to the Worker to prevent false positives.
6. **jseval integration**: `_check_build_freshness()` runs between index reset and ingestion, comparing the running backend's stamp against the on-disk stamp file. Warns on mismatch (does not abort).

## Historical Consequences (pre-ADR-0049)

**Positive:**
- Gradle UP-TO-DATE correct — content-based hashing means the stamp only changes when JARs actually change, unlike timestamps which would change on every build.
- Deterministic — same source code produces the same stamp across builds.
- Works across all launch paths (Gradle tasks, MCP reload, jseval).
- Low overhead (~200ms for hash computation during `installDist`).
- Catches the exact problem: developer rebuilds, running JVM doesn't match.

**Negative:**
- Adds a new Gradle task and plumbing through `WorkerSpawner`, `StatusResponse`, and the MCP reload tool.
- Head process staleness is not detected (the Head runs from Gradle's source classpath, not `installDist`, and has no reload mechanism). Head-side changes always require a full dev stack restart.
- After `reload` (which runs `compileJava`, not `installDist`), the on-disk distribution stamp is stale. The stamp propagation mechanism prevents false positives but creates a subtlety: a subsequent `assemble` updates the stamp, and if the developer doesn't restart, jseval will correctly warn.

## Alternatives Considered

### Timestamp-based stamp
Write the build timestamp into the stamp file. Rejected because timestamps break Gradle's UP-TO-DATE checking — every `installDist` run would produce a different stamp even when no code changed, forcing unnecessary task re-execution and defeating Gradle's build avoidance.

### Git SHA
Use the current git commit SHA as the build identity. Rejected because git SHA doesn't capture uncommitted changes — the most common staleness scenario is "I edited code, rebuilt, but the running JVM has the old version." The SHA would be identical for both the old and new build if the code isn't committed yet.

### Spring DevTools / JRebel
Use an existing hot-reload framework. Rejected because Spring DevTools requires Spring Boot (JustSearch uses Javalin), and JRebel is commercial (~$50/month) with licensing constraints. Both are classloader-restart frameworks rather than staleness detection — they solve a different problem and add significant runtime overhead to a local-first desktop application.
