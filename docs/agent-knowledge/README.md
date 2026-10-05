# Agent knowledge (moved from the retired skills)

These files were the domain skills of JustSearch's former agent layer (`.claude/skills`). When
JustSearch adopted agent-system on 2026-10-05, the workflow skills were retired, because agent-system's
own stage guides and role contracts replace them. The skills below carry project knowledge, so they
moved here unchanged. Agent-system project knowledge points to them; read the one that matches the
work before starting it.

| File | Read before |
| --- | --- |
| [search-quality.md](search-quality.md) | Retrieval or ranking quality work; it holds the search-quality register |
| [inference-runtime.md](inference-runtime.md) | Inference runtime, llama-server or GPU work; it holds the inference-runtime register |
| [dev-stack.md](dev-stack.md) | Starting, sharing or stopping the local dev stack |
| [jseval.md](jseval.md) | Profiling, evaluation runs or live-stack measurement |
| [installer.md](installer.md) | Installer, packaging or upgrade work |
| [ci-triage.md](ci-triage.md) | A failing hosted CI run |
| [ui-check.md](ui-check.md) | UI changes that need a visual or accessibility check |
| [module-arch.md](module-arch.md) | Changes that cross module boundaries |
| [governance.md](governance.md) | Discipline gates and governance registers |
| [ssot-catalog.md](ssot-catalog.md) | SSOT catalog changes |
| [docs-maintenance.md](docs-maintenance.md) | Canonical documentation upkeep |

The two registers (search quality, inference runtime) are updated in place when work changes what
they record. Passages that mention the old layer (slash commands, `CLAUDE.md`, hooks, tempdoc
numbering rules for skills) are historical.
