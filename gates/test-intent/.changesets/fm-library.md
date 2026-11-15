---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: codex-builder-fm-s6-20261115
---

# Slice 6a test intent

This is the intermediate presentation slice. Save-on-change is deferred to 6b.
The independent verifier must inspect the per-file diffs and accept this changeset;
no acceptance records were written by this builder. New presentation and
count tests (the four frontend files have a New entry because the test-only import scanner reads every tracked source) exercise the supplied decisions. Fail-before evidence for the five new files is
gates/test-intent/evidence/fm-library.json (run-evidence --before origin/main 8a312d1484; each fails by assertion). The two review-repairs files (review and packaged-run repairs) have evidence gates/test-intent/evidence/fm-library-repairs.json (--before dd87dd0474, the candidate before those repairs).

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K2 expanded facts: \"Search by meaning\", \"Couldn't be read\", \"Read\", \"Checked for changes\", \"Open in Files\"; M3 preserves failure/last-known distinctions. M9 preserves keyboard access.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Failure facts move from the collapsed summary chip to the expanded Couldn't be read line. Open that folder first, then retain failure drill-down, per-folder scope, last-known treatment, focus and list behaviour assertions.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Failure facts move from the collapsed summary chip to the expanded Couldn't be read line. Open that folder first, then retain failure drill-down, per-folder scope, last-known treatment, focus and list behaviour assertions.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ContextMenu.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "owner-words",
          "quote": "The Library \"more\" menu is the same menu as everywhere else: same surface, rows, hover. It closes on outside click, Escape, or opening another menu.",
          "location": "task t-20261113-fmimpl agreement, fm-08 picture note quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K4/M10 extract the bar menu surface, rows, keyboard, dismissal and transient arbitration for Library and Browse context menus; M9 makes row actions keyboard reachable.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "The shared menu replaces ContextMenu private focus-index/document-key handling. Assert real focused rows, keyboard selection and preserved filter bounds through the shared menu; category grouping, unavailable actions and dismissal remain covered.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "The shared menu replaces ContextMenu private focus-index/document-key handling. Assert real focused rows, keyboard selection and preserved filter bounds through the shared menu; category grouping, unavailable actions and dismissal remain covered.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/FailedFilesList.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingControls.toolbar.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Packaged-run repair decisions V1 (no-AI headline 'Your files are searchable', row 'Up to date', meaning state once in the open row's 'Search by meaning' line, no 'semantic search') and V2 ('Rebuilding' only when a rebuild is observed; raw codes in plain words).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "R3: a failed folder-list (roots) read is uncertainty whether or not an earlier observation exists; it is never 'no folders' and never leaves rows 'Up to date'.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        },
        {
          "oldAssertion": "Row text contains 'semantic search waiting for AI install' and 'keyword search ready' (paused and running).",
          "newAssertion": "Row status is 'Up to date'; open row says 'Search by meaning Needs AI · word search is available' (word search still stated available, M3) while running and paused; no 'semantic search'; pending/indexed icon assertions unchanged.",
          "reason": "Decisions V1/V2 (cited) after the packaged-app check; the remaining behavioural assertions are kept."
        },
        {
          "oldAssertion": "Test host answered the folder-list read with a failure (503/rejected fetch) while rows were placed directly.",
          "newAssertion": "The folder-list read is left unanswered (or answered with the rows under test); every assertion is unchanged.",
          "reason": "R3 (cited) makes a failed folder-list read visible uncertainty, so a fixture failing that read no longer represents the placed rows' scenario; the oracle is kept."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IngestionSummary.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K5 embedded activity summary behind developer tools; \"Activity\" is a text action revealing the activity summary.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "The activity summary is initially absent. Activate Activity to mount it, then preserve transport, event-count, cancellation and tab/reopen assertions; historical outcome labels use reading terms.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "The activity summary is initially absent. Activate Activity to mount it, then preserve transport, event-count, cancellation and tab/reopen assertions; historical outcome labels use reading terms.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingStatusFollowup.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "M7 headline truthful when only other sources exist. K2 keeps unknown/last-known truth.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Keep transient, stale and recovery headline assertions; a current positive own-file observation with no watched folders now remains searchable rather than asking the user to start searching.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Keep transient, stale and recovery headline assertions; a current positive own-file observation with no watched folders now remains searchable rather than asking the user to start searching.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingStatusSurfaces.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "R3: a failed folder-list (roots) read is uncertainty whether or not an earlier observation exists; it is never 'no folders' and never leaves rows 'Up to date'.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        },
        {
          "oldAssertion": "Test host answered the folder-list read with a failure (503/rejected fetch) while rows were placed directly.",
          "newAssertion": "The folder-list read is left unanswered (or answered with the rows under test); every assertion is unchanged.",
          "reason": "R3 (cited) makes a failed folder-list read visible uncertainty, so a fixture failing that read no longer represents the placed rows' scenario; the oracle is kept."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.firstMinutesRepairs.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "owner-words",
          "quote": "The Library \"more\" menu is the same menu as everywhere else: same surface, rows, hover. It closes on outside click, Escape, or opening another menu.",
          "location": "task t-20261113-fmimpl agreement, fm-08 picture note quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K4/M10 extract the bar menu surface, rows, keyboard, dismissal and transient arbitration for Library and Browse context menus; M9 makes row actions keyboard reachable.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "The shared menu replaces ContextMenu private focus-index/document-key handling. Assert real focused rows, keyboard selection and preserved filter bounds through the shared menu; category grouping, unavailable actions and dismissal remain covered.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "The shared menu replaces ContextMenu private focus-index/document-key handling. Assert real focused rows, keyboard selection and preserved filter bounds through the shared menu; category grouping, unavailable actions and dismissal remain covered.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/hooks/resolvePathLazy.engineAddress.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/renderers/controls/FolderCardRenderer.failedChip.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K2 expanded facts: \"Search by meaning\", \"Couldn't be read\", \"Read\", \"Checked for changes\", \"Open in Files\"; M3 preserves failure/last-known distinctions. M9 preserves keyboard access.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Failure facts move from the collapsed summary chip to the expanded Couldn't be read line. Open that folder first, then retain failure drill-down, per-folder scope, last-known treatment, focus and list behaviour assertions.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Failure facts move from the collapsed summary chip to the expanded Couldn't be read line. Open that folder first, then retain failure drill-down, per-folder scope, last-known treatment, focus and list behaviour assertions.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/aiVerdict.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/availability.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/folderStatus.failedChip.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K2 expanded facts: \"Search by meaning\", \"Couldn't be read\", \"Read\", \"Checked for changes\", \"Open in Files\"; M3 preserves failure/last-known distinctions. M9 preserves keyboard access.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Failure facts move from the collapsed summary chip to the expanded Couldn't be read line. Open that folder first, then retain failure drill-down, per-folder scope, last-known treatment, focus and list behaviour assertions.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Failure facts move from the collapsed summary chip to the expanded Couldn't be read line. Open that folder first, then retain failure drill-down, per-folder scope, last-known treatment, focus and list behaviour assertions.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/folderStatus.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingFreshness.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingStatus.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingWaiting.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsBridge.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsQueueRail.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.snapshotLiveness.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BulkOperationImmediateRefusal.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "owner-words",
          "quote": "The Library \"more\" menu is the same menu as everywhere else: same surface, rows, hover. It closes on outside click, Escape, or opening another menu.",
          "location": "task t-20261113-fmimpl agreement, fm-08 picture note quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K4/M2 \"Read everything again\" runs core.reindex with force=true; \"Read waiting files now\" has no count and must not imply completion of all waiting work. Operation consent/outcome tracking unchanged.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Use the real shared Library menu instead of an always-mounted jf-operation. The re-read menu sends force=true; keep accepted-versus-completed receipts, failed outcomes, picture-off refusal and disabled empty-scope assertions.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Use the real shared Library menu instead of an always-mounted jf-operation. The re-read menu sends force=true; keep accepted-versus-completed receipts, failed outcomes, picture-off refusal and disabled empty-scope assertions.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.addCollection.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K2 Collection behind developer tools. M12 Hidden settings survive save, restart, profile change and another window's change.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Collection naming now requires developer tools; seed that visibility before checking the same form and collection argument. Add a folder labels change; reserved-name validation and omission of blank fields remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Collection naming now requires developer tools; seed that visibility before checking the same form and collection argument. Add a folder labels change; reserved-name validation and omission of blank fields remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.bandFocus-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "M7 discoverable route in default view: one line \"N documents from other apps\" reveals the section. M1 unfinished/unknown/failed removal outcomes stay visible.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Open the other-apps disclosure by its new documents-from-other-apps label; Add a folder uses its shared name. Preserve source-order, exact-target confirmation, removal and focus-recovery assertions.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Open the other-apps disclosure by its new documents-from-other-apps label; Add a folder uses its shared name. Preserve source-order, exact-target confirmation, removal and focus-recovery assertions.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.bulkOutcome.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.failedChip.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K2 expanded facts: \"Search by meaning\", \"Couldn't be read\", \"Read\", \"Checked for changes\", \"Open in Files\"; M3 preserves failure/last-known distinctions. M9 preserves keyboard access.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Packaged-run repair decisions V1 (no-AI headline 'Your files are searchable', row 'Up to date', meaning state once in the open row's 'Search by meaning' line, no 'semantic search') and V2 ('Rebuilding' only when a rebuild is observed; raw codes in plain words).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "R3: a failed folder-list (roots) read is uncertainty whether or not an earlier observation exists; it is never 'no folders' and never leaves rows 'Up to date'.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Failure facts move from the collapsed summary chip to the expanded Couldn't be read line. Open that folder first, then retain failure drill-down, per-folder scope, last-known treatment, focus and list behaviour assertions.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Failure facts move from the collapsed summary chip to the expanded Couldn't be read line. Open that folder first, then retain failure drill-down, per-folder scope, last-known treatment, focus and list behaviour assertions.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        },
        {
          "oldAssertion": "Fixture set provisional only.",
          "newAssertion": "Fixture sets provisional and the observed rebuild flag together, so the same 'Rebuilding' assertions apply to an observed rebuild; assertions unchanged.",
          "reason": "Decisions V1/V2 (cited) after the packaged-app check; the remaining behavioural assertions are kept."
        },
        {
          "oldAssertion": "Test host answered the folder-list read with a failure (503/rejected fetch) while rows were placed directly.",
          "newAssertion": "The folder-list read is left unanswered (or answered with the rows under test); every assertion is unchanged.",
          "reason": "R3 (cited) makes a failed folder-list read visible uncertainty, so a fixture failing that read no longer represents the placed rows' scenario; the oracle is kept."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.failedFiles.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K2 expanded facts: \"Search by meaning\", \"Couldn't be read\", \"Read\", \"Checked for changes\", \"Open in Files\"; M3 preserves failure/last-known distinctions. M9 preserves keyboard access.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Failure facts move from the collapsed summary chip to the expanded Couldn't be read line. Open that folder first, then retain failure drill-down, per-folder scope, last-known treatment, focus and list behaviour assertions.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Failure facts move from the collapsed summary chip to the expanded Couldn't be read line. Open that folder first, then retain failure drill-down, per-folder scope, last-known treatment, focus and list behaviour assertions.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "M7 discoverable route in default view: one line \"N documents from other apps\" reveals the section. M1 unfinished/unknown/failed removal outcomes stay visible.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Open the other-apps disclosure by its new documents-from-other-apps label; Add a folder uses its shared name. Preserve source-order, exact-target confirmation, removal and focus-recovery assertions.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Open the other-apps disclosure by its new documents-from-other-apps label; Add a folder uses its shared name. Preserve source-order, exact-target confirmation, removal and focus-recovery assertions.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "owner-words",
          "quote": "The Library \"more\" menu is the same menu as everywhere else: same surface, rows, hover. It closes on outside click, Escape, or opening another menu.",
          "location": "task t-20261113-fmimpl agreement, fm-08 picture note quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K4/M2 \"Read everything again\" runs core.reindex with force=true; \"Read waiting files now\" has no count and must not imply completion of all waiting work. Operation consent/outcome tracking unchanged.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Use the real shared Library menu instead of an always-mounted jf-operation. The re-read menu sends force=true; keep accepted-versus-completed receipts, failed outcomes, picture-off refusal and disabled empty-scope assertions.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Use the real shared Library menu instead of an always-mounted jf-operation. The re-read menu sends force=true; keep accepted-versus-completed receipts, failed outcomes, picture-off refusal and disabled empty-scope assertions.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.gapDecision.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ingestion.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K5 embedded activity summary behind developer tools; \"Activity\" is a text action revealing the activity summary.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "The activity summary is initially absent. Activate Activity to mount it, then preserve transport, event-count, cancellation and tab/reopen assertions; historical outcome labels use reading terms.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "The activity summary is initially absent. Activate Activity to mount it, then preserve transport, event-count, cancellation and tab/reopen assertions; historical outcome labels use reading terms.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownCount.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K2 headline = state in words, no count subtitle; M5 counts keep scope and cap; M7 headline truthful when only other sources exist.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "R3: a failed folder-list (roots) read is uncertainty whether or not an earlier observation exists; it is never 'no folders' and never leaves rows 'Up to date'.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The repeated searchable-document subtitle is retired. Assert its absence, keep the one disk-file count per root, and retain own-versus-Help readiness and unknown/late-count headline cases.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "The repeated searchable-document subtitle is retired. Assert its absence, keep the one disk-file count per root, and retain own-versus-Help readiness and unknown/late-count headline cases.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        },
        {
          "oldAssertion": "Test host answered the folder-list read with a failure (503/rejected fetch) while rows were placed directly.",
          "newAssertion": "The folder-list read is left unanswered (or answered with the rows under test); every assertion is unchanged.",
          "reason": "R3 (cited) makes a failed folder-list read visible uncertainty, so a fixture failing that read no longer represents the placed rows' scenario; the oracle is kept."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K2 default Library facts and no count subtitle; M3 meaning distinctions and word-search availability remain; M4 active reading never shows a bare past Read time; M5 count scope/cap; M7 other-app-only headline is truthful.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "R3: a failed folder-list (roots) read is uncertainty whether or not an earlier observation exists; it is never 'no folders' and never leaves rows 'Up to date'.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Default rows expose the agreed meaning/failure/read facts rather than Collection and redundant word-search facts. Remove subtitle assertions or move exceptional wait/retry detail assertions to the attention detail. Retain stale-state, disclosure, path, recovery and themed/fallback parity checks.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Default rows expose the agreed meaning/failure/read facts rather than Collection and redundant word-search facts. Remove subtitle assertions or move exceptional wait/retry detail assertions to the attention detail. Retain stale-state, disclosure, path, recovery and themed/fallback parity checks.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        },
        {
          "oldAssertion": "Test host answered the folder-list read with a failure (503/rejected fetch) while rows were placed directly.",
          "newAssertion": "The folder-list read is left unanswered (or answered with the rows under test); every assertion is unchanged.",
          "reason": "R3 (cited) makes a failed folder-list read visible uncertainty, so a fixture failing that read no longer represents the placed rows' scenario; the oracle is kept."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.render.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K2 default Library facts and no count subtitle; M3 meaning distinctions and word-search availability remain; M4 active reading never shows a bare past Read time; M5 count scope/cap; M7 other-app-only headline is truthful.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        },
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Packaged-run repair decisions V1 (no-AI headline 'Your files are searchable', row 'Up to date', meaning state once in the open row's 'Search by meaning' line, no 'semantic search') and V2 ('Rebuilding' only when a rebuild is observed; raw codes in plain words).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "R3: a failed folder-list (roots) read is uncertainty whether or not an earlier observation exists; it is never 'no folders' and never leaves rows 'Up to date'.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "P1 (narrowing after the second real-app failure): the folder's 'Read <time>' fact is removed because the root time follows scans, not writes; 'Reading now' shows only while reading; 'Checked for changes' stays.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "Anything a picture shows that the app has no data for: build the screen without it and name it in the delivery. Never fill it with a made-up value.",
          "location": "task t-20261113-fmimpl agreement, adopted design handover 'Not decided: do not guess' (picture author's rule)",
          "label": "owner-adopted design note"
        }
      ],
      "behaviour": "Default rows expose the agreed meaning/failure/read facts rather than Collection and redundant word-search facts. Remove subtitle assertions or move exceptional wait/retry detail assertions to the attention detail. Retain stale-state, disclosure, path, recovery and themed/fallback parity checks.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Default rows expose the agreed meaning/failure/read facts rather than Collection and redundant word-search facts. Remove subtitle assertions or move exceptional wait/retry detail assertions to the attention detail. Retain stale-state, disclosure, path, recovery and themed/fallback parity checks.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        },
        {
          "oldAssertion": "Text contains 'semantic search waiting for AI install'.",
          "newAssertion": "Open row's details match 'Search by meaning Needs AI'; no 'semantic search'; 'fully searchable' still absent; enrichmentBlocked still true.",
          "reason": "Decisions V1/V2 (cited) after the packaged-app check; the remaining behavioural assertions are kept."
        },
        {
          "oldAssertion": "Test host answered the folder-list read with a failure (503/rejected fetch) while rows were placed directly.",
          "newAssertion": "The folder-list read is left unanswered (or answered with the rows under test); every assertion is unchanged.",
          "reason": "R3 (cited) makes a failed folder-list read visible uncertainty, so a fixture failing that read no longer represents the placed rows' scenario; the oracle is kept."
        },
        {
          "oldAssertion": "Open row of a settled folder matches 'Read just now' (two tests).",
          "newAssertion": "Open row contains 'Search by meaning' and matches neither 'Reading now' nor 'Read just now'.",
          "reason": "P1 (cited) removes the read time; the settled folder still claims no reading in progress."
        },
        {
          "oldAssertion": "Empty library mounted with a failing folder-list read.",
          "newAssertion": "Empty library mounted with an Engine answer of an empty folder list; same empty-library assertions.",
          "reason": "R3 (cited): a failed read is not 'no folders'."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K1/M11 6a is intermediate: rules/hours retain Save/Reload/Preview/Apply with all explanations and failure feedback. M6 weekday editor is behind developer tools with effective days visible; M12 hidden settings survive writes and other windows.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Update visible reading labels and Library hours destinations while retaining manual Save, witnessed writes, conflicts, drafts, hidden days, battery preferences, serialization and uncertain-outcome checks.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Update visible reading labels and Library hours destinations while retaining manual Save, witnessed writes, conflicts, drafts, hidden days, battery preferences, serialization and uncertain-outcome checks.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.transitions.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "M7 headline truthful when only other sources exist. K2 keeps unknown/last-known truth.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "R3: a failed folder-list (roots) read is uncertainty whether or not an earlier observation exists; it is never 'no folders' and never leaves rows 'Up to date'.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Keep transient, stale and recovery headline assertions; a current positive own-file observation with no watched folders now remains searchable rather than asking the user to start searching.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Keep transient, stale and recovery headline assertions; a current positive own-file observation with no watched folders now remains searchable rather than asking the user to start searching.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        },
        {
          "oldAssertion": "Test host answered the folder-list read with a failure (503/rejected fetch) while rows were placed directly.",
          "newAssertion": "The folder-list read is left unanswered (or answered with the rows under test); every assertion is unchanged.",
          "reason": "R3 (cited) makes a failed folder-list read visible uncertainty, so a fixture failing that read no longer represents the placed rows' scenario; the oracle is kept."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K1/M11 6a is intermediate: rules/hours retain Save/Reload/Preview/Apply with all explanations and failure feedback. M6 weekday editor is behind developer tools with effective days visible; M12 hidden settings survive writes and other windows.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Update visible reading labels and Library hours destinations while retaining manual Save, witnessed writes, conflicts, drafts, hidden days, battery preferences, serialization and uncertain-outcome checks.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Update visible reading labels and Library hours destinations while retaining manual Save, witnessed writes, conflicts, drafts, hidden days, battery preferences, serialization and uncertain-outcome checks.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "M7 discoverable route in default view: one line \"N documents from other apps\" reveals the section. M1 unfinished/unknown/failed removal outcomes stay visible.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Open the other-apps disclosure by its new documents-from-other-apps label; Add a folder uses its shared name. Preserve source-order, exact-target confirmation, removal and focus-recovery assertions.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Open the other-apps disclosure by its new documents-from-other-apps label; Add a folder uses its shared name. Preserve source-order, exact-target confirmation, removal and focus-recovery assertions.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SystemSelfView.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/searchResultViewModel.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "M10 distinct icons for PDF, document, Markdown/text, spreadsheet/CSV, mail, image, code, other from the existing icon set, adding to that set only where needed.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Markdown/text now has its distinct glyph in the shared file-kind projection; keep kind derivation and content/excerpt assertions.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Markdown/text now has its distinct glyph in the shared file-kind projection; keep kind derivation and content/excerpt assertions.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/settingsRegister.searchRows.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        }
      ],
      "behaviour": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Reading labels replace indexing labels; assertions about observation freshness, counts, holds, errors, destinations and state transitions remain.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/e2e/csp-chromium.mjs"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K3 changes displayed index/indexing words to read/reading; K5/M11 move expert facts behind developer tools while preserving explicit editors and explanations; K6/M9 use accessible Browse tree rows. The typography audit must still inspect the moved facts through the retained routes.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Exercise expanded Browse via role=treeitem and the new CSS indentation classes while retaining the no-inline-style desktop CSP assertions.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Exercise expanded Browse via role=treeitem and the new CSS indentation classes while retaining the no-inline-style desktop CSP assertions.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/e2e/type-ladder-chromium.mjs"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K3 changes displayed index/indexing words to read/reading; K5/M11 move expert facts behind developer tools while preserving explicit editors and explanations; K6/M9 use accessible Browse tree rows. The typography audit must still inspect the moved facts through the retained routes.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Keep typography audit coverage for the Activity and developer Library facts after they move behind their explicit routes. Capture and expand both routes in both themes, require their successful nonempty inventories, and update the exact inventory from 158 to 164 surfaces.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Keep typography audit coverage for the Activity and developer Library facts after they move behind their explicit routes. Capture and expand both routes in both themes, require their successful nonempty inventories, and update the exact inventory from 158 to 164 surfaces.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/boot/type-ladder-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K3 changes displayed index/indexing words to read/reading; K5/M11 move expert facts behind developer tools while preserving explicit editors and explanations; K6/M9 use accessible Browse tree rows. The typography audit must still inspect the moved facts through the retained routes.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Keep typography audit coverage for the Activity and developer Library facts after they move behind their explicit routes. Capture and expand both routes in both themes, require their successful nonempty inventories, and update the exact inventory from 158 to 164 surfaces.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Keep typography audit coverage for the Activity and developer Library facts after they move behind their explicit routes. Capture and expand both routes in both themes, require their successful nonempty inventories, and update the exact inventory from 158 to 164 surfaces.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.notice-layout-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K3 changes displayed index/indexing words to read/reading; K5/M11 move expert facts behind developer tools while preserving explicit editors and explanations; K6/M9 use accessible Browse tree rows. The typography audit must still inspect the moved facts through the retained routes.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Replace the superseded indexing and Add folder display words with reading and Add a folder. Retain the existing behavior and error, focus, outcome and layout checks.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Replace the superseded indexing and Add folder display words with reading and Add a folder. Retain the existing behavior and error, focus, outcome and layout checks.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SurfaceReach-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K3 changes displayed index/indexing words to read/reading; K5/M11 move expert facts behind developer tools while preserving explicit editors and explanations; K6/M9 use accessible Browse tree rows. The typography audit must still inspect the moved facts through the retained routes.",
          "label": "agent choice; K/M decisions supplied by the orchestrator"
        }
      ],
      "behaviour": "Browse uses column heads as its fixed header and a roving tree tab stop. Enter that tree stop before End; retain scroll reach, header position, geometry and focus assertions.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "Browse uses column heads as its fixed header and a roving tree tab stop. Enter that tree stop before End; retain scroll reach, header position, geometry and focus assertions.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/commands/CommandPaletteProjection.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K3 explicitly moves command-palette entries from index/indexing to read/reading in the same change.",
          "label": "agent choice; K3 supplied by the orchestrator"
        }
      ],
      "behaviour": "The Library navigation action is named Change reading rules and found by reading rules. Its stable command/operation IDs, argument-free invocation guard and Library focus route are preserved.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "The Library navigation action is named Change reading rules and found by reading rules. Its stable command/operation IDs, argument-free invocation guard and Library focus route are preserved.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/rebuildLabel.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules quoted in supplied slice 6a builder brief",
          "label": "owner words quoted in the supplied brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, implementation delegation quoted in supplied slice 6a builder brief",
          "delegationScope": "implement the agreed first-minutes design; slice 6a Library and file-browser presentation only",
          "choice": "K3 requires rebuild words to be rewritten plainly with their consequences kept. Confirmation tokens and operation IDs are unchanged.",
          "label": "agent choice; K3 supplied by the orchestrator"
        }
      ],
      "behaviour": "The served rebuild label and visible button say Rebuild all search data. The typed confirmation remains rebuild and the same catalog operation is invoked.",
      "conflictSearch": "Decision records searched for Library, file browser, Read everything, Add a folder and Search by meaning. ADR-0028 concerns scoped path lookup and ADR-0005 bindings, neither supersedes these presentation decisions. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Prior presentation labels, collapsed facts or private component interaction path.",
          "newAssertion": "The served rebuild label and visible button say Rebuild all search data. The typed confirmation remains rebuild and the same catalog operation is invoked.",
          "reason": "The cited owner wording and supplied K/M decisions replace that presentation while preserving the remaining behavioural assertions."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/browse-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/views/library-first-minutes/operation-wording.test.ts",
        "modules/ui-web/src/shell-v0/views/library-first-minutes/palette-wording.test.ts",
        "modules/ui-web/src/shell-v0/views/library-first-minutes/presentation.test.ts"
      ],
      "class": "New",
      "behaviour": "Library shows one state headline, one scoped folder count (capped as 50,000+, unknown as none), expanded facts that keep uncertainty, reading-in-progress and deletion recovery; expert sections are opt-in while unfinished/unknown removals, interrupted imports, missing folders and other sources stay reachable; hidden weekday restrictions stay effective and visible; the Library menu is the shared menu (dismissal and arbitration with the bar menu) and says what each operation runs; Browse has column heads, a stable root count, byte/date presentation, no inline styles, distinct kind icons shared with search results, failed/truncated/filtered-empty/confirmed-empty states, and a keyboard tree with one tab stop, Right/Left, focus recovery and actions that never open the row; operation and command-palette labels use reading words.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "The Library \"more\" menu is the same menu as everywhere else: same surface, rows, hover. It closes on outside click, Escape, or opening another menu.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "File browser. An icon per kind of file, files indented under their folder, column heads, sizes in sensible units. The row under the pointer shows its actions without changing height.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "In the app, never set style= on an element: the desktop window drops it.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Slice 6a decisions K1-K7 as amended by M1-M12 (Library presentation, shared menu, Browse; save-on-change deferred), as quoted in the acceptor brief.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "R3: a failed folder-list (roots) read is uncertainty whether or not an earlier observation exists; it is never 'no folders' and never leaves rows 'Up to date'.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "R5: the Browse filter is a developer tool; it is cleared when developer tools turn off and never applies while they are off.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "P1 (narrowing after the second real-app failure): the folder's 'Read <time>' fact is removed because the root time follows scans, not writes; 'Reading now' shows only while reading; 'Checked for changes' stays.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "Anything a picture shows that the app has no data for: build the screen without it and name it in the delivery. Never fill it with a made-up value.",
          "location": "task t-20261113-fmimpl agreement, adopted design handover 'Not decided: do not guess' (picture author's rule)",
          "label": "owner-adopted design note"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-library.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/browse-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/views/library-first-minutes/operation-wording.test.ts",
        "modules/ui-web/src/shell-v0/views/library-first-minutes/palette-wording.test.ts",
        "modules/ui-web/src/shell-v0/views/library-first-minutes/presentation.test.ts"
      ],
      "repaired": true,
      "assertionChanges": [
        {
          "oldAssertion": "Test host answered the folder-list read with a failure (503/rejected fetch) while rows were placed directly.",
          "newAssertion": "The folder-list read is left unanswered (or answered with the rows under test); every assertion is unchanged.",
          "reason": "R3 (cited) makes a failed folder-list read visible uncertainty, so a fixture failing that read no longer represents the placed rows' scenario; the oracle is kept."
        },
        {
          "oldAssertion": "M8 filter test filters with developer tools off.",
          "newAssertion": "Turns developer tools on first, then the same filter assertions.",
          "reason": "R5 (cited)."
        },
        {
          "oldAssertion": "M4: paused meaning work keeps a 'Read' line whose value is not 'Reading now'.",
          "newAssertion": "M4: paused meaning work shows 'Search by meaning', never 'Reading now', and no 'Read' term.",
          "reason": "P1 (cited); the never-'Reading now' half of M4 is kept."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/review-repairs.test.ts",
        "modules/ui-web/src/shell-v0/views/library-first-minutes/review-repairs-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Review and packaged-run repairs: a failed roots read shows as uncertainty, not 'Up to date'; one count formatter keeps cap and unknown (50,000+ with explicit digit grouping, no number when unknown, at-least when part of a tree was unreadable) in Library, Browse, removal text and status-panel rows, and Library and Browse agree; a path rendered twice keeps one tab stop and navigation follows the focused occurrence; Browse reloads roots when shown and children on expand; completed folder coverage is not reported blocked by another folder's work; no-AI headline and row words per V1, plain words for raw codes and Rebuilding only when observed per V2; Browse rows sorted by name with no blank band and singular '1 file'; file counts refreshed after reading everything again (the read-time line itself was withdrawn by P1); remaining index wording replaced; Shift+Tab out of a menu closes it.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "The Library \"more\" menu is the same menu as everywhere else: same surface, rows, hover. It closes on outside click, Escape, or opening another menu.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "File browser. An icon per kind of file, files indented under their folder, column heads, sizes in sensible units. The row under the pointer shows its actions without changing height.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "In the app, never set style= on an element: the desktop window drops it.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Slice 6a decisions K1-K7 as amended by M1-M12 (Library presentation, shared menu, Browse; save-on-change deferred), as quoted in the acceptor brief.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Review repair decisions N1-N6 (N1 roots-observation availability joins visible uncertainty; N2 one cap-preserving count formatter, absent cap with count equal to the 50,000 limit is capped; N3 occurrence identity in Browse; N4 Refresh and filter stay behind developer tools, Browse refreshes roots on show and children on expand; N5 a root's completed coverage wins over a global block; N6 remaining index wording) and packaged-run decisions V1-V8 (V1 and V2 as above; V3 unreadable subfolder never shown as an exact count; V4 Browse sorted by name, no blank band, '1 file'; V5 explicit digit grouping and one shared root count for Library and Browse; V6 read times refreshed when Read everything again completes; V7 remaining index words; V8 moving focus out of a menu closes it).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-library-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/review-repairs.test.ts",
        "modules/ui-web/src/shell-v0/views/library-first-minutes/review-repairs-lockdown.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerCountPresentationTest.java"
      ],
      "class": "New",
      "behaviour": "The roots response carries each folder's bounded file count with its cap flag: a count that reaches the 50,000 limit is marked capped; a folder whose disk observation is missing is unknown (-1), not an empty folder; a folder with an unreadable subfolder reports the readable count as capped (at least N), never as exact.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "M5 (counts keep scope and cap: capped -> '50,000+ files'; unknown -> no count; carry the cap flag through the roots response as a small additive field) and V3 (an unreadable subfolder never shown as an exact count).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-library.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerCountPresentationTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/ReindexHandlerTest.java"
      ],
      "class": "New",
      "behaviour": "The consent preview for reading everything again uses reading words and keeps the exact targets: forced 'Read N watched locations again, including unchanged files:' plus each path; not forced 'Read N watched locations again:' plus each path.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "R6: the Library authorization dialog's 'Reindex N watched locations.' becomes 'Read N watched locations again:' (forced: 'Read N watched locations again, including unchanged files:') followed by the same exact target paths, one per line (M2: Read everything again runs a forced reread).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-library.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/ReindexHandlerTest.java"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-5yh5fsbk",
  "verdict": "reject",
  "digest": "sha256:328f16bba2ace72f1b027094b78a87b6b1f9ebfa8b04f7e0d927623c15021037",
  "note": "The four exempt modules/ui-web/src/shell-v0/views/library-first-minutes/*.test.ts files are not isolated: scripts/governance/gates/test-intent/units.test.mjs:775 calls scanTestOnlyImports, whose recursive tracked-source scan reads all four. The supplied no-scanner/no-other-test exemption condition is unmet. Add explicit test-intent entries with sources and execution evidence for these files."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-j25sm91k",
  "verdict": "reject",
  "digest": "sha256:c8e9c4317ac2786418c056a7edc943f95a6bb1d0750ceea647a28b3339ed030a",
  "note": "Entry 56: browse-lockdown.test.ts:68 asserts exactly 34px row height without a source; remove the exact-height assertion and keep the before/hover equality. Entries 1-55 acceptable."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-mp-ch9vx",
  "verdict": "accept",
  "digest": "sha256:fe65baf4db69786e219197d87c4b6146588c4b117475aeba4468d23a7d4a69f0"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-01a1dfcc-80d6-7d50-9372-e48e2b538148",
  "verdict": "reject",
  "digest": "sha256:0ec15a635ba43f3e9c6bdfd564ad9f5e45605abd01b3f6d5430677557b81f9cf",
  "note": "Entry 5 drops the no-AI-through-Pause assertion that word search remains available; M3 still requires that fact and V1 does not retire it. The exempt IndexingControllerCountPresentationTest.java is read by scripts/ci/classify-changes.test.mjs:113,156, so it is not isolated under the supplied no-scanner condition and needs an explicit entry. Other entries reviewed without an intent objection; independent runtime reruns were blocked by sandbox dependency/process/network restrictions."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1dfd4-3692-7002-a823-d1ea476c6159",
  "verdict": "accept",
  "digest": "sha256:fa417d1831eecd19128059fbf9da23ced3fa29131be85e403dcded6844b97e8d"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1e018-64fa-7191-ae76-aaf837cac78a",
  "verdict": "reject",
  "digest": "sha256:af07fd0f67e3d0007947200f05af0583085f20cc97d144151c52d32a559a2f0b",
  "note": "Entry 57: review-repairs-lockdown.test.ts:289-290 requires the column-head and ::before band background colors to equal the body background. P2 requires menus above the heads and no scrolled rows above them; it does not require exact color equality or a pseudo-element. Keep behavior assertions or cite an agreed source for these extra expectations. Other entries have no intent objection; local runtime reruns are unverified due sandbox restrictions."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-go4808z5",
  "verdict": "accept",
  "digest": "sha256:dea84a5c306129df3a976b5d67e4efb2157677703892559744703eeecd715fb2"
}
```
