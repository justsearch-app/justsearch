// SPDX-License-Identifier: Apache-2.0
/**
 * Table — schema-driven, virtualized data table (slice 3a.1 Phase 4b).
 *
 * Built on `@tanstack/table-core` (framework-agnostic table engine)
 * + `@lit-labs/virtualizer` (Lit's row virtualizer). The shell-v0
 * shell embeds this as one of the four generic renderers.
 *
 * Column derivation:
 *  - If `columns` is supplied, it wins.
 *  - Otherwise, if `schema` is supplied with `type: 'array'` and
 *    `items.type: 'object'`, columns are derived from
 *    `items.properties` (each property becomes one column; the
 *    property's `title` becomes the header).
 *  - Otherwise, an empty header row renders.
 *
 * Sorting: header clicks toggle ascending → descending → off through
 * tanstack-table's `getSortedRowModel`. The current sort indicator
 * is a triangle next to the header label.
 *
 * Virtualization: row body is rendered via `<lit-virtualizer>` so a
 * 100k-row dataset renders in O(visible-rows) DOM nodes.
 *
 * Usage:
 *   <jf-table
 *     .schema=${arraySchema}
 *     .data=${rows}
 *     @row-click=${(e) => handle(e.detail.row)}
 *   ></jf-table>
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import type { JsonSchema } from '@jsonforms/core';
import {
  createTable,
  getCoreRowModel,
  getSortedRowModel,
  type ColumnDef,
  type SortingState,
  type Table as TanstackTable,
  type Updater,
} from '@tanstack/table-core';
import '@lit-labs/virtualizer';
import type { RendererUserConfig } from '../renderers/userConfig.js';
import { activateOnKey } from '../utils/keyboardHandler.js';

/**
 * Direct-render threshold. Below this row count the table renders
 * each row directly via `.map()`; above, the virtualizer takes over.
 * The threshold is empirical: a 100-row table is ~6 px × 100 = 600px
 * tall, well within a typical pane viewport, and fits in DOM without
 * perceptible cost.
 */
const VIRTUALIZER_THRESHOLD = 100;

export interface TableColumn<T = Record<string, unknown>> {
  id: string;
  header: string;
  accessor: (row: T) => unknown;
  sortable?: boolean;
  format?: (value: unknown) => unknown;
}

export interface RowClickEventDetail<T = Record<string, unknown>> {
  row: T;
  index: number;
}

/**
 * Slice 3a.1.9 §A.7 — Per-row affordance renderer. Returns Lit content
 * for the trailing "Actions" cell. Bound by `<jf-resource-view>` when
 * the Resource declares non-empty `itemOperations` (the cell renders
 * `<jf-row-actions>`); standalone consumers can pass any TemplateResult.
 *
 * @param row the original row object (post-accessor)
 * @param primaryKey the value of the row's primary-key field (extracted
 *   from `row[primaryKeyFieldName]`); useful when the renderer needs to
 *   forward the key as Operation args without re-deriving it.
 */
export type RowActionsRenderer<T = Record<string, unknown>> = (
  row: T,
  primaryKey: string,
) => TemplateResult;

export class Table<T extends Record<string, unknown> = Record<string, unknown>>
  extends JfElement
{
  static override properties = {
    schema: { attribute: false },
    columns: { attribute: false },
    data: { attribute: false },
    sorting: { state: true },
    userConfig: { attribute: false },
    paneId: { type: String, attribute: 'pane-id' },
    rowActionsRenderer: { attribute: false },
    primaryKey: { type: String, attribute: 'primary-key' },
  } as const;

  declare schema: JsonSchema | null;
  declare columns: TableColumn<T>[] | null;
  declare data: T[];
  declare sorting: SortingState;
  /**
   * Slice 3a.1.7 — threaded through; the table reads its own paneId's
   * paneConfig.columnOrder + hiddenFields if present.
   */
  declare userConfig: RendererUserConfig | undefined;
  /**
   * Pane id used to look up this table's slice of userConfig.paneConfig.
   * The Lumino Shell sets this when the table is mounted as a pane;
   * standalone use can leave it empty (then no userConfig overrides
   * apply).
   */
  declare paneId: string;
  /**
   * Slice 3a.1.9 §A.7 — Optional per-row actions renderer. When set, the
   * Table renders a trailing "Actions" cell per row whose contents are
   * the renderer's TemplateResult. Header gets a non-sortable "Actions"
   * column to match.
   */
  declare rowActionsRenderer: RowActionsRenderer<T> | undefined;
  /**
   * Slice 3a.1.9 §A.7 — Primary-key field name on each row. Used to
   * extract the key value for `rowActionsRenderer`'s second argument.
   * Required when `rowActionsRenderer` is set; ignored otherwise.
   */
  declare primaryKey: string;

  constructor() {
    super();
    this.schema = null;
    this.columns = null;
    this.data = [];
    this.sorting = [];
    this.userConfig = undefined;
    this.paneId = '';
    this.rowActionsRenderer = undefined;
    this.primaryKey = '';
  }

  static styles = css`
    :host {
      display: block;
      height: 100%;
      width: 100%;
      box-sizing: border-box;
      color: var(--justsearch-shell-table-text-color, inherit);
      background: var(--justsearch-shell-table-bg, transparent);
      font-size: var(--justsearch-shell-table-font-size, 0.9375rem);
    }
    .table-root {
      display: flex;
      flex-direction: column;
      height: 100%;
      width: 100%;
    }
    .header-row {
      display: flex;
      flex-direction: row;
      flex: 0 0 auto;
      background: var(--justsearch-shell-table-header-bg, #f5f5f5);
      border-bottom: 1px solid
        var(--justsearch-shell-table-header-border, #d0d0d0);
      font-weight: var(--justsearch-shell-table-header-weight, 600);
      user-select: none;
    }
    .header-cell {
      flex: 1 1 0;
      padding: var(--justsearch-shell-table-cell-padding, 0.5rem 0.75rem);
      cursor: default;
      display: flex;
      align-items: center;
      gap: 0.25rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .header-cell.sortable {
      cursor: pointer;
    }
    .header-cell.sortable:hover {
      background: var(--justsearch-shell-table-header-hover-bg, #ececec);
    }
    .sort-indicator {
      font-size: var(--font-size-xs);
      opacity: 0.7;
    }
    .body {
      flex: 1 1 auto;
      overflow: auto;
    }
    lit-virtualizer {
      height: 100%;
      width: 100%;
    }
    .row {
      display: flex;
      flex-direction: row;
      border-bottom: 1px solid
        var(--justsearch-shell-table-row-border, #eee);
      cursor: pointer;
    }
    .row:hover {
      background: var(--justsearch-shell-table-row-hover-bg, #f9f9f9);
    }
    .cell {
      flex: 1 1 0;
      padding: var(--justsearch-shell-table-cell-padding, 0.5rem 0.75rem);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .empty {
      padding: 1rem;
      color: var(--justsearch-shell-table-empty-color, #888);
      font-style: italic;
    }
  `;

  override render(): TemplateResult {
    const resolvedColumns = this.resolveColumns();
    if (resolvedColumns.length === 0) {
      return html`<div class="empty">No columns to display.</div>`;
    }
    const table = this.buildTanstackTable(resolvedColumns);
    const rowModel = table.getRowModel();
    const headerGroups = table.getHeaderGroups();

    const hasRowActions = !!this.rowActionsRenderer;

    return html`
      <div class="table-root" role="table">
        <div class="header-row" role="row">
          ${headerGroups.flatMap((g) =>
            g.headers.map((header) => {
              const sortable = header.column.getCanSort();
              const sortDir = header.column.getIsSorted();
              return html`
                <div
                  class="header-cell ${sortable ? 'sortable' : ''}"
                  role="columnheader"
                  title=${String(header.column.columnDef.header ?? header.id)}
                  tabindex=${sortable ? 0 : -1}
                  aria-sort=${sortDir === 'asc' ? 'ascending' : sortDir === 'desc' ? 'descending' : 'none'}
                  @click=${sortable
                    ? () => header.column.toggleSorting()
                    : null}
                  @keydown=${sortable
                    ? (e: KeyboardEvent) => activateOnKey(e, () => header.column.toggleSorting())
                    : null}
                >
                  <span>${String(header.column.columnDef.header ?? header.id)}</span>
                  ${sortDir === 'asc'
                    ? html`<span class="sort-indicator">▲</span>`
                    : sortDir === 'desc'
                      ? html`<span class="sort-indicator">▼</span>`
                      : null}
                </div>
              `;
            }),
          )}
          ${hasRowActions
            ? html`<div class="header-cell actions-header" role="columnheader">
                <span>Actions</span>
              </div>`
            : null}
        </div>
        <div class="body">
          ${rowModel.rows.length === 0
            ? html`<div class="empty">No rows.</div>`
            : rowModel.rows.length <= VIRTUALIZER_THRESHOLD
              ? // Direct render for small datasets — saves the
                // virtualizer's setup cost for the common case
                // (most form-side tables have <100 rows). Also
                // sidesteps the virtualizer's incomplete behavior
                // under non-browser DOM shims (e.g., happy-dom in
                // unit tests).
                html`
                  ${rowModel.rows.map((row, idx) => this.renderRow(row, idx))}
                `
              : html`
                  <lit-virtualizer
                    .items=${rowModel.rows}
                    .renderItem=${(
                      row: (typeof rowModel.rows)[number],
                      index: number,
                    ) => this.renderRow(row, index)}
                  ></lit-virtualizer>
                `}
        </div>
      </div>
    `;
  }

  private renderRow(
    row: ReturnType<TanstackTable<T>['getRowModel']>['rows'][number],
    index: number,
  ): TemplateResult {
    const cells = row.getVisibleCells();
    const actionsRenderer = this.rowActionsRenderer;
    let primaryKeyValue = '';
    if (actionsRenderer && this.primaryKey) {
      const v = (row.original as Record<string, unknown>)[this.primaryKey];
      primaryKeyValue = v === undefined || v === null ? '' : String(v);
    }
    return html`
      <div
        class="row"
        role="row"
        @click=${() => this.fireRowClick(row.original, index)}
      >
        ${cells.map((cell) => {
          const raw = cell.getValue();
          const meta = cell.column.columnDef.meta as
            | { format?: (v: unknown) => unknown }
            | undefined;
          const display = meta?.format
            ? meta.format(raw)
            : String(raw ?? '');
          return html`
            <div class="cell" role="cell" title=${String(display)}>${display}</div>
          `;
        })}
        ${actionsRenderer
          ? html`<div
              class="cell actions-cell"
              role="cell"
              @click=${(e: Event) => e.stopPropagation()}
            >
              ${actionsRenderer(row.original, primaryKeyValue)}
            </div>`
          : null}
      </div>
    `;
  }

  private fireRowClick(row: T, index: number): void {
    this.dispatchEvent(
      new CustomEvent<RowClickEventDetail<T>>('row-click', {
        detail: { row, index },
        bubbles: true,
        composed: true,
      }),
    );
  }

  private resolveColumns(): TableColumn<T>[] {
    const baseColumns =
      this.columns && this.columns.length > 0
        ? this.columns
        : deriveColumnsFromSchema<T>(this.schema);
    return applyUserConfigToColumns(
      baseColumns,
      this.userConfig,
      this.paneId,
    );
  }

  private buildTanstackTable(
    cols: TableColumn<T>[],
  ): TanstackTable<T> {
    const columnDefs: ColumnDef<T>[] = cols.map((c) => ({
      id: c.id,
      header: c.header,
      accessorFn: c.accessor as (row: T) => unknown,
      enableSorting: c.sortable !== false,
      meta: c.format ? { format: c.format } : undefined,
    }));
    const onSortingChange = (updater: Updater<SortingState>) => {
      this.sorting =
        typeof updater === 'function' ? updater(this.sorting) : updater;
    };
    return createTable<T>({
      data: this.data,
      columns: columnDefs,
      // The framework adapters (@tanstack/react-table etc) merge
      // partial `state` with internally-tracked defaults. Using
      // `createTable` directly we get no such merge — passing only
      // `{ sorting }` leaves `columnPinning`, `columnVisibility`,
      // etc. as `undefined`, and tanstack reads e.g.
      // `state.columnPinning.left` unconditionally → crash. So we
      // build a fully-defaulted state object here.
      state: {
        sorting: this.sorting,
        columnFilters: [],
        columnOrder: [],
        columnPinning: { left: [], right: [] },
        columnSizing: {},
        columnSizingInfo: {
          startOffset: null,
          startSize: null,
          deltaOffset: null,
          deltaPercentage: null,
          isResizingColumn: false,
          columnSizingStart: [],
        },
        columnVisibility: {},
        expanded: {},
        globalFilter: undefined,
        grouping: [],
        pagination: { pageIndex: 0, pageSize: this.data.length || 1 },
        rowPinning: { top: [], bottom: [] },
        rowSelection: {},
      },
      onSortingChange,
      onStateChange: () => {
        // Lit re-renders on reactive-property change; tanstack's
        // own state churn doesn't need a separate callback path.
      },
      getCoreRowModel: getCoreRowModel(),
      getSortedRowModel: getSortedRowModel(),
      renderFallbackValue: null,
    });
  }
}

customElements.define('jf-table', Table);

// ===================================================================
// Schema-driven column derivation
// ===================================================================

/**
 * Derive a column list from a JSON Schema describing an array of
 * objects. Each property of `items` becomes one column. Returns an
 * empty array if the schema doesn't fit the array-of-objects shape.
 */
export function deriveColumnsFromSchema<T = Record<string, unknown>>(
  schema: JsonSchema | null | undefined,
): TableColumn<T>[] {
  if (!schema) {
    return [];
  }
  const s = schema as {
    type?: string;
    items?: { type?: string; properties?: Record<string, { title?: string; type?: string }> };
  };
  if (s.type !== 'array' || !s.items || s.items.type !== 'object') {
    return [];
  }
  const props = s.items.properties ?? {};
  const cols: TableColumn<T>[] = [];
  for (const [name, propSchema] of Object.entries(props)) {
    const isObject = propSchema.type === 'object';
    cols.push({
      id: name,
      header: propSchema.title ?? prettifyKey(name),
      accessor: (row: T) => (row as Record<string, unknown>)[name],
      sortable: !isObject,
      format: isObject
        ? (v: unknown) => {
            if (v == null) return '';
            const obj = v as Record<string, unknown>;
            if (obj.transport) return `${String(obj.transport)}`;
            return JSON.stringify(v);
          }
        : undefined,
    });
  }
  return cols;
}

function prettifyKey(name: string): string {
  // 'firstName' → 'First Name'; 'last_name' → 'Last Name'.
  const spaced = name
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/_/g, ' ')
    .trim();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

/**
 * Apply userConfig.paneConfig overrides to a base column list (slice
 * 3a.1.7). When `paneId` and a matching paneConfig are present:
 *  - `hiddenFields`: columns whose id is in the list are filtered out.
 *  - `columnOrder`: surviving columns are reordered to match the list;
 *    columns not in the list are appended in their original order.
 *
 * Stable + deterministic; safe with arbitrary user-supplied lists. If
 * userConfig is undefined or paneId is empty / no matching paneConfig,
 * the base column list is returned unchanged.
 */
function applyUserConfigToColumns<T>(
  baseColumns: TableColumn<T>[],
  userConfig: RendererUserConfig | undefined,
  paneId: string,
): TableColumn<T>[] {
  if (!userConfig?.paneConfig || !paneId) {
    return baseColumns;
  }
  const paneCfg = userConfig.paneConfig[paneId];
  if (!paneCfg) {
    return baseColumns;
  }
  let working = baseColumns;
  if (paneCfg.hiddenFields && paneCfg.hiddenFields.length > 0) {
    const hidden = new Set(paneCfg.hiddenFields);
    working = working.filter((c) => !hidden.has(c.id));
  }
  if (paneCfg.columnOrder && paneCfg.columnOrder.length > 0) {
    const byId = new Map(working.map((c) => [c.id, c]));
    const ordered: TableColumn<T>[] = [];
    for (const id of paneCfg.columnOrder) {
      const c = byId.get(id);
      if (c) {
        ordered.push(c);
        byId.delete(id);
      }
    }
    // Append remaining columns not in user's order list, preserving
    // their original sequence.
    for (const c of working) {
      if (byId.has(c.id)) {
        ordered.push(c);
      }
    }
    working = ordered;
  }
  return working;
}
