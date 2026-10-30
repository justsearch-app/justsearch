#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const SEARCH_VIEW = 'modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ts';
export const MAX_LINES = 5532;

export function checkSearchViewSize(source) {
  const lines = source.split('\n').length - Number(source.endsWith('\n'));
  return { lines, failure: lines > MAX_LINES
    ? `${SEARCH_VIEW} has ${lines} lines (ceiling ${MAX_LINES}). Move code out rather than raise the number.`
    : null };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const { lines, failure } = checkSearchViewSize(readFileSync(SEARCH_VIEW, 'utf8'));
    if (failure) throw new Error(failure);
    console.log(`search-view-size gate OK (${lines}/${MAX_LINES} lines).`);
  } catch (error) {
    console.error(`search-view-size gate FAILED: ${error.message}`);
    process.exitCode = 1;
  }
}
