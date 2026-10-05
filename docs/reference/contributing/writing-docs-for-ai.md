---
title: Writing Docs for AI
type: reference
status: stable
description: "How to keep docs agent-friendly."
---

# Writing Docs for AI

**ATTENTION AI AGENTS:** JustSearch’s documentation is written to be **agent-friendly** by default.
The canonical, must-not-drift docs live under:
- `docs/explanation/`
- `docs/reference/`
- `docs/how-to/`
- `docs/decisions/`

Noncanonical notes/ideas that may drift live under:
- `docs/tempdocs/`

Current LLMs consume documentation differently than humans. We scan, chunk, and embed. We do not "read" linearly. Therefore, when you write documentation in this repo—whether for humans or for other agents—you must follow these **LLM-Optimization Standards**.

---

## 1. The Core Philosophy: "Token Efficiency & Semantic Clarity"

### 1.1. Context Independence (Chunking Friendly)
*   **Bad:** "As mentioned in the previous section, the server does this." (Requires maintaining long-term state).
*   **Good:** "The **Knowledge Server** processes files." (Self-contained facts).
*   **Rule:** meaningful paragraphs should stand on their own. Assume your reader might only see this one paragraph in a RAG retrieval window.

### 1.2. Structure vs. Layout
*   **Avoid:** Complex HTML tables, multi-column layouts, or images containing critical text. Multimodal models are slow; text-only models are blind.
*   **Prefer:** Flat Markdown lists, properly nested headers (`#`, `##`, `###`), and code blocks.
*   **Why:** Markdown structure maps directly to semantic meaning. It is the native language of technical LLMs.

---

## 2. The `llms.txt` Standard

We follow the standard proposed by Jeremy Howard (2024).

### 2.1. The Map (`/llms.txt`)
At the root of the site (or docs), we maintain an `llms.txt`. This is the **Index**.
*   It lists the *high-value* files.
*   It provides a brief summary of what each file contains.
*   **Goal:** An agent can read this single file and know exactly which 3 other files to request to answer a user's question.

### 2.2. The Context (`/llms-full.txt`)
Some projects maintain a concatenated `llms-full.txt` file. **JustSearch does not maintain this by default.**

If we later add an automated generator, it should concatenate **canonical docs only** (exclude `tempdocs/` and the observations conditions store) to avoid drift/noise.

---

## 3. Writing Style for Agents

1.  **Be Specific, Not Generic:**
    *   ❌ "The system updates the database."
    *   ✅ "The **IndexingLoop** updates the **SQLite JobQueue**."
    *   *Why:* "System" is a stop-word in RAG. Unique class names are strong semantic hooks.

2.  **Define Terms Early:**
    *   If you introduce a term like "The Suicide Pact," define it immediately in the same paragraph. Do not force the model to look up a glossary definition 500 lines away.

3.  **Code Snippets are Gold:**
    *   Agents understand code better than prose.
    *   Include method signatures (e.g., `public void index(Document doc)`) rather than just describing them.

4.  **Avoid formatting noise:**
    *   Do not use excessive bolding or italics for emphasis (it distracts from the tokens). Use them only for keywords.

### 3.1. Make operational claims checkable

Claims about current behavior, coverage, versions, counts, defaults, or support
must name a source an agent can inspect: a code symbol, generated artifact,
register, contract test, or exact command. Prefer a projection from that source
over a copied value. If a sentence cannot be checked mechanically and is likely
to change, qualify it as dated evidence or move it to a tempdoc instead of
presenting it as current canonical truth.

---

## 4. Doc Types

Canonical docs follow a [Diataxis](https://diataxis.fr/)-inspired structure. Each type serves a different purpose:

| Type | Directory | When to use |
|------|-----------|-------------|
| **Explanation** | `docs/explanation/` | How something works and why it was built that way |
| **Reference** | `docs/reference/` | Lookup information: API contracts, config knobs, checklists |
| **How-To** | `docs/how-to/` | Step-by-step procedures for specific tasks |
| **Decision (ADR)** | `docs/decisions/` | Architectural decisions with alternatives considered |

**Explanation vs ADR:** An explanation doc describes how the system works today. An ADR captures a specific decision point — what alternatives were considered and why they were rejected. Most architecture topics get an explanation doc. An ADR is warranted when the "why not X?" question has a non-obvious answer worth preserving.

**Findings vs ADRs:** the domain registers ([search quality](../search-quality-register.md), [inference runtime](../inference-runtime-register.md)) and owning tempdocs' open-items sections track ongoing tensions, known trade-offs, and verified bugs; their entries evolve as the system changes. ADRs are immutable one-time decisions. (The standing `docs/reference/issues/` registers were retired in tempdoc 821 §7 D5; the observations conditions store was retired in tempdoc 872.)

**Altitude within a type — write the invariant, not the parameter.** The *type* (above) picks the directory; the *altitude* decides what survives. Within an explanation doc, state what is **invariantly true** — the model, the boundary laws, the named seams and classes — not the parameter values the current implementation happened to choose. Parameter-level facts (caps, thresholds, operator lists, error-string discriminants, method signatures) belong in **reference**, where detail and versioning are expected; the dated narrative of *how* a change arrived belongs in its **tempdoc** (`tempdocs-are-dated-history`). The operational test: **would the newest tempdoc in this area have falsified this sentence?** If yes, it is parameter-level — move it to reference, or let the enforcing gate/type be its own source of truth. If no, it is invariant-level and earns its place in explanation. A tell that this went wrong: an explanation doc that opens "records the conventions that emerged from tempdoc N" — that is a reference catalog wearing an explanation `type:`, and it will rot at the next N.

ADR conventions (template, numbering, append-only rules) are in `docs/decisions/README.md`.

Prompt-surface ownership rules (historical, superseded) are in
[Agent Prompt Surface Governance](agent-prompt-surface-governance.md).

---

## 5. Frontmatter

Every canonical doc requires YAML frontmatter. The `llms.txt` auto-generator reads these fields to build the docs index.

| Field | Required | Values |
|-------|----------|--------|
| `title` | Yes | Human-readable title |
| `type` | Yes | `explanation`, `reference`, `how-to`, `decision` |
| `status` | Yes | `stable`, `in-progress`, `draft`, `advisory` |
| `description` | Yes | One-line summary (appears in `llms.txt`) |
| `date` | Decisions only | `YYYY-MM-DD` |

Example:

```yaml
---
title: Storage Engine
type: explanation
status: stable
description: "Lucene internals, schema (SSOT), and locking strategies."
---
```

**Status behavior:**
- `stable` and `in-progress` → included in `llms.txt`
- `advisory` → included in `llms.txt`; describes planned/G0 contracts not yet enforced
- `draft` → excluded from `llms.txt`
- Missing `status` → CI error (build fails)

---

## 6. Adding or Updating Docs

### Procedure

1. Create the file in the correct canonical directory with the required frontmatter (§5).
2. Regenerate the docs index: `node scripts/docs/llmstxt-generate.mjs`
3. Verify with the 4 CI checks:

```text
node scripts/docs/verify-canonical-doc-links.mjs
node scripts/architecture/module-deps.mjs --check-canonical
npx markdownlint docs/explanation/**/*.md docs/reference/**/*.md docs/how-to/**/*.md docs/decisions/**/*.md
node scripts/docs/llmstxt-generate.mjs --check
```

### Rules enforced by CI

- **No tempdoc cross-references:** Canonical docs must not link to `docs/tempdocs/`. The link checker rejects this.
- **Module dependency doc freshness:** `docs/reference/architecture/module-deps.md` must match the current Gradle graph. The module-deps checker rejects stale generated content.
- **Code fence language tags:** Every fenced code block needs a language identifier (MD040). Use `text` for ASCII diagrams, directory trees, and command output.
- **llms.txt freshness:** The `--check` mode compares the committed `llms.txt` against what the generator would produce. If they differ, CI fails. Always regenerate after frontmatter changes.
