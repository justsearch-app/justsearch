#!/usr/bin/env node
/**
 * message-single-model gate — tempdoc 559 Authority III (messaging channel).
 *
 * The collapse landed, so this gate now ENFORCES the single model outright — no
 * ratchet baseline / grandfathering (the prior baseline file is deleted). The
 * parallel `SimpleToast` + `jf-show-toast` channel is gone; every client-
 * originated transient message flows through ONE seam:
 *
 *   emitEphemeralToast(spec)  →  document event `jf-advisory-ephemeral`
 *     →  the single AdvisoryStore folds it in as a local-origin EPHEMERAL record
 *       →  the single AdvisoryToastHost renders it (severity = the tone axis).
 *
 * The gate locks that seam by structure:
 *
 *  (1) **Retired channel is gone.** No production file calls `showToast(` or
 *      references the retired `jf-show-toast` event. (The second toast model.)
 *  (2) **Single emitter.** Only `ephemeralToast.ts` constructs / names the
 *      `jf-advisory-ephemeral` event — every other caller goes through
 *      `emitEphemeralToast()`. A file that hand-rolls the event = a bypass.
 *  (3) **Single sink.** Only `AdvisoryStore.ts` listens for the event
 *      (`addEventListener(EPHEMERAL_TOAST_EVENT`). A second listener = a second
 *      model consuming the channel.
 *
 * Coverage = the full ui-web component tree (a new file is scanned
 * automatically); no enumerated allowlist. Lighter scripts/ci tier; wired as a
 * ci.yml step + the pre-merge checks table (docs/reference/contributing/pre-merge-checks.md).
 *
 * NOTE (honest scope): `ResolutionToast` (chat citation-resolution) is a
 * chat-interaction affordance, not a system-notification channel, so it is not
 * in this gate's scope; the system-message singularity is toast/banner/pill for
 * notifications, which all route through the seam above. The reindex banner and
 * connection pill are projections of the 557 observed-state authority (not a
 * second message model) — see tempdoc 559 §Authority III.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname } from 'node:path';
import { stripComments } from '../lib/strip-comments.mjs';

const SRC = 'modules/ui-web/src';
const EMIT_OWNER = 'shell-v0/components/advisory/ephemeralToast.ts';
const SINK_OWNER = 'shell-v0/components/advisory/AdvisoryStore.ts';

const RETIRED = /showToast\(|['"`]jf-show-toast['"`]/;
const EVENT_LITERAL = /['"`]jf-advisory-ephemeral['"`]/;
const EVENT_LISTEN = /addEventListener\(\s*(EPHEMERAL_TOAST_EVENT|['"`]jf-advisory-ephemeral['"`])/;

const files = [];
(function walk(d) {
  for (const e of readdirSync(d)) {
    const p = join(d, e).replace(/\\/g, '/');
    const s = statSync(p);
    if (s.isDirectory()) {
      if (e === 'node_modules' || e === 'generated') continue;
      walk(p);
    } else if (extname(p) === '.ts' && !/\.(test|spec)\.ts$/.test(p)) {
      files.push(p);
    }
  }
})(SRC);

const rel = (p) => p.slice(SRC.length + 1);
const failures = [];

for (const f of files) {
  const r = rel(f);
  const src = stripComments(readFileSync(f, 'utf8'));

  // (1) retired parallel channel
  if (RETIRED.test(src)) {
    failures.push(
      `${r}: references the retired \`showToast\`/\`jf-show-toast\` channel — ` +
        `route transient messages through emitEphemeralToast() (559 Authority III).`,
    );
  }
  // (2) single emitter — only ephemeralToast.ts names the event literal
  if (r !== EMIT_OWNER && EVENT_LITERAL.test(src)) {
    failures.push(
      `${r}: hand-rolls the \`jf-advisory-ephemeral\` event — call emitEphemeralToast() ` +
        `instead (the one emit seam; 559 Authority III).`,
    );
  }
  // (3) single sink — only AdvisoryStore.ts listens for the event
  if (r !== SINK_OWNER && EVENT_LISTEN.test(src)) {
    failures.push(
      `${r}: adds a second listener for the ephemeral-message event — the single ` +
        `AdvisoryStore is the only sink (559 Authority III).`,
    );
  }
}

if (failures.length > 0) {
  console.error(
    '✗ message-single-model gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'),
  );
  process.exit(1);
}
console.log(
  `✓ message-single-model gate OK — one emit seam (emitEphemeralToast), one event, ` +
    `one sink (AdvisoryStore); the retired jf-show-toast channel is gone.`,
);
