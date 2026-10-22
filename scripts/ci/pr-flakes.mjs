import { readFileSync } from 'node:fs';
export const PR_FLAKES = readFileSync(new URL('./pr-flakes.tsv', import.meta.url), 'utf8')
  .split(/\r?\n/).filter((line) => line && !line.startsWith('#'))
  .map((line) => { const [runner, source, identifier] = line.split('\t'); return { runner, source, identifier }; });
export function skipPrFlake(source, identifier) {
  if (process.env.JUSTSEARCH_PR_CI !== 'true') return false;
  const found = PR_FLAKES.some((row) => row.source === source && row.identifier === identifier);
  if (found) console.log(`SKIP PR flake: ${source}: ${identifier}`);
  return found;
}
