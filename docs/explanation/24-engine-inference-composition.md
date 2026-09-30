---
title: Engine Inference Composition
type: explanation
status: stable
description: "How the Engine resolves installed model inputs, builds ORT sessions, and owns index and query inference generations."
---

# Engine Inference Composition

Inference runs inside the Engine JVM's index half. `InferenceCompositionRoot` is the single
production entry point for composing the ORT-backed roles. It consumes resolved configuration,
detected hardware, an optional install contract, the model directory, and a GPU arbiter; it returns a
typed `InferenceSurface`. The composition root is not a process boundary and does not create a
second runtime. (`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/InferenceCompositionRoot.java:267-311`)

The current role set is embedding, NER, SPLADE, BGE-M3, reranker, and citation. BGE-M3 replaces the
separate embedding and SPLADE choices when selected; if its assembly fails, the composition falls
back to SPLADE. Reranker and citation are query roles and are partitioned from the index roles before
the serving generation is published. A missing variant or an assembly failure makes that role
unavailable through an empty optional; it does not abort every other role. (`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/InferenceCompositionRoot.java:477-579,745-907`,
`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/InferenceSurface.java:22-59,106-140`)

## The shipped composition path

```text
ResolvedConfig + HardwareProfile + InstallContract/dev probe
                         |
                         v
          EncoderConfigurationProjection
             +-- index composition plan
             +-- query role plans and witness
                         |
                         v
       RuntimePolicy + ModelSessionPolicy per role
                         |
                         v
        OrtSessionAssembler.buildManager(...)
                         |
                         v
      SessionHandle + role-specific Assembly values
                         |
                         v
              InferenceSurface
                +-- index roles -> EncoderSet
                +-- query roles -> QueryRoleSet
```

The root first captures the query plans and an index composition plan. A captured plan retains the
selected variant, resolved policy, model directory, and input witnesses needed for a retry. On
replay, the root validates the captured inputs and separately composes the current query roles; it
does not silently re-resolve the index generation from mutable process state. (`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/InferenceCompositionRoot.java:392-424,427-460,581-605`)

`RuntimePolicyResolver` is the single owner of JVM-wide ORT settings. `ModelSessionPolicyResolver`
is the single owner of per-role settings such as execution provider, arena cap, CPU optimization,
CPU deferral, GPU retry, and run options. Both are pure functions over typed inputs. The current
The hardware parameter is part of the stable resolver signature, and the resolver returns policy
records for the selected role. (`modules/ort-common/src/main/java/io/justsearch/ort/RuntimePolicyResolver.java:8-21,37-87`,
`modules/ort-common/src/main/java/io/justsearch/ort/ModelSessionPolicyResolver.java:12-36,47-74`)

`OrtSessionAssembler.buildManager` is the production entry for a long-lived `SessionHandle`. It
derives the GPU branch from the resolved variant, passes the policy records to
`NativeSessionHandle`, and binds GPU arbitration through the `GpuArbiter`. The other assembler
entries are narrow model verification or short-lived graph probes; the deleted fallback builders are
not production paths. (`modules/ort-common/src/main/java/io/justsearch/ort/OrtSessionAssembler.java:19-50,56-115,117-145,215-255`)

## Surface and generation ownership

`InferenceSurface` is a typed bundle of optional role assemblies, policy observations, native session
handles, and a component observation. Its `close()` attempts to retire every handle and reports an
aggregate failure if a handle remains active, retiring, or refused. (`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/InferenceSurface.java:22-87,218-270`)

`EncoderSet` owns the index-time wrappers and the exact model identity for one representation
generation. It exposes a readiness latch, accepts leases for already-issued work, refuses new leases
after retirement starts, and closes wrappers only after the leases and native surface have drained.
A refused close leaves the generation owner retained for a later retry. (`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/EncoderSet.java:18-25,63-97,133-205`)

The serving view retains an `EncoderSet` lease and a separate query-role lease. The query partition
keeps reranker and citation handles out of the index set, while the index set owns embedding, NER,
and the selected sparse role. This lifecycle split stays inside one Engine JVM. (`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:209-257`,
`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/InferenceSurface.java:106-140`)

## Registry and runtime boundaries

The D1 `EngineComponentRegistry` is the shipped process-level lifecycle and readiness authority.
`EngineRoot` registers `index` and `encoders` component handles, and the inference root publishes
component observations through the index owner. (`modules/app-engine/src/main/java/io/justsearch/app/engine/EngineRoot.java:443-467`,
`modules/core/src/main/java/io/justsearch/core/component/EngineComponentRegistry.java:7-29`)

`ModelRegistry` remains a v2 package registry for installed model definitions and variants. Composition
consumes the resolved role projection and variant selected for the current generation. (`modules/configuration/src/main/java/io/justsearch/configuration/model/ModelRegistry.java:6-24`)

## Diagnostics and failure behaviour

The surface's policy snapshot and component observation make resolved roles, missing roles, and
applied configuration evidence inspectable by the existing Engine diagnostics. A role may be
missing because its variant is absent, its metadata or capability contract fails, or session
creation fails. The composition root records that absence in the surface and continues composing
independent roles; callers must use the optional value and the component observation rather than
assuming every installed role is ready. (`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/InferenceSurface.java:26-34,89-104,166-208`,
`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/InferenceCompositionRoot.java:755-790,807-825,881-907`)
