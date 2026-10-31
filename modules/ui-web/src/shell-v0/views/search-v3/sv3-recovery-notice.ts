// SPDX-License-Identifier: Apache-2.0
import { html, nothing, type TemplateResult } from 'lit';
import { retryWording } from '../../state/chatResidency.js';
import type { HumanTask, Sv3DraftDomain } from './sv3-draft-domain.js';

/** The retained task's controls follow its delivery evidence and local stream ownership. */
export function retainedTaskNotice(task: HumanTask | null, draft: Sv3DraftDomain, live: boolean,
  streaming: boolean, check: () => void): TemplateResult | typeof nothing {
  return task === null ? nothing : html`<p>${task.saved.delivery === 'unknown'
        ? live ? 'Request in progress in its conversation.' : 'Delivery unknown—check the saved conversation before making a new submission.'
        : task.saved.delivery === 'unaccepted' ? 'Question not sent.' : 'Review the retained question before a new submission.'}</p>
        <pre>${task.saved.raw || (task.saved.record ? 'Saved question: ' + task.saved.record : '')}</pre>
        <p>${task.refusal?.message ?? ''} ${task.refusal ? retryWording(task.refusal) : ''}</p>
        ${task.saved.needsReselection || task.restored ? html`<p>Review the original mode, schema and files. Reselect any passage before sending. Restored copies never send automatically.</p>` : nothing}
        ${draft.humanOwned(task) && task.saved.delivery === 'unaccepted' && task.refusal !== null ? html`<button type="button" data-testid="sv3-residency-retry" ?disabled=${draft.humanLoad !== null || streaming || Date.now() < task.retryAt}
          @click=${() => { void draft.continueHumanTask(false); }}>Retry</button>
          ${task.refusal?.forceAllowed === true ? html`<button type="button" data-testid="sv3-residency-force" ?disabled=${draft.humanLoad !== null || streaming}
            @click=${() => { void draft.continueHumanTask(true); }}>Load anyway</button>` : nothing}` : nothing}
        ${draft.humanLoad !== null ? html`<button type="button" data-testid="sv3-residency-stop" @click=${() => { draft.revokeHumanTask(); draft.saveRecovery(); }}>Stop waiting</button>` : nothing}
        ${task.saved.delivery === 'unknown' && task.saved.dispatchTurnId !== undefined && !live
          ? html`<button type="button" data-testid="sv3-residency-check" @click=${() => {
            check();
          }}>Check again</button>` : nothing}
        <button type="button" data-testid="sv3-residency-discard" ?disabled=${streaming}
          @click=${() => draft.discardHumanTask()}>Discard retained task; allow a new submission</button>`;
}
