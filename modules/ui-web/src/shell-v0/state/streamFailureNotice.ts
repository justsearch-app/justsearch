// SPDX-License-Identifier: Apache-2.0
/**
 * What the app DOES when a per-event stream handler throws (tempdoc 941).
 *
 * `consumeShapeStream` (`api/streams.ts`) deliberately survives a throwing handler: one broken
 * consumer must not cost the reader the rest of an answer that is still arriving. That survival is
 * what makes the failure invisible — the turn renders as though the missing part was never sent
 * (847 F-12) — so the throw has to be reported. Reporting is a MESSAGE-MODEL decision (which class,
 * which politeness, what the reader is told), which is why the policy lives here in `shell-v0`
 * beside {@link LOCAL_MESSAGE_CLASSES} rather than in the transport module that detects it. The
 * `message-classes` gate scans this layer for emit sites for exactly that reason.
 *
 * Two audiences, two channels, because they need different things:
 *
 *  - The READER gets the app's ONE client-originated message channel (`emitEphemeralToast`, 559
 *    Authority III), worded for the consequence they can observe (part of the response is missing),
 *    never for the exception.
 *  - Whoever is DIAGNOSING gets the localStorage ring beside `wireDriftTelemetry`
 *    (`api/streamHandlerTelemetry.ts`), which rides the existing `core.export-diagnostics` payload.
 *    A `console.warn` is not a channel — nobody has devtools open when it happens.
 */
import { recordStreamHandlerFailure } from '../../api/streamHandlerTelemetry.js';
import { emitEphemeralToast } from '../components/advisory/ephemeralToast.js';

/**
 * The SSE events that END a stream. `consumeShapeStream` sets `receivedTerminal` / builds
 * `errorFromEvent` from the payload BEFORE dispatching to the handler, so by the time a handler
 * throws on one of these, nothing more is coming — which is what the wording below turns on.
 */
const TERMINAL_EVENTS = new Set(['done', 'error']);

/**
 * Report a handler failure to both audiences. Returns nothing: the caller must not branch on it —
 * the stream continues either way.
 *
 * `alreadyReported` is the CALLER's per-stream set (declared inside `consumeShapeStream`, never
 * module-level), so a later stream reports its own failures instead of inheriting an earlier
 * stream's silence. Deduping per stream per EVENT NAME is what keeps a systematic failure to one
 * signal: a `chunk` handler that throws once throws on every token.
 */
export function reportStreamHandlerFailure(
  eventName: string,
  handlerError: unknown,
  alreadyReported: Set<string>,
): void {
  console.warn(`[stream] handler for "${eventName}" threw`, handlerError);
  // Dedupe covers the toast AND the ring: neither a pile of overlays nor 500 identical ring rows
  // says more than the first one did.
  if (alreadyReported.has(eventName)) return;
  alreadyReported.add(eventName);
  // The inspectable trace comes first, and is kept for EVERY event including the terminal ones —
  // whoever is diagnosing wants the `done` handler that broke, even where the reader is
  // deliberately told nothing extra (below).
  recordStreamHandlerFailure(eventName, handlerError);

  // `error` is the one event that must NOT produce a toast. `errorFromEvent` is assembled from the
  // payload before the handler is dispatched and rethrown to the caller once the stream drains, so
  // the caller already surfaces a real, specific error for this turn. A second overlay would be a
  // vaguer message racing the true one — two notices about one failure, and the vaguer one wrong.
  if (eventName === 'error') return;

  // Wording turns on whether this event ENDED the stream, for the same reason. Telling a reader
  // whose `done` handler threw that "the rest of it is still arriving" is a false statement about
  // the only thing the sentence claims — and `done` is where a throw costs most, since the handler
  // that commits the finished turn is a `done` handler.
  emitEphemeralToast({
    classId: 'core.stream.partial-failure',
    message: TERMINAL_EVENTS.has(eventName)
      ? 'This response may be incomplete — part of it could not be displayed. ' +
        'See the browser console for the failure detail.'
      : 'Part of this response could not be displayed — the rest of it is still arriving. ' +
        'See the browser console for the failure detail.',
  });
}
