// SPDX-License-Identifier: Apache-2.0
/**
 * ModalController — tempdoc 574 §22.G (Move 4: the FULL modal contract, BY CONSTRUCTION).
 *
 * §22.D's `check-modality-contract` gate caught a raw `showModal()` that did not COMPOSE a
 * `ModalityController` — but it could not check that `enter()`/`exit()` were actually CALLED, so a modal
 * could compose the controller yet still be half-wired (showModal without scroll-lock / focus-restore).
 * This controller closes that hole: it bundles the platform half (native `<dialog>.showModal()/close()` →
 * focus-trap + background `inert` + Top Layer) with the {@link ModalityController} half (scroll-lock +
 * focus-restore) into one atomic {@link open}/{@link close}, so a modal host composes ONE controller and
 * gets the WHOLE contract by construction — the modal sibling of `TransientController`.
 */
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import { ModalityController } from './modality.js';

export interface ModalControllerOptions {
  /** Getter for the managed `<dialog>` (queried lazily — it renders after the host's first update). */
  dialog: () => HTMLDialogElement | null | undefined;
  /**
   * Non-blocking overlay (e.g. AuthorizationHost's lightweight low/medium-risk path): `open()` uses
   * `dialog.show()` and SKIPS the scroll-lock + focus-save, so the page stays interactive. `close()` is
   * unchanged (`modality.exit()` is idempotent — a no-op when never entered).
   */
  nonBlocking?: boolean;
  /** Optional hook fired right after the dialog opens (e.g. focus a specific child via rAF). */
  onOpened?: () => void;
}

export class ModalController implements ReactiveController {
  private readonly modality: ModalityController;

  constructor(
    host: ReactiveControllerHost,
    private readonly opts: ModalControllerOptions,
  ) {
    host.addController(this);
    this.modality = new ModalityController(host);
  }

  /**
   * Open the modal: save focus + lock background scroll (unless non-blocking), then `showModal()` (or
   * `show()`). The full contract fires together, so a showModal without the modality half is unwritable
   * for adopters. Idempotent (no-op if the dialog is already open or not yet rendered).
   */
  open(opts?: { nonBlocking?: boolean }): void {
    const dlg = this.opts.dialog();
    if (!dlg || dlg.open) return;
    // Per-call override wins (AuthorizationHost decides modal-vs-lightweight per ceremony on one dialog).
    const nonBlocking = opts?.nonBlocking ?? this.opts.nonBlocking ?? false;
    if (nonBlocking) {
      dlg.show();
    } else {
      this.modality.enter();
      dlg.showModal();
    }
    this.opts.onOpened?.();
  }

  /**
   * Arm the focus-save + scroll-lock BEFORE the render that will autofocus a child (the residue-#5
   * timing — capturing in the post-render `open()` would grab the autofocused field, not the invoker).
   * Idempotent: a following `open()` will not re-enter. Only a modal whose content autofocuses on render
   * (ElicitHost's form) needs this; the rest just call `open()`.
   */
  captureFocus(): void {
    this.modality.enter();
  }

  /**
   * Close the modal: close the dialog, then release scroll-lock + restore focus. Idempotent.
   * A dialog the platform already closed (Escape, `dialog.close()`) still releases its modality
   * here, so a host's `close` listener can end the contract the same way its own close does.
   * `skipFocusRestore` passes straight through to {@link ModalityController.exit} (855 §11.2
   * merge-blocker) — for a navigation-initiated close where the address already moved elsewhere.
   */
  close(opts?: { skipFocusRestore?: boolean }): void {
    const dlg = this.opts.dialog();
    if (dlg?.open) dlg.close();
    this.modality.exit(opts);
  }

  hostDisconnected(): void {
    // A modal torn down while open must not leak the scroll-lock.
    this.modality.exit();
  }
}
