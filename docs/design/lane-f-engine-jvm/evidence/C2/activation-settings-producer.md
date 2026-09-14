# C2-6 activation settings producer — 2026-09-14

Activation captures one settings snapshot before model selection and self-test,
then commits executable, GPU preference and enabled intent through the existing
accepted settings owner. The owner publishes configuration before inference applies.
Profile selection submits executable, effective GPU/context and model/projector
together in one lifecycle-manager apply. Deactivation commits the CPU baseline
path. Inference failure uses the manager's existing rollback; a separate accepted
settings compensation compares exactly the activation receipt. It cannot erase
an intervening settings write and never performs a second inference transition.

Application-written server executable JVM properties and their ownership marker
are retired across activation, boot and installer CUDA selection. Boot discovery
joins remembered auto-detection at150; persistent preferences remain300 and
environment/JVM authority remains400/500. Runtime application uses the published
effective GPU/context, including operator zero. Dynamic profile switching passes
the profile directly rather than mutating its bootstrap operator property.

Review exposed the old GPU-zero ambiguity. Envelope4 keeps one nullable GPU
override, migrates old zero to automatic, and preserves positive values and the
revision witness. Explicit CPU zero now survives settings serialization, canonical
response projection, ConfigStore publication, runtime application and subsequent
boot discovery. Known GPU selection recomputes the derived context window; unknown
selection preserves the remembered window. No additional intent bit or path
heuristic was introduced. Canonical documentation and its generator are updated.

Independent review is clear after these corrections. Final1420 represents4,851
cases in715 suites, four existing skips, zero failures/errors. App-services2833
and UI1219 ran; configuration272, app-api204 and inference323 reused applicable
executed results. Main/test PMD for services, UI, inference and configuration,
all five module format checks and UI integration compilation pass. Documentation
regeneration, matrix verification and canonical links pass.

The [machine-readable evidence](activation-settings-producer.json) records the
exact command, tested source hashes, task reuse, accessible raw logs/XML archives
and retention. Preserve failures as well as passing runs:1407 was line endings;
1409 had a JSON member-order assertion and PMD qualification;1410 reproduced the
hosted UI PMD defect;1414 deliberately restored three defects and produced four
expected regression failures;1416 found a test qualification;1417 caught invalid
CPU inference from missing GPU observation. The correct implementation passed
1418, then1420 after operator-precedence regressions. Tests were not weakened.

This is local implementation proof, not hosted, live-model or installed proof.
C2-6 remains open. Other installer/import settings writers, public settings
update/witness wire and installed successor scenarios are next, followed by the
remaining C2/D1/D2/E/F work. Merge placement remains F.
