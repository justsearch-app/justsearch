---
title: "ADR-0043: Multilingual by construction — no per-language levers"
type: decision
status: accepted
description: "Retrieval analysis uses one multilingual model stack and locale-invariant analysis, with no per-language stemmers, analyzer fields, spelling dictionaries or curated synonyms. English word lists remain in NER cleanup; the 2026-10-20 amendment records this limit of the broader engine claim."
date: 2026-06-15
probes:
  - adr-0043-no-per-language-levers
last_reviewed: 2026-10-20
---

# ADR-0043: Multilingual by construction — no per-language levers

## Status

Accepted. Spun out of tempdoc 581. Partially supersedes ADR-0013 (the per-language
synonym placeholder is retired; ADR-0013's analyzer-fingerprinting algorithm remains in force).

Current implementation corrections are recorded in the 2026-10-20 amendment below.

## Context

JustSearch must retrieve well in any language a user indexes. There are two ways to get there:

1. **Per-language components** — a stemmer, analyzer field, stopword list, spelling dictionary,
   or curated synonym set authored *per language*. This is the traditional Lucene path.
2. **Multilingual models** — one tokenizer + one encoder family that covers many languages at
   once, with no per-language artifact to author.

Per-language components carry an **O(languages) maintenance cost forever**, and they degrade
*silently*: a stemmer nobody updates, a locale field nobody routes to. The cost is also
*asymmetric* — you do "the German work", then "the French work", then "the Chinese work", and
coverage is whatever set someone got to. The pipeline is already built almost entirely from
language-agnostic primitives (ICU tokenization, NFC normalization, BM25, and the multilingual
dense/sparse/reranker models), and the measured evidence shows this is enough: German, French,
and Chinese each retain **90%+** of their isolated retrieval quality when mixed into one index
(register F-007), and MIRACL de/fr/zh all score well through the *same* pipeline.

A 2026-06-15 audit (tempdoc 581) found the only per-language scaffolding present — the
`content_en`/`content_de` fields, the `en`/`de` analyzers, and the `synonyms.{en,de}` files —
was completely **inert**: the synonym files were empty, the locale analyzers were byte-identical
to the default, and the locale fields were never written or queried. The measured multilingual
quality was being achieved with *zero* per-language processing active.

## Decision

**The engine is multilingual by construction, and stays that way with no per-language
engineering or maintenance.** Adding or improving support for a language must require **no new
per-language artifact to author or maintain**. The lever space is classified into three buckets:

- **A — Per-language COMPONENTS: REJECTED.** Anything requiring a distinct authored artifact per
  language — language-specific stemmers, locale-aware analyzer fields, per-language stopword
  lists, spelling dictionaries, or hand-curated synonym sets.
- **B — Language as a SIGNAL to a uniform policy: ALLOWED.** Using *detected language* as one
  input feature to a single language-agnostic policy adds no per-language artifact (e.g. a
  learned recipe-weight function may read language as one feature; the existing `language` field,
  set by one uniform script heuristic, is the channel). The test: *does adding language N require
  a human to author something for language N?* If no, it is allowed.
- **C — Language-agnostic levers: FAIR GAME.** Quantization, fusion strategy, chunking, ANN
  parameters — evaluated on their own merits, untouched by this decision.

Consequently the per-language scaffolding was removed and analysis is now locale-invariant
(`ICUTokenizer → NFC → lowercase`), and the lever backlog is reclassified: **FW-006** (English
stemming), **Q-004** (locale-aware BM25 routing), and per-language synonym programs are all
**won't-do** (register D-003).

## Consequences

- **Positive:** zero per-language maintenance; uniform coverage across all languages the models
  support at once; the "cheap win" re-litigation loop (Q-004 resurfacing) is closed structurally.
- **Negative:** a few points of monolingual peak are traded away vs a hand-tuned monolingual
  stack; coverage is bounded to the languages the multilingual models handle (~70–75 for
  gte-multilingual); the per-language work is outsourced to the model vendors, not eliminated.
- **Enforcement (tempdoc 576 ladder):**
  - *Rung 1 (unrepresentable):* the analyzer-provider field in
    `SSOT/schemas/indexing/analyzers-catalog.schema.json` is a closed `enum` (`icu`, `keyword`) —
    a per-language provider is a schema-validation error.
  - *Rung 2 (guarded):* `scripts/ci/check-language-agnostic-analysis.mjs` fails the build on a
    non-`*` locale analyzer, an out-of-enum provider, a `content_<lang>` field, a non-empty
    per-language synonym file under `SSOT/catalogs/`, or a `content_<lang>` query-path literal.

## Scope

This decision governs the **search engine** (the language of the indexed corpus and its
retrieval). It does **not** govern UI localization — interface strings
(`SSOT/messages/errors.*.json`), prompt templates (`SSOT/prompts/<lang>/…`), or the i18n
catalog. Those are a separate, legitimately per-language surface: no multilingual model
translates your UI, so per-language authoring there is the product feature, not a smuggled-in
lever (tempdoc 581 §12.7).

## Revisit when

A *measured, large* monolingual gap appears that a **uniform mechanism** cannot close — e.g. a
script the encoders handle poorly. The sanctioned response is still uniform from the engine's
perspective: improve or swap the single multilingual model, or choose a better single model for a
deployment. "Language N retrieval is a bit weak" is **not** sufficient cause to start the
per-language treadmill. Per-language components stay out unless the fix can be expressed as one
uniform mechanism rather than an O(languages) program.

## Alternatives considered

- **Per-language analyzer fields routed by detected language (Q-004).** Rejected: it is the
  O(languages) maintenance this decision forbids, and the multilingual models already deliver the
  cross-language quality (F-007). Also incompatible with the zero-hit fuzzy-correction path
  (tempdoc 223).
- **Keep the inert scaffolding "frozen" instead of removing it.** Rejected: dead, asymmetric
  (en/de only) scaffolding invites reactivation and contradicts "by construction"; removal makes
  the bad state unrepresentable (rung 1).
- **Document-only (prose) enforcement.** Rejected as insufficient: prose at ~70% adherence is
  exactly what let Q-004 keep resurfacing; the schema enum + CI gate raise it to ~100%.

## Amendment 2026-10-20: limits of the no-language-artifact claim

The locale-invariant retrieval-analysis decision and its SSOT guard remain.
It is not evidence that all entity processing is free of authored English
heuristics: `BioTagDecoder.HEADER_STOPWORDS` contains English header words, and
`EntityNormalizer.LEADING_ARTICLES` contains `the`, `a` and `an` alongside
honorific lists. These are active NER/disambiguation cleanup heuristics in
`modules/worker-core/src/main/java/io/justsearch/indexerworker/ner/BioTagDecoder.java`
and `modules/worker-core/src/main/java/io/justsearch/indexerworker/disambiguation/EntityNormalizer.java`.
This records a limit of the broad "engine" wording, not a new exemption for
per-language analyzers, spelling dictionaries or synonym lists. Changing those
entity heuristics or deciding their policy scope is separate work.
