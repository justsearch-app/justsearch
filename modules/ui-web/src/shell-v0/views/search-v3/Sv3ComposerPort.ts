// SPDX-License-Identifier: Apache-2.0
import { ComposerPort, type ComposerCapture } from '../../state/composerPort.js';
import type { Sv3Composer } from './Sv3Composer.js';

/** View-side binding: the element keeps assignment events, committed edits, focus and caret. */
export class Sv3ComposerPort {
  private seed = '';
  private seedPending = false;
  private readonly ports = new WeakMap<Sv3Composer, ComposerPort>();
  // Before render there is no composer incarnation or edit counter. Keep the existing seed
  // semantics, including optional clear being a no-op until the element exists.
  private readonly unmounted = new ComposerPort({
    read: () => ({ text: this.seed, edit: undefined }),
    isCurrent: () => this.composer() === null,
    replace: text => { this.seed = text; this.seedPending = true; },
    clear: () => {},
  });

  constructor(private readonly composer: () => Sv3Composer | null) {}

  private current(): ComposerPort {
    const composer = this.composer();
    if (composer === null) return this.unmounted;
    let port = this.ports.get(composer);
    if (port === undefined) {
      port = new ComposerPort({
        read: () => ({ text: composer.draft, edit: composer.draftVersion }),
        isCurrent: () => this.composer() === composer,
        replace: text => { composer.draft = text; },
        clear: () => composer.clearDraft(),
      });
      this.ports.set(composer, port);
    }
    return port;
  }

  snapshot(): ComposerCapture { return this.current().snapshot(); }
  read(): string { return this.snapshot().text; }
  replace(text: string): void { this.current().replace(text); }
  clear(): void { this.current().clear(); }

  /** First render consumes the last seed exactly once; later renders cannot overwrite typing. */
  flushSeed(): void {
    if (!this.seedPending) return;
    const composer = this.composer();
    if (composer === null) return;
    this.seedPending = false;
    composer.draft = this.seed;
  }
}
