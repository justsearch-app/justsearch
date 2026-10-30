// SPDX-License-Identifier: Apache-2.0
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import type { Sv3ContextDomain } from './sv3-context-domain.js';

/** The browser's online notification; initial retry stays at the view's existing connect point. */
export class Sv3ContextController implements ReactiveController {
  constructor(host: ReactiveControllerHost, private readonly domain: Sv3ContextDomain) {
    host.addController(this);
  }

  connect(): void {
    window.addEventListener('online', this.domain.retryContextOnReconnect);
    this.domain.retryContextOnReconnect();
  }

  hostDisconnected(): void {
    window.removeEventListener('online', this.domain.retryContextOnReconnect);
  }
}
