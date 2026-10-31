// SPDX-License-Identifier: Apache-2.0
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import type { Sv3AskDomain } from './sv3-ask-domain.js';
import type { Sv3SessionList } from './sv3-sessions.js';

/** Re-project retained streams when the rendered session list changes. */
export class Sv3AskController implements ReactiveController {
  private previousSessions: Sv3SessionList | undefined;

  constructor(host: ReactiveControllerHost, private readonly domain: Sv3AskDomain,
    private readonly sessions: () => Sv3SessionList) {
    host.addController(this);
  }

  hostUpdate(): void {
    const sessions = this.sessions();
    if (sessions === this.previousSessions) return;
    this.previousSessions = sessions;
    this.domain.syncActiveAsk();
  }
}
