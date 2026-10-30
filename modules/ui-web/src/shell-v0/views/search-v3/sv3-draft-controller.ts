// SPDX-License-Identifier: Apache-2.0
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import type { Sv3DraftDomain } from './sv3-draft-domain.js';

/** Browser lifecycle signals, connected at the view's existing save/revocation points. */
export class Sv3DraftController implements ReactiveController {
  constructor(host: ReactiveControllerHost, private readonly domain: Sv3DraftDomain) {
    host.addController(this);
  }

  private readonly hide = (): void => {
    if (document.visibilityState === 'hidden') this.domain.leaveHumanTask();
  };

  connect(): void {
    document.addEventListener('visibilitychange', this.hide);
    window.addEventListener('pagehide', this.domain.leaveHumanTask);
  }

  disconnect(): void {
    document.removeEventListener('visibilitychange', this.hide);
    window.removeEventListener('pagehide', this.domain.leaveHumanTask);
  }

  hostDisconnected(): void { this.disconnect(); }
}
