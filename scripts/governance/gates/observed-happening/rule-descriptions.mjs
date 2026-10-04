/**
 * Rule descriptions for the observed-happening gate (tempdoc 575 — the projection spine at the
 * observability-authority tier). Keyed by ruleId; surfaced in SARIF + `--explain`.
 */
export const OBSERVED_HAPPENING_RULE_DESCRIPTIONS = {
  'observed-happening/register-missing':
    'governance/observed-happening.v1.json (the observed-happening register) was not found at its ' +
    'configured path.',
  'observed-happening/contributor-unresolved':
    'A concept in the register names a contributor id that does not resolve to any real Resource ' +
    '(*ResourceCatalog.java) or DiagnosticChannel (*DiagnosticChannelCatalog.java). The register may not ' +
    'name a phantom stream — coverage projects from the catalogs. Fix the id or remove the stale entry.',
  'observed-happening/contributors-resolve':
    'Every declared contributor resolves to a real Resource / DiagnosticChannel (healthy).',
  'observed-happening/concept-canonical-source':
    'A concept declares a canonicalSource that is not one of its own contributors (or declares none). ' +
    'Every concept names exactly one canonical source, and that source must be a contributor (the one ' +
    'store the projections derive from). Add it to contributors, or correct the canonicalSource.',
  'observed-happening/concept-source-declared':
    'Every concept names exactly one canonical source, itself a contributor (healthy).',
  'observed-happening/contributor-shared':
    'A stream (Resource / DiagnosticChannel id) is claimed by more than one concept. A stream ' +
    'contributes to exactly one observed-happening concept — two concepts claiming one record is the ' +
    'representation-drift that fragmented the action lifecycle (tempdoc 550 F-2). This forecloses ' +
    're-fragmentation of a DECLARED concept; recognizing a NEW fragmentation stays detection+review ' +
    '(the irreducible discovery problem, 575 §9). Merge the concepts, or fix the contributor.',
  'observed-happening/contributor-single-concept':
    'No stream is claimed by two concepts (healthy).',
  'observed-happening/operator-trace-must-be-channel':
    'A Resource declares an operator-trace origin (a ProducerKind: IN_PROCESS_LOGBACK / ' +
    'EXTERNAL_OBSERVER) — it models operator-trace data as a Resource. Operator ' +
    'traces are not Resource truth (ADR-0036 / C-012): different consumer model, schema discipline, ' +
    'privacy class, and self-observation risk. Model it as a DiagnosticChannel ' +
    '(*DiagnosticChannelCatalog.java), not a Resource. Drop the .withOrigin(...) declaration, or move ' +
    'the stream to a DiagnosticChannel. (Bounded teeth, tempdoc 575 §4.2: catches the explicit ' +
    'mis-model, not a silent omission.)',
  'observed-happening/no-operator-trace-resource':
    'No Resource declares an operator-trace origin (healthy).',
  'observed-happening/kind-mismatch':
    "A concept's declared kind is inconsistent with the primitive class of its canonicalSource: a " +
    'concept sourced by a DiagnosticChannel must declare kind "diagnostic-channel"; one sourced by a ' +
    'Resource must not (tempdoc 575 §4.2 — the cheap completion that makes the register kind non-inert; ' +
    'NOT a render-genre derivation, which 571 §9.B / 575 §3 declined). Fix the kind or the canonicalSource.',
  'observed-happening/kind-consistent':
    "Every concept's kind matches the primitive class of its canonical source (healthy).",
  'observed-happening/projection-unresolved':
    'A concept declares a projection (read-view) that does not resolve to a real surface mount tag in the ' +
    'surface catalog (tempdoc 575 §4.1, Pillar 1\'s third element — reuses the operation-surface lineage ' +
    'check). Projections are optional, but a declared one may not be a phantom. Fix the mount tag or remove it.',
  'observed-happening/projections-resolve':
    'Every declared projection resolves to a real surface mount tag (healthy).',
  'observed-happening/stateful-requires-liveness':
    'A concept marked stateful (it has in-flight records) does not name a livenessOwner. Every in-flight ' +
    'record must have a live owner (550 Thesis II / 575 §4.3b) — name the state machine + owner that ' +
    'reconciles in-flight records against a live signal, or set stateful: false. This is the build-time ' +
    'half of the liveness invariant; the runtime mechanism is the owner it names.',
  'observed-happening/stateful-liveness-declared':
    'Every stateful concept declares a livenessOwner (healthy).',
  'observed-happening/live-concept-unprojected':
    'A concept marked stateful (it has live, in-flight state) declares no projection — no read-view ' +
    'surface renders its live state. A stateful concept must name at least one projection (the System ' +
    'Self-View or any declared "jf-*" surface, tempdoc 575 §17.4 Face B): a live concept no surface ' +
    'shows is invisible at runtime. This is §5\'s meta-failure closed at the presentation tier (the §13 ' +
    'L1 reverse-coverage pattern turned onto display). Add a projection, or set stateful: false. ' +
    'BOUNDED (the FE ceiling): it proves the surface is DECLARED, not that its code subscribes ' +
    '(declaration-resolves, not code-proves-render — projection resolution is the projection-unresolved rule).',
  'observed-happening/live-concepts-projected':
    'Every stateful concept declares a projection that renders its live state (healthy).',
  'observed-happening/stream-uncovered':
    'A Resource / DiagnosticChannel the catalogs declare (parsed from `implements ResourceCatalog` / ' +
    '`DiagnosticChannelCatalog`, robust to filename) is neither a contributor of any concept nor listed ' +
    "in the register's outOfFamily array. Reverse-coverage (tempdoc 575 §13 L1) closes the meta-gap §5 " +
    'names: a new observability stream may not escape the register silently. Declare it as a concept, or ' +
    'add it to outOfFamily with a reason if it is genuinely not a what-happened/what\'s-true stream. ' +
    'BOUNDED: covers the Resource/Channel-backed universe; non-Resource family members (boot trace, scan ' +
    'progress, the 553 search-trace) are governed by their own spines, out of this register\'s scope.',
  'observed-happening/streams-covered':
    'Every catalog stream is a declared contributor or explicitly out-of-family (healthy).',
  'observed-happening/vacuous-scan':
    'Fewer Resource/Channel stream ids were parsed from the catalogs than scan.expectedMinPopulation ' +
    '(default 1) — almost always a renamed/moved catalog root, not a real removal. The reverse-coverage ' +
    'and kind/contributor checks would then pass VACUOUSLY (enforcing nothing while green) — the §5 ' +
    'vacuous-pass downgrade (tempdoc 576). Fix the scan roots, or lower scan.expectedMinPopulation in ' +
    'the register with the change that shrank the stream universe.',
  'observed-happening/scan-population-live':
    'The catalog scan parsed >= the declared floor of Resource/Channel stream ids — not vacuous (healthy).',
  // 'observed-happening/liveness-window-*' — RETIRED (tempdoc 575 §17 Face A): the window is now
  // generated from the register into the worker + FE constants; see regen-all --check.
};
