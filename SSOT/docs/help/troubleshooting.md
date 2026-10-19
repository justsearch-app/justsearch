# Troubleshooting

## Search returns no results

- Verify that you have added at least one folder in the Library view
- Check that the folder contains supported file types (text, Markdown, PDF,
  DOCX, HTML, or source code)
- Wait for indexing to complete — large folders take longer to index

## AI models not working

- Open the **AI Brain** view to see whether the models are installed and
  running
- If "Install AI" shows an error, check your internet connection and retry;
  an interrupted download can be continued with "Resume Download"
- Chat, cited answers, and summarization need an NVIDIA GPU with CUDA and at
  least 7.5 GB of video memory. On other machines the chat model is skipped
  and the AI Brain view says why
- On Linux, chat also needs a CUDA-enabled llama.cpp server provided through
  the `JUSTSEARCH_SERVER_EXE` environment variable (see the README)
- Without an NVIDIA GPU, the search models run on the CPU and take longer to
  load

## Backend not starting

If the application shows a connection error:

- Close and reopen JustSearch
- Only one copy of JustSearch runs at a time: opening it again brings the
  existing window to the front, so close that window before reopening
- Look for a leftover JustSearch Engine process: `javaw.exe` in Task Manager
  on Windows, or `java` (for example with `ps aux | grep justsearch`) on Linux
- Check the log files `engine.log` and `llama-server.log` in the `logs`
  folder (see "Where JustSearch keeps its data" below)

## Slow search

- Without AI models, search uses keyword matching, which is fast but less
  accurate
- Without an NVIDIA GPU, the AI models run on the CPU, so loading them and
  ranking results are slower
- For best performance, use a machine with an NVIDIA GPU (CUDA support)

## Where JustSearch keeps its data

The desktop app keeps its search index, settings, AI models, and logs in one
folder:

- **Windows:** `%APPDATA%\io.justsearch.shell\`
- **Linux:** `~/.local/share/io.justsearch.shell/` (or
  `$XDG_DATA_HOME/io.justsearch.shell/` if `XDG_DATA_HOME` is set)

Inside it, `models` holds the AI models and `logs` holds the log files.

## High disk usage

- The AI models take about 10 GB on Windows and about 8 GB on Linux with the
  chat model, or about 2–4 GB without it
- The search index grows with the number of indexed documents
- **Settings → Data → "Delete all local data"** closes JustSearch and, on the
  next launch, deletes your index, settings, logs, and chat history. Your AI
  models (and the AI runtime that runs them) are kept. Type DELETE to confirm. If you
  have encrypted your chat history, memories, or agent history, they cannot be
  rebuilt, so export an encrypted backup first in Security & Privacy
- To remove the AI models as well, close JustSearch and delete the `models`
  folder inside the data folder above
