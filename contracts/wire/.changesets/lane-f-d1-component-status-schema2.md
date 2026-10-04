---
evolution-rule: remove
---
Lane F D1 replaces the schema1 status components with the schema2 component model,
as authorized by docs/design/lane-f-engine-jvm/stages/D1.md and its
evidence/D1/schema2-consumer-plan-2026-09-21.md migration plan.

StatusResponse field4 and the name components are reserved. The old public
Components and Component protobuf messages are removed. Field35 engine_components
uses the new EngineComponents/EngineComponent types and the distinct six-state
EngineComponentState vocabulary; its JSON name remains components for schema2's
API/index/encoders/generative slots. Existing field numbers are not repurposed.

This is an intentional breaking contract replacement: buf FILE detects the old
field and message removals even though the field is reserved. Wire VERSION advances
from the PR baseline2.0.0 to3.0.0. Runtime Contract0.4.0 is a separate negotiated
version; its bump does not substitute for the wire contract's major version.
The Java, frontend, runtime client and native/dev host projections migrate together.
