# Non-Goals

JustSearch is a **local-first, private neural search engine for your own files.** That focus implies some things
it deliberately is **not**. These boundaries keep the project coherent and maintainable by a small team —
they're not judgments about other tools.

### Privacy & locality (hard invariants — these will never change)
- **No telemetry, analytics, or phone-home.** The product does not report usage anywhere. A PR that adds outbound
  reporting will be declined.
  Today, desktop builds with an embedded release URL check GitHub for updates on launch, including CI-built
  branch candidates and pre-releases, with no off switch yet; these requests send no usage data. Install AI
  downloads models and runtimes from `github.com` and `huggingface.co` with consent. Trace export is off by
  default; only if you enable tracing and set an OTLP endpoint are traces sent there. They can include paths
  and document excerpts. These network paths and external integrations are described in [Privacy](README.md#privacy).
- **Not a (required) cloud service.** JustSearch runs on *your* machine; we won't make a cloud component, an
  account, or server-side processing of your documents *mandatory* for the local core.
- **We don't train on your data**, and we never upload your files. Retrieval and inference are local.
  Today, connected AI clients and tools receive the data you pass to them; an enabled trace exporter receives
  paths and document excerpts included in traces.

### Product scope (what it is *for*)
- **Not built to be a general-purpose chatbot.** A free-chat mode is available, but the product is *for*
  answering questions *grounded in your files* with citations — that's where the tuning and engineering go;
  it isn't positioned or optimized as an open-domain assistant.
- **Not a model trainer or fine-tuning platform.** It *uses* models (BYO-LLM via llama.cpp); it doesn't train them.
- **Not a note-taking / PKM app.** It searches the files you already have in the formats you already use; it
  doesn't impose a vault, a note format, or a writing surface. (Indexing a notes folder is fine; *being* the
  notes app is out of scope.)
- **Not an open-web search engine or crawler.** It indexes *your* files — and, in a future release, *your*
  connected cloud/email accounts — not the public internet.
- **Not an enterprise DMS / records-management / e-discovery product.** It finds and answers; it isn't a
  system of record, and it makes no compliance certification.

### Maturity & platform
- **No correctness or compliance guarantees.** Answers are grounded in your documents and cited — always check
  the citation. Like any AI it can be wrong; the guarantee here is *privacy*, not infallibility, so don't act on
  an unverified answer in high-stakes work. No warranty (see [LICENSE](LICENSE)).
- **Windows and Linux only.** The installer is Windows-only; on Linux JustSearch runs from source. macOS is **not in
  the current scope** — not ruled out forever, but not planned now.

### Engineering scope
- **No per-language search levers.** Analysis is locale-invariant by design (one multilingual pipeline); we
  won't add per-language analyzers/stopwords/synonym dictionaries. Multilingual quality comes from the model
  stack, not from per-language tuning.

If you're unsure whether an idea fits, **open a discussion before a PR** — we'd rather shape it together than
decline finished work.
</content>
