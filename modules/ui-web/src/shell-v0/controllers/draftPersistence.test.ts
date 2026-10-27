// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest';
import { LitElement } from 'lit';
import {
  DraftPersistence,
  __draftStorageKey,
  __resetDraftProvidersForTest,
} from './draftPersistence.js';

const KEY = 'test.composer';

class DraftProbe extends LitElement {
  value = '';
  readonly persist = new DraftPersistence(
    this,
    KEY,
    () => this.value,
    (v) => {
      this.value = v;
    },
  );
  override createRenderRoot(): HTMLElement {
    return this;
  }
}
if (!customElements.get('jf-draft-probe')) customElements.define('jf-draft-probe', DraftProbe);

function mount(): DraftProbe {
  const el = document.createElement('jf-draft-probe') as DraftProbe;
  document.body.appendChild(el);
  return el;
}

describe('DraftPersistence (tempdoc 609 §R T2.1)', () => {
  beforeEach(() => {
    localStorage.removeItem(__draftStorageKey(KEY));
    __resetDraftProvidersForTest();
    document.body.innerHTML = '';
  });

  it('flushes a draft to localStorage on disconnect', () => {
    const el = mount();
    el.value = 'unsent message';
    el.remove(); // disconnect → flush
    expect(localStorage.getItem(__draftStorageKey(KEY))).toBe('unsent message');
  });

  it('rehydrates a fresh instance from storage on first connect (the reload path)', () => {
    localStorage.setItem(__draftStorageKey(KEY), 'restored draft');
    const el = mount(); // a brand-new instance = first connect → restore
    expect(el.value).toBe('restored draft');
  });

  it('does NOT clobber the live in-memory draft when a RETAINED instance reconnects', () => {
    localStorage.setItem(__draftStorageKey(KEY), 'stale storage');
    const el = mount(); // first connect → restores 'stale storage'
    expect(el.value).toBe('stale storage');
    el.value = 'newer in-memory edit';
    el.remove(); // flush 'newer in-memory edit'
    document.body.appendChild(el); // reconnect SAME instance (retention) → must keep in-memory, not re-read
    expect(el.value).toBe('newer in-memory edit');
  });

  it('removes the storage entry when the draft is emptied', () => {
    localStorage.setItem(__draftStorageKey(KEY), 'old');
    const el = mount();
    el.value = '';
    el.remove(); // flush empty → delete
    expect(localStorage.getItem(__draftStorageKey(KEY))).toBeNull();
  });
});

it('DS1 a failed raw read never authorizes overwriting an unread sole copy on disconnect', () => {
  __resetDraftProvidersForTest(); document.body.replaceChildren();
  const key = __draftStorageKey(KEY);
  localStorage.setItem(key, 'unread original A');
  const original = localStorage.getItem;
  const descriptor = Object.getOwnPropertyDescriptor(localStorage, 'getItem')
    ?? { configurable: true, writable: true, enumerable: false, value: original };
  Object.defineProperty(localStorage, 'getItem', { ...descriptor, value: (candidate: string) => {
    if (candidate === key) throw new DOMException('read denied', 'SecurityError');
    return original(candidate);
  } });
  try {
    const el = mount(); el.value = 'new live B'; el.remove();
    expect(original(key)).toBe('unread original A');
    expect(el.value).toBe('new live B');
  } finally {
    Object.defineProperty(localStorage, 'getItem', descriptor);
    localStorage.removeItem(key); __resetDraftProvidersForTest();
  }
});

it('DS1 failed destination re-read during adoption preserves another tab sole successor before disconnect', () => {
  __resetDraftProvidersForTest(); document.body.replaceChildren();
  const key = __draftStorageKey(KEY);
  const el = mount(); // This mount successfully observed the destination as empty.
  localStorage.setItem(key, 'unread original A'); // Another tab now owns the sole successor copy.
  const original = localStorage.getItem;
  const descriptor = Object.getOwnPropertyDescriptor(localStorage, 'getItem')
    ?? { configurable: true, writable: true, enumerable: false, value: original };
  Object.defineProperty(localStorage, 'getItem', { ...descriptor, value: (candidate: string) => {
    if (candidate === key) throw new DOMException('read denied', 'SecurityError');
    return original(candidate);
  } });
  try {
    el.persist.adoptLegacy('test.legacy');
    el.value = 'new live B'; el.remove();
    expect(original(key)).toBe('unread original A');
    expect(el.value).toBe('new live B');
  } finally {
    Object.defineProperty(localStorage, 'getItem', descriptor);
    localStorage.removeItem(key); __resetDraftProvidersForTest();
  }
});
