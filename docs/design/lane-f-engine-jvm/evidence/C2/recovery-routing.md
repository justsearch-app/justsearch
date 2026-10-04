# C2 d.3b.2a.3 — recovery routing and invocation preservation

Decisions after code tracing at 40a9a14b9 and d23f1136e:

1. WorkerSnapshotTap maps embedding_mismatch, embedding_legacy and rebuild_brake_exhausted to
   core.rebuild-index with {}. Schema_mismatch/legacy_index/unknown retain core.reindex force=true.
   Existing condition id/subject/severity stay stable; reason changes update the recovery body.
2. ConditionRecoveryEntry remains grouped by target. Each ConditionRef gains the source
   OperationInvocation.defaultArgsJson so distinct conditions sharing one operation preserve
   their own arguments. The record is only a projection; ConditionStore remains authority.
   Update builder, additive generated JSON schema, schema and serialization tests. No new writer.
3. HealthSurface uses one REST/SSE decoder returning operation plus parsed object arguments per
   condition. Malformed/non-object/missing defaults must not silently dispatch {}; omit the
   unusable candidate. Display-only HealthLitView remains compatible with the additive field.
4. Render recommendations through existing jf-operation, like the adjacent quick actions, to
   retain the catalog audience, capability and inline confirmation policy. The superseded jf-button
   directly invoked with consented=false, which is not the intended HIGH-risk rebuild ceremony.
   Keep human-readable condition label and plugin marker alongside the actual operation control;
   remove the now-unused raw invokeOp/busy machinery if no other consumer remains.
5. Plugin overlay continues to replace target only and passes {} (its declared contract). Never
   leak the overridden core operation arguments into another plugin action. No plugin schema
   extension is needed for the lane fix.
6. Tests: exact reason→target/args and modified-condition updates; same-target/different-condition
   args retained; serialization/schema; REST and SSE snapshot/update parsing, bad-args omission,
   overlay precedence; real rendered jf-operation gets exact args and follows HIGH inline-confirm
   before invocation. Preserve positive low-risk force recovery without extra confirmation.
7. Canonical secondary-views-behavior corrects incremental quick action versus full rebuild and
   documents the recommended catalog controls. Run regeneration, Java affected tests/PMD, frontend
   typecheck/unit and ui-check affected health capture/measurement. Audit negative controls must
   detect dropped args/wrong target and bypassed confirmation. Capture exact evidence and limits.

8. REST is only the connection's initial fallback. The first valid SSE snapshot/update takes
   precedence over its concurrent REST response. Reuse the existing AbortController as connection
   owner and a local received-stream flag; reject aborted/replaced connection results. Reconnect
   resets precedence and clears old actions. A permanently monotonic catalogVersion guard is not
   suitable: backend versions reset and the builder reads snapshot/version separately. No new
   store, timer or version authority is introduced. Deferred REST and abort-ignoring old-request
   tests must prove this behavior.

This is mandatory before .2b producer binding; no owner-gated decision, no stage-placement change.
Independent discovery confirmed both root and packaged schema copies and the shared REST/SSE
serializer. Also add condition-recovery-index.v1.json to SchemaController's existing served list:
the advertised catalog schema currently returns404. This is a required reachability correction.
HealthLitView only displays a target string and never invokes it; additive args do not require a
second decoder there. Root verified its render path; no retirement or unrelated redesign.

Implementation is againstd23f1136e plus this per-item diff. First review found that switching
controls dropped the old surface error handler: OpButton emits op-error but does not display it.
1795 reproduces the absent error alert; the fix handles its typed event and clears the error on
success, with a failed-invocation/successful-retry regression. Root retained the source condition
label in an accessible group beside its catalog control. No catalog confirmation was bypassed.

## Verification so far (2026-09-14)

| Proof | Result and tested scope |
|---|---|
|1790|Typecheck passed; frontend had six fixture assertion failures because an unrelated AI status read was counted as an operation. Narrowed assertions to no invoke/no approval, preserving their intent.|
|1791|63 cases/11 suites executed; sole failure was the old18 schema-count expectation after adding the19th served schema.54 app-observability/services cases already passed.|
|1792|34 frontend cases passed after the fixture correction.|
|1795|New failed-recovery regression failed because the error alert was absent; nine unrelated cases excluded by selection.|
|1796|35 frontend cases passed after typed error handling and successful-retry clearing.|
|1799|Nine UI Java cases newly executed and passed;54 unchanged app-observability/services cases reused UP-TO-DATE.63 represented, zero failures/errors/skips. Six PMD tasks and format pass.|
|1800|Full frontend6,587 tests/490 files passed, typecheck passed, and16 Python harness/index cases passed.|
|1802|Real Lit browser capture with transport fixtures proves exact force=true dispatch and no rebuild dispatch before inline confirmation.114 accessibility landmarks, zero axe violations, zero real/environment console errors, no overflow. Root inspected the PNG and measurement.|
|1803|Temporary wrong-target and dropped-builder-args faults produced three expected assertion failures in53 cases/seven suites;50 positive cases passed. All source restored byte-for-byte.|
|1804|Temporary UI args loss and confirmation bypass produced four expected failures in12 cases; eight positives passed. Actual force args and catalog INLINE policy assertions failed for the intended reasons. All source restored byte-for-byte.|
|1807|Deferred initial REST regression reproduced stale force-reindex replacing the streamed full-rebuild recommendation before correction.|
|1809|Final full frontend6,589 tests/490 files passed after ordering correction and byte-for-byte restoration.|
|1810|Restored Java62 cases/10 suites passed:17 newly executed,45 app-services FROM-CACHE; unchanged schema test remains covered by1791/1799. Six PMD tasks and format passed.|
|1808|Typecheck and37 focused frontend cases/two files passed after connection-scoped precedence and old-request fencing. Independent correction review found no remaining defect.|
|1811|Final three governance gates, store recoverability, canonical index/links and skill regeneration/checks, and UI step coverage passed.|
|1805|Three governance gates, store recoverability, canonical index/links and skill regeneration/checks, and UI step coverage passed.|

The scoped CSS/group-label adjustment after1800 changes layout and accessibility grouping only;
1802 exercises it. The ordering correction subsequently passes focused1808; final full frontend1809 passes. Existing UI suite console
noise from background localhost reads is retained in1800; the process exited0 and all cases passed.
The first1797 browser attempt used the wrong fixture URL glob and timed out;1798/1802 use the
actual OperationClient path /api/operations/<id>/invoke.1793 baseline health/completion captures
pre-date the dedicated recovery step and do not prove recovery invocation.

The new health-recovery step is registered in jseval/ui_step_index.json and uses transport
fixtures, not private Lit state mutation or a live index rebuild. It complements the unit test
that drives the backend428→approval→capsule retry. The optional display-only HealthLitView
continues to show the operation id and makes no invocation, so no arguments are lost at an action
boundary there.

Evidence: tmp/1790-1811 logs as named,1791/1799 copied XML/count manifests,1802-ui/health-recovery.png
and .measure.json, plus executed tmp/routing-negative.py,1803 copied XML/counts and1804/1807/1808 logs. Retain through lane
completion plus30 days (at least2026-10-14). No final local/hosted/live stage completion claimed.

Pushed prerequisite d23f1136e has12/13 hosted CI34893182564 jobs passing; app-ui fails only
RecordedIngestionCoordinator.bindProducer being unreferenced on all three attempts. CLA34893179972
passes. Downloaded exact failure XML is tmp/1801-latest-ci-xml; the stage needs producer binding,
not an exemption. Merge remains at F.


Independent final evidence review re-read the three1803 XML failures, four1804 UI failures,
all four original-byte hashes,1808/1809/1810 results and1811 checks. It found no wrong-reason
passes or unmet per-item acceptance. This completes .2a.3 locally; producer binding and final
C2 live/installed/hosted proof remain required.
