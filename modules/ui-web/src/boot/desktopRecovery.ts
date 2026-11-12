// SPDX-License-Identifier: Apache-2.0
import { isTauriRuntime } from '../utils/tauriRuntime.js';
import { installSupervisorStateBridge, isTerminal, type SupervisorState } from '../api/supervisorState.js';
import type { EngineRecovery } from '../shell-v0/components/EngineRecovery.js';
import { installBackendRestartBridge } from '../api/backendRestart.js';
import { resolveBootApiBase } from './apiBase.js';
import { gateFactoryReset } from './factoryReset.js';
import { reportSupervisorState } from '../shell-v0/state/engineLoss.js';

const frames = new WeakMap<HTMLElement, HTMLElement>();

const recoverySurfaces = new WeakMap<HTMLElement, {
  recovery: EngineRecovery;
  teardown: () => void;
}>();
/** The application `mountBootApplication` presented in each root. */
const applications = new WeakMap<HTMLElement, HTMLElement>();

/** Linux keeps this webview alive while apt runs, including its lock wait: the app gives way to it. */
function holdsLinuxUpdate(state: SupervisorState | null): boolean {
  return /Linux/.test(navigator.platform) && state?.state === 'stopped' && state.reason === 'upgrade';
}

function needsRecovery(state: SupervisorState | null): boolean {
  // Windows hands presentation to NSIS and exits, so preserve its existing upgrade-stop presentation.
  return isTerminal(state) || !!state?.notReady || holdsLinuxUpdate(state);
}

/**
 * S5c — once the application is on screen and takes input, engine loss is shown inside it (the
 * engine-loss treatment keeps the top bar, Health and the report), except for the Linux update hold.
 * While the start-up frame still covers it, the recovery page owns loss, as before the app loaded.
 */
function presentsApplication(root: HTMLElement): boolean {
  const application = applications.get(root);
  return !!application && application.parentElement === root && !application.inert;
}

/** Keep supervisor recovery presentation authoritative while asynchronous shell boot finishes. */
export function mountBootApplication(root: HTMLElement, application: HTMLElement): void {
  const owner = recoverySurfaces.get(root);
  let presentation = application;
  if (owner && needsRecovery(owner.recovery.supervisor)) {
    owner.teardown();
    presentation = owner.recovery;
  }
  if (presentation !== application) {
    if (root.firstChild !== presentation || root.childNodes.length !== 1) root.replaceChildren(presentation);
    return;
  }
  applications.set(root, application);
  const frame = frames.get(root) ?? root.querySelector<HTMLElement>('#boot-frame');
  if (root.querySelector('#boot-failure') || frame?.parentElement !== root) {
    application.inert = false;
    root.replaceChildren(application);
    return;
  }
  // Attach beneath the fixed paint. Lit's first render, including the top bar, owns hand-off.
  application.inert = true;
  // Inert excludes element targets, but shell shortcuts and drag/navigation listeners also
  // run on window/document. Capture at window before attachment registers those listeners.
  const inputTypes = ['keydown', 'keyup', 'keypress', 'pointerdown', 'pointerup', 'pointermove', 'pointercancel',
    'mousedown', 'mouseup', 'click', 'wheel', 'touchstart', 'input', 'dragenter', 'dragover', 'dragleave', 'drop',
    'mouseenter', 'mouseleave', 'scroll', 'contextmenu', 'paste'] as const;
  const releaseInput = (): void => {
    for (const type of inputTypes) window.removeEventListener(type, guardInput, true);
    observer.disconnect();
  };
  const stillCovered = (): boolean => frame.parentElement === root && application.parentElement === root;
  const guardInput = (event: Event): void => {
    if (!stillCovered()) { releaseInput(); return; }
    // Keep browser/OS defaults (including reload); nothing under the frame receives input.
    event.stopImmediatePropagation();
  };
  const observer = new MutationObserver(() => { if (!stillCovered()) releaseInput(); });
  observer.observe(root, { childList: true });
  for (const type of inputTypes) window.addEventListener(type, guardInput, true);
  root.appendChild(application);
  const rendered = application as HTMLElement & { updateComplete?: Promise<boolean> };
  if (!rendered.updateComplete) return; // A never-rendering shell remains covered until the deadline.
  void (async () => {
    try {
      while (await rendered.updateComplete === false) { /* Lit scheduled a subsequent render. */ }
      const bar = application.shadowRoot?.querySelector('jf-top-bar') as (HTMLElement & { updateComplete?: Promise<boolean> }) | null;
      if (!bar) return;
      if (bar.updateComplete) await bar.updateComplete;
      // Two animation frames let the first rendered surface paint before its cover leaves.
      await new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
      if (application.parentElement !== root || owner && needsRecovery(owner.recovery.supervisor)) return;
      // Recovery/reset may have replaced both nodes while Lit was pending. Never reclaim their root.
      if (frame.parentElement === root) {
        // Remove only the cover. Re-inserting the application would disconnect it, and a modal
        // <dialog> it opened while covered (Settings restored at launch) would leave the top layer.
        for (const node of [...root.childNodes]) if (node !== application) node.remove();
        releaseInput();
        application.inert = false;
      }
    } catch { window.__justsearchBootFailure?.('bootstrap'); }
  })();
}

/** Resolve the API only after desktop recovery events can reach the boot surface. */
export async function resolveBootWithRecovery(
  root: HTMLElement,
  teardown: () => void = () => {},
): Promise<string | null> {
  const frame = root.querySelector<HTMLElement>('#boot-frame');
  if (frame) frames.set(root, frame);
  if (!isTauriRuntime()) {
    const base = await resolveBootApiBase();
    if (!base) {
      window.__justsearchBootFailure?.('no-engine-address');
    }
    return base;
  }

  if (!await gateFactoryReset(root)) return null;

  const { EngineRecovery } = await import('../shell-v0/components/EngineRecovery.js');
  const recovery = new EngineRecovery();
  recoverySurfaces.set(root, { recovery, teardown });
  root.replaceChildren(recovery);
  let waitingForApi = false;
  await installSupervisorStateBridge((state) => {
    recovery.supervisor = state;
    reportSupervisorState(state);
    if (needsRecovery(state) && !(presentsApplication(root) && !holdsLinuxUpdate(state))) {
      // Store-owned subscriptions outlive shell listeners, so release them explicitly.
      teardown();
      if (recovery.parentElement !== root) root.replaceChildren(recovery);
      waitingForApi = true;
    }
    // Initial boot has no backend-restart event. If it finishes after API discovery gave up,
    // retry discovery through the normal entry point when the host announces it is running.
    if (waitingForApi && state.state === 'running' && !state.notReady) window.location.reload();
  });
  await installBackendRestartBridge(() => window.location.reload());
  const base = await resolveBootApiBase();
  if (base && !needsRecovery(recovery.supervisor)) {
    if (recovery.parentElement === root) {
      recovery.remove();
      const frame = frames.get(root);
      if (frame && !root.hasChildNodes()) root.appendChild(frame);
    }
  }
  else waitingForApi = true;
  return base;
}
