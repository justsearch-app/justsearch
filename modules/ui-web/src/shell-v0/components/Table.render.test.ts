// @vitest-environment happy-dom

/**
 * Render tests for the Table component (slice 3a.1 Phase 4b).
 *
 * Covers:
 *  - Schema-driven column derivation (array-of-objects → columns).
 *  - Manual columns override.
 *  - Empty data + empty schema cases.
 *  - Row click → row-click CustomEvent with detail.
 *  - Header click toggles sort state (asc/desc); sort indicator
 *    reflects current state.
 *  - prettifyKey behavior (camelCase + snake_case).
 */

import { describe, expect, it } from 'vitest';
import './Table.js';
import {
  deriveColumnsFromSchema,
  type RowClickEventDetail,
  type Table,
  type TableColumn,
} from './Table.js';
import type { JsonSchema } from '@jsonforms/core';

interface PersonRow extends Record<string, unknown> {
  name: string;
  age: number;
}

async function mountTable<T extends Record<string, unknown>>(
  props: Partial<Table<T>>,
): Promise<Table<T>> {
  const el = document.createElement('jf-table') as Table<T>;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

const PERSON_SCHEMA: JsonSchema = {
  type: 'array',
  items: {
    type: 'object',
    properties: {
      name: { type: 'string', title: 'Full Name' },
      age: { type: 'integer', title: 'Age' },
    },
  },
};

describe('Table — column derivation', () => {
  it('derives columns from an array-of-objects schema', async () => {
    const el = await mountTable<PersonRow>({
      schema: PERSON_SCHEMA,
      data: [{ name: 'Ada', age: 36 }],
    });
    const headers = el.shadowRoot?.querySelectorAll('.header-cell');
    expect(el.shadowRoot?.querySelector('.table-root')?.getAttribute('role')).toBe('table');
    expect(headers?.length).toBe(2);
    expect(headers?.[0]?.textContent).toContain('Full Name');
    expect(headers?.[1]?.textContent).toContain('Age');
    el.remove();
  });

  it('manual columns override schema-derived columns', async () => {
    const columns: TableColumn<PersonRow>[] = [
      {
        id: 'composite',
        header: 'Composite',
        accessor: (r) => `${r.name} (${r.age})`,
      },
    ];
    const el = await mountTable<PersonRow>({
      schema: PERSON_SCHEMA,
      columns,
      data: [{ name: 'Ada', age: 36 }],
    });
    const headers = el.shadowRoot?.querySelectorAll('.header-cell');
    expect(headers?.length).toBe(1);
    expect(headers?.[0]?.textContent).toContain('Composite');
    el.remove();
  });

  it('renders an empty-state when neither schema nor columns yields columns', async () => {
    const el = await mountTable<PersonRow>({
      schema: { type: 'object' },
      data: [],
    });
    expect(el.shadowRoot?.textContent).toContain('No columns');
    el.remove();
  });

  it('renders an empty-rows message when data is empty but columns exist', async () => {
    const el = await mountTable<PersonRow>({
      schema: PERSON_SCHEMA,
      data: [],
    });
    expect(el.shadowRoot?.textContent).toContain('No rows');
    el.remove();
  });
});

describe('Table — row click', () => {
  it('emits row-click with the row + index', async () => {
    const events: RowClickEventDetail<PersonRow>[] = [];
    const el = await mountTable<PersonRow>({
      schema: PERSON_SCHEMA,
      data: [
        { name: 'Ada', age: 36 },
        { name: 'Grace', age: 85 },
      ],
    });
    el.addEventListener('row-click', ((e: CustomEvent) => {
      events.push(e.detail as RowClickEventDetail<PersonRow>);
    }) as EventListener);
    // The lit-virtualizer mounts rows in light DOM under the body
    // element; query within the shadow root.
    await new Promise((r) => requestAnimationFrame(r));
    const rows = el.shadowRoot?.querySelectorAll('.row');
    expect(rows && rows.length).toBeGreaterThan(0);
    (rows?.[0] as HTMLElement | undefined)?.click();
    expect(events).toHaveLength(1);
    expect(events[0]?.row.name).toBe('Ada');
    expect(events[0]?.index).toBe(0);
    el.remove();
  });
});

describe('Table — sorting', () => {
  it('header click toggles sort indicator on a sortable column', async () => {
    const el = await mountTable<PersonRow>({
      schema: PERSON_SCHEMA,
      data: [
        { name: 'Ada', age: 36 },
        { name: 'Grace', age: 85 },
      ],
    });
    const headers = el.shadowRoot?.querySelectorAll(
      '.header-cell.sortable',
    ) as NodeListOf<HTMLElement>;
    expect(headers.length).toBe(2);
    headers[0]?.click();
    await el.updateComplete;
    let indicator = el.shadowRoot?.querySelector('.sort-indicator');
    expect(indicator?.textContent).toBe('▲');
    headers[0]?.click();
    await el.updateComplete;
    indicator = el.shadowRoot?.querySelector('.sort-indicator');
    expect(indicator?.textContent).toBe('▼');
    el.remove();
  });
});

describe('deriveColumnsFromSchema helper', () => {
  it('returns an empty array for null or non-array schemas', () => {
    expect(deriveColumnsFromSchema(null)).toEqual([]);
    expect(deriveColumnsFromSchema({ type: 'object' })).toEqual([]);
    expect(
      deriveColumnsFromSchema({ type: 'array', items: { type: 'string' } }),
    ).toEqual([]);
  });

  it('uses property title as header when present, else prettifies the key', () => {
    const schema: JsonSchema = {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          firstName: { type: 'string' },
          last_name: { type: 'string', title: 'Surname' },
        },
      },
    };
    const cols = deriveColumnsFromSchema(schema);
    expect(cols.map((c) => c.header)).toEqual(['First Name', 'Surname']);
  });

  it('accessor extracts the property value from a row', () => {
    const schema: JsonSchema = {
      type: 'array',
      items: { type: 'object', properties: { x: { type: 'integer' } } },
    };
    const cols = deriveColumnsFromSchema<{ x: number }>(schema);
    expect(cols[0]?.accessor({ x: 42 })).toBe(42);
  });
});

describe('Table — userConfig.paneConfig (3a.1.7)', () => {
  const COLUMNS_SCHEMA: JsonSchema = {
    type: 'array',
    items: {
      type: 'object',
      properties: {
        name: { type: 'string', title: 'Name' },
        age: { type: 'integer', title: 'Age' },
        email: { type: 'string', title: 'Email' },
      },
    },
  };

  it('reorders columns per paneConfig.columnOrder', async () => {
    const el = await mountTable<PersonRow & { email?: string }>({
      schema: COLUMNS_SCHEMA,
      data: [],
      paneId: 'demo-table',
      userConfig: {
        version: 1,
        paneConfig: {
          'demo-table': { columnOrder: ['email', 'name', 'age'] },
        },
      },
    });
    const headers = el.shadowRoot?.querySelectorAll('.header-cell');
    expect(headers?.length).toBe(3);
    expect(headers?.[0]?.textContent).toContain('Email');
    expect(headers?.[1]?.textContent).toContain('Name');
    expect(headers?.[2]?.textContent).toContain('Age');
    el.remove();
  });

  it('hides columns named in paneConfig.hiddenFields', async () => {
    const el = await mountTable<PersonRow & { email?: string }>({
      schema: COLUMNS_SCHEMA,
      data: [],
      paneId: 'demo-table',
      userConfig: {
        version: 1,
        paneConfig: {
          'demo-table': { hiddenFields: ['age'] },
        },
      },
    });
    const headers = el.shadowRoot?.querySelectorAll('.header-cell');
    expect(headers?.length).toBe(2);
    const headerTexts = Array.from(headers ?? []).map((h) =>
      h.textContent?.trim() ?? '',
    );
    expect(headerTexts.some((t) => t.includes('Age'))).toBe(false);
    el.remove();
  });

  it('combines hiddenFields + columnOrder consistently', async () => {
    const el = await mountTable<PersonRow & { email?: string }>({
      schema: COLUMNS_SCHEMA,
      data: [],
      paneId: 'demo-table',
      userConfig: {
        version: 1,
        paneConfig: {
          'demo-table': {
            hiddenFields: ['age'],
            columnOrder: ['email', 'name'],
          },
        },
      },
    });
    const headers = el.shadowRoot?.querySelectorAll('.header-cell');
    expect(headers?.length).toBe(2);
    expect(headers?.[0]?.textContent).toContain('Email');
    expect(headers?.[1]?.textContent).toContain('Name');
    el.remove();
  });

  it('appends columns not in columnOrder, preserving original sequence', async () => {
    const el = await mountTable<PersonRow & { email?: string }>({
      schema: COLUMNS_SCHEMA,
      data: [],
      paneId: 'demo-table',
      userConfig: {
        version: 1,
        paneConfig: {
          'demo-table': { columnOrder: ['email'] },
        },
      },
    });
    const headers = el.shadowRoot?.querySelectorAll('.header-cell');
    expect(headers?.length).toBe(3);
    expect(headers?.[0]?.textContent).toContain('Email');
    // 'name' and 'age' appended in the schema's original order.
    expect(headers?.[1]?.textContent).toContain('Name');
    expect(headers?.[2]?.textContent).toContain('Age');
    el.remove();
  });

  it('ignores userConfig overrides when paneId is empty', async () => {
    const el = await mountTable<PersonRow & { email?: string }>({
      schema: COLUMNS_SCHEMA,
      data: [],
      paneId: '',
      userConfig: {
        version: 1,
        paneConfig: {
          'demo-table': { columnOrder: ['email', 'name', 'age'] },
        },
      },
    });
    const headers = el.shadowRoot?.querySelectorAll('.header-cell');
    expect(headers?.length).toBe(3);
    // Schema-derived order: name, age, email.
    expect(headers?.[0]?.textContent).toContain('Name');
    expect(headers?.[1]?.textContent).toContain('Age');
    expect(headers?.[2]?.textContent).toContain('Email');
    el.remove();
  });

  it('ignores userConfig overrides when no paneConfig matches the paneId', async () => {
    const el = await mountTable<PersonRow & { email?: string }>({
      schema: COLUMNS_SCHEMA,
      data: [],
      paneId: 'other-pane',
      userConfig: {
        version: 1,
        paneConfig: {
          'demo-table': { columnOrder: ['email', 'name', 'age'] },
        },
      },
    });
    const headers = el.shadowRoot?.querySelectorAll('.header-cell');
    expect(headers?.length).toBe(3);
    expect(headers?.[0]?.textContent).toContain('Name');
    el.remove();
  });
});

// ============================================================
// Slice 3a.1.9 §A.7 — rowActionsRenderer extension
// ============================================================

describe('Table — rowActionsRenderer (slice 3a.1.9)', () => {
  it('renders no actions cell when rowActionsRenderer is undefined', async () => {
    const el = await mountTable<PersonRow>({
      schema: PERSON_SCHEMA,
      data: [{ name: 'Ada', age: 36 }],
    });
    const headers = el.shadowRoot?.querySelectorAll('.header-cell');
    expect(headers?.length).toBe(2); // name + age, no Actions
    const actionsCells = el.shadowRoot?.querySelectorAll('.actions-cell');
    expect(actionsCells?.length).toBe(0);
    el.remove();
  });

  it('renders trailing Actions header + cell per row when set', async () => {
    const { html } = await import('lit');
    const el = await mountTable<PersonRow>({
      schema: PERSON_SCHEMA,
      data: [
        { name: 'Ada', age: 36 },
        { name: 'Babbage', age: 79 },
      ],
      primaryKey: 'name',
      rowActionsRenderer: (row, key) =>
        html`<button data-key=${key}>do for ${(row as PersonRow).name}</button>`,
    });
    // Header has 3 cells: name, age, actions.
    const headers = el.shadowRoot?.querySelectorAll('.header-cell');
    expect(headers?.length).toBe(3);
    expect(headers?.[2]?.textContent).toContain('Actions');
    // Two rows × one actions cell each.
    const actionsCells = el.shadowRoot?.querySelectorAll('.actions-cell');
    expect(actionsCells?.length).toBe(2);
    // Each cell renders the per-row template with primaryKey value bound.
    const buttons = el.shadowRoot?.querySelectorAll('.actions-cell button');
    expect(buttons?.length).toBe(2);
    expect(buttons?.[0]?.getAttribute('data-key')).toBe('Ada');
    expect(buttons?.[1]?.getAttribute('data-key')).toBe('Babbage');
    el.remove();
  });

  it('actions-cell click does NOT bubble row-click', async () => {
    const { html } = await import('lit');
    let rowClicks = 0;
    const el = await mountTable<PersonRow>({
      schema: PERSON_SCHEMA,
      data: [{ name: 'Ada', age: 36 }],
      primaryKey: 'name',
      rowActionsRenderer: () => html`<button class="probe">x</button>`,
    });
    el.addEventListener('row-click', () => {
      rowClicks++;
    });
    const button = el.shadowRoot?.querySelector('.probe') as HTMLButtonElement;
    button?.click();
    expect(rowClicks).toBe(0);
    // Confirm clicking a non-actions cell still fires row-click.
    const cells = el.shadowRoot?.querySelectorAll('.cell:not(.actions-cell)');
    (cells?.[0] as HTMLElement)?.click();
    expect(rowClicks).toBe(1);
    el.remove();
  });

  it('passes empty primaryKey value when row missing the field', async () => {
    const { html } = await import('lit');
    let captured = 'unset';
    const el = await mountTable<PersonRow>({
      schema: PERSON_SCHEMA,
      data: [{ name: 'Ada', age: 36 }],
      primaryKey: 'nonexistent',
      rowActionsRenderer: (_row, key) => {
        captured = key;
        return html`<span></span>`;
      },
    });
    expect(captured).toBe('');
    el.remove();
  });
});
