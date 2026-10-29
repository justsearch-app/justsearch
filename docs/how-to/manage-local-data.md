---
title: Manage local data
type: how-to
status: stable
description: "Forget indexed files or folders, review unfinished removals, and understand which local copies remain."
---

# Manage local data

Use **Forget** in Search to remove a file or folder from search and the app's index.
Its confirmation names the exact path. It does not delete the original file or promise to erase
every copy of its content.

## Forget an indexed file or folder

1. Open a result's context menu with the pointer, the context-menu key, or **Shift+F10**.
2. Choose **Forget this file** or **Forget its folder**.
3. Check the exact path and the stated indexing consequence, then confirm. Cancel or Escape
   leaves the request unapproved.

An exact file rule applies only to that file. A folder rule includes its descendants, with a path
boundary: forgetting `b` does not include sibling `bc`. Glob characters in a forgotten path are
literal filename characters. Whitespace and control characters are shown explicitly where needed
to distinguish otherwise similar paths.

A completed forget removes the target's indexed document rows, its owned chunks and queued indexing
work. Search results, current selection, Ask scope and an open document pane update after confirmed
completion. Built-in help and agent-history sources are protected. To stop watching an entire
watched root, or a folder containing one, use the folder's **Remove** control in Library instead.

Files inside watched roots and every permitted folder forget get an exact keep-out entry. It blocks
later indexing while it remains effective. A file outside all watched roots is the exception: its
confirmation explains that no permanent keep-out is added. A new deliberate add, import or relevant
file operation accepted after its completed forget can index that file again.

## Review an unfinished or uncertain result

Library's **Unfinished forgets** section remains available after the original result disappears.
Removal and keep-out are reported separately. An incomplete or unknown outcome does not establish
that all rows are gone, or that a keep-out is effective. A lost response is observed using the same
intent; it does not authorize another removal automatically.

Use **Retry** to continue that exact intent when the reported restriction has cleared. The app can
refuse a new request while index or settings authority is unavailable or an index transition is
active. An already admitted request can remain incomplete after partial work. Restarting or opening
Library does not automatically repeat its destructive work.

Use **Withdraw** to end an unfinished intent and release only its own still-installed exact keep-out.
Prior removal may already have happened. Withdrawal restores neither indexed rows nor original files.
If withdrawal cannot be confirmed, it remains incomplete and destructive Retry stays disabled; use
**Retry withdrawal** when available. A removed or replaced entry supersedes its old intent, which
cannot recreate that entry through Retry.

If completion wins before withdrawal, the completed receipt stays completed. The app explains that
withdrawal cannot reverse it. Remove an installed keep-out separately to permit later indexing.

## Permit indexing again

In Library, review **Exact paths kept out** alongside **Exclude patterns**. Remove the intended
exact-path row and use **Save patterns**. Exact rows are separate from the glob editor, so editing globs keeps
their original path bytes. Removing a rule permits a later indexing pass; it does not create a new
watcher or immediately restore removed rows. A separate remaining exclusion can still block indexing.

## What Forget leaves in place

| Data | Effect of indexed-file or folder Forget |
| --- | --- |
| Original files and folders | Remain on disk. |
| Learned Memory | Remains; inspect it separately in **Memory**. Forgetting a Memory item is a different action. |
| Conversations, saved answers and summaries | Remain, including content or quotations already saved there. |
| Existing backups and exports | Remain; Forget does not rewrite their contents. |
| Logs, audit records and operational receipts | Remain under their existing handling and may still contain paths or earlier content. |
| Older index generations or unreclaimed index bytes | No secure-erasure promise; removing searchable rows does not establish physical erasure of every copy. |

This guide describes indexed Forget. It does not establish that uninstalling the application removes
all local data. Packaged uninstall behavior is unverified here, including Windows package mechanics.
See [Using the UI](use-ui.md) for the separate reset and backup controls.
