// SPDX-License-Identifier: Apache-2.0
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import type { Sv3BranchDomain } from './sv3-branch-domain.js';

/** Fork reads stay at the view's existing pre-render and disconnect positions. */
export class Sv3BranchController implements ReactiveController {
  constructor(host: ReactiveControllerHost, private readonly domain: Sv3BranchDomain) {
    host.addController(this);
  }

  update(): void { this.domain.syncForkReads(); }
  disconnect(): void { this.domain.clearForkReads(); }
  hostDisconnected(): void { this.disconnect(); }
}
