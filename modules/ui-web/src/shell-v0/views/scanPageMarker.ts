// SPDX-License-Identifier: Apache-2.0
/**
 * The page marker a scan's extracted text carries between pages — exactly what PdfOcrEngine emits on
 * both OCR paths. It is a machine artefact: the result row's snippet and the pane's reading view
 * leave it out, while the stored text, its offsets and its line numbers keep it.
 */
const MARKER = String.raw`--- OCR page [1-9]\d* ---`;

/** Every marker in a text; snippets may have flattened the newlines around it. */
export const SCAN_PAGE_MARKERS = new RegExp(MARKER, 'g');

const MARKER_LINE = new RegExp(String.raw`^\s*${MARKER}\s*$`);

/** Whether a line holds nothing but a page marker. A longer line that contains one is text. */
export function isScanPageMarkerLine(line: string): boolean {
  return MARKER_LINE.test(line);
}
