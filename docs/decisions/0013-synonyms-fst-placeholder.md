---
title: "ADR-0013: Synonyms FST Placeholder"
type: decision
status: accepted - partially superseded by ADR-0043
description: "The synonym FST placeholder is moot: per-language synonyms, SynonymsCompiler and ssotCompileSynonyms were removed (ADR-0043). The live part is the analyzer fingerprinting algorithm: SHA-256 over a sorted-key JSON descriptor, pinned by SsotValidatorFingerprintTest."
date: 2025-10-15
probes: none - partially superseded by ADR-0043, which owns the surviving question; the placeholder artifacts are covered by the language-agnostic-analysis check through 0043.
last_reviewed: 2026-09-02
---

# ADR-0013: Synonyms FST Placeholder

## Status

**Partially superseded by [ADR-0043](0043-multilingual-by-construction-no-per-language-levers.md) (2026-06-15).**
The synonyms-FST-placeholder rationale is now moot: per-language synonyms were removed entirely
(tempdoc 581 §13 — native multilingual, no per-language levers), and `SynonymsCompiler` + the
`ssotCompileSynonyms` build task were deleted. The **Fingerprinting Algorithm** section below is
NOT superseded — it remains the canonical description of how analyzer fingerprints in
`SSOT/catalogs/analyzers.v1.json` are computed and pinned (`SsotValidatorFingerprintTest`).

Original status: Accepted (temporary — pending real FST compiler implementation).

## Context

SSOT synonyms must be compiled to artifacts referenced in commit metadata. A deterministic placeholder is sufficient for gating SSOT drift and wiring, while the Lucene-backed FST compiler lands in a later phase.

## Decision

- `SynonymsCompiler` (in `modules/ssot-tools`) writes normalized, deterministic text artifacts (`*.fst`) — not real Lucene FSTs. Originally a Node.js script (`synonyms-compile.mjs`), migrated to Java in March 2026.
- Mark this as a temporary placeholder and plan a Java helper (`Lucene SynonymMap` to FST bytes) to replace it.

## Fingerprinting Algorithm

Analyzer fingerprints in `SSOT/catalogs/analyzers.v1.json` must be deterministic and non-zero. The fingerprint is computed as SHA-256 of a canonical JSON descriptor containing `{id, locale, provider, components}`.

**Canonicalization method:** Jackson `ObjectMapper` with `ORDER_MAP_ENTRIES_BY_KEYS` (sorted-key JSON). This is **not** JCS (RFC 8785) — Jackson sorted-key output is equivalent for descriptors containing only strings and string arrays, but differs from JCS for numbers and special Unicode escapes. The fingerprints were recomputed when migrating from the Node.js `canonicalize` library (JCS) to Jackson canonicalization. A pinned-hash test in `SsotValidatorFingerprintTest` guards against silent drift from Jackson version upgrades.

When the real Lucene FST compiler lands, pivot the descriptor to include the FST bytes hash and Lucene/ICU version pins. Keep the algorithm stable for the same inputs.

## Consequences

- CI drift checks and hashes remain stable across OSes due to input normalization.
- Index runtime must not rely on these artifacts until the real FST compiler is introduced.

## Follow-up

- Implement real Lucene FST compiler in `modules/infra-core` and update `SynonymsCompiler` to produce actual FST binaries instead of text placeholders.
- Update fingerprint descriptor to include FST bytes hash and Lucene/ICU version pins.
