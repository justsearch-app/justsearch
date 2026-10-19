# AI Features

JustSearch includes optional AI capabilities that run entirely on your
machine. Downloads, update checks, and optional trace export are described below.

## What gets installed

Open the **AI Brain** view and click "Install AI". What is downloaded depends
on your hardware, and the confirmation dialog shows the exact size:

- **With a supported NVIDIA GPU** (CUDA working and at least 7.5 GB of video
  memory): the search models plus the local chat model — about 10 GB on
  Windows (including the GPU runtime) and about 8 GB on Linux.
- **Without one**: the search models only (plus the GPU runtime on Windows if
  CUDA works) — about 2–4 GB. Semantic search, entity recognition, and
  reranking work; chat, questions with cited answers, summarization, and query
  expansion are not available, and the AI Brain view says why the chat model
  was skipped.

Install AI downloads from github.com and huggingface.co, including their download
redirects. On Windows with supported NVIDIA hardware it also downloads a CUDA
build of llama-server and selects it automatically, unless you configured your
own executable. Repair and upgrades may download files again.

## Network activity and privacy

Stable tagged desktop releases check GitHub for updates on launch using the
release URL embedded in the build. GitHub sees your IP address and request
time; the check sends no document content or queries. There is no off switch
yet. Builds without an embedded URL do not check; development builds can use
an explicitly configured URL.

Trace export is off by default. If you enable API or index tracing and also
set an OTLP endpoint, the Engine sends traces there. Traces can include file
paths and bounded document excerpts. An endpoint by itself does not
enable tracing. See the repository's environment-variable reference for the
tracing levels and OTEL_EXPORTER_OTLP endpoint variables.

External MCP clients receive the passages and paths you retrieve. Optional
MCP host tools are user-chosen programs and may use the network. Links opened
in your browser follow that browser's network behavior.

## Semantic search

After installing AI models, search understands the meaning of your query,
not just keywords. Searching for "how to deploy" finds documents about
"release process" or "production setup" even without those exact words.

## Named entity recognition (NER)

JustSearch automatically identifies people, organizations, and locations in
your documents. AI tools connected over MCP can use them to narrow results,
for example to documents that mention a given organization.

## Query expansion

While the chat model is running, your search query is expanded with other
forms of the same words — plurals, past tenses, and similar variants — so a
search for "deploy" also finds "deployed" and "deployment".

## Cross-encoder reranking

Search results are reranked using a cross-encoder model that compares each
result directly against your query for more accurate relevance scoring.

## SPLADE sparse retrieval

The AI install includes a SPLADE (Sparse Lexical and Expansion) model, which
adds learned term weights to your documents as they are indexed. The search
bar does not use them by default; they are available to searches that request
SPLADE explicitly, such as through the API or the built-in agent's search tool.

## Ask your documents

With the chat model installed, you can ask questions in natural language and
get answers drawn from your files — each backed by **citations** to the source
passages, so you can verify every claim. In the Search view, a query that reads
like a question (for example one containing "?" or starting with "how" or
"what") is asked rather than searched; press Ctrl+Enter to take the other
route for that query, or choose "Ask — answers with citations" below the
search bar.

## Summarize and extract

The same local model can **summarize** text you select or a set of results,
and the "Structured — fields as JSON" option below the search bar
**extracts** fields from your documents against a JSON schema — all running
on your machine.

## Built-in agent

For multi-step requests, choose "Delegate — the agent works multi-step" below
the search bar. The agent can search your index, browse indexed folders,
request ingestion of new files, perform file operations, and remember facts
and preferences for later conversations. Ingesting files and file operations
wait for your approval; saving something to the agent's memory does not ask
first.

## Use JustSearch from other AI tools (MCP)

JustSearch can act as a private retrieval backend for external AI agents over
the **Model Context Protocol (MCP)**. Tools like Claude Code, Cursor, and
Claude Desktop can connect to your running instance and search, retrieve
context, and ask questions over your files. This works with or without the AI
models installed.

JustSearch's MCP server listens only on your own machine; the passages and
file paths it returns go to the client you connected. A cloud-hosted
assistant, such as Claude Code or Claude Desktop, forwards them to its model
provider like any other tool result.

## Requirements

- Search models: about 2–4 GB of disk space; they run on the CPU, and faster
  with an NVIDIA GPU
- Chat, cited answers, summarization, and query expansion: an NVIDIA GPU with
  CUDA and at least 7.5 GB of video memory; about 10 GB of disk space on
  Windows, about 8 GB on Linux
- On Linux, chat also needs a CUDA-enabled llama.cpp server that you provide
  through the `JUSTSEARCH_SERVER_EXE` environment variable (see the README)
