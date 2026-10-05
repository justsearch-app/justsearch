---
description: "TRIGGER when: editing files in docs/explanation/, docs/reference/, docs/how-to/, docs/decisions/, after creating/modifying canonical documentation, or when auditing docs for tempdoc-to-canonical drift. Loads the post-edit regeneration sequence, doc quality rules, and the drift-audit procedure."
user-invocable: true
---

# Docs Maintenance

After editing any canonical doc, run this regeneration sequence.

## Post-Edit Regeneration (required)

Run these commands after every canonical doc change:

```bash
# 1. Always — regenerate the docs index
node scripts/docs/llmstxt-generate.mjs

# 2. Always — sync skills from canonical docs (if any synced skill sources changed)
node scripts/docs/skills-sync.mjs

# 3. After module architecture changes — update dependency graph
node scripts/architecture/module-deps.mjs --update-canonical

# 4. After configuration changes — update runtime config matrix
node scripts/docs/generate-runtime-config-matrix.mjs --write-doc docs/reference/configuration/runtime-config-ownership-matrix.md
```

Steps 1 and 2 are always required. Steps 3 and 4 are conditional.

## Verification (CI gate)

```bash
node scripts/docs/llmstxt-generate.mjs --check
node scripts/docs/skills-sync.mjs --check
node scripts/docs/verify-canonical-doc-links.mjs
node scripts/architecture/module-deps.mjs --check-canonical
node scripts/docs/verify-runtime-config-matrix.mjs
```

For prompt-surface or agent-instruction changes, also run:

```bash
node scripts/docs/prompt-surface-inventory.mjs
```

This reports prompt-like surfaces, generated/manual status, size, and
suspicious stale tokens. It is a drift-control report, not an agent-quality
metric.

## Doc Quality Rules

### Canonical vs Noncanonical
- **Canonical (must not drift):** `docs/explanation/`, `docs/reference/`, `docs/how-to/`, `docs/decisions/`
- **Noncanonical (dated working history, allowed to drift):** `docs/tempdocs/`

If the two disagree, canonical is truth. If canonical is unclear, verify against source and
contract tests — not against a tempdoc, which reflects only its writing date.

### Link Rules
- Canonical docs **must not** reference tempdocs (`docs/tempdocs/`). CI lint will reject this.
- Replace tempdoc cross-references with source file references or canonical doc references.

### Frontmatter Requirements
Every canonical doc needs YAML frontmatter:
```yaml
---
title: "Document Title"
type: explanation | reference | how-to | decision
status: stable | draft | deprecated
description: "One-line summary."
---
```

### Writing Style (Agent-Friendly)
- Context-independent paragraphs (each should stand alone in RAG retrieval)
- Flat Markdown (no complex HTML tables, no images with critical text)
- Specific names: "The **IndexingLoop** updates the **SQLite JobQueue**" not "The system updates the database"
- Tables over prose for structured data
- Code blocks with language tags (MD040 lint rule)

### ADR Template
New ADRs use MADR-lite: Status / Context / Decision / Consequences / Alternatives Considered.
Next available number: check `docs/decisions/README.md` Decision Log table.

## Drift Audit (periodic tempdoc → canonical review)

Use this when auditing doc freshness rather than reacting to a single edit.

1. **List recent tempdocs** — `ls -lt docs/tempdocs/ | head -N`. Highest number first; a
   newer tempdoc supersedes an older one's claims.
2. **Per tempdoc, determine canonical impact** — which canonical docs it affects (check
   `docs/llms.txt`), what specifically changed (features, API, model swaps, decisions), and
   whether it warrants a new ADR (a load-bearing decision with alternatives considered).
3. **Categorize** — stale facts (wrong model names, deleted modules still listed, "planned"
   features now shipped) · missing content (new endpoints, pipeline stages, schema fields) ·
   new ADRs needed (architectural decisions recorded only in a tempdoc).
4. **Group by topic cluster** (API contract, search pipeline, model inventory) before
   editing, to minimize context switching and cross-reference errors.
5. **Execute, then run the full verification block above.**

### Common drift patterns

| Pattern | Example | Impact |
|---------|---------|--------|
| Model swap | Tempdoc swaps reranker; model-inventory still shows the old model | Agents use the wrong model name in prompts/configs |
| Module deletion | Tempdoc deletes a module; module-architecture still lists it | Agents try to find/modify deleted code |
| Feature activation | Tempdoc ships entity facets; the ADR still says "future feature" | Agents treat a shipped feature as unimplemented |
| API expansion | Tempdoc adds response fields; the contract map is incomplete | Frontend/agent consumers miss new capabilities |
| New invariant | Tempdoc establishes a pipeline rule; the invariants doc is missing it | Agents violate the rule unknowingly |
| Phantom path | A doc names a directory/file that no longer exists (or never did) | Agents route work to a location that isn't there |

## Current decisions and evidence

Keep current decisions in their owning sections and a short dated index linking
rationale and superseded alternatives. Handoffs summarize current state and next
actions; private transcripts are optional background. Keep bulky logs in retained
artifacts with access/retention limits, not repeated in design prose. Follow
`docs/reference/contributing/agent-workflow.md` when recording acceptance proof.
