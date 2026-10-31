// SPDX-License-Identifier: Apache-2.0
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import { subscribeProfileSwitch } from '../../state/UserStateDocument.js';
import { subscribeProfiles, getActiveProfileId as getWorkspaceProfileId } from '../../substrates/profiles/index.js';
import type { Sv3SessionsDomain } from './sv3-sessions-domain.js';

/** Browser notifications, connected explicitly at the view's existing lifecycle positions. */
export class Sv3SessionsController implements ReactiveController {
  private profileUnsubscribe: (() => void) | null = null;

  constructor(host: ReactiveControllerHost, private readonly domain: Sv3SessionsDomain) {
    host.addController(this);
  }

  connectDraft(): void { window.addEventListener('storage', this.domain.releaseDraftOwnership); }
  hostDisconnected(): void { window.removeEventListener('storage', this.domain.releaseDraftOwnership); }

  connect(): void {
    this.domain.connect();
    const reset = (): void => this.domain.resetProfileRecents();
    const stopUserProfile = subscribeProfileSwitch(reset);
    let workspaceProfile = getWorkspaceProfileId();
    const stopWorkspaceProfile = subscribeProfiles(() => {
      const next = getWorkspaceProfileId();
      if (next === workspaceProfile) return;
      workspaceProfile = next;
      reset();
    });
    this.profileUnsubscribe = () => { stopUserProfile(); stopWorkspaceProfile(); };
    this.domain.loadConversations();
  }

  disconnect(): void {
    this.profileUnsubscribe?.();
    this.profileUnsubscribe = null;
    this.domain.disconnect();
  }
}
