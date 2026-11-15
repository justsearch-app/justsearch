// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, it } from 'vitest';

it('K3: command-palette operation labels use reading words without promising an incremental pass reads every file', () => {
  const catalog = readFileSync(resolve(process.cwd(), '../app-api/src/main/resources/messages/registry-operation.en.properties'), 'utf8');
  const labels = Object.fromEntries(catalog.split('\n').filter(line => /^[^#].*=/.test(line)).map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
  expect(labels).toMatchObject({
    'ops.reindex.label': 'Read changed files',
    'ops.bulk-reindex.label': 'Rebuild selected search data',
    'ops.rebuild-index.label': 'Rebuild all search data',
    'ops.indexing-pause.label': 'Pause reading',
    'ops.indexing-resume.label': 'Resume reading',
    'ops.set-indexing-rules.label': 'Set reading rules',
    'ops.add-watched-root.label': 'Add a folder',
    'ops.remove-watched-root.label': 'Remove from library',
    'ops.trigger-offline-processing.label': 'Read waiting files now',
    'ops.cancel-indexing-job.label': 'Stop reading this file',
    'ops.retry-indexing-job.label': 'Read this file again',
  });
  expect(labels['ops.reindex.description']).toContain('force=true');
  expect(labels['ops.reindex.description']).toContain('unchanged');
});
