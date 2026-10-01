# Contributing to JustSearch

Thank you for your interest in contributing to JustSearch! This document provides guidelines and instructions for contributing.

> **The short version.** Clone the repo, run the bootstrap below, build and test, pick a good-first-issue,
> open a PR, and sign the CLA once when the bot prompts you on your first PR. That's it. You do **not**
> need Claude Code, Codex, the agent hooks, the dev-stack
> tooling, or the governance/discipline gates to contribute — those are *how the maintainer develops*,
> published as transparency, not as a required contributor path. Adopt them if you like them; ignore them and
> just send a PR. Curious how the maintainer develops? See [`MAINTAINING.md`](MAINTAINING.md).

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## How to Contribute

### Reporting Bugs

Use the [bug report template](https://github.com/justsearch-app/justsearch/issues/new?template=bug_report.md) to report issues. Include:
- Steps to reproduce
- Expected vs actual behavior
- Environment details (OS, Java version, GPU if applicable)
- Relevant logs from `%LOCALAPPDATA%\JustSearch\logs\`

### Suggesting Features

Use the [feature request template](https://github.com/justsearch-app/justsearch/issues/new?template=feature_request.md) to propose new features. Describe:
- The problem you're trying to solve
- Your proposed solution
- Alternatives you've considered

### Pull Requests

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Make your changes
4. Verify locally: `./gradlew.bat build` (compile + tests), and for frontend changes `cd modules/ui-web && npm run typecheck && npm run test:unit:run`
5. Commit your changes
6. Push and open a pull request

You do not need to curate your branch into perfect public-history commits before
opening a PR. Maintainers may squash PRs before merge so public `main` stays
readable while contributors can work naturally on branches. Keep the PR title
and body focused on the durable change because they become the squash commit;
maintainers keep mutable verification and review state in a separate managed PR
comment.

## Development Setup

Choose either contributor environment:

- **Native Windows:** install Node first if necessary with
  `./scripts/setup/bootstrap-node-win.ps1`, then run `node scripts/setup/bootstrap.mjs`. The
  PowerShell script is only the pre-Node step; the Node script performs the shared repository setup.
- **Dev container / Codespaces:** reopen the repository in the checked-in dev container. Its Ubuntu
  24.04 environment pins Temurin 25, Node 24, Python 3.13, and stable Rust. The container runs the
  same bootstrap and the onramp doctor after creation.

The dev container is a CPU-only contributor environment. It does not add Linux product support,
package the desktop application for Linux, mount models, or require a GPU.

### Prerequisites

**For native development** (the dev container supplies these automatically):

- Windows 10/11
- A JVM to launch the Gradle wrapper. The checked-in Daemon JVM criteria selects and, when necessary,
  auto-provisions Temurin JDK 25 for the build, independent of the wrapper client's ambient Java. Keeping
  `JAVA_HOME` and `PATH` on JDK 25 is still recommended so direct `java`/`javac` use and non-Gradle tooling
  agree; the dev-runner also resolves a suitable JDK explicitly for the Engine JVM.
- Node.js 20+ (for the `modules/ui-web` frontend) — `scripts/setup/bootstrap-node-win.ps1` can install it for you
- Python 3.13+ for the repository's Python-backed checks and evaluation tooling
- Rust stable only when working on the optional Tauri shell under `modules/shell`; its absence does
  not block core Java/web contribution

Run `node scripts/setup/bootstrap.mjs --check` for a non-mutating prerequisite and lockfile check,
or `node scripts/setup/bootstrap.mjs` to install the repository's pinned JavaScript dependencies.
The bootstrap never chooses a host package manager or edits your shell profile.

The core build does **not** require the Rust/Tauri toolchain (the desktop
shell in `modules/shell` builds separately, only for packaging the installer), a **GPU**, or the **~9 GB model
download** — the models are fetched on first *run* of the app, not to build or test it.

**To run the full desktop app** (not needed to contribute code):

- Keyword search works with **no models and no GPU** (see "First run from source" below). Semantic
  search adds the ONNX models (~3.5 GB); cited AI answers additionally need an NVIDIA GPU (8 GB+ VRAM)
  and the chat model. The packaged installer's "Install AI" flow downloads what your hardware supports.

### Building

```text
# Windows                         # Dev container / Unix
./gradlew.bat build               ./gradlew build
./gradlew.bat build -x test       ./gradlew build -x test
./gradlew.bat :modules:ui:run     ./gradlew :modules:ui:run
```

### First run from source (the onramp)

Running from source does **not** auto-download the AI models (that's the packaged app's "Install AI"
flow). You still reach a useful result immediately — the capability is **tiered**, and each tier is a
complete success:

- **Tier 0 — keyword search, zero download.** Start the dev stack, index the bundled demo corpus
  (`examples/onramp-corpus/`), and a keyword query returns a real result — no models, no GPU.
- **Tier 1 — semantic/hybrid search.** Add the ONNX models (~3.5 GB) for meaning-based retrieval.
- **Tier 2 — cited AI answers.** Add the chat model **and** a GPU runtime (`gradlew[.bat]
  :modules:ui:stageLlamaCudaVariant`, once, at the main checkout) for grounded, cited answers.

`node scripts/dev/doctor.mjs` reports which tier your environment is at and the single next step to
reach the next one. The runnable proof `node scripts/dev/test-onramp-first-success.mjs` starts the
stack, indexes the demo corpus, and asserts a first result end-to-end.

### Testing

```text
# Replace gradlew.bat with ./gradlew in the dev container or on Unix.
./gradlew.bat test
./gradlew.bat :modules:adapters-lucene:test
./gradlew.bat build
```

### Code Style

Code style is enforced automatically:

- **Java**: Spotless (auto-formats on build)
- **PMD**: Static analysis rules enforced
- **TypeScript**: ESLint + Prettier

To fix formatting issues:

```text
# Replace gradlew.bat with ./gradlew in the dev container or on Unix.
./gradlew.bat spotlessApply
```

## Contributor agreement: CLA

Contributing to JustSearch requires a one-time Contributor License Agreement (CLA). The CLA keeps
the project's long-term licensing options open — **without taking away your rights**: you keep the
copyright to everything you contribute, and the project is and stays open-source under
**Apache-2.0**.

### Contributor License Agreement (CLA) — signed once

When you open your **first pull request**, an automated **CLA assistant** comments on it. You agree by
replying once (a single comment); that records your signature against your GitHub account and covers
all your future contributions. A PR can't be merged until the CLA is signed.

**What you're agreeing to** (full text: [`CLA.md`](CLA.md)): you **keep your copyright**, and you grant
the maintainer a broad licence to use your contribution — including the right to license the project as
a whole under additional terms in the future (e.g. a commercial licence alongside the open-source one).
This is **not** a copyright assignment and does **not** make JustSearch proprietary. It exists because,
with only the open-source licence, the project could never offer a dual/commercial licence later without
the consent of every past contributor — the CLA preserves that option now, while contributors are few.
If you contribute on behalf of an employer, see §8 of the CLA (a Corporate CLA may be needed).

## Project Structure

| Directory | Purpose |
|-----------|---------|
| `modules/ui` | HTTP API (Head process) |
| `modules/ui-web` | Frontend (Lit web components, TypeScript, Vite) |
| `modules/indexer-worker` | Lucene indexing (Body process) |
| `modules/gpu-bridge` | GPU/VRAM detection, hardware capability helpers |
| `modules/app-inference` | Local `llama-server` lifecycle (chat/RAG) |
| `modules/shell` | Tauri desktop shell |
| `docs/` | Documentation (Diataxis structure) |

For detailed architecture, see [System Overview](docs/explanation/01-system-overview.md).

## Questions?

[Open an issue](https://github.com/justsearch-app/justsearch/issues/new/choose) for questions or ideas that aren't bug reports or feature requests — GitHub Discussions is not enabled on this repo.
