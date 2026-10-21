// SPDX-License-Identifier: Apache-2.0
/**
 * SecuritySurface — tempdoc 629 (remaining-work, 2026-06-23): the unified "Security & Privacy" rail
 * surface (the design's UX-1). It gives the whole at-rest story one home: the read-only data-protection
 * status (shared `renderAtRestCard`) above the chat-encryption control + recovery + encrypted
 * backup/import + auto-lock that previously lived inside Settings.
 *
 * The encryption control block was MOVED here verbatim from SettingsSurface (it was only coupled to
 * surface-generic deps — `host_.data.fetch`, localStorage, shadow-DOM ids — all available in any
 * JfElement surface). Settings now links here instead of hosting it.
 *
 * Side-effect registers `<jf-security-surface>` for the chrome dispatcher.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { surfaceScrollLayoutStyles } from '../primitives/surfaceLayout.js';
import { icon } from '../components/Icon.js';
import { readAutoLockMinutes, writeAutoLockMinutes } from '../utils/autoLock.js';
import { subscribeAiState, refreshStatusNow, type StatusSnapshot } from '../state/aiStateStore.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { renderAtRestCard, atRestCardStyles } from './security/atRestCard.js';
import '../components/Button.js';

export class SecuritySurface extends JfElement {
  static properties = {
    status: { state: true },
    encState: { state: true },
    encRecoveryKey: { state: true },
    encError: { state: true },
    encBusy: { state: true },
    encAwaitingRecoverySave: { state: true },
    encShowChangePass: { state: true },
    encRecoveryUnsaved: { state: true },
    encShowImport: { state: true },
    encImportSummary: { state: true },
  };

  // 609 surface-task-state-retention: transient flags/errors/buffers/ephemeral key reset on hide.
  // encRecoveryUnsaved is NOT here — it is localStorage-backed and must survive navigation (Fix 3).
  static transientState = {
    encError: null,
    encBusy: false,
    encShowChangePass: false,
    encShowImport: false,
    encImportSummary: null,
    encRecoveryKey: null,
    encAwaitingRecoverySave: false,
  };

  declare host_: PluginHostApi;
  declare status: StatusSnapshot | null;
  declare encState: string; // unknown | not_configured | locked | unlocked
  declare encRecoveryKey: string | null;
  declare encError: string | null;
  declare encBusy: boolean;
  declare encAwaitingRecoverySave: boolean;
  declare encShowChangePass: boolean;
  declare encRecoveryUnsaved: boolean;
  declare encShowImport: boolean;
  declare encImportSummary: string | null;

  private unsubAi: (() => void) | null = null;

  constructor() {
    super();
    this.host_ = undefined as unknown as PluginHostApi;
    this.status = null;
    this.encState = 'unknown';
    this.encRecoveryKey = null;
    this.encError = null;
    this.encBusy = false;
    this.encAwaitingRecoverySave = false;
    this.encShowChangePass = false;
    this.encRecoveryUnsaved = false;
    this.encShowImport = false;
    this.encImportSummary = null;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    // The at-rest status card projects the shared /api/status snapshot (atRestProtection +
    // conversationProtection) — one poll, never stale.
    this.unsubAi = subscribeAiState((s) => {
      this.status = s.status;
      // External lock/unlock and restarts reach the control through the canonical owner projection.
      this.encState = s.status?.conversationProtection?.state ?? 'unknown';
    });
    void this.loadEncryption();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubAi?.();
    this.unsubAi = null;
  }

  private doFetch(path: string, init?: RequestInit): Promise<Response> {
    return this.host_.data.fetch(path, {
      method: init?.method,
      headers: init?.headers as Record<string, string> | undefined,
      body: init?.body as string | undefined,
    });
  }

  private async loadEncryption(): Promise<void> {
    // Tempdoc 629 (Fix 3): the recovery-unsaved flag lives in localStorage so it survives navigation
    // (the surface unmounts). If the user set up encryption then clicked away without acknowledging the
    // recovery key, the reminder banner must re-appear on return.
    this.encRecoveryUnsaved = localStorage.getItem('enc-recovery-unsaved') === '1';
    try {
      const res = await this.doFetch('/api/conversations/encryption');
      if (res.ok) {
        this.encState = ((await res.json()) as { state?: string }).state ?? 'unknown';
      }
    } catch {
      /* leave as unknown */
    }
  }

  private encPost(path: string, body: Record<string, string>): Promise<Response> {
    return this.doFetch(`/api/conversations/encryption/${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  }

  private async setupEncryption(passphrase: string): Promise<void> {
    this.encError = null;
    if (passphrase.length < 8) {
      this.encError = 'Passphrase must be at least 8 characters.';
      return;
    }
    this.encBusy = true;
    try {
      const res = await this.encPost('setup', { passphrase });
      const data = (await res.json()) as { state?: string; recoveryKey?: string; error?: string };
      if (!res.ok) {
        this.encError = data.error ?? 'Setup failed.';
        return;
      }
      this.encState = data.state ?? 'unlocked';
      this.encRecoveryKey = data.recoveryKey ?? null;
      // Tempdoc 629 (#4): enter the REQUIRED recovery-save gate — setup is not "done" in the UI until
      // the user acknowledges they saved the key (Apple-ADP gating). The key can never be re-shown.
      this.encAwaitingRecoverySave = this.encRecoveryKey != null;
      if (this.encRecoveryKey != null) {
        this.encRecoveryUnsaved = true;
        localStorage.setItem('enc-recovery-unsaved', '1');
      }
    } finally {
      this.encBusy = false;
    }
  }

  // Tempdoc 629 (#4) — recovery & management ops on the unlocked store.
  private async regenerateRecovery(): Promise<void> {
    this.encError = null;
    this.encBusy = true;
    try {
      const res = await this.encPost('regenerate-recovery', {});
      const data = (await res.json()) as { recoveryKey?: string; error?: string };
      if (!res.ok) {
        this.encError = data.error ?? 'Could not regenerate the recovery key.';
        return;
      }
      this.encRecoveryKey = data.recoveryKey ?? null;
      this.encAwaitingRecoverySave = this.encRecoveryKey != null;
      if (this.encRecoveryKey != null) {
        this.encRecoveryUnsaved = true;
        localStorage.setItem('enc-recovery-unsaved', '1');
      }
    } finally {
      this.encBusy = false;
    }
  }

  private async changePassphrase(oldPass: string, newPass: string): Promise<void> {
    this.encError = null;
    if (newPass.length < 8) {
      this.encError = 'New passphrase must be at least 8 characters.';
      return;
    }
    this.encBusy = true;
    try {
      const res = await this.encPost('change-passphrase', {
        oldPassphrase: oldPass,
        newPassphrase: newPass,
      });
      const data = (await res.json()) as { state?: string; error?: string };
      if (!res.ok) {
        this.encError = data.error ?? 'Could not change the passphrase.';
        return;
      }
      this.encState = data.state ?? 'unlocked';
      this.encShowChangePass = false;
    } finally {
      this.encBusy = false;
    }
  }

  // Tempdoc 629 (#10) — persist the auto-lock idle timeout (minutes; 0 = off). The Shell's app-wide
  // watcher re-reads localStorage each tick, so the change takes effect without cross-component plumbing.
  private setAutoLock(minutes: number): void {
    writeAutoLockMinutes(minutes);
    this.requestUpdate();
  }

  private acknowledgeRecoverySaved(): void {
    this.encAwaitingRecoverySave = false;
    this.encRecoveryKey = null;
    // Tempdoc 629 (Fix 3): the user confirmed they saved it — clear the persistent reminder.
    this.encRecoveryUnsaved = false;
    localStorage.removeItem('enc-recovery-unsaved');
  }

  private async copyRecoveryKey(): Promise<void> {
    if (this.encRecoveryKey) await navigator.clipboard?.writeText(this.encRecoveryKey).catch(() => {});
  }

  private downloadEmergencyKit(): void {
    if (!this.encRecoveryKey) return;
    const text =
      'JustSearch — Chat encryption recovery key\n\n' +
      'Keep this safe. It is the ONLY way to recover your encrypted chat history if you forget\n' +
      'your passphrase. Your search index is unaffected (it rebuilds from your files).\n\n' +
      `Recovery key:\n${this.encRecoveryKey}\n`;
    const blob = new Blob([text], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'justsearch-recovery-kit.txt';
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }

  private async unlockEncryption(passphrase: string): Promise<void> {
    this.encError = null;
    this.encBusy = true;
    try {
      const res = await this.encPost('unlock', { passphrase });
      const data = (await res.json()) as { state?: string; error?: string };
      if (!res.ok) {
        this.encError = data.error ?? 'Unlock failed.';
        return;
      }
      this.encState = data.state ?? 'unlocked';
      // Tempdoc 727 F-8: the DATA PROTECTION row (shared `renderAtRestCard`) derives its
      // "Conversations: … unlocked/locked" text from the aiStateStore's POLLED /api/status
      // snapshot, not this response — without this, it disagreed with this panel until the next
      // scheduled poll (up to statusPoll's INTERVAL_MS) caught up.
      void refreshStatusNow();
    } finally {
      this.encBusy = false;
    }
  }

  private async lockEncryption(): Promise<void> {
    this.encBusy = true;
    try {
      const res = await this.encPost('lock', {});
      if (res.ok) {
        this.encState = ((await res.json()) as { state?: string }).state ?? 'locked';
        // Tempdoc 727 F-8: same state-source refresh as unlockEncryption — the symmetric
        // transition has the identical staleness mechanism.
        void refreshStatusNow();
      }
    } finally {
      this.encBusy = false;
    }
  }

  // Tempdoc 629 (#7) — download an encrypted, portable backup of authored data. Fetched with the session
  // token (a plain <a download> would not carry it), then delivered as a blob via an anchor click.
  private async exportBackup(): Promise<void> {
    this.encError = null;
    this.encBusy = true;
    try {
      // POST (not GET): the export streams the encrypted vault, and only POST carries the session token
      // (629 Fix 1). doFetch reaches host_.data.fetch, whose performFetch routes every non-GET through
      // shell-v0's authorizedFetch seam, which attaches the token (804 B3).
      const res = await this.doFetch('/api/conversations/encryption/export', { method: 'POST' });
      if (!res.ok) {
        this.encError = 'Could not export your backup.';
        return;
      }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'justsearch-backup.json';
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch {
      this.encError = 'Could not export your backup.';
    } finally {
      this.encBusy = false;
    }
  }

  // Tempdoc 629 (#E) — restore an encrypted backup: read the container file, POST it with the backup's
  // passphrase. The backend decrypts with the backup's own keystore and writes each store back (skip-
  // existing), re-sealing under the LOCAL data key. Requires the local store unlocked.
  private async importBackup(file: File, passphrase: string): Promise<void> {
    this.encError = null;
    this.encImportSummary = null;
    this.encBusy = true;
    try {
      let container: unknown;
      try {
        container = JSON.parse(await file.text());
      } catch {
        this.encError = 'That file is not a valid JustSearch backup.';
        return;
      }
      const res = await this.doFetch('/api/conversations/encryption/import', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ container, passphrase }),
      });
      const data = (await res.json()) as { restored?: Record<string, number>; skipped?: number; error?: string };
      if (!res.ok) {
        this.encError = data.error ?? 'Could not restore the backup.';
        return;
      }
      const restored = data.restored ?? {};
      const total = Object.values(restored).reduce((a, b) => a + (b || 0), 0);
      const skipped = data.skipped ?? 0;
      this.encImportSummary =
        total === 0
          ? skipped > 0
            ? 'Nothing new to restore.'
            : 'Nothing new to restore (everything in the backup already exists here).'
          : 'Restored ' +
            Object.entries(restored)
              .filter(([, v]) => (v || 0) > 0)
              .map(([k, v]) => `${v} ${k}`)
              .join(', ') +
            '.';
      if (skipped > 0) {
        this.encImportSummary += skipped === 1
          ? ' Skipped 1 entry with an invalid id.'
          : ` Skipped ${skipped} entries with invalid ids.`;
      }
      this.encShowImport = false;
    } catch {
      this.encError = 'Could not restore the backup.';
    } finally {
      this.encBusy = false;
    }
  }

  private encInputValue(): string {
    const el = this.shadowRoot?.querySelector('#enc-pass') as HTMLInputElement | null;
    return el?.value ?? '';
  }

  private renderChatProtection(): TemplateResult {
    const inputStyle =
      'padding:0.4rem 0.6rem; background:var(--surface-2); color:var(--text-secondary); border:1px solid var(--surface-3); border-radius:0.25rem';
    const row = 'display:flex; gap:0.5rem; margin-top:0.5rem; align-items:center; flex-wrap:wrap';
    let body: TemplateResult;
    if (this.encAwaitingRecoverySave && this.encRecoveryKey) {
      // Tempdoc 629 (#4): the REQUIRED recovery-save gate. Setup/regenerate is not "done" until the
      // user acknowledges — and the key cannot be re-shown (only its wrap is stored).
      body = html`
        <p class="help" style="margin-top:0">
          <strong>Save your recovery key now.</strong> It is the only way back into your chat history if
          you forget your passphrase, and it <strong>cannot be shown again</strong>. Your search index is
          unaffected.
        </p>
        <div
          style="margin-top:0.5rem; padding:0.6rem; background:var(--accent-warning-16); border-radius:0.25rem"
        >
          <code style="display:block; word-break:break-all; font-size:var(--font-size-md)" data-testid="enc-recovery-key"
            >${this.encRecoveryKey}</code
          >
        </div>
        <div style=${row}>
          <jf-button variant="secondary" .onActivate=${() => void this.copyRecoveryKey()}>Copy</jf-button>
          <jf-button variant="secondary" .onActivate=${() => this.downloadEmergencyKit()}
            >Download kit</jf-button
          >
          <jf-button .onActivate=${() => this.acknowledgeRecoverySaved()}
            >I've saved my recovery key</jf-button
          >
        </div>
      `;
    } else if (this.encState === 'not_configured') {
      body = html`
        <p class="help" style="margin-top:0">
          Encrypt new chat messages with a passphrase so they can't be read without unlocking. This only
          applies going forward — messages already saved stay unencrypted.
          <strong>If you forget the passphrase, your chat history is lost for good</strong> — your
          search index is unaffected (it rebuilds from your files). You'll save a recovery key next.
        </p>
        <div style=${row}>
          <input id="enc-pass" type="password" placeholder="Choose a passphrase" style=${inputStyle} />
          <jf-button
            ?disabled=${this.encBusy}
            .onActivate=${() => void this.setupEncryption(this.encInputValue())}
          >
            ${this.encBusy ? 'Setting up…' : 'Encrypt chat history'}
          </jf-button>
        </div>
      `;
    } else if (this.encState === 'locked') {
      body = html`
        <p class="help" style="margin-top:0">
          Your new chat messages are <strong>locked</strong>. Enter your passphrase to unlock them.
        </p>
        <div style=${row}>
          <input id="enc-pass" type="password" placeholder="Passphrase" style=${inputStyle} />
          <jf-button
            ?disabled=${this.encBusy}
            .onActivate=${() => void this.unlockEncryption(this.encInputValue())}
          >
            ${this.encBusy ? 'Unlocking…' : 'Unlock'}
          </jf-button>
        </div>
      `;
    } else if (this.encState === 'unlocked') {
      body = html`
        <p class="help" style="margin-top:0">
          Your new chat messages are <strong>encrypted and unlocked</strong>.
        </p>
        ${this.encRecoveryUnsaved
          ? html`<div
              style="margin-top:0.5rem; padding:0.6rem; background:var(--accent-warning-16); border-radius:0.25rem"
              role="status"
            >
              <strong>You haven't confirmed saving your recovery key.</strong> It can't be shown again
              — if you lose it and forget your passphrase, your chat history is gone. Use
              <em>Regenerate recovery key</em> to get a fresh one to save.
            </div>`
          : nothing}
        <div style=${row}>
          <jf-button
            variant="secondary"
            ?disabled=${this.encBusy}
            .onActivate=${() => void this.regenerateRecovery()}
            >Regenerate recovery key</jf-button
          >
          <jf-button
            variant="secondary"
            ?disabled=${this.encBusy}
            .onActivate=${() => (this.encShowChangePass = !this.encShowChangePass)}
            >Change passphrase</jf-button
          >
          <jf-button
            variant="secondary"
            ?disabled=${this.encBusy}
            .onActivate=${() => void this.exportBackup()}
            >Export encrypted backup</jf-button
          >
          <jf-button
            variant="secondary"
            ?disabled=${this.encBusy}
            .onActivate=${() => (this.encShowImport = !this.encShowImport)}
            >Import backup</jf-button
          >
          <jf-button variant="secondary" ?disabled=${this.encBusy} .onActivate=${() =>
            void this.lockEncryption()}>
            ${icon({ name: 'shield', size: 14 })} Lock now
          </jf-button>
        </div>
        ${this.encShowImport
          ? html`<div style=${row}>
              <input
                id="enc-import-file"
                type="file"
                accept="application/json,.json"
                style=${inputStyle}
              />
              <input
                id="enc-import-pass"
                type="password"
                placeholder="Backup's passphrase"
                style=${inputStyle}
              />
              <jf-button
                ?disabled=${this.encBusy}
                .onActivate=${() => {
                  const f = (
                    this.shadowRoot?.querySelector('#enc-import-file') as HTMLInputElement | null
                  )?.files?.[0];
                  const p =
                    (this.shadowRoot?.querySelector('#enc-import-pass') as HTMLInputElement | null)
                      ?.value ?? '';
                  if (f) void this.importBackup(f, p);
                  else this.encError = 'Choose a backup file to restore.';
                }}
                >${this.encBusy ? 'Restoring…' : 'Restore'}</jf-button
              >
            </div>`
          : nothing}
        ${this.encImportSummary
          ? html`<p class="help" style="margin-top:0.5rem; color:var(--text-secondary)">
              ${this.encImportSummary}
            </p>`
          : nothing}
        <div style=${row}>
          <label class="help" for="enc-autolock" style="margin:0"
            >Auto-lock after inactivity</label
          >
          <select
            id="enc-autolock"
            style=${inputStyle}
            .value=${String(readAutoLockMinutes())}
            @change=${(e: Event) =>
              this.setAutoLock(Number((e.target as HTMLSelectElement).value))}
          >
            <option value="0">Off</option>
            <option value="5">5 minutes</option>
            <option value="15">15 minutes</option>
            <option value="30">30 minutes</option>
          </select>
        </div>
        ${this.encShowChangePass
          ? html`<div style=${row}>
              <input id="enc-oldpass" type="password" placeholder="Current passphrase" style=${inputStyle} />
              <input id="enc-newpass" type="password" placeholder="New passphrase" style=${inputStyle} />
              <jf-button
                ?disabled=${this.encBusy}
                .onActivate=${() =>
                  void this.changePassphrase(
                    (this.shadowRoot?.querySelector('#enc-oldpass') as HTMLInputElement | null)?.value ??
                      '',
                    (this.shadowRoot?.querySelector('#enc-newpass') as HTMLInputElement | null)?.value ??
                      '',
                  )}
                >${this.encBusy ? 'Changing…' : 'Change'}</jf-button
              >
            </div>`
          : nothing}
      `;
    } else {
      body = html`<p class="help" style="margin-top:0">Checking encryption status…</p>`;
    }
    return html`
      <div class="section">
        <h3>${icon({ name: 'shield', size: 12 })} Chat encryption</h3>
        ${body}
        ${this.encError
          ? html`<p class="help" style="color:var(--text-danger); margin-top:0.5rem">${this.encError}</p>`
          : nothing}
      </div>
    `;
  }

  override render(): TemplateResult {
    return html`
      <div class="header">
        <div>
          <h2>Security &amp; Privacy</h2>
          <p class="subtitle">Encryption, backups, and what's protected at rest</p>
        </div>
      </div>
      <div class="body">
        ${renderAtRestCard(this.status)}
        ${this.renderChatProtection()}
      </div>
    `;
  }

  static styles = [
    surfaceScrollLayoutStyles,
    atRestCardStyles,
    css`
      .body {
        padding: 1rem 1.5rem;
        display: flex;
        flex-direction: column;
        gap: 1rem;
      }
      h2 {
        margin: 0;
        font-size: var(--font-size-lg);
      }
      .subtitle {
        margin: 0.25rem 0 0 0;
        font-size: var(--font-size-sm);
        color: var(--text-secondary);
      }
      /* Tempdoc 855 §15.1 — the flat row idiom (T1), matching SettingsSurface's restyle so the
         window ships one visual dialect (this section now renders INSIDE the settings window).
         Fix-round F2 — renderAtRestCard() (atRestCard.ts) renders <div class="card section">:
         a card SHARED with HealthSurface (atRestCardStyles' .card rule gives it full chrome).
         An unqualified .section here comes later in this element's static styles array than
         atRestCardStyles, so at equal specificity it WINS the cascade and clobbers that chrome —
         and since the at-rest card is the FIRST child of .body, an unconditional divider also
         drew a spurious top border above the very first section. :not(.card) excludes the shared
         card from Security's OWN flat treatment; :not(:first-child) (mirroring SettingsSurface's
         .section:not(:first-child) above) keeps the divider off whichever section renders first. */
      .section:not(.card) {
        padding: 0;
        background: transparent;
        border: none;
        border-radius: 0;
      }
      .section:not(.card):not(:first-child) {
        margin-top: 1.5rem;
        padding-top: 1.5rem;
        border-top: 1px solid var(--border-subtle);
      }
      .section h3 {
        margin: 0 0 0.75rem 0;
        font-size: var(--font-size-md);
        font-weight: 700;
        letter-spacing: normal;
        text-transform: none;
        color: var(--text-primary);
        display: flex;
        align-items: center;
        gap: 0.4rem;
      }
      p.help {
        margin: 0.5rem 0 0 0;
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
        line-height: 1.5;
      }
    `,
  ];
}

customElements.define('jf-security-surface', SecuritySurface);
