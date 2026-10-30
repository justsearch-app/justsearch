---
title: Export a conversation
type: how-to
status: stable
description: "Copy or download a conversation as Markdown or versioned JSON, with answer sources and outcome labels."
---

# Export a conversation

Open the conversation in Search. Choose **Export conversation** beside its other actions in the
sidebar. The button's accessible name includes the conversation title. Choose **Copy Markdown**,
**Save Markdown**, **Copy JSON**, or **Save JSON**. Tab reaches each action; Escape closes the dialog
and cancels a pending read.

Export loads the complete conversation record and its exclusions before enabling those actions.
A failed or unreadable record leaves them unavailable with a reason. Changing the conversation
while that read is pending does not change the export's target. Reopen export to take a new snapshot.

Settled Ask, Delegate and model turns are included in order. Turns still streaming are left out.
Stopped, failed, refused and no-evidence outcomes keep their status and any received partial text.
Excluded messages are included and marked **excluded from context** in both formats. Exclusion
refers to the explicit message exclusion setting, independently of any context floor or compaction.
A branch names its parent and branch point; inherited turns returned by the record are included.

Markdown keeps the title and message text, followed by each answer's citation sentences with their
**[n]** marks and a matching source list with paths and line locations. Citation sentences are listed
separately so formatting and repeated text cannot move a mark onto the wrong sentence.

**Copied** means the clipboard write succeeded. **Download started** means the file was handed to
the browser's download mechanism; it does not establish that a file was saved or that a later save
dialog was accepted. Check your downloads. A download or copy failure says so. The packaged Windows
app's download behavior still requires an owner check; this feature uses the existing backup-download
mechanism. There is no native save-dialog receipt.

Export is unavailable while conversation history is locked; unlock it in **Security & Privacy**.
Encryption does not have to be configured to export. Copies and exports contain readable conversation
content and local source paths; existing files are not removed when the conversation is deleted.

## JSON format, version 1

This is a public export format, separate from the application's stored records. Text is preserved
as received, including Markdown. UTF-8 JSON has these fields:

| Field | Meaning |
| --- | --- |
| `formatVersion` | Integer `1`. Consumers should check it before reading other fields. |
| `conversationId` | The conversation selected when export began. |
| `title` | Its title, or `Untitled conversation`. |
| `branch` | `null` for a root; otherwise `parentConversationId` and `branchPointMessageId` (which may be `null` when unknown). |
| `turns` | Settled exchanges, oldest first. Streaming exchanges are omitted. |
| `turns[].kind` | `ask`, `agent` (Delegate), or `model`. |
| `turns[].status` | `complete`, `halted` (stopped), `failed`, `refused`, or `no-evidence`. |
| `turns[].detail` | Outcome explanation when reported, otherwise an empty string. |
| `turns[].disposition` | Reported termination disposition, or `null` when absent. |
| `turns[].messages` | The user message followed by each saved assistant message, in order. |
| `messages[].role` | `user` or `assistant`. |
| `messages[].content` | Exact message text, including any partial response. |
| `messages[].excludedFromContext` | Whether the message is explicitly excluded from subsequent prompts. |
| `messages[].citations` | Ordered citation associations: `sentence` text and an array of one-based source `labels`. |
| `messages[].sources` | This answer's sources; labels restart for each assistant message. |
| `sources[].label` | One-based position matching citation labels. |
| `sources[].path` | Reported local path, or the source's document identity when no separate path was reported. |
| `sources[].startLine`, `sources[].endLine` | Reported line location; zero or negative values mean the producer did not provide a usable line location. |
| `sources[].heading` | Reported heading, or an empty string. |
| `sources[].excerpt` | Reported source passage. |

Sources and citations are empty arrays when none were reported. An empty array does not assert that
an answer was verified or that no relevant sources exist. Reasoning traces, tool payloads and internal
saved-record metadata are outside this format.
