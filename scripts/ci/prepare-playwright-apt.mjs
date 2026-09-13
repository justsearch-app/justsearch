#!/usr/bin/env node
/* SPDX-License-Identifier: Apache-2.0 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Playwright Chromium does not use the branded Google Chrome APT distribution.
const chromeUri = /^https?:\/\/dl\.google\.com\/linux\/chrome(?:-stable)?\/deb\/?$/u;

function sourceUris(text, extension) {
  const content = text.split(/\r?\n/u).filter(line => !/^\s*#/u.test(line)).join('\n');
  if (extension === '.list') {
    return content.split('\n').filter(line => line.trim()).map(line =>
      /^\s*deb(?:-src)?\s+(?:\[[^\]]*\]\s+)?(\S+)\s/u.exec(line)?.[1] ?? null);
  }
  return content.split(/\n\s*\n/u).filter(block => block.trim()).flatMap(block => {
    const fields = [...block.matchAll(/^URIs:\s*([^\n]*(?:\n[ \t]+[^\n]*)*)/gimu)];
    const uris = fields.flatMap(field => field[1].trim().split(/\s+/u));
    return fields.length === 1 ? uris : [null, ...uris];
  });
}

export function preparePlaywrightAptSources(directory) {
  const planned = [];
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const extension = path.extname(entry.name);
    if (!entry.isFile() || !['.list', '.sources'].includes(extension)) continue;
    const source = path.join(directory, entry.name);
    const uris = sourceUris(fs.readFileSync(source, 'utf8'), extension);
    if (!uris.some(uri => chromeUri.test(uri))) continue;
    if (uris.some(uri => !chromeUri.test(uri))) {
      throw new Error(`Refusing mixed or unrecognized APT source file: ${entry.name}`);
    }
    const disabled = `${source}.disabled`;
    if (fs.existsSync(disabled)) throw new Error(`APT backup already exists: ${disabled}`);
    planned.push({ source, disabled });
  }
  // Validate the whole plan before changing any source. Files remain available for inspection.
  for (const { source, disabled } of planned) fs.renameSync(source, disabled);
  return planned.map(({ source }) => path.basename(source));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  console.log(JSON.stringify({ disabledChromeSources:
    preparePlaywrightAptSources('/etc/apt/sources.list.d') }));
}
