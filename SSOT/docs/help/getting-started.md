# Getting Started with JustSearch

JustSearch is a local-first knowledge engine that indexes your files and lets
you search across them using natural language — and, with the optional AI
models, ask questions and get answers backed by citations. Indexing, retrieval,
and built-in AI inference run on your machine. See AI Features for downloads,
update checks, optional trace export, and external integrations.

## Adding files to search

1. Open JustSearch
2. Open the **Library** view from the sidebar
3. Click "Add Folder" and select a folder containing your documents
4. JustSearch will index the files automatically

Supported formats include plain text, Markdown, PDF, Word documents (DOCX),
HTML, and source code files.

## Searching

Open the **Search** view from the sidebar and type your query in the search
bar. JustSearch supports:

- **Natural language queries**: "meeting notes from last week about budget"
- **Keyword search**: specific terms like filenames or code identifiers

Results are ranked by relevance. Without AI models installed, search uses
keyword matching (BM25). With AI models, search uses hybrid retrieval
combining keywords with semantic understanding.

## Installing AI models

For better search quality, install the AI models:

1. Open the **AI Brain** view from the sidebar
2. Click "Install AI"
3. Confirm the download. The dialog shows the exact size for your machine:
   about 10 GB on Windows and about 8 GB on Linux with a supported NVIDIA GPU,
   or about 2–4 GB without one (see AI Features for what each includes)

AI models enable semantic search, named entity recognition, and result
reranking. On a machine with a supported NVIDIA GPU they also include the chat
model, which adds query expansion, questions answered with citations, and
summarization (see AI Features). Connecting external AI tools over MCP works
with or without the AI models. All inference runs locally on your machine.

## Indexing restrictions

Manual Pause keeps indexed files searchable. Resume clears the manual pause; saved indexing hours
and the pause-on-battery preference still apply. Outside the saved hours, processing waits for the
schedule. On a known battery reading, it waits for AC power or a change to the battery preference.
An unknown power reading adds no battery hold. The battery preference is off by default and stays saved
even when no battery is detected.

Hours use local wall-clock minutes: the start is included and the end excluded. Overnight hours
belong to the selected starting day, including Sunday into Monday. A repeated daylight-saving hour
follows the same rule twice; a skipped hour never occurs. Windows follows current OS local time;
if that read fails, the JVM zone is used until a later native read succeeds. That fallback can lag
an OS zone change. Other systems use the JVM default zone: after an OS zone change, restart the Engine process (restarting indexing within
the same process does not guarantee a zone refresh). An explicit JVM time-zone setting takes
precedence over the OS zone.

PAUSING means admitted work is finishing its publication. PAUSED means all processing lanes are
quiet. Discovery, deletions and search remain available. With battery pause enabled, each indexing
activation waits up to three seconds for a fresh power observation, then proceeds as UNKNOWN if
it has none; a later battery observation still applies. Settings recovery remains authoritative:
check exclusions and complete the existing settings-reset flow before indexing resumes.
