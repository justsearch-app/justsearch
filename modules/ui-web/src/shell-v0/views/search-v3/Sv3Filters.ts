// SPDX-License-Identifier: Apache-2.0
import { css, html, nothing, type PropertyValues, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { localizeResourceKey as message, onCatalogUpdated } from '../../../i18n/resourceCatalog.js';
import { getFilters, setFilters, subscribeFilters, type SearchFilterSpec, type SizeUnit } from '../../state/searchFiltersState.js';
import { dateBound, localDay, restrictionLabels, SIZE_UNITS, sizeBound, withoutRestriction, type RestrictionKind } from './sv3-filter-input.js';

/** Draft text lives here; only a validated committed dimension reaches the session store. */
export class Sv3Filters extends JfElement {
  static properties = { active: { type: Boolean, reflect: true }, open: { type: Boolean, reflect: true }, note: { type: String }, serverError: { type: String },
    spec: { state: true }, errors: { state: true }, text: { state: true } };
  declare active: boolean;
  declare open: boolean;
  declare note: string;
  declare serverError: string;
  declare private spec: SearchFilterSpec;
  declare private errors: Partial<Record<RestrictionKind, string>>;
  declare private text: Record<'folder' | 'from' | 'to' | 'minimum' | 'maximum', string>;
  private fromUnit: SizeUnit = 'KB';
  private toUnit: SizeUnit = 'KB';
  private unsubscribers: Array<() => void> = [];

  static styles = css`
    :host { display: block; min-block-size: 0; min-inline-size: 0; color: var(--foreground); font-size: var(--font-size-sv3-sm); }
    :host([open]), :host([active]) { overflow-y: auto; overscroll-behavior: contain; }
    button, input, select { font: inherit; color: inherit; }
    button { cursor: pointer; background: transparent; border: 1px solid var(--border); border-radius: var(--radius-sm); padding: var(--space-1) var(--space-2); }
    button:focus-visible, input:focus-visible, select:focus-visible { outline: 2px solid var(--ring); outline-offset: 1px; }
    .chips { display: flex; flex-wrap: wrap; gap: var(--space-1); padding-block: var(--space-1); }
    .chips button { max-inline-size: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-align: start; }
    .panel { border: 1px solid var(--border); border-radius: var(--radius-lg); padding: var(--space-2); background: var(--background); }
    .heading { position: sticky; top: 0; z-index: 1; background: var(--background); display: flex; justify-content: space-between; align-items: center; gap: var(--space-2); }
    h2, p { margin: 0; font-size: inherit; }
    .fields { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-2); margin-block: var(--space-1); }
    .folder { grid-column: 1 / -1; }
    label { display: block; min-inline-size: 0; }
    input { box-sizing: border-box; inline-size: 100%; min-inline-size: 0; background: var(--background); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: var(--space-1); }
    .size-input { display: flex; gap: var(--space-1); }
    select { background: var(--background); border: 1px solid var(--border); border-radius: var(--radius-sm); }
    .error, .hint, .notes { font-size: var(--font-size-sv3-xs); overflow-wrap: anywhere; }
    .error { color: var(--text-danger); }
    .notes { margin-block-start: var(--space-1); }
    .error:empty { display: none; }
    @media (forced-colors: active) { .panel, input, select, button { background: Canvas; color: CanvasText; border-color: CanvasText; } .error { color: CanvasText; } }
  `;

  constructor() {
    super(); this.active = false; this.open = false; this.note = ''; this.serverError = ''; this.spec = {};
    this.errors = {}; this.text = { folder: '', from: '', to: '', minimum: '', maximum: '' };
  }
  override connectedCallback(): void {
    super.connectedCallback();
    this.unsubscribers = [subscribeFilters(spec => this.receive(spec)), onCatalogUpdated(() => this.requestUpdate())];
  }
  override disconnectedCallback(): void { this.unsubscribers.forEach(stop => stop()); super.disconnectedCallback(); }
  resetInputs(): void { this.text = { folder: '', from: '', to: '', minimum: '', maximum: '' }; this.errors = {}; }
  private displayedText(spec: SearchFilterSpec): Sv3Filters['text'] {
    return { folder: spec.pathPrefix ?? '',
      from: spec.modifiedFromMs === undefined || spec.modifiedFromMs === 0 ? '' : localDay(spec.modifiedFromMs),
      to: spec.modifiedToMs === undefined || spec.modifiedToMs === 0 ? '' : localDay(spec.modifiedToMs),
      minimum: spec.sizeFromInput?.text ?? (spec.sizeFromBytes === undefined ? '' : String(spec.sizeFromBytes / 1024)),
      maximum: spec.sizeToInput?.text ?? (spec.sizeToBytes === undefined ? '' : String(spec.sizeToBytes / 1024)) };
  }
  private receive(spec: SearchFilterSpec): void {
    const old = this.spec, displayed = this.displayedText(spec);
    // Unrelated dimension commits must not erase unfinished text in another control.
    if (spec.pathPrefix !== old.pathPrefix) this.text = { ...this.text, folder: displayed.folder };
    if (spec.modifiedFromMs !== old.modifiedFromMs || spec.modifiedToMs !== old.modifiedToMs) this.text = { ...this.text,
      from: displayed.from, to: displayed.to };
    if (spec.sizeFromBytes !== old.sizeFromBytes || spec.sizeToBytes !== old.sizeToBytes || spec.sizeFromInput !== old.sizeFromInput || spec.sizeToInput !== old.sizeToInput) {
      this.fromUnit = spec.sizeFromInput?.unit ?? 'KB'; this.toUnit = spec.sizeToInput?.unit ?? 'KB';
      this.text = { ...this.text, minimum: displayed.minimum, maximum: displayed.maximum };
    }
    this.spec = spec; this.active = restrictionLabels(spec).length > 0;
  }
  override updated(changed: PropertyValues): void {
    if (changed.has('serverError') && this.serverError) this.open = true;
  }
  private error(kind: RestrictionKind): string {
    const field = kind === 'folder' ? 'pathPrefix' : kind === 'date' ? 'modifiedAt' : 'sizeBytes';
    return this.errors[kind] || (this.serverError.includes(field) ? this.serverError : '');
  }
  private commit(kind: RestrictionKind, key: keyof Sv3Filters['text']): void {
    const current = getFilters(), displayed = this.displayedText(current);
    const fromChanged = key === 'from' && this.text.from !== displayed.from, toChanged = key === 'to' && this.text.to !== displayed.to;
    const minimumChanged = key === 'minimum' && (this.text.minimum !== displayed.minimum || this.fromUnit !== (current.sizeFromInput?.unit ?? 'KB'));
    const maximumChanged = key === 'maximum' && (this.text.maximum !== displayed.maximum || this.toUnit !== (current.sizeToInput?.unit ?? 'KB'));
    const badDate = [...(this.shadowRoot?.querySelectorAll<HTMLInputElement>('input[type=date]') ?? [])].some(input => input.validity.badInput);
    // A displayed day or unit value may be less precise than the stored API bound.
    // Visiting a field, including Enter after a date-picker change, is not an edit.
    if ((kind === 'folder' && this.text.folder === displayed.folder)
      || (kind === 'date' && !fromChanged && !toChanged && !badDate)
      || (kind === 'size' && !minimumChanged && !maximumChanged)) {
      this.errors = { ...this.errors, [kind]: '' };
      return;
    }
    let next = withoutRestriction(current, kind), error = '';
    if (kind === 'folder' && this.text.folder !== '') {
      if (this.text.folder.includes('\0') || this.text.folder.length > 4096 || !/^(?:\/|[A-Za-z]:[\\/]|\\\\)/.test(this.text.folder)) error = message('search.filters.folder-error');
      else next = { ...next, pathPrefix: this.text.folder };
    } else if (kind === 'date') {
      const draftFrom = dateBound(this.text.from, false), draftTo = dateBound(this.text.to, true);
      const from = fromChanged ? draftFrom : current.modifiedFromMs;
      const to = toChanged ? draftTo : current.modifiedToMs;
      if (Number.isNaN(draftFrom) || Number.isNaN(draftTo) || badDate) error = message('search.filters.date-error');
      else if (from !== undefined && to !== undefined && from > to) error = message('search.filters.date-order');
      else next = { ...next, ...(from === undefined ? {} : { modifiedFromMs: from }), ...(to === undefined ? {} : { modifiedToMs: to }) };
    } else if (kind === 'size') {
      const draftFrom = sizeBound(this.text.minimum, this.fromUnit), draftTo = sizeBound(this.text.maximum, this.toUnit);
      const from = minimumChanged ? draftFrom : current.sizeFromBytes;
      const to = maximumChanged ? draftTo : current.sizeToBytes;
      if (Number.isNaN(draftFrom) || Number.isNaN(draftTo)) error = message('search.filters.size-error');
      else if (from !== undefined && to !== undefined && from > to) error = message('search.filters.size-order');
      else next = { ...next, ...(from === undefined ? {} : { sizeFromBytes: from }), ...(to === undefined ? {} : { sizeToBytes: to }),
        ...(minimumChanged ? (from === undefined ? {} : { sizeFromInput: { text: this.text.minimum, unit: this.fromUnit } })
          : current.sizeFromInput ? { sizeFromInput: current.sizeFromInput } : {}),
        ...(maximumChanged ? (to === undefined ? {} : { sizeToInput: { text: this.text.maximum, unit: this.toUnit } })
          : current.sizeToInput ? { sizeToInput: current.sizeToInput } : {}) };
    }
    this.errors = { ...this.errors, [kind]: error };
    if (error === '') setFilters(next);
  }
  private clear(kind?: RestrictionKind): void {
    const kinds: RestrictionKind[] = kind ? [kind] : ['folder', 'date', 'size'];
    for (const k of kinds) {
      this.errors = { ...this.errors, [k]: '' };
      if (k === 'folder') this.text = { ...this.text, folder: '' };
      if (k === 'date') this.text = { ...this.text, from: '', to: '' };
      if (k === 'size') this.text = { ...this.text, minimum: '', maximum: '' };
    }
    setFilters(kind ? withoutRestriction(getFilters(), kind) : {});
  }
  private field(key: keyof Sv3Filters['text'], kind: RestrictionKind, type = 'text'): TemplateResult {
    const error = this.error(kind);
    return html`<input data-filter-input=${key} type=${type} .value=${this.text[key]}
      aria-label=${message(`search.filters.${key}`)}
      aria-invalid=${error ? 'true' : 'false'} aria-describedby=${`${kind}-hint ${kind}-error`}
      @input=${(e: Event) => { this.text = { ...this.text, [key]: (e.target as HTMLInputElement).value }; }}
      @change=${() => { if (type === 'date') this.commit(kind, key); }} @blur=${() => this.commit(kind, key)}
      @keydown=${(e: KeyboardEvent) => { if (e.key === 'Enter') { e.preventDefault(); e.stopPropagation(); this.commit(kind, key); } }}>`;
  }
  private unit(end: boolean): TemplateResult {
    return html`<select aria-label=${message(end ? 'search.filters.maximum-unit' : 'search.filters.minimum-unit')}
      .value=${end ? this.toUnit : this.fromUnit} @change=${(e: Event) => {
        if (end) this.toUnit = (e.target as HTMLSelectElement).value as SizeUnit;
        else this.fromUnit = (e.target as HTMLSelectElement).value as SizeUnit;
        this.commit('size', end ? 'maximum' : 'minimum');
      }}>${Object.keys(SIZE_UNITS).map(unit => html`<option value=${unit}>${unit}</option>`)}</select>`;
  }
  override render(): TemplateResult {
    const labels = restrictionLabels(this.spec);
    const chips = labels.length ? html`<div class="chips" role="group" aria-label=${message('search.filters')}>
      ${labels.map(({kind, label}) => html`<button type="button" data-filter=${kind}
        title=${label} aria-label=${`${message('search.filters.remove')}: ${label}`} @click=${() => this.clear(kind)}>${label} ×</button>`)}
    </div>` : nothing;
    return html`${this.open ? html`<section class="panel" aria-labelledby="filters-heading" data-testid="sv3-filters-panel"
      @keydown=${(e: KeyboardEvent) => { if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); this.dispatchEvent(new CustomEvent('sv3-filters-close', { bubbles: true, composed: true })); } }}>
      <div class="heading"><h2 id="filters-heading">${message('search.filters')}</h2><button type="button" data-testid="sv3-filters-clear" @click=${() => this.clear()}>${message('search.filters.clear')}</button></div>
      ${chips}<div class="fields">
        <label class="folder">${message('search.filters.folder')}${this.field('folder', 'folder')}<span class="hint" id="folder-hint">${message('search.filters.folder-hint')}</span><p class="error" id="folder-error" role="alert">${this.error('folder')}</p></label>
        <label>${message('search.filters.modified')} · ${message('search.filters.from')}${this.field('from', 'date', 'date')}</label>
        <label>${message('search.filters.modified')} · ${message('search.filters.to')}${this.field('to', 'date', 'date')}</label>
        <div class="folder hint" id="date-hint">${message('search.filters.date-hint')}<p class="error" id="date-error" role="alert">${this.error('date')}</p></div>
        <label>${message('search.filters.size')} · ${message('search.filters.minimum')}<span class="size-input">${this.field('minimum', 'size')}${this.unit(false)}</span></label>
        <label>${message('search.filters.size')} · ${message('search.filters.maximum')}<span class="size-input">${this.field('maximum', 'size')}${this.unit(true)}</span></label>
        <div class="folder hint" id="size-hint">${message('search.filters.size-hint')}<p class="error" id="size-error" role="alert">${this.error('size')}</p></div>
      </div>
      <p class="notes">${message('search.filters.intersection')} ${message('search.filters.context')} ${message('search.filters.session')}</p>
      ${this.note ? html`<p class="notes" role="status" data-testid="sv3-filter-note">${this.note}</p>` : nothing}
    </section>` : chips}`;
  }
}
customElements.define('jf-sv3-filters', Sv3Filters);
declare global { interface HTMLElementTagNameMap { 'jf-sv3-filters': Sv3Filters } }
