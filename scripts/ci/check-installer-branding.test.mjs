#!/usr/bin/env node
/* SPDX-License-Identifier: Apache-2.0 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

import {
  STOCK_TAURI_ICON_SHA256,
  checkBrandingSource,
  checkBundleIcons,
  checkFaviconWiring,
  checkFullPageBranding,
  checkWizardImages,
  runCheck,
} from './check-installer-branding.mjs';

const failures = [];
let passed = 0;

function test(label, assertion) {
  try {
    assertion();
    passed += 1;
  } catch (error) {
    failures.push(`${label}: ${error.message}`);
  }
}

// -------------------- branding source (bundle.copyright) --------------------

const CLEAN_CONF = JSON.stringify({
  version: '0.2.0',
  bundle: { copyright: 'JustSearch 0.2.0 - Copyright (c) JustSearch' },
});

test('the shipped tauri.conf.json branding shape passes', () => {
  assert.deepEqual(checkBrandingSource(CLEAN_CONF), []);
});

test('a missing bundle.copyright fails', () => {
  const conf = JSON.stringify({ version: '0.2.0', bundle: {} });
  assert.equal(checkBrandingSource(conf).length, 1);
});

test('an empty bundle.copyright fails', () => {
  const conf = JSON.stringify({ version: '0.2.0', bundle: { copyright: '   ' } });
  assert.equal(checkBrandingSource(conf).length, 1);
});

test('a copyright naming a different version than the bundle fails', () => {
  const conf = JSON.stringify({
    version: '0.2.0',
    bundle: { copyright: 'JustSearch 0.1.9 - Copyright (c) JustSearch' },
  });
  assert.equal(checkBrandingSource(conf).length, 1);
});

test('unparseable tauri.conf.json is a failure, not a silent pass', () => {
  assert.equal(checkBrandingSource('{not json').length, 1);
});

// -------------------- MUI full pages (Welcome, Finish) --------------------

const CLEAN_NSH = `
!define MUI_WELCOMEPAGE_TEXT "$(MUI_TEXT_WELCOME_INFO_TEXT)$\\r$\\n$\\r$\\n\${COPYRIGHT}"
!define MUI_FINISHPAGE_TEXT_LARGE
!define MUI_FINISHPAGE_TEXT "$(MUI_TEXT_FINISH_INFO_TEXT)$\\r$\\n$\\r$\\n\${COPYRIGHT}"

!macro NSIS_HOOK_PREINSTALL
!macroend
`;

test('the shipped installer-hooks.nsh full-page shape passes', () => {
  assert.deepEqual(checkFullPageBranding(CLEAN_NSH), []);
});

test('dropping the Welcome page text fails', () => {
  const nsh = CLEAN_NSH.split('\n')
    .filter((line) => !line.includes('MUI_WELCOMEPAGE_TEXT'))
    .join('\n');
  assert.equal(checkFullPageBranding(nsh).length, 1);
});

test('dropping the Finish page text fails', () => {
  const nsh = CLEAN_NSH.split('\n')
    .filter((line) => !/MUI_FINISHPAGE_TEXT\s/.test(line))
    .join('\n');
  assert.equal(checkFullPageBranding(nsh).length, 1);
});

test('a page text that does not reference ${COPYRIGHT} fails', () => {
  const nsh = CLEAN_NSH.replace(
    '$(MUI_TEXT_WELCOME_INFO_TEXT)$\\r$\\n$\\r$\\n${COPYRIGHT}',
    'Welcome to JustSearch',
  );
  assert.equal(checkFullPageBranding(nsh).length, 1);
});

test('a hard-coded version string instead of ${COPYRIGHT} fails (it would drift)', () => {
  const nsh = CLEAN_NSH.replace(
    '$(MUI_TEXT_FINISH_INFO_TEXT)$\\r$\\n$\\r$\\n${COPYRIGHT}',
    'JustSearch 0.2.0 installed.',
  );
  assert.equal(checkFullPageBranding(nsh).length, 1);
});

test('overriding the finish text without MUI_FINISHPAGE_TEXT_LARGE fails (clipped line)', () => {
  const nsh = CLEAN_NSH.split('\n')
    .filter((line) => !line.includes('MUI_FINISHPAGE_TEXT_LARGE'))
    .join('\n');
  assert.equal(checkFullPageBranding(nsh).length, 1);
});

test('commented-out defines do not count as present', () => {
  const nsh = CLEAN_NSH.split('\n')
    .map((line) => (line.startsWith('!define MUI_') ? `; ${line}` : line))
    .join('\n');
  // Welcome missing + Finish missing = 2 (the TEXT_LARGE rule only fires when TEXT is present).
  assert.equal(checkFullPageBranding(nsh).length, 2);
});

// -------------------- identity assets (tempdoc 815) --------------------

/** Minimal but structurally real BMP: 14-byte file header + 40-byte BITMAPINFOHEADER. */
function bmp(width, height, bitCount = 24) {
  const buffer = Buffer.alloc(54 + 16);
  buffer.write('BM', 0, 'latin1');
  buffer.writeUInt32LE(40, 14);
  buffer.writeInt32LE(width, 18);
  buffer.writeInt32LE(height, 22);
  buffer.writeUInt16LE(1, 26);
  buffer.writeUInt16LE(bitCount, 28);
  return buffer;
}

const ASSET_CONF = {
  version: '0.2.0',
  bundle: {
    copyright: 'JustSearch 0.2.0 - Copyright (c) JustSearch',
    icon: ['icons/32x32.png', 'icons/icon.icns', 'icons/icon.ico'],
    windows: {
      nsis: {
        installerHooks: 'nsis/installer-hooks.nsh',
        installerIcon: 'icons/icon.ico',
        uninstallerIcon: 'icons/icon.ico',
        headerImage: 'nsis/header.bmp',
        sidebarImage: 'nsis/sidebar.bmp',
      },
    },
  },
};
const assetConf = (mutate = () => {}) => {
  const conf = structuredClone(ASSET_CONF);
  mutate(conf);
  return JSON.stringify(conf);
};
const cleanAssets = () =>
  new Map([
    ['icons/32x32.png', Buffer.from('png-bytes')],
    ['icons/icon.icns', Buffer.from('icns-bytes')],
    ['icons/icon.ico', Buffer.from('our-own-mark')],
    ['nsis/sidebar.bmp', bmp(164, 314)],
    ['nsis/header.bmp', bmp(150, 57)],
  ]);

test('the shipped icon + wizard-artwork shape passes', () => {
  assert.deepEqual(checkBundleIcons(assetConf(), cleanAssets()), []);
  assert.deepEqual(checkWizardImages(assetConf(), cleanAssets()), []);
});

test('a bundle.icon entry with no file on disk fails (the phantom-reference class)', () => {
  const assets = cleanAssets();
  assets.set('icons/32x32.png', null);
  assert.equal(checkBundleIcons(assetConf(), assets).length, 1);
});

test('a zero-byte bundle.icon entry fails', () => {
  const assets = cleanAssets();
  assets.set('icons/icon.icns', Buffer.alloc(0));
  assert.equal(checkBundleIcons(assetConf(), assets).length, 1);
});

test('an empty bundle.icon list fails', () => {
  const conf = assetConf((c) => {
    c.bundle.icon = [];
  });
  assert.equal(checkBundleIcons(conf, cleanAssets()).length, 1);
});

test('shipping the stock Tauri icon.ico fails', () => {
  const assets = cleanAssets();
  const stock = Buffer.from('pretend this is the stock tauri icon');
  assets.set('icons/icon.ico', stock);
  const stockSha = createHash('sha256').update(stock).digest('hex');
  const failures = checkBundleIcons(assetConf(), assets, stockSha);
  assert.equal(failures.length, 1);
  assert.match(failures[0], /stock Tauri placeholder/);
  // ...and the very same bytes pass once they are no longer what the placeholder hashes to,
  // so the assertion is about identity, not about the file being unusual.
  assert.deepEqual(checkBundleIcons(assetConf(), assets, 'f'.repeat(64)), []);
});

test('the stock-icon digest constant is a real sha256, not a placeholder', () => {
  assert.match(STOCK_TAURI_ICON_SHA256, /^[0-9a-f]{64}$/);
});

test('dropping bundle.windows.nsis.sidebarImage fails (falls back to stock win.bmp)', () => {
  const conf = assetConf((c) => {
    delete c.bundle.windows.nsis.sidebarImage;
  });
  assert.equal(checkWizardImages(conf, cleanAssets()).length, 1);
});

test('dropping bundle.windows.nsis.headerImage fails', () => {
  const conf = assetConf((c) => {
    delete c.bundle.windows.nsis.headerImage;
  });
  assert.equal(checkWizardImages(conf, cleanAssets()).length, 1);
});

test('dropping bundle.windows.nsis.installerIcon fails (MUI_ICON never defined)', () => {
  const conf = assetConf((c) => {
    delete c.bundle.windows.nsis.installerIcon;
  });
  const failures = checkWizardImages(conf, cleanAssets());
  assert.equal(failures.length, 1);
  assert.match(failures[0], /installer wizard/);
});

test('dropping bundle.windows.nsis.uninstallerIcon fails (MUI_UNICON inherits nothing)', () => {
  const conf = assetConf((c) => {
    delete c.bundle.windows.nsis.uninstallerIcon;
  });
  const failures = checkWizardImages(conf, cleanAssets());
  assert.equal(failures.length, 1);
  assert.match(failures[0], /uninstaller wizard/);
});

test('a wizard bitmap that is not on disk fails', () => {
  const assets = cleanAssets();
  assets.set('nsis/sidebar.bmp', null);
  assert.equal(checkWizardImages(assetConf(), assets).length, 1);
});

test('a wizard bitmap at the wrong pixel size fails (MUI stretches it silently)', () => {
  const assets = cleanAssets();
  assets.set('nsis/sidebar.bmp', bmp(164, 313));
  const failures = checkWizardImages(assetConf(), assets);
  assert.equal(failures.length, 1);
  assert.match(failures[0], /164x313/);
});

test('a renamed PNG in place of a wizard bitmap fails', () => {
  const assets = cleanAssets();
  assets.set('nsis/header.bmp', Buffer.from([0x89, 0x50, 0x4e, 0x47, ...Buffer.alloc(60)]));
  const failures = checkWizardImages(assetConf(), assets);
  assert.equal(failures.length, 1);
  assert.match(failures[0], /not a BMP/);
});

test('a 32-bit wizard bitmap fails', () => {
  const assets = cleanAssets();
  assets.set('nsis/header.bmp', bmp(150, 57, 32));
  const failures = checkWizardImages(assetConf(), assets);
  assert.equal(failures.length, 1);
  assert.match(failures[0], /32-bit/);
});

const CLEAN_INDEX_HTML = '<link rel="icon" type="image/svg+xml" href="/favicon.svg" />';

test('the shipped index.html favicon wiring passes', () => {
  assert.deepEqual(checkFaviconWiring(CLEAN_INDEX_HTML), []);
});

test('a lingering vite.svg reference fails', () => {
  const html = '<link rel="icon" type="image/svg+xml" href="/vite.svg" />';
  // Two failures: the vite reference itself, and the missing favicon.svg link it displaced.
  assert.equal(checkFaviconWiring(html).length, 2);
});

test('dropping the favicon link entirely fails', () => {
  assert.equal(checkFaviconWiring('<head><title>JustSearch</title></head>').length, 1);
});

test('the full gate checks Windows overlay artwork and preserves base branding', () => {
  const base = structuredClone(ASSET_CONF);
  delete base.bundle.windows;
  const files = {
    'modules/shell/src-tauri/tauri.conf.json': JSON.stringify(base),
    'modules/shell/src-tauri/tauri.windows.conf.json': JSON.stringify({ bundle: { windows: ASSET_CONF.bundle.windows } }),
    'modules/shell/src-tauri/nsis/installer-hooks.nsh': CLEAN_NSH,
    'modules/ui-web/index.html': CLEAN_INDEX_HTML,
  };
  const assets = cleanAssets();
  const check = () => runCheck('', file => files[file],
    file => assets.get(file.replace('modules/shell/src-tauri/', '')) ?? null);
  assert.deepEqual(check(), []);
  const overlay = JSON.parse(files['modules/shell/src-tauri/tauri.windows.conf.json']);
  overlay.bundle.windows.nsis.headerImage = 'nsis/missing.bmp';
  files['modules/shell/src-tauri/tauri.windows.conf.json'] = JSON.stringify(overlay);
  assert.equal(check().length, 1, 'missing artwork selected by the overlay still fails');
});

if (failures.length > 0) {
  console.error(`check-installer-branding.test FAILED (${failures.length}):`);
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}

console.log(`check-installer-branding.test OK - ${passed} assertions passed.`);
