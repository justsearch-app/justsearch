/** Axis tag -> [min, max] from a WOFF2 font's fvar table; throws on a malformed font. */
export function readWoff2Axes(font: Uint8Array): Record<string, [number, number]>;
