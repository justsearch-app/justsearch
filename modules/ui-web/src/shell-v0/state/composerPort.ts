// SPDX-License-Identifier: Apache-2.0
/** Draft ownership without a browser, element or event dependency. */
declare const editToken: unique symbol;
type ComposerEditToken = { readonly [editToken]: true };

export interface ComposerCapture {
  readonly port: ComposerPort;
  readonly text: string;
  readonly incarnation: symbol;
  readonly edit: ComposerEditToken;
}

interface ComposerSource {
  read(): { text: string; edit: unknown };
  isCurrent(): boolean;
  replace(text: string): void;
  clear(): void;
}

/** A port stays bound to its incarnation, even after the current composer changes. */
export class ComposerPort {
  private readonly incarnation = Symbol('composer');

  constructor(private readonly source: ComposerSource) {}

  snapshot(): ComposerCapture {
    const { text, edit } = this.source.read();
    return { port: this, text, incarnation: this.incarnation, edit: edit as ComposerEditToken };
  }

  isCurrent(capture: ComposerCapture): boolean {
    return capture.incarnation === this.incarnation && this.source.isCurrent()
      && this.source.read().edit === capture.edit;
  }

  replace(text: string): void { this.source.replace(text); }
  clear(): void { this.source.clear(); }

  clearIf(capture: ComposerCapture): boolean {
    if (!this.isCurrent(capture)) return false;
    this.clear();
    return true;
  }
}
