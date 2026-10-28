---
classification: declared-growth
tempdoc: 852
---

D-T40 / SA15 decision 3 retains the public component capability in
`src/shell-v0/views/runStepPresentation.ts`: `stepPresentation`, branded `StepPresentation`
and the `StepGlyph` vocabulary, along with `RunNode.presentation`,
`ToolCallCard.stepPresentation`, their render contracts and tests.

Removing UnifiedChatView removes the projector's only production call site. Search passing null
is not a decision that external/public component consumers no longer use this capability. This
changeset explicitly declares that newly unused production capability as retained by D-T40;
it is not kept merely to satisfy its tests and receives no dummy production consumer.

The fresh Knip report still counts one unused export here (StepGlyph); the existing pin is one
and is not increased. No other newly unused export or pin increase is licensed by this rationale.
Private projection/client/evidence leftovers are pruned, and the independent shrink changeset
records only downward ratchet changes.
