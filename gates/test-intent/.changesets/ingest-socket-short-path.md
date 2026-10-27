---
schema: test-intent.v1
task: t-20261027-ingest-socket
author-role: builder
author-session: 01a17b47-08d2-7a22-82e9-470da2fdee8e
---

# Keep the ingest socket fixture below the Unix-domain pathname limit

The fixture relocation keeps the refusal oracle. The additional same-path regular-file control
uses the existing readable-file type guard and documented absolute-path acceptance. On the
baseline, with a long temp root, the socket bind raised java.net.SocketException ("Unix domain
path too long", a 174-byte pathname) before the product refusal was reached; the other 10
cases passed. The candidate runs all 11 cases with no skips.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/IngestPreparationBoundaryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Only socketInputIsRefusedAsAnUnsupportedFileType changes. Its socket moves from JUnit tempDir to Files.createTempDirectory(Path.of(\"/tmp\"), \"js-ing-\") on non-Windows hosts; Windows keeps tempDir. The directory has rwx------ POSIX permissions where supported. The existing isOther special-file witness is retained. The existing unsupported-channel-open assumption is retained; bind, path and permission failures propagate. The BAD_REQUEST and readable-regular-file-or-directory refusal oracle is retained. The stronger message equality and three negative type checks are classified separately below. The channel closes before replacing or finally deleting the node, and cleanup removes only that node and this test-created directory. No global properties, selection, tags, other tests or production code change.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including red-main and flaky-test repairs",
          "choice": "Relocate only the Unix-domain socket fixture into a unique short /tmp directory on non-Windows hosts, with owner-only POSIX permissions where supported. Preserve the unsupported-channel-open assumption, special-file witnesses, BAD_REQUEST refusal and its message; close and remove only fixture-owned resources.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/IngestPreparationBoundaryTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Require the full refusal message to equal \"Path is not a readable regular file or directory: \" + socketPath. This strengthens the former substring check and rejects extra diagnostic context.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including red-main and flaky-test repairs",
          "choice": "Pin the exact refusal message, including the refused path. IngestTool.attributes constructs badInput(\"Path is not a readable regular file or directory: \" + input) in modules/app-agent/src/main/java/io/justsearch/agent/tools/IngestTool.java:254; the refusal must name the refused path.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingest-socket-short-path.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/IngestPreparationBoundaryTest.java#socketInputIsRefusedAsAnUnsupportedFileType"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/IngestPreparationBoundaryTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Require the bound socket node to be neither a symbolic link, a regular file nor a directory, using three assertFalse checks alongside the retained isOther witness.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including red-main and flaky-test repairs",
          "choice": "Require the fixture to be the special file the guard refuses. The three negative type witnesses establish that the bound node is not a symbolic link, regular file or directory; IngestTool.attributes rejects that special-file type in modules/app-agent/src/main/java/io/justsearch/agent/tools/IngestTool.java:252-254.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingest-socket-short-path.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/IngestPreparationBoundaryTest.java#socketInputIsRefusedAsAnUnsupportedFileType"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/IngestPreparationBoundaryTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "After closing the Unix-domain socket and deleting its node, write a readable ordinary file at that exact pathname. Using prepare(tool(List.of()), ...) and RecordedRootPlan.fromReplayPayload, require successful preparation with exactly one single-file root whose path equals the normalized absolute former socket path. No watched roots are configured. This same-path control distinguishes type rejection from a location or name rejection; it pins already-supported regular-file and absolute-path behavior, not repaired production behavior.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including red-main and flaky-test repairs",
          "choice": "Add an ordinary-file control at the exact former socket pathname after channel closure and socket-node deletion. The expectation follows from IngestTool.attributes (modules/app-agent/src/main/java/io/justsearch/agent/tools/IngestTool.java:252-266), which accepts readable regular files, and its resolvePath absolute-path branch (:231-237), documented as \"absolute ⇒ normalize\" in docs/tempdocs/877-agent-tool-centralisation.md:302-306. With no watched roots, preparing the replacement file must yield one single-file root at that normalized absolute path. This checks that socket refusal is caused by file type rather than the relocated directory or filename.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingest-socket-short-path.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/IngestPreparationBoundaryTest.java#socketInputIsRefusedAsAnUnsupportedFileType"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a17be9-1009-72f3-900e-f3b2f4f7a558",
  "verdict": "accept",
  "digest": "sha256:19770b1d21b2afa8c49897da0833e7ca46df3e8e9be1115e7144b0ee72305356"
}
```
