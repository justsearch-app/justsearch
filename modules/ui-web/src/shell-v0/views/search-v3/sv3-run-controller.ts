// SPDX-License-Identifier: Apache-2.0
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import { subscribeAgentSession } from '../../state/agentSessionStore.js';
import type { Sv3RunDomain } from './sv3-run-domain.js';

/** Subscribing observes the shared run without constructing it or starting polling. */
export class Sv3RunController implements ReactiveController {
  private unsubscribe: (() => void) | null = null;

  constructor(host: ReactiveControllerHost, private readonly domain: Sv3RunDomain) {
    host.addController(this);
  }

  hostConnected(): void {
    this.unsubscribe ??= subscribeAgentSession(this.domain.onAgentUpdate);
  }

  hostDisconnected(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
  }
}
