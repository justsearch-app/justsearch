// @vitest-environment happy-dom

/**
 * Search v3's response rendering (tempdoc 822 Phase F4).
 *
 * The properties asserted as MECHANISMS rather than appearances:
 *  - **The raw-asterisk era is over.** A settled turn is asserted for rendered STRUCTURE (strong,
 *    list items, code) and for the ABSENCE of the source markers — a renderer that silently fell
 *    back to plain text would pass a "contains the words" check and fails this one.
 *  - **One renderer, one mount.** The block is the same ELEMENT across the terminal, so a
 *    stream-then-swap implementation (which would re-parse and re-mount) cannot pass.
 *  - **Nothing is counted twice.** The panel's cards, the inline marks and the turn's own note are
 *    all read off the ONE stored evidence record; each assertion compares rendered against stored.
 *  - **No renderer of this window's own.** The window imports no markdown package: the parse, the
 *    sanitiser and the source presentation are the product's shared components or nothing.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { resetSearchState } from '../../state/searchState.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

function aiOnline(): void {
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

interface FakeStream {
  emit(event: string, data: unknown): void;
  end(): void;
}

/** The SSE stub of `SearchV3View.ask.test.ts`, kept here so this file drives its own frames. */
function stubStream(): FakeStream {
  const encoder = new TextEncoder();
  const queued: Array<{ done: boolean; value?: Uint8Array }> = [];
  let wake: (() => void) | null = null;
  let signal: AbortSignal | null = null;
  const push = (frame: { done: boolean; value?: Uint8Array }): void => {
    queued.push(frame);
    wake?.();
    wake = null;
  };
  fetchMock.mockImplementation(async (_url: unknown, init: { signal?: AbortSignal }) => {
    signal = init?.signal ?? null;
    return {
      ok: true,
      status: 200,
      body: {
        getReader: () => ({
          read: async () => {
            while (queued.length === 0) {
              if (signal?.aborted === true) throw new Error('The operation was aborted.');
              await new Promise<void>((resolve) => {
                wake = resolve;
                signal?.addEventListener('abort', () => resolve(), { once: true });
              });
            }
            return queued.shift();
          },
          releaseLock: () => {},
        }),
      },
    };
  });
  return {
    emit: (event, data) =>
      push({ done: false, value: encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`) }),
    end: () => push({ done: true }),
  };
}

beforeEach(() => {
  // Phase F6 wired this window to APP-WIDE, process-lifetime authorities (the conversation store,
  // the per-tab reload pointer, the shared draft controller). Each is a module singleton or a
  // storage key, so a case that did not reset them would be reading the previous case's state.
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({}) });
  vi.stubGlobal('fetch', fetchMock);
  __resetAiStateForTest();
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function mount(): Promise<SearchV3View & Mounted> {
  const el = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  el.setAttribute('api-base', 'http://127.0.0.1:9999');
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

async function region(el: Mounted, tag: string): Promise<Mounted> {
  const found = el.shadowRoot?.querySelector(tag) as Mounted | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 8; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

async function ask(el: Mounted, draft: string): Promise<void> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  field.value = draft;
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  (
    composer.shadowRoot?.querySelector('[data-testid="sv3-composer-send"]') as HTMLButtonElement | null
  )?.click();
  await settle(el);
}

const firstTurn = (main: Mounted): HTMLElement => {
  const turn = main.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-turn"]');
  if (!turn) throw new Error('no turn in the transcript');
  return turn;
};

interface MountedBlock extends HTMLElement {
  citations: readonly unknown[];
  updateComplete: Promise<unknown>;
}

const blockIn = (turn: HTMLElement): MountedBlock => {
  const block = turn.querySelector<MountedBlock>('[data-testid="sv3-turn-markdown"]');
  if (!block) throw new Error('no markdown block in the turn');
  return block;
};

const renderedIn = (turn: HTMLElement): HTMLElement => {
  const content = blockIn(turn).shadowRoot?.querySelector<HTMLElement>('.md-content');
  if (!content) throw new Error('the markdown block rendered no content');
  return content;
};

interface MountedPanel extends HTMLElement {
  citations: readonly unknown[];
  sources: readonly unknown[];
  retrievalMode: string;
  updateComplete: Promise<unknown>;
}

const panelIn = (turn: HTMLElement): MountedPanel | null =>
  turn.querySelector<MountedPanel>('[data-testid="sv3-turn-citations"]');

/**
 * Open the tail's own disclosure (Phase F11): the shared panel is mounted body-only, behind the
 * window's ONE trigger, so a collapsed turn has no panel to interrogate.
 */
async function openSources(
  main: HTMLElement & { updateComplete: Promise<unknown> },
  turn: HTMLElement,
): Promise<MountedPanel> {
  const trigger = turn.querySelector<HTMLButtonElement>('[data-testid="sv3-turn-sources"]');
  if (trigger === null) throw new Error('the turn landed without a sources disclosure');
  trigger.click();
  await main.updateComplete;
  const panel = panelIn(turn);
  if (panel === null) throw new Error('the disclosure opened onto no panel');
  await panel.updateComplete;
  return panel;
}

/** One retrieval source in the shape the backend mints (`rag.citations`). */
const source = (i: number): Record<string, unknown> => ({
  parentDocId: `f:/docs/note-${i}.md`,
  chunkIndex: i,
  chunkTotal: 3,
  startChar: 0,
  endChar: 40,
  score: 0.8,
  excerpt: `excerpt ${i}`,
  startLine: 1 + i,
  endLine: 4 + i,
  headingText: `Section ${i}`,
  headingLevel: 2,
});

describe('a settled answer is rendered markdown, not the model\'s source text', () => {
  it('renders bold, a list and code as structure, with no marker left on screen', async () => {
    aiOnline();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'how does the lock work?');

    stream.emit('chunk', {
      text: 'The **lock** is per session.\n\n- it is taken on submit\n- it is released on the terminal\n\nCall `release()` to be sure.',
    });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    const main = await region(el, 'jf-sv3-main');
    const content = renderedIn(firstTurn(main));

    // Structure, not appearance: each construct exists as an ELEMENT.
    expect(content.querySelector('strong')?.textContent).toBe('lock');
    expect(content.querySelectorAll('ul li')).toHaveLength(2);
    expect(content.querySelector('code')?.textContent).toBe('release()');
    // The raw-asterisk probe: a renderer that fell through to plain text would still contain the
    // words, so what is asserted is that the MARKERS are gone.
    const text = content.textContent ?? '';
    expect(text).toContain('it is taken on submit');
    expect(text).not.toContain('**');
    expect(text).not.toContain('`');
    expect(text).not.toContain('- it is');
  });

  it('renders the spec dialect as documents — headings, a table, a rule, task items', async () => {
    // Tempdoc 822 §C2 slice S5. The gap report's decisive experiment, promoted to a fixture: the
    // shape a model emits once the answer grammar asks for structure. Two claims are asserted
    // together, because either alone would be satisfied by a broken window — the STRUCTURE reaches
    // the DOM (a renderer that dropped a table would still show its cells as words), and the block
    // WEARS the variant (the rules for that structure exist only under `:host([prose])`, so a turn
    // that renders a table without the attribute renders a UA table with 1px cells).
    aiOnline();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'what does the lease do?');

    stream.emit('chunk', {
      text: [
        'The lease is renewed on every acquire.',
        '',
        '## The lock',
        '',
        '| Step | Result |',
        '| --- | --- |',
        '| acquire | held |',
        '| release | free |',
        '',
        '---',
        '',
        '- [ ] renew the lease',
        '- [x] release the lock',
        '  - the nested note',
      ].join('\n'),
    });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    const main = await region(el, 'jf-sv3-main');
    const turn = firstTurn(main);
    expect(blockIn(turn).hasAttribute('prose')).toBe(true);

    const content = renderedIn(turn);
    expect(content.querySelector('h2')?.textContent).toBe('The lock');
    expect(content.querySelectorAll('table th')).toHaveLength(2);
    expect(content.querySelectorAll('table td')).toHaveLength(4);
    expect(content.querySelector('hr')).toBeTruthy();
    expect(content.querySelectorAll('li input[type="checkbox"]')).toHaveLength(2);
    expect(content.querySelectorAll('ul ul li')).toHaveLength(1);
    // The raw-pipe probe, the table's equivalent of the raw-asterisk one above.
    expect(content.textContent ?? '').not.toContain('|');
  });

  it('renders through ONE block that survives the terminal, never a stream-then-swap', async () => {
    aiOnline();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'why?');
    const main = await region(el, 'jf-sv3-main');

    stream.emit('chunk', { text: 'Because the **lock' });
    await settle(el);
    const streamingTurn = firstTurn(main);
    const during = blockIn(streamingTurn);
    // The shared renderer's own streaming contract: the mend pass closes the half-written bold, so
    // partial syntax renders as structure instead of flashing its markers.
    expect(during.hasAttribute('is-streaming')).toBe(true);
    expect(during.shadowRoot?.querySelector('.md-content strong')?.textContent).toBe('lock');

    stream.emit('chunk', { text: '** held.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    const after = blockIn(firstTurn(main));
    // The mutation probe for a settle-swap: a second mount would be a DIFFERENT element here.
    expect(after).toBe(during);
    expect(after.hasAttribute('is-streaming')).toBe(false);
    expect(firstTurn(main).querySelectorAll('[data-testid="sv3-turn-markdown"]')).toHaveLength(1);
    expect(renderedIn(firstTurn(main)).textContent?.trim()).toBe('Because the lock held.');
  });
});

describe('the answer\'s evidence is stored on its turn and rendered from there', () => {
  it('hands every stored source to the shared panel, and renders one card each', async () => {
    aiOnline();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'what grounds this?');

    stream.emit('chunk', { text: 'The renewal failed.' });
    stream.emit('rag.meta', { retrieval_mode: 'HYBRID' });
    stream.emit('rag.citations', { citations: [source(0), source(1), source(2)] });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    const main = await region(el, 'jf-sv3-main');
    const turn = firstTurn(main);
    // Collapsed by construction (Phase F11): the disclosure is the window's, and it opens the panel.
    expect(panelIn(turn)).toBeNull();
    const panel = await openSources(main, turn);

    // Compared against what the STREAM sent, not against what the panel was handed: a window that
    // truncated on the way in would otherwise agree with itself all the way down.
    expect(panel.sources).toHaveLength(3);
    expect(panel.retrievalMode).toBe('HYBRID');
    const cards = panel.shadowRoot?.querySelectorAll('.source-card') ?? [];
    expect(cards).toHaveLength(3);

    // The count is the tail disclosure's accessible name; the turn note does not say it a second time.
    expect(turn.querySelector('[data-testid="sv3-turn-note"]')).toBeNull();
  });

  it('weaves one inline mark per resolved citation, and never more than were stored', async () => {
    aiOnline();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'what happened?');

    stream.emit('chunk', { text: 'The lock held. The renewal failed.' });
    stream.emit('rag.citations', { citations: [source(0), source(1)] });
    stream.emit('rag.citation_matches', {
      matches: [
        {
          sentenceIndex: 0,
          sentenceText: 'The lock held.',
          sourceIndex: 0,
          similarity: 0.91,
          parentDocId: 'f:/docs/note-0.md',
        },
        {
          sentenceIndex: 1,
          sentenceText: 'The renewal failed.',
          sourceIndex: 1,
          similarity: 0.88,
          parentDocId: 'f:/docs/note-1.md',
        },
      ],
      sentencesMatched: 2,
      sentencesTotal: 2,
    });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    const main = await region(el, 'jf-sv3-main');
    const turn = firstTurn(main);
    const block = blockIn(turn);
    await block.updateComplete;

    // The marks are the SHARED resolver's output, stored on the turn and handed to the renderer.
    expect(block.citations).toHaveLength(2);
    const markers = block.shadowRoot?.querySelectorAll('.cite-ref') ?? [];
    // The mutation probe, both ways round: the marks must match what the STREAM reported (a window
    // that dropped one on the way in fails here) AND what the renderer was handed (a renderer that
    // dropped one fails here) — a lost mark and an invented one are the same defect.
    expect(markers).toHaveLength(2);
    expect(markers).toHaveLength(block.citations.length);
    expect([...markers].map((m) => m.textContent)).toEqual(['1', '2']);

    // The matches reach the panel too, so its grouping is the same evidence the marks stand on.
    const panel = await openSources(main, turn);
    expect(panel.citations).toHaveLength(2);
    expect(panel.sources).toHaveLength(2);
  });

  it('says nothing about sources when the backend reported none, and "0 sources" when it did', async () => {
    aiOnline();
    const silent = stubStream();
    const el = await mount();
    await ask(el, 'no evidence at all?');
    silent.emit('chunk', { text: 'A guess.' });
    silent.emit('done', {});
    silent.end();
    await settle(el);

    const main = await region(el, 'jf-sv3-main');
    // Never told is not "0 sources": no panel, and no note claiming a number.
    expect(panelIn(firstTurn(main))).toBeNull();
    expect(firstTurn(main).querySelector('[data-testid="sv3-turn-note"]')).toBeNull();

    const reported = stubStream();
    await ask(el, 'and now?');
    reported.emit('chunk', { text: 'Still a guess.' });
    reported.emit('rag.citations', { citations: [] });
    reported.emit('done', {});
    reported.end();
    await settle(el);

    const turns = main.shadowRoot?.querySelectorAll<HTMLElement>('[data-testid="sv3-turn"]') ?? [];
    const second = turns[1] as HTMLElement;
    // A REPORTED empty set is a different claim, and the note is what carries it (the panel, having
    // nothing to show, renders nothing at all).
    expect(panelIn(second)).toBeNull();
    expect(second.querySelector('[data-testid="sv3-turn-note"]')?.textContent?.trim()).toBe(
      '0 sources',
    );
  });
});

/**
 * The markdown/sanitiser FORK shapes, as code rather than as vocabulary: a dependency edge on either
 * package (static, side-effect, re-export, `require`, dynamic `import()`), or a call site of one of
 * their APIs for the case where the binding arrives some other way (a global, a local re-export).
 * The import half is already covered more strongly by the "no third-party package" assertion above;
 * the call-site half is what this adds, and it is why the guard is patterns rather than a word list.
 */
const MARKDOWN_FORK_PATTERNS: readonly RegExp[] = [
  /(?:^|\W)(?:import|export)\b[^'"\n]*\bfrom\s*['"](?:marked|dompurify)(?:\/[^'"]*)?['"]/m,
  /^\s*import\s*['"](?:marked|dompurify)(?:\/[^'"]*)?['"]/m,
  /\b(?:import|require)\s*\(\s*['"](?:marked|dompurify)(?:\/[^'"]*)?['"]\s*\)/,
  /\bnew\s+Marked\s*\(/,
  /\bmarked\s*\.\s*(?:parse|parseInline|use|setOptions|lexer|Lexer|Renderer)\b/,
  /\bmarked\s*\(/,
  /\bDOMPurify\s*\.\s*sanitize\s*\(/,
  /\bcreateDOMPurify\s*\(/,
];

const forksMarkdown = (src: string): boolean =>
  MARKDOWN_FORK_PATTERNS.some((pattern) => pattern.test(src));

describe('the window brings no renderer and no dependency of its own', () => {
  const here = dirname(fileURLToPath(import.meta.url));
  const sources = (): string[] =>
    readdirSync(here).filter((name) => name.endsWith('.ts') && !name.endsWith('.test.ts'));

  it('imports no third-party package beyond Lit — markdown arrives via the shared component', () => {
    // This file names the packages in order to forbid them; the production files may not.
    const offenders = sources().filter((name) => {
      const src = readFileSync(join(here, name), 'utf8');
      // Anchored to real import/export statements: prose that happens to contain from "…" is not
      // a dependency, and a scan that counted it would be noise rather than a guard.
      const specs = [
        ...src.matchAll(/^\s*(?:import|export)\b[^'"\n]*\bfrom\s*['"]([^'"]+)['"]/gm),
        ...src.matchAll(/^\s*import\s*['"]([^'"]+)['"]/gm),
      ].map((m) => m[1] as string);
      return specs.some(
        (spec) => !spec.startsWith('.') && spec !== 'lit' && !spec.startsWith('lit/'),
      );
    });
    expect(offenders).toEqual([]);
  });

  it('reaches markdown and citations through the product\'s ONE component each', () => {
    const main = readFileSync(join(here, 'Sv3Main.ts'), 'utf8');
    expect(main).toContain("../../components/chat/MarkdownBlock.js");
    expect(main).toContain("../../components/chat/CitationsPanel.js");
    // A window-local parse would be the fork these imports exist to prevent — including a second
    // sanitiser, which is the part that would be a security defect and not merely a duplicate.
    const parsers = sources().filter((name) =>
      forksMarkdown(readFileSync(join(here, name), 'utf8')),
    );
    expect(parsers).toEqual([]);
  });

  // Tempdoc 859 (2026-08-25) — the guard used to be /\b(marked|Marked|DOMPurify|dompurify)\b/ over
  // the whole file, so an ordinary English sentence using the word "marked" (this window's own
  // vocabulary: a citation MARK, a turn marked cut-short) failed a SECURITY assertion. A guard that
  // fires on prose gets read as noise and then gets loosened, which is how the real one goes away.
  // These cases pin the tightening in both directions: the fork shapes must still fail, and the
  // prose must not.
  it('the fork guard reads dependency edges and call sites, not the English word', () => {
    for (const fork of [
      `import { marked } from 'marked';`,
      `import {marked as md} from "marked";`,
      `import * as marked from 'marked/lib/marked.esm.js';`,
      `import 'dompurify';`,
      `export { marked } from 'marked';`,
      `const { marked } = require('marked');`,
      `const mod = await import("dompurify");`,
      `const md = new Marked({ gfm: true });`,
      `return marked.parse(text);`,
      `marked.setOptions({});`,
      `const html = marked(text);`,
      `return DOMPurify.sanitize(html);`,
      `const purify = createDOMPurify(window);`,
    ]) {
      expect(forksMarkdown(fork), fork).toBe(true);
    }
    for (const prose of [
      `/* a turn marked cut-short keeps its answer */`,
      `// the ungrounded citation is marked in the answer prose`,
      `const marked = false; // a local flag named for the English word`,
      `expect(row.marked).toBe(true);`,
      `/* every step is marked with its own disposition */`,
    ]) {
      expect(forksMarkdown(prose), prose).toBe(false);
    }
  });
});
