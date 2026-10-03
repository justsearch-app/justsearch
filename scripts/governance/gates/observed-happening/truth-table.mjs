/**
 * Observed-happening truth table (tempdoc 575 — the projection spine at the observability-authority
 * tier). Sibling of the surface-altitude (571), interaction-surface (561), execution-surface (553) and
 * operation-surface (550) anti-fragmentation gates, lifted one tier DOWN: it governs the observability
 * STREAM family (logs, health conditions, the action lifecycle, indexing jobs, metrics, advisories) —
 * each declared as a CONCEPT with one canonical source + its contributors.
 *
 * BOUNDED per 575 §9 (it inherits 550 §5's honesty): the gate forecloses re-fragmentation of a DECLARED
 * concept (~100% in scope) and an explicit mis-model; it does NOT solve the irreducible discovery problem
 * (recognizing two independently-declared streams are secretly one concept stays detection+review).
 *
 * Conforms to the kernel truth-table contract: (input) → { ruleId, status, reason }.
 */

/** Verdict when the register file itself is absent. */
export function verdictForMissingRegister({ path }) {
  return {
    ruleId: 'observed-happening/register-missing',
    status: 'fail',
    reason: `Cannot run the observed-happening gate: the register was not found at ${path}.`,
  };
}

/**
 * Verdict: one or more declared contributor ids do not resolve to a real Resource or DiagnosticChannel
 * in the catalogs. The register may not name a phantom stream — coverage projects from the catalogs.
 */
export function verdictForUnresolvedContributors({ violations }) {
  if (violations.length > 0) {
    return {
      ruleId: 'observed-happening/contributor-unresolved',
      status: 'fail',
      reason:
        `These declared contributors do not resolve to any Resource or DiagnosticChannel catalog id: ` +
        `${violations.join(', ')}. Every contributor in governance/observed-happening.v1.json must name ` +
        `a real stream (a *ResourceCatalog.java Resource id or a *DiagnosticChannelCatalog.java channel ` +
        `id). Fix the id, or remove the stale concept entry.`,
    };
  }
  return {
    ruleId: 'observed-happening/contributors-resolve',
    status: 'pass',
    reason: 'Every declared contributor resolves to a real Resource / DiagnosticChannel (healthy).',
  };
}

/**
 * Verdict: one or more concepts declare a canonicalSource that is NOT among their own contributors (or
 * declare none). A concept's canonical source is the ONE store its projections derive from — it must be
 * one of the concept's contributors, so the concept is not sourceless or sourced-by-an-outsider.
 */
export function verdictForSourcelessConcept({ violations }) {
  if (violations.length > 0) {
    return {
      ruleId: 'observed-happening/concept-canonical-source',
      status: 'fail',
      reason:
        `These concepts declare a canonicalSource that is not one of their own contributors: ` +
        `${violations.join(', ')}. Every concept names exactly one canonical source, and that source ` +
        `must itself be a contributor (the one store the others project from). Add the source to ` +
        `contributors, or correct the canonicalSource.`,
    };
  }
  return {
    ruleId: 'observed-happening/concept-source-declared',
    status: 'pass',
    reason: 'Every concept names exactly one canonical source, itself a contributor (healthy).',
  };
}

/**
 * Verdict (THE single-source foreclosure — the F-2 class): a contributor id appears in more than one
 * concept. A stream contributes to exactly one observed-happening concept; the same record claimed by two
 * concepts is the representation-drift that fragmented Activity / operation-history / advisories
 * (tempdoc 550 F-2). Within a DECLARED concept this is foreclosed; recognizing a NEW fragmentation is the
 * irreducible discovery problem (575 §9 — detection+review).
 */
export function verdictForSharedContributor({ violations }) {
  if (violations.length > 0) {
    return {
      ruleId: 'observed-happening/contributor-shared',
      status: 'fail',
      reason:
        `These streams are claimed by more than one concept: ${violations.join(', ')}. A stream ` +
        `contributes to exactly one observed-happening concept — two concepts claiming one record is the ` +
        `representation-drift that fragmented the action lifecycle (tempdoc 550 F-2). Merge the concepts, ` +
        `or move the contributor to the one it truly belongs to.`,
    };
  }
  return {
    ruleId: 'observed-happening/contributor-single-concept',
    status: 'pass',
    reason: 'No stream is claimed by two concepts (healthy).',
  };
}

/**
 * Verdict (THE Channel-vs-Resource foreclosure — tempdoc 575 §4.2): one or more Resources declare an
 * operator-trace {@code origin} ({@link ProducerKind} — IN_PROCESS_LOGBACK / EXTERNAL_OBSERVER).
 * Operator-trace data is not Resource truth (ADR-0036 / C-012) — it belongs on a
 * {@link DiagnosticChannel} (different consumer model, schema discipline, privacy class, self-observation
 * risk). The `origin` facet exists so this boundary is representable-and-rejected rather than a silent
 * mis-model. Bounded teeth: it catches the EXPLICIT mis-model, not a silent omission.
 */
export function verdictForOperatorTraceResource({ violations }) {
  if (violations.length > 0) {
    return {
      ruleId: 'observed-happening/operator-trace-must-be-channel',
      status: 'fail',
      reason:
        `These Resources declare an operator-trace origin (a ProducerKind), so they model operator-trace ` +
        `data as a Resource: ${violations.join(', ')}. Operator-trace data is not Resource truth ` +
        `(ADR-0036) — model it as a DiagnosticChannel (a *DiagnosticChannelCatalog.java entry), not a ` +
        `Resource. Drop the .withOrigin(...) declaration, or move the stream to a DiagnosticChannel.`,
    };
  }
  return {
    ruleId: 'observed-happening/no-operator-trace-resource',
    status: 'pass',
    reason: 'No Resource declares an operator-trace origin (healthy).',
  };
}

/**
 * Verdict (KIND consistency — tempdoc 575 §4.2, the cheap completion of "kind is not inert"): a concept's
 * declared `kind` must match the primitive CLASS of its canonicalSource — a DiagnosticChannel id ⟺
 * `kind: "diagnostic-channel"`; a Resource id ⟺ a Resource-shape kind (anything but diagnostic-channel).
 * NOT a render-genre derivation (571 §9.B / 575 §3 declined that) and NOT a duplicate of
 * ResourceAreaValidator's shape checks — only that the register's label can't contradict the actual
 * primitive its source is.
 */
export function verdictForKindMismatch({ violations }) {
  if (violations.length > 0) {
    return {
      ruleId: 'observed-happening/kind-mismatch',
      status: 'fail',
      reason:
        `These concepts declare a kind inconsistent with the primitive class of their canonicalSource: ` +
        `${violations.join(', ')}. A concept sourced by a DiagnosticChannel must declare ` +
        `kind "diagnostic-channel"; one sourced by a Resource must not. Fix the kind, or the canonicalSource.`,
    };
  }
  return {
    ruleId: 'observed-happening/kind-consistent',
    status: 'pass',
    reason: "Every concept's kind matches the primitive class of its canonical source (healthy).",
  };
}

/**
 * Verdict (governed projections — tempdoc 575 §4.1, Pillar 1's third element): a concept's declared
 * `projections` must each resolve to a real surface mount tag (the read-view that renders it). Projections
 * are OPTIONAL (575 §9 — forcing completeness is the irreducible discovery problem), but a DECLARED
 * projection may not be a phantom. Mirrors the operation-surface lineage check (Check 4), reused not
 * duplicated.
 */
export function verdictForUnresolvedProjection({ violations }) {
  if (violations.length > 0) {
    return {
      ruleId: 'observed-happening/projection-unresolved',
      status: 'fail',
      reason:
        `These declared projections do not resolve to a real surface mount tag: ${violations.join(', ')}. ` +
        `Every projection in a concept must name a read-view that exists in the surface catalog ` +
        `(a "jf-*" mount tag). Fix the mount tag, or remove the stale projection.`,
    };
  }
  return {
    ruleId: 'observed-happening/projections-resolve',
    status: 'pass',
    reason: 'Every declared projection resolves to a real surface mount tag (healthy).',
  };
}

/**
 * Verdict (the liveness DECLARATION — tempdoc 575 §4.3b, the build-time half of the liveness invariant):
 * a concept marked `stateful: true` (it has in-flight records) MUST name a `livenessOwner` — the state
 * machine + owner that reconciles its in-flight records against a live signal ("every in-flight record has
 * a live owner" — 550 Thesis II). This is the build-time foreclosure; the runtime mechanism (in-flight
 * derives from a live owner, not stream membership) is the C-ii / 550 Thesis II work the owner names.
 */
export function verdictForStatefulMissingLiveness({ violations }) {
  if (violations.length > 0) {
    return {
      ruleId: 'observed-happening/stateful-requires-liveness',
      status: 'fail',
      reason:
        `These stateful concepts do not declare a livenessOwner: ${violations.join(', ')}. A concept with ` +
        `in-flight records (stateful: true) must name the state machine + owner that reconciles them ` +
        `against a live signal — every in-flight record must have a live owner (550 Thesis II / 575 §4.3b). ` +
        `Add a livenessOwner, or set stateful: false if the concept has no in-flight state.`,
    };
  }
  return {
    ruleId: 'observed-happening/stateful-liveness-declared',
    status: 'pass',
    reason: 'Every stateful concept declares a livenessOwner (healthy).',
  };
}

/**
 * Verdict (PRESENTATION reverse-coverage — tempdoc 575 §17 Face B, the projection-spine closure turned
 * on presentation): a concept marked `stateful: true` (it has live, in-flight state) MUST declare at
 * least one `projection` — the read-view surface that renders that live state (the System Self-View OR
 * any declared surface, §17.4). A live concept that no surface renders is invisible at runtime; this is
 * the §5 meta-failure ("a new concept escapes governance") closed at the presentation tier, the §13 L1
 * stream-uncovered pattern turned from data onto display. The projections' RESOLUTION is the separate
 * `projection-unresolved` rule; this rule is the existence half. BOUNDED (the FE ceiling, 575 §9): it
 * proves a surface is DECLARED, not that the surface's code provably subscribes — declaration-resolves,
 * not code-proves-render (the same bar as the inflight-liveness register).
 */
export function verdictForStatefulUnprojected({ violations }) {
  if (violations.length > 0) {
    return {
      ruleId: 'observed-happening/live-concept-unprojected',
      status: 'fail',
      reason:
        `These stateful concepts declare no projection (no surface renders their live state): ` +
        `${violations.join(', ')}. A concept with live, in-flight state (stateful: true) must name at ` +
        `least one projection — the read-view that renders it (the System Self-View or any declared ` +
        `surface, 575 §17.4 Face B). A live concept no surface shows is invisible at runtime. Add a ` +
        `projection (a "jf-*" mount tag), or set stateful: false if it has no in-flight state to render.`,
    };
  }
  return {
    ruleId: 'observed-happening/live-concepts-projected',
    status: 'pass',
    reason: 'Every stateful concept declares a projection that renders its live state (healthy).',
  };
}

/**
 * Verdict (reverse-coverage — tempdoc 575 §13 L1, the completeness foreclosure): every Resource /
 * DiagnosticChannel id declared by a catalog (parsed from `implements ResourceCatalog` /
 * `DiagnosticChannelCatalog`, robust to filename) must be a contributor of some concept OR explicitly
 * listed in the register's `outOfFamily` array (with a reason). This closes the meta-gap §5 names — a NEW
 * observability stream can no longer escape the register silently. BOUNDED per 575 §9: it covers the
 * Resource/Channel-backed universe; the non-Resource family members (boot trace, scan progress, the 553
 * search-trace) are governed by their own spines and are out of this register's enforcement scope. The
 * `outOfFamily` list is where the irreducible discovery-judgment ("is this a happening?") lives, made
 * explicit and reviewable rather than silent.
 */
export function verdictForUncoveredStream({ violations }) {
  if (violations.length > 0) {
    return {
      ruleId: 'observed-happening/stream-uncovered',
      status: 'fail',
      reason:
        `These catalog streams are neither a declared contributor nor listed in outOfFamily: ` +
        `${violations.join(', ')}. Every Resource / DiagnosticChannel the catalogs declare must be ` +
        `accounted for — either declare it as (a contributor of) an observed-happening concept, or add it ` +
        `to the register's "outOfFamily" array with a reason if it is genuinely not a what-happened/` +
        `what's-true stream (e.g. a capability handshake). A new stream may not escape the register silently.`,
    };
  }
  return {
    ruleId: 'observed-happening/streams-covered',
    status: 'pass',
    reason: 'Every catalog stream is a declared contributor or explicitly out-of-family (healthy).',
  };
}

// verdictForLivenessWindow — RETIRED (tempdoc 575 §17 Face A). The liveness window is now GENERATED
// from the register into the worker + FE constants (scripts/codegen/gen-liveness-constants.mjs), so
// drift is impossible by construction; the `regen-all --check` gate is the replacement.
