// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 629 — the read-only "Data protection" status card, extracted from HealthSurface so the new
 * Security & Privacy surface and Health can render the SAME card without duplicating its markup.
 *
 * Pure projection of the typed `/api/status` snapshot (`atRestProtection` + `conversationProtection`)
 * — no host, no fetch, no state. The card never over-claims: the index is only OS-disk-encrypted, an
 * unlocked window is readable, and configuration quality (TPM-only vs pre-boot PIN) shows
 * "unknown — needs admin" only where there is a real reason to believe elevation would help — never
 * as a blanket answer for every unsensed case (798: a machine with nothing encryptable, probed in an
 * already-elevated session, was still told to "get admin" — advice that cannot possibly help there).
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { icon } from '../../components/Icon.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';

export const atRestPillStyles = css`
  .at-rest-pill { float: right; padding: 0.125rem 0.5rem; border-radius: 0.25rem; font-weight: 500; }
    :host .at-rest-note { padding: 0.5rem 0; }
    :host .at-rest-disclosure { padding: 0.5rem 0; line-height: 1.5; }

`;

/** Card CSS for surfaces that don't already define `.card`/`.data-row` (e.g. the Security surface). */
export const atRestCardStyles = css`
  ${atRestPillStyles}
  .card {
    padding: 0.875rem;
    background: var(--surface-secondary);
    border: 1px solid var(--border-subtle);
    border-radius: 0.5rem;
  }
  .section h3 {
    margin: 0 0 0.5rem 0;
    font-size: var(--font-size-xs);
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--text-secondary);
    font-weight: 600;
  }
  .data-row {
    display: flex;
    justify-content: space-between;
    padding: 0.375rem 0;
    font-size: var(--font-size-sm);
    border-bottom: 1px solid var(--border-subtle);
  }
  .data-row:last-of-type {
    border-bottom: none;
  }
  .data-row .key {
    color: var(--text-secondary);
  }
  .data-row .val {
    color: var(--text-primary);
    font-family: monospace;
  }
  .empty {
    padding: 1rem;
    text-align: center;
    color: var(--text-secondary);
    font-size: var(--font-size-sm);
  }
`;

const NEUTRAL_PILL = 'background: var(--surface-2); color: var(--text-secondary);';

/**
 * The wording this card derives from one `diskEncryption` state (plus its provenance). Exported (and
 * pure) so the honesty rules below are assertable without rendering.
 *
 * Sandbox round 7: the `Configuration` row used to read "Unknown — needs admin" for EVERY state
 * whose quality could not be sensed, which recommended elevation in cases elevation cannot resolve.
 * Elevation reveals the TPM-only-vs-pre-boot-PIN distinction only where an encrypted (or encrypting)
 * volume is known to exist (ENCRYPTED / ENCRYPTING) — there the claim is a general architectural fact
 * about the OS APIs (`manage-bde` / `Get-BitLockerVolume` / WMI all require elevation), independent of
 * this session. Where the volume is plainly unencrypted, or cannot be OS-encrypted at all
 * (NOT_APPLICABLE), there is no configuration to learn and no admin to ask.
 *
 * 798: UNKNOWN needed a second look. Nothing in this codebase ever checks whether the *current*
 * process is elevated, so "needs admin" is never a verified claim — it must rest on the state alone
 * telling us a question exists. UNKNOWN splits into two wire-distinguishable shapes (source ·
 * confidence, mirroring the GPU-capability confidence axis, tempdoc 587):
 *   - `source === 'shell-property' && confidence === 'LOW'` — the probe ran, exited 0, and returned a
 *     value the OS itself calls indeterminate, or one this bundle doesn't recognise. A real BitLocker
 *     state may still be sitting behind that read, so elevation might resolve it.
 *   - `source === 'none'` — the probe produced no answer at all (non-Windows, execution failure, or
 *     the property was absent — e.g. no BitLocker feature present, the "nothing encryptable" shape).
 *     There is no basis to believe an encrypted volume exists here, so offering elevation repeats the
 *     original bug. An unrecognised future state (no source/confidence supplied) gets the same,
 *     more conservative answer: make no claim about elevation in either direction.
 */
export function atRestPresentation(
  state: string,
  qualityKnown: boolean,
  source?: string,
  confidence?: string,
): { pillLabel: string; pillCss: string; storeStatus: string; configuration: string } {
  const pill =
    state === 'ENCRYPTED'
      ? {
          label: 'Encrypted',
          css: 'background: var(--accent-success-16); color: var(--text-success);',
        }
      : state === 'NOT_ENCRYPTED'
        ? {
            label: 'Not encrypted',
            css: 'background: var(--accent-warning-16); color: var(--text-warning);',
          }
        : state === 'ENCRYPTING'
          ? { label: 'Encrypting…', css: NEUTRAL_PILL }
          : state === 'NOT_APPLICABLE'
            ? { label: 'Not applicable', css: NEUTRAL_PILL }
            : { label: 'Unknown', css: NEUTRAL_PILL };
  const storeStatus =
    state === 'ENCRYPTED'
      ? 'Protected by OS disk encryption'
      : state === 'NOT_ENCRYPTED'
        ? 'Not encrypted'
        : state === 'NOT_APPLICABLE'
          ? 'This volume cannot be encrypted by the OS'
          : 'Unknown';
  // An allowlist, not an exclusion (798): an unrecognized state (a newer backend than this bundle, or
  // an UNKNOWN with no positive signal that a real encrypted volume exists) makes no claim about
  // elevation and reads "Not applicable" — the conservative default when we cannot verify the claim.
  const probedButIndeterminate =
    state === 'UNKNOWN' && source === 'shell-property' && confidence === 'LOW';
  const elevationCouldResolveIt = state === 'ENCRYPTED' || state === 'ENCRYPTING' || probedButIndeterminate;
  const configuration = qualityKnown
    ? 'Known'
    : elevationCouldResolveIt
      ? 'Unknown — needs admin'
      : 'Not applicable';
  return { pillLabel: pill.label, pillCss: pill.css, storeStatus, configuration };
}

/** Render the Data-protection card from the status snapshot, or `nothing` if at-rest data is absent. */
export function renderAtRestCard(status: StatusSnapshot | null): TemplateResult | typeof nothing {
  const ar = status?.atRestProtection;
  if (!ar) return nothing;
  const state = ar.diskEncryption ?? 'UNKNOWN';
  const notEncrypted = state === 'NOT_ENCRYPTED';
  const { pillLabel, pillCss, storeStatus, configuration } = atRestPresentation(
    state,
    ar.qualityKnown ?? false,
    ar.source,
    ar.confidence,
  );
  // Conversations carry an additional app-encryption dimension (passphrase), read from the REACTIVE
  // status field so the row can never go stale (629 FE design §4).
  const convState = status?.conversationProtection?.state;
  const convLabel =
    convState === 'unlocked'
      ? 'Encrypted (passphrase) · unlocked'
      : convState === 'locked'
        ? 'Encrypted (passphrase) · locked'
        : storeStatus;
  return html`
    <div class="card section">
      <h3>${icon({ name: 'shield', size: 12 })} Data protection
        <span
          class="at-rest-pill" .style=${pillCss}
          >${pillLabel}</span
        >
      </h3>
      <div class="data-row">
        <span class="key">Disk encryption</span>
        <span class="val">${pillLabel}</span>
      </div>
      ${ar.source && ar.source !== 'none'
        ? html`
            <div class="data-row">
              <span class="key">Source</span>
              <span class="val">${ar.source}${ar.confidence ? html` · ${ar.confidence}` : nothing}</span>
            </div>
          `
        : nothing}
      <div class="data-row">
        <span class="key">Configuration</span>
        <span class="val">${configuration}</span>
      </div>
      <div class="data-row">
        <span class="key">Index</span>
        <span class="val">${storeStatus}</span>
      </div>
      <div class="data-row">
        <span class="key">Conversations</span>
        <span class="val">${convLabel}</span>
      </div>
      ${notEncrypted
        ? html`<div class="empty at-rest-note">
            Turn on device encryption (BitLocker with a startup PIN on Windows) to protect your data at
            rest. Note: Windows backs the recovery key up to your Microsoft account by default.
          </div>`
        : nothing}
      <!-- 629 (#12) / F-7: honest "what's protected vs not" disclosure, projected from the typed status.
           Scoped forward-only (727 F-7): chat encryption is encrypt-on-write, with no backfill of
           pre-setup content, and agent-run transcripts saved for search are never passphrase-encrypted
           regardless of this setting (AgentHistoryIndexer writes plaintext .md, not covered by
           DataKeyManager). -->
      <div class="empty at-rest-disclosure">
        <strong>What this protects:</strong> your chat history and memories are encrypted with your
        passphrase${convState === 'unlocked'
          ? ' — readable right now because they are unlocked'
          : convState === 'locked'
            ? ' and are currently locked'
            : ' once you set up chat encryption'}.
        This only covers what's written from setup onward — anything on disk from before you set up
        encryption stays as plaintext.
        Agent-run transcripts saved for search are <em>not</em> passphrase-encrypted, regardless of this
        setting. Your search index is <em>not</em> passphrase-encrypted either — it is protected only by
        your device's disk encryption (above); the document index rebuilds from your original files, but
        agent-run transcripts do not (there is no source file to rebuild them from).
      </div>
    </div>
  `;
}
