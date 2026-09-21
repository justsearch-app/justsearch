// SPDX-License-Identifier: Apache-2.0
/**
 * 548 §4.1 (tier-1 enum-collapse) — FE-side lifecycle-state authority.
 *
 * The lifecycle-state vocabulary is the generated wire contract `StatusResponse` (record → JSON
 * Schema → {TS, Zod}): its `lifecycle.state` enum carries the full wire names the Java
 * producer emits on `/api/status` + `/api/health` (`LIFECYCLE_STATE_READY`). FE comparisons derive
 * their constants from this one place. The `satisfies` check below binds each constant to a real
 * member of the generated enum, so a change to the Java `LifecycleState` enum fails this typecheck
 * — the generated contract stays the single authority, with no proto (`*_pb`) dependency on the FE
 * (tempdoc 564 Phase C: the FE consumes the JSON-Schema projection, not the proto).
 */
import type { StatusResponse } from './generated/schema-types/status-response';

/** A non-null lifecycle-state wire member of the generated `StatusResponse` enum. */
type LifecycleWire = NonNullable<NonNullable<StatusResponse['lifecycle']>['state']>;

/** Canonical lifecycle-state wire names, checked against the generated enum (the authority). */
export const LIFECYCLE = {
  STARTING: 'LIFECYCLE_STATE_STARTING',
  READY: 'LIFECYCLE_STATE_READY',
  DEGRADED: 'LIFECYCLE_STATE_DEGRADED',
  ERROR: 'LIFECYCLE_STATE_ERROR',
  STOPPING: 'LIFECYCLE_STATE_STOPPING',
  STOPPED: 'LIFECYCLE_STATE_STOPPED',
} as const satisfies Record<string, LifecycleWire>;

/** A lifecycle-state wire string as emitted on `/api/status` / `/api/health`. */
export type LifecycleWireName = (typeof LIFECYCLE)[keyof typeof LIFECYCLE];
