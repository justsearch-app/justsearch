// SPDX-License-Identifier: Apache-2.0
/** Display only: citation events and document anchors retain the original passage. */
export function displayPassageExcerpt(passage: string): string {
  const frontMatter = /^---\r?\n(?:[^\n]*\n)*?---(?:\r?\n|$)/.exec(passage);
  if (!frontMatter) return passage;
  const body = passage.slice(frontMatter[0].length).replace(/^(?:[ \t]*\r?\n)+/, '');
  return body.trim() ? body : passage;
}
