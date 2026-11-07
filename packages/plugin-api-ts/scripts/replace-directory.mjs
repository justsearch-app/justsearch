// Replace prepared output with rollback for ordinary filesystem failures. A failed
// rollback leaves its named backup in place for manual recovery (not crash atomic).
import { existsSync, renameSync, rmSync } from 'node:fs';

export function replaceDirectory(staged, destination) {
  const backup = `${staged}-backup`;
  const hadPrevious = existsSync(destination);
  if (hadPrevious) renameSync(destination, backup);
  try {
    renameSync(staged, destination);
  } catch (error) {
    if (hadPrevious) {
      try {
        renameSync(backup, destination);
      } catch (restoreError) {
        throw new AggregateError([error, restoreError], `Replacement failed; recover previous output from ${backup}`);
      }
    }
    throw error;
  }
  if (hadPrevious) {
    try {
      rmSync(backup, { recursive: true });
    } catch (error) {
      console.warn(`Output replaced successfully; backup cleanup failed at ${backup}: ${error.message}`);
    }
  }
}
