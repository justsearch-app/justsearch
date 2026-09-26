import path from 'node:path';

/** File names shared by the operation and migration transition supervisor proofs. */
export function barrierFiles(data, family = 'operation-fault') {
  if (!['operation-fault', 'migration-barrier'].includes(family)) {
    throw new Error(`unknown supervisor barrier family: ${family}`);
  }
  const runtime = path.join(data, 'runtime');
  return {
    reachedFile: path.join(runtime, `${family}-reached.json`),
    releaseFile: path.join(runtime, `${family}-release`),
  };
}
