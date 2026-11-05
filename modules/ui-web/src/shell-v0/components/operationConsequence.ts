// SPDX-License-Identifier: Apache-2.0
/**
 * The consequence sentence a catalog operation's in-place confirmation shows (design system §7
 * "In-place confirmation", §8 words: say what changes, what stays, and whether it can be undone —
 * never "Are you sure?"). The catalog's descriptions are written for developers and agents (argument
 * shapes, risk tiers), so the person-facing sentence lives here, keyed by operation id. An operation
 * without its own sentence still says plainly that it runs now and has no undo.
 */
const CONSEQUENCES: Readonly<Record<string, string>> = {
  'core.rebuild-index': 'Every watched folder is read again into a new index. Search keeps using the current index until the new one is ready; this takes a while and can be cancelled while it runs, not undone.',
  'core.bulk-reindex': 'The chosen collections are read again into a new index. Search keeps using the current index until the new one is ready; this takes a while and can be cancelled while it runs, not undone.',
  'core.clear-failed-jobs': 'Failed files leave the failed list and are not retried. Your files on disk stay where they are, and a file is read again the next time it changes. This can\'t be undone.',
  'core.reset-settings': 'Your settings return to their defaults, including skip rules and indexing times. Your folders, your index and paths set by an administrator stay. This can\'t be undone.',
  'core.index-gc': 'Old index versions that search no longer uses are deleted to free space. The current index stays. This can\'t be undone.',
};

export function operationConsequence(operationId: string, label: string): string {
  return CONSEQUENCES[operationId] ?? `${label} runs now. It can't be undone from here.`;
}
