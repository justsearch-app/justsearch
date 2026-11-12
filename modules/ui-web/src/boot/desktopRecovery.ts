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

/** Linux keeps this webview alive while apt runs, including its lock wait: the app gives way to it. */
function holdsLinuxUpdate(state: SupervisorState | null): boolean {
  return /Linux/.test(navigator.platform) && state?.state === 'stopped' && state.reason === 'upgrade';
}

function needsRecovery(state: SupervisorState | null): boolean {
  // Windows hands presentation to NSIS and exits, so preserve its existing upgrade-stop presentation.
  return isTerminal(state) || !!state?.notReady || holdsLinuxUpdate(state);
}

/**
 * The one answer to "is the app usable?" (S5c, F-desktop B2 FD1): the application `mountBootApplication`
 * attached after boot is still in the root and its start-up cover has left, because its first surface
 * (the top bar) has rendered and painted, or could not paint because the window is hidden. From then on
 * engine loss shows inside the app (the engine-loss treatment keeps the top bar, Health and the report),
 * except for the Linux update hold; before, the recovery page owns loss.
 */
const usableApplications = new WeakMap<HTMLElement, HTMLElement>();
function appIsUsable(root: HTMLElement): boolean {
  return usableApplications.get(root)?.parentElement === root;
}

/**
 * Two animation frames let the first rendered surface paint before its cover leaves. A hidden window
 * (started to the tray, minimized) runs no animation frame and paints nothing, so there is nothing to
 * wait for: the app paints directly when the window is shown.
 */
function firstPaint(): Promise<void> {
  return new Promise<void>((resolve) => {
    const done = (): void => { document.removeEventListener('visibilitychange', onVisibility); resolve(); };
    const onVisibility = (): void => { if (document.visibilityState === 'hidden') done(); };
    if (document.visibilityState === 'hidden') { resolve(); return; }
    document.addEventListener('visibilitychange', onVisibility);
    requestAnimationFrame(() => requestAnimationFrame(done));
  });
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
  const frame = frames.get(root) ?? root.querySelector<HTMLElement>('#boot-frame');
  if (root.querySelector('#boot-failure') || frame?.parentElement !== root) {
    application.inert = false;
    root.replaceChildren(application);
    usableApplications.set(root, application);
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
      await firstPaint();
      if (application.parentElement !== root || owner && needsRecovery(owner.recovery.supervisor)) return;
      // Recovery/reset may have replaced both nodes while Lit was pending. Never reclaim their root.
      if (frame.parentElement === root) {
        // Remove only the cover. Re-inserting the application would disconnect it, and a modal
        // <dialog> it opened while covered (Settings restored at launch) would leave the top layer.
        for (const node of [...root.childNodes]) if (node !== application) node.remove();
        releaseInput();
        application.inert = false;
        usableApplications.set(root, application);
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
  /** Show the recovery page in place of whatever the root presents (the frame, or the app before it is usable). */
  const present = (): void => {
    // Store-owned subscriptions outlive shell listeners, so release them explicitly.
    teardown();
    if (recovery.parentElement !== root) root.replaceChildren(recovery);
  };
  let waitingForApi = false;
  await installSupervisorStateBridge((state) => {
    recovery.supervisor = state;
    reportSupervisorState(state);
    if (needsRecovery(state) && !(appIsUsable(root) && !holdsLinuxUpdate(state))) {
      present();
      waitingForApi = true;
    }
    // Initial boot has no backend-restart event. If it finishes after API discovery gave up,
    // retry discovery through the normal entry point when the host announces it is running.
    if (waitingForApi && state.state === 'running' && !state.notReady) window.location.reload();
  });
  await installBackendRestartBridge(() => window.location.reload());
  // While discovery runs the start-up frame stays ("Starting"); a reload that rebinds to a restarted
  // engine looks like the app it replaces. The recovery page shows only when there is something to
  // recover from: a supervisor state that needs it, or no engine found ("Connecting to JustSearch",
  // until the host reports the engine running).
  const base = await resolveBootApiBase();
  if (base && !needsRecovery(recovery.supervisor)) {
    if (recovery.parentElement === root) {
      recovery.remove();
      const frame = frames.get(root);
      if (frame && !root.hasChildNodes()) root.appendChild(frame);
    }
  } else {
    present();
    waitingForApi = true;
  }
  return base;
}
