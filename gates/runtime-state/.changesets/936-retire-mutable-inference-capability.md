---
classification: rule-retired
tempdoc: 936
---
Lane F D1 removed the mutable InferenceCapability authority. Remove its stale
registered path and retain InferenceCapabilityWiring, which projects physical
inference observations and desired RuntimeSpec onto the generative ComponentHandle.
The component registry owns Engine lifecycle state. The existing wiring test
remains the guard; no gate or population floor is relaxed.

Hosted CI35651061321 detected the orphan at checkpoint4e4cd88f6. The registered
replacement already existed; restoring the deleted class would recreate the
superseded authority. See docs/design/lane-f-engine-jvm/evidence/D1/
schema2-owner-verification-2026-09-21.md for the verification record.
