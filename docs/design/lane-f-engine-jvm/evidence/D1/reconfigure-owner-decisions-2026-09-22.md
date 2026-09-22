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
  [Typed API-port plan](api-port-design-2026-09-22.md) now names the exact path,
  nullable inherited versus explicit zero semantics, and existing precedence.
  The installed restart proof must avoid dev-runner's forced environment-port
  override, which would correctly mask persisted settings and prove the wrong path.
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

Next: close the outstanding D1-5 candidate-context integrated/installed/hosted
proof at runtime code22800c842, then settle the prepared publication and teardown
protocol against the actual owning code. The [continuation brief](../../continuation-brief.md)
defines these first two batches. Candidate/rollback code and focused proof exist;
no D1-4 dispatch implementation or completion is claimed by this decision record.

## Prepared-publication source check

The runner already accepts both SETTINGS_APPLY and RECONFIGURE through
`OperationAttemptRunnerImpl.applySettingsOwned` (lines479-532 at the current
checkpoint). It validates its executing body capability, reserves with the fixed
settings owner, arms the SQL revision marker, and supplies its private commitment
control. Extend this route; do not expose that control or a replacement writer to
the catalog handler.

`SettingsCommitCoordinator.applyOwned` (lines162-227) currently prepares the file,
ResolvedConfig and response before replacement, then records commitment and swaps
ConfigStore. The runtime composition collaborator belongs at this preparation
boundary. Candidate preparation, rollback ownership and the prebuilt publication
action must be retained through replacement; listener notification stays outside
the physical mutex. A callback passed by arbitrary handlers would enlarge the
authority boundary and is not the selected design.

The existing global admission freeze is a shutdown/upgrade mechanism, and
`cancelInteractive` includes the reconfigure turn itself. It is not yet evidence
of a suitable live multi-component publication barrier. HeadAssembly also has
separate volatile client and service-graph fields. Before implementing dispatch,
prove how each affected reader obtains a coherent installed component/config
pair; neither these volatile fields nor the apply lease alone provides that proof.

## Teardown proposal under refutation

The initial read-only apply-drain proposal is not implementation-ready. Review
found that freezeAdmission can reuse another owner's preparation id, which that
owner can later release. Checking merely that some freeze exists does not prove
admission stayed closed. Even checking the exact id at each wakeup leaves a
release-after-return interval unless shutdown retains authority for the whole
destructive sequence.

Interactive drain also does not prove durable work has stopped. Executor close
interrupts and waits for a bounded interval; a surviving retained durable handle
must prevent operations-store teardown and instance-lock release. Settle both
lifetime obligations against the existing owners before adding a drain method.
No shutdown acceptance is deferred or weakened by rejecting the incomplete plan.
