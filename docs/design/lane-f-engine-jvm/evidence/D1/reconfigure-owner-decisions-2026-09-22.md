# D1-4 current ownership decisions

Read-only discovery reconciles the dated reconfigure design with the implemented
C2 settings owner. These decisions preserve the acceptance scope.

- Keep the full SettingsWitness (revision and last committed operation key).
  A scalar expectedRevision alone loses C2's ABA protection. Reconfigure must
  reuse its runner-owned accepted row and reservation; calling applyInternal
  would incorrectly create another settings operation.
- The actual desired-settings domain is typed SettingsV2/UiSettings. Do not add
  a generic canonical-key override bag. Preserve the named API_PORT restart
  acceptance by adding an explicit typed desired API-port setting and its
  persisted/config mapping. The effective ephemeral bound port remains runtime
  evidence, not the desired setting. Schema/compatibility and frontend projection
  must be designed with that field before implementation.
- Extend SettingsCommitCoordinator's existing preparation/commit/publication
  transaction. It remains the sole runtime publisher. All fallible composition
  and allocation occur before atomic settings replacement; afterward publish
  prepared references and let the runner complete from its receipt.
- The registry apply lease alone does not make several component-reference
  publications atomic to readers. Reconcile the existing request/admission gate
  with a batch publication seam before claiming coherent multi-component apply.
- Drain or refuse active apply before operation-store teardown and final lock
  release. Normal and fatal close must preserve required dependencies and lock
  ownership if process-resource closure refuses. Existing shutdown ordering is
  an implementation obligation, not a reason to weaken apply guarantees.

Next: finish D1-5 candidate/rollback semantics, then settle the prepared
publication and teardown protocol against the actual owning code. No D1-4
dispatch implementation or completion is claimed by this decision record.
