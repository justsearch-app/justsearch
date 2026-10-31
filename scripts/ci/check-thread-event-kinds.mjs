#!/usr/bin/env node
/**
 * Compare InteractionEventKind with its generated runtime tuple, in both directions, and check that
 * the client uses that tuple for strict parsing and known-kind rejection. UNKNOWN remains a frontend
 * fallback. The existing parity check stays in the required ui-web-gates recipe.
 */
import { readFileSync } from 'node:fs';
import { stripComments } from '../lib/strip-comments.mjs';

const JAVA_ENUM_FILE =
  'modules/app-agent-api/src/main/java/io/justsearch/agent/api/interaction/InteractionEventKind.java';
const FE_KINDS_FILE = 'modules/ui-web/src/api/generated/registry-enums.generated.ts';
const FE_CLIENT_FILE = 'modules/ui-web/src/shell-v0/views/unifiedThreadClient.ts';

/** Strip Java block/line comments so enum-constant extraction can't false-positive on prose. */
function stripJavaComments(src) {
  return stripComments(src, { withHtml: false });
}

/** Extract the bare `IDENT,` enum-constant names from `enum InteractionEventKind { ... }`. */
export function extractJavaEnumKinds(javaSrc) {
  const withoutComments = stripJavaComments(javaSrc);
  const start = withoutComments.indexOf('enum InteractionEventKind');
  const slice = start >= 0 ? withoutComments.slice(start) : withoutComments;
  const braceStart = slice.indexOf('{');
  const braceEnd = slice.indexOf('}');
  const region = braceStart >= 0 && braceEnd > braceStart ? slice.slice(braceStart + 1, braceEnd) : '';
  const kinds = new Set();
  const re = /\b([A-Z][A-Z0-9_]*)\b/g;
  let m;
  while ((m = re.exec(region)) !== null) kinds.add(m[1]);
  return kinds;
}

/** Extract the generated runtime tuple (also accepts the original literal array in fixtures). */
export function extractFeKnownKinds(tsSrc) {
  tsSrc = stripComments(tsSrc, { withHtml: false });
  const start = tsSrc.search(/(?:KNOWN_EVENT_KINDS|INTERACTION_EVENT_KINDS)\s*=/);
  const slice = start >= 0 ? tsSrc.slice(start) : tsSrc;
  const end = slice.indexOf('] as const');
  const region = end >= 0 ? slice.slice(0, end) : slice;
  const kinds = new Set();
  const re = /'([A-Z][A-Z0-9_]*)'/g;
  let m;
  while ((m = re.exec(region)) !== null) kinds.add(m[1]);
  return kinds;
}

/** Pure correspondence check. Returns an array of failure strings (empty = pass). */
export function checkCorrespondence({ javaKinds, feKinds }) {
  const failures = [];

  for (const kind of javaKinds) {
    if (!feKinds.has(kind)) {
      failures.push(
        `forward: InteractionEventKind member \`${kind}\` has no FE KNOWN_EVENT_KINDS entry in ` +
          `registry-enums.generated.ts — an FE build unaware of it degrades every \`${kind}\` event to the ` +
          `generic UNKNOWN item (S4a forward-tolerance, not a crash) but likely needs a real FE render. ` +
          `Regenerate with -PupdateSchemas and add its case to unifiedThreadProjection.ts's KIND_MAP.`,
      );
    }
  }

  for (const kind of feKinds) {
    if (!javaKinds.has(kind)) {
      failures.push(
        `backward: FE KNOWN_EVENT_KINDS declares \`${kind}\`, which is not an InteractionEventKind Java ` +
          `enum member — a dead or mistyped FE entry. Remove it, or fix the Java enum if it's the typo.`,
      );
    }
  }

  return failures;
}

function main() {
  const javaKinds = extractJavaEnumKinds(readFileSync(JAVA_ENUM_FILE, 'utf8'));
  const feKinds = extractFeKnownKinds(readFileSync(FE_KINDS_FILE, 'utf8'));

  if (javaKinds.size === 0 || feKinds.size === 0) {
    console.error(
      `✗ thread-event-kinds check FAILED: could not extract kinds ` +
        `(java=${javaKinds.size}, fe=${feKinds.size}) — one of the two seams moved; update this script.`,
    );
    process.exit(1);
  }

  const failures = checkCorrespondence({ javaKinds, feKinds });
  const client = stripComments(readFileSync(FE_CLIENT_FILE, 'utf8'), { withHtml: false });
  if (!/import\s*\{\s*INTERACTION_EVENT_KINDS\s+as\s+KNOWN_EVENT_KINDS\s*\}\s*from\s*['"]\.\.\/\.\.\/api\/generated\/registry-enums\.generated\.js['"]/.test(client)
      || !client.includes('z.enum(KNOWN_EVENT_KINDS)')
      || !client.includes('new Set(KNOWN_EVENT_KINDS)')) {
    failures.push('The thread client must use the generated tuple for strict parsing and known-kind rejection.');
  }

  if (failures.length > 0) {
    console.error(
      '✗ thread-event-kinds check FAILED (tempdoc S4a):\n' + failures.map((x) => '  - ' + x).join('\n'),
    );
    process.exit(1);
  }

  console.log(
    `✓ thread-event-kinds check OK — InteractionEventKind↔KNOWN_EVENT_KINDS correspond ` +
      `(${javaKinds.size} Java enum members, ${feKinds.size} FE known kinds); no backend kind silently ` +
      `degrades unmirrored, and no FE entry is dead/mistyped.`,
  );
}

if (process.argv[1] && process.argv[1].replace(/\\/g, '/').endsWith('check-thread-event-kinds.mjs')) {
  main();
}
