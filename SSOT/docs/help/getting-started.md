# Getting Started with JustSearch

JustSearch is a local-first knowledge engine that indexes your files and lets
you search across them using natural language — and, with the optional AI
models, ask questions and get answers backed by citations. Everything stays on
your machine.

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
