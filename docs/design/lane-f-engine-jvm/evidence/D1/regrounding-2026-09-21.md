# D1-1 owner map, 2026-09-21

Read-only preparation at `ba1440624b2fd0b4d0cc1dd4254c87dfe6d29001`.
C2 verification remains active; D1 implementation and acceptance are unperformed.
Recheck these anchors after any intervening runtime edit.

| Component | Current owner and integration seam |
| --- | --- |
| api | `modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java:454-476` constructs HeadAssembly and LocalApiServer; `modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java:580` binds. HeadlessApp coordinates shutdown. |
| index | `modules/app-engine/src/main/java/io/justsearch/app/engine/EngineRoot.java:99-103` defines ServerFactory, with its index factory at192-201. `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerBootstrap.java:296-341` starts and checks the owned server. |
| encoders | `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:182` holds inferenceSurface and separate model owners; composition at1556 and close at2521. D1-12 owns their future EncoderSet migration. |
| generative | `modules/app-services/src/main/java/io/justsearch/app/services/HeadAssembly.java:445-467` creates and retains optional InferenceLifecycleManager. HeadlessApp supplies the composition context. |

EngineRoot owns the shared registry lifetime, but the API and generative resources
are composed through HeadlessApp. Use those actual composition paths rather than
registering fictitious resources in EngineRoot. The existing executor handoff is
the precedent for passing the component registry to the front and index half.

The core precedent is `EngineExecutorRegistry`; its app-engine implementation has
typed refusals and sorted immutable snapshots. `RetainedStateBudget` already offers
producer activation and permits. `governance/retained-state.v1.json` contains
attempted-configurations with cap1 and awaitingProducer D1. D1-1 activates only that
producer and proves held count1 plus refusal of a second apply-lock acquisition.
Representation-generations and co-resident-encoders remain assigned to D1-9/D1-12.

The four real registrations, dependency-sensitive applied versions, coverage floor4,
apply-lock refusal/count and resource-policy tests are all required by D1-1.
Readiness projections remain D1-2. The current encoder surface is the available
D1-1 seam; registering it does not implement co-resident encoder sets.

Configuration declarations span EnvRegistry and YAML-only ConfigKey, as documented
in `docs/explanation/06-configuration-ssot.md`. ConfigStore owns the resolved snapshot.
C2 SettingsCommitOwner's revision/operation key is a separate authority from D1's
future applied-value hashes. D1-3 must reconcile the EnvRegistry-only register wording
with these existing owners before classifying dependencies; do not create a second
config-key authority in D1-1.

The old D1-1 anchors for EngineRoot fields, ServerFactory and API handoff are stale.
Update the owning checklist during D1 design. Before Flow A, reconcile C2's durable
bulk settlement/ACK and promoted-with-gaps behavior with D1 abandonment, gap acceptance
and live activation. Existing C2 evidence cannot be deleted ahead of its owner protocol.
