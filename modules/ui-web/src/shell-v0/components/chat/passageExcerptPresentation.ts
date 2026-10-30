// SPDX-License-Identifier: Apache-2.0
/** A metadata line: `key: value`, an indented continuation, or a YAML list item. */
const METADATA_LINE = /^(?:[A-Za-z_][\w-]*\s*:|[ \t]+\S|-[ \t])/;

/** Display only: citation events and document anchors retain the original passage. */
export function displayPassageExcerpt(passage: string): string {
  const frontMatter = /^---\r?\n((?:[^\n]*\n)*?)---(?:\r?\n|$)/.exec(passage);
  if (!frontMatter) return passage;
  // Two horizontal rules around ordinary Markdown are not front matter: keep that text.
  const lines = frontMatter[1]!.split(/\r?\n/).filter((line) => line.trim() !== '');
  if (!lines.every((line) => METADATA_LINE.test(line))) return passage;
  const body = passage.slice(frontMatter[0].length).replace(/^(?:[ \t]*\r?\n)+/, '');
  return body.trim() ? body : passage;
}
