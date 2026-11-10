import js from '@eslint/js'
import globals from 'globals'
import tseslint from '@typescript-eslint/eslint-plugin'
import tsParser from '@typescript-eslint/parser'
import { defineConfig, globalIgnores } from 'eslint/config'
import { TARGETS as WIRE_CONTRACT_TARGETS } from '../../scripts/codegen/gen-wire-schema-types.mjs'

// Tempdoc 564 facet 4d — unrepresentability at the lint tier (author-time, ~100%, above the CI
// single-authority gate). A migrated wire record is the single generated projection; hand-declaring
// `interface <Name>` / `type <Name> =` for one of those names anywhere outside `src/api/generated/**`
// is forbidden (the second copy cannot be authored). Driven by the codegen TARGETS, so it
// auto-extends as more records migrate — no hand list to maintain.
const WIRE_TYPE_DECL_SELECTORS = WIRE_CONTRACT_TARGETS.flatMap((t) => {
  const msg =
    `'${t.rootName}' is a generated wire-contract type — import it from ` +
    `api/generated/schema-types, do not hand-declare it (tempdoc 564 facet 4d: the second copy ` +
    `is unrepresentable). Re-export with \`export type { ${t.rootName} } from '…/generated/…'\` ` +
    `if a stable path is needed.`
  return [
    { selector: `TSInterfaceDeclaration[id.name="${t.rootName}"]`, message: msg },
    { selector: `TSTypeAliasDeclaration[id.name="${t.rootName}"]`, message: msg },
  ]
})

// Tempdoc 804 B3/D3 — the companion to `no-restricted-globals: fetch`, which only ever sees the
// BARE identifier. `globalThis.fetch` / `window.fetch` is a member expression and was invisible to
// it — which is how the `fetchImpl ?? globalThis.fetch` family kept shipping token-less writes that
// 401 under the packaged (prod=true) backend. Declared once and spread into EVERY config object
// that sets `no-restricted-syntax` for `src/**`: eslint flat-config REPLACES an array-typed rule
// when a later object matches the same file, so a selector living in only one object is silently
// dropped for every file the other object also matches.
const FETCH_MEMBER_ACCESS_SELECTOR = {
  selector: "MemberExpression[object.name=/^(globalThis|window|self)$/][property.name='fetch']",
  message:
    '`globalThis.fetch` / `window.fetch` bypasses session-token attachment (tempdoc 804 B3). Use ' +
    '`authorizedFetch` from shell-v0/api/authorizedFetch.ts, or `request()` from @/api/http.',
}

/**
 * Feature-Sliced Design (FSD) layer hierarchy (strict top-to-bottom):
 *   app → pages → widgets → features → entities → shared
 *
 * Import boundary rules:
 * - shared: can import nothing above it (only external packages)
 * - entities: can import shared
 * - features: can import entities + shared
 * - widgets: can import features + entities + shared
 * - pages: can import widgets + features + entities + shared
 * - app: can import everything
 *
 * We use no-restricted-imports to enforce these boundaries.
 * Start with 'warn' during migration; flip to 'error' once complete.
 */

export default defineConfig([
  globalIgnores(['dist']),
  // Tempdoc 930 §22.2 follow-up 8 — `lint` now runs with `--max-warnings=0`, which makes an
  // unused `eslint-disable` a hard failure. `src/api/generated/**` is written by a Java
  // generator (RegistryEnumsTsGenerationTest and friends) whose drift check owns those files
  // byte-for-byte, so their blanket `/* eslint-disable */` header cannot be edited here —
  // stripping it would red the Java drift test on the next Gradle run. Silence the
  // unused-directive report for the generated tree instead of forcing a cross-language edit.
  {
    files: ['src/api/generated/**'],
    linterOptions: { reportUnusedDisableDirectives: 'off' },
  },
  // JavaScript/JSX files
  {
    files: ['**/*.{js,jsx}'],
    extends: [js.configs.recommended],
    languageOptions: {
      ecmaVersion: 2020,
      // Slice 477 H2.7 — `lockdown` is the SES global injected by
      // `import 'ses'` (slice 466). Declared here instead of per-call
      // `eslint-disable-next-line no-undef` comments.
      globals: { ...globals.browser, lockdown: 'readonly', Compartment: 'readonly' },
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
    },
    rules: {
      'no-unused-vars': ['error', { varsIgnorePattern: '^[A-Z_]' }],
    },
  },
  // TypeScript files
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      parser: tsParser,
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
      // Slice 477 H2.7 — SES globals (lockdown, Compartment) declared
      // for the TS layer too (TS noUndef is 'off' but eslint-plugin
      // rules may still rely on `globals`).
      globals: { ...globals.browser, lockdown: 'readonly', Compartment: 'readonly' },
    },
    plugins: {
      '@typescript-eslint': tseslint,
    },
    rules: {
      ...js.configs.recommended.rules,
      'no-undef': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { varsIgnorePattern: '^[A-Z_]', argsIgnorePattern: '^_' }],
      'no-unused-vars': 'off',
      // Tempdoc 930 chunk F — replaces the `ts-any` kernel gate. The gate counted `any` with a
      // regex over raw file text, so it scored the English word "any" in prose comments (the
      // `ts-any-gate-counts-english-prose` red). The lint rule parses, so it cannot. The existing
      // violations are carried in `eslint-suppressions.json` (ESLint bulk suppressions), which is
      // the ratchet: a NEW `any` fails, and removing one and re-running `--prune-suppressions`
      // lowers the count. That is the same ratchet shape with none of the kernel bookkeeping.
      '@typescript-eslint/no-explicit-any': 'error',
      // Tempdoc 930 chunk F — replaces the `todo-fixme` kernel gate (TypeScript half; the Java
      // half is the PMD `CommentContent` rule in config/pmd/ruleset.xml).
      'no-warning-comments': ['error', { terms: ['todo', 'fixme', 'xxx'], location: 'anywhere' }],
    },
  },

  // SES replaces Date's locale methods with raw date strings. Use the shared Intl formatters;
  // type information keeps Number#toLocaleString available for ordinary counts.
  {
    files: ['src/**/*.{js,jsx,ts,tsx}'],
    languageOptions: {
      parser: tsParser,
      parserOptions: {
        projectService: { allowDefaultProject: ['src/*.js', 'src/*.jsx', 'src/boot/*.js'] },
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: {
      ses: { rules: {
        'no-date-locale': {
          meta: { type: 'problem', schema: [], messages: {
            locale: 'Date locale methods ignore formatting under SES. Use display/format.ts Intl formatters.',
          } },
          create(context) {
            const services = context.sourceCode.parserServices;
            const checker = services.program.getTypeChecker();
            const isDate = type => {
              if (type.isUnionOrIntersection()) return type.types.some(isDate);
              if (type.getSymbol()?.getName() === 'Date') return true;
              const constraint = checker.getBaseConstraintOfType(type);
              if (constraint && constraint !== type) return isDate(constraint);
              return type.getBaseTypes?.()?.some(isDate) ?? false;
            };
            return { MemberExpression(node) {
              const method = node.computed ? node.property.value : node.property.name;
              if (!['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString'].includes(method)) return;
              const receiver = services.esTreeNodeToTSNodeMap.get(node.object);
              if (isDate(checker.getTypeAtLocation(receiver))) context.report({ node, messageId: 'locale' });
            } };
          },
        },
      } },
    },
    rules: { 'ses/no-date-locale': 'error' },
  },

  // ==================== FSD Layer Boundary Rules ====================
  // These rules enforce the Feature-Sliced Design import hierarchy.
  // Currently set to 'warn' during migration; flip to 'error' when complete.

  // shared layer: cannot import from any FSD layer above it
  {
    files: ['src/shared/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': ['warn', {
        patterns: [
          { group: ['*/entities/*', '../entities/*', '../../entities/*'], message: 'FSD: shared cannot import from entities' },
          { group: ['*/features/*', '../features/*', '../../features/*'], message: 'FSD: shared cannot import from features' },
          { group: ['*/widgets/*', '../widgets/*', '../../widgets/*'], message: 'FSD: shared cannot import from widgets' },
          { group: ['*/pages/*', '../pages/*', '../../pages/*'], message: 'FSD: shared cannot import from pages' },
          { group: ['*/app/*', '../app/*', '../../app/*'], message: 'FSD: shared cannot import from app' },
        ],
      }],
    },
  },

  // entities layer: can import shared only
  {
    files: ['src/entities/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': ['warn', {
        patterns: [
          { group: ['*/features/*', '../features/*', '../../features/*'], message: 'FSD: entities cannot import from features' },
          { group: ['*/widgets/*', '../widgets/*', '../../widgets/*'], message: 'FSD: entities cannot import from widgets' },
          { group: ['*/pages/*', '../pages/*', '../../pages/*'], message: 'FSD: entities cannot import from pages' },
          { group: ['*/app/*', '../app/*', '../../app/*'], message: 'FSD: entities cannot import from app' },
        ],
      }],
    },
  },

  // features layer: can import entities + shared
  {
    files: ['src/features/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': ['warn', {
        patterns: [
          { group: ['*/widgets/*', '../widgets/*', '../../widgets/*'], message: 'FSD: features cannot import from widgets' },
          { group: ['*/pages/*', '../pages/*', '../../pages/*'], message: 'FSD: features cannot import from pages' },
          { group: ['*/app/*', '../app/*', '../../app/*'], message: 'FSD: features cannot import from app' },
        ],
      }],
    },
  },

  // widgets layer: can import features + entities + shared
  {
    files: ['src/widgets/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': ['warn', {
        patterns: [
          { group: ['*/pages/*', '../pages/*', '../../pages/*'], message: 'FSD: widgets cannot import from pages' },
          { group: ['*/app/*', '../app/*', '../../app/*'], message: 'FSD: widgets cannot import from app' },
        ],
      }],
    },
  },

  // pages layer: can import widgets + features + entities + shared
  {
    files: ['src/pages/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': ['warn', {
        patterns: [
          { group: ['*/app/*', '../app/*', '../../app/*'], message: 'FSD: pages cannot import from app' },
        ],
      }],
    },
  },

  // app layer: can import everything (no restrictions)

  // ==================== Authorized Writes Enforcement (D2 / 804 B3) ====================
  // Restrict raw `fetch()` usage outside the API layer to enforce token correctness.
  // Backend calls go through `request()` (@/api/http) or, in shell-v0, the
  // `authorizedFetch` seam (shell-v0/api/authorizedFetch.ts) — both attach the
  // session token the packaged (prod=true) backend requires on POST/PUT/DELETE.
  //
  // 804 D3: `no-restricted-globals` only sees the BARE identifier — every
  // `globalThis.fetch` / `window.fetch` member access was invisible to it (that is
  // how the `fetchImpl ?? globalThis.fetch` family stayed unflagged while shipping
  // 401s). The companion `no-restricted-syntax` rule below closes that hole.
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: [
      'src/api/**/*.{ts,tsx}',
      'src/shell-v0/api/authorizedFetch.ts',
      'src/**/*.test.{ts,tsx}',
    ],
    rules: {
      'no-restricted-globals': ['error', {
        name: 'fetch',
        message: 'Use the `request()` helper from @/api/http, or `authorizedFetch` from shell-v0/api/authorizedFetch.ts, so the session token is attached.',
      }],
      'no-restricted-syntax': ['error', FETCH_MEMBER_ACCESS_SELECTOR],
    },
  },

  // ==================== Generated wire-types + registry types — barrel/canonical-consumer access ====================
  // Two related restrictions live in this single block because eslint flat-config
  // replaces `no-restricted-imports` (an array-typed rule) when multiple config
  // objects match the same file — they do NOT merge. Splitting would silently
  // drop one restriction; the union of both `patterns` is the only correct shape.
  //
  // (a) `api/generated/wire-types` — slice 3a.1.3 + ADR-08. Consumers must go
  //     through `api/generated/index.ts` (the barrel) so its overrides (HealthEvent
  //     body union, UnknownEventBody.kind required, FE-name aliases) are picked
  //     up. Direct imports also defeat the slice 3a.1.8 Phase 4 contract-layer
  //     flip. Match both extensionless paths and `.js`/`.ts` suffixes — ESM-mode
  //     TypeScript imports use `.js`, so an extensionless-only pattern is
  //     trivially bypassable.
  //
  // (b) `api/types/registry` — tempdoc 511 §511-followup-A. The aggregate-surfacing
  //     substrate (`shell-v0/aggregate-substrate/`) is the canonical consumer chain
  //     for registry records (Resource, Operation, Audience, …). Surfaces should
  //     mount the aggregate components (`<jf-operation>`, `<jf-resource>`,
  //     `<jf-health-event>`), not destructure the wire records themselves.
  //     Allowlist covers consumers identified in §511-investigation Track 3:
  //     substrate-internal, state/projection, substrate-adjacent helpers,
  //     catalog client, and the SettingsSurface audience toggle.
  //
  // `ignores` is the union of both restrictions' exemptions. The wire-types rule's
  // exemption alone is `src/api/generated/**`; the registry rule's allowlist is
  // broader (substrate-internal + state + adjacent + tests + fixtures). The union
  // means substrate-internal files are NOT subject to the wire-types restriction —
  // a benign exception since the substrate uses the barrel by convention. New
  // consumers added inside the substrate should still prefer the barrel.
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: [
      // Wire-types: generated files import the type module they live next to.
      'src/api/generated/**/*.{ts,tsx}',
      // Registry: substrate-internal (canonical-strategy chain).
      'src/shell-v0/aggregate-substrate/**/*.{ts,tsx}',
      // Registry: state / projection that mirrors a wire enum.
      'src/shell-v0/state/UserStateDocument.ts',
      'src/shell-v0/state/viewerAudienceState.ts',
      // Registry: substrate-adjacent helpers (catalog clients, view contracts,
      // subscription strategy) — consumers of the substrate boundary.
      'src/shell-v0/components/ResourceView.ts',
      'src/shell-v0/components/advisory/AdvisoryStore.ts',
      'src/shell-v0/renderers/resourceViewContract.ts',
      'src/shell-v0/strategies/subscriptionStrategy.ts',
      // Registry: audience toggle surface needs the Audience enum directly.
      'src/shell-v0/views/SettingsSurface.ts',
      // Registry: fixtures + tests legitimately construct wire records.
      'src/**/*.test.{ts,tsx}',
      'src/**/__fixtures__/**',
    ],
    rules: {
      'no-restricted-imports': ['error', {
        patterns: [
          {
            group: [
              '*/api/generated/wire-types',
              '*/api/generated/wire-types.js',
              '*/api/generated/wire-types.ts',
              '../api/generated/wire-types',
              '../api/generated/wire-types.js',
              '../../api/generated/wire-types',
              '../../api/generated/wire-types.js',
              '../../../api/generated/wire-types',
              '../../../api/generated/wire-types.js',
              '../../../../api/generated/wire-types',
              '../../../../api/generated/wire-types.js',
            ],
            message: 'Import from `api/generated` (the barrel), not `api/generated/wire-types` directly. Slice 3a.1.3 + ADR-08.',
          },
          {
            group: [
              '*/api/types/registry',
              '*/api/types/registry.js',
              '*/api/types/registry.ts',
              '../api/types/registry',
              '../api/types/registry.js',
              '../../api/types/registry',
              '../../api/types/registry.js',
              '../../../api/types/registry',
              '../../../api/types/registry.js',
              '../../../../api/types/registry',
              '../../../../api/types/registry.js',
            ],
            message: 'Mount an aggregate component from `shell-v0/aggregate-substrate/` (e.g. `<jf-operation>`, `<jf-resource>`, `<jf-health-event>`) instead of importing registry types directly. See tempdoc 511 §511-followup-A. If your file is a legitimate consumer, add it to the allowlist in eslint.config.js.',
          },
        ],
      }],
      // Tempdoc 557 §2.A — tier-4 display guard. Never hardcode a raw i18n
      // label key (`ops.*.label`) as literal template text; resolve it through
      // the one display/label authority (localizeResourceKey / deriveLabel).
      // (Runtime-data leaks aren't statically catchable per §5; this guards the
      // literal-regression vector that produced the Q3 command-palette defect.)
      'no-restricted-syntax': [
        'error',
        {
          selector: 'TemplateElement[value.raw=/ops\\.[a-z0-9-]+\\.label/]',
          message: 'Do not hardcode a raw i18n label key (`ops.*.label`) in a template — resolve via localizeResourceKey / deriveLabel (tempdoc 557 §2.A).',
        },
        // Tempdoc 564 facet 4d — forbid hand-declaring a migrated wire type outside the generated set.
        ...WIRE_TYPE_DECL_SELECTORS,
        // Tempdoc 804 B3 — repeated here because this object REPLACES (not merges) the
        // authorized-writes object's `no-restricted-syntax` for every file both match.
        FETCH_MEMBER_ACCESS_SELECTOR,
      ],
    },
  },
])
