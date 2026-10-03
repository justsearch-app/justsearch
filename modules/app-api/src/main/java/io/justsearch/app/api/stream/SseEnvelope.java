/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.stream;

import java.time.Instant;
import java.util.Objects;

/**
 * Universal SSE envelope wrapping every streamed frame.
 *
 * <p>Per slice 436 §B.2: every SSE frame across the framework's streams (registry
 * catalogs, UI surfaces, system status) carries this envelope verbatim. Single JSON
 * object as the SSE {@code data:} payload; SSE event name is constant {@code "frame"}.
 * Consumers route by {@link #frameKind()} (and the lifecycle-subkind nested in the
 * payload), not by SSE event name.
 *
 * <p>Field semantics:
 *
 * <ul>
 *   <li>{@link #streamId} — kind-prefixed slug identifying the source stream (e.g.,
 *       {@code "registry:capabilities"}). Stable across reconnects.
 *   <li>{@link #frameKind} — top-level discriminator: data frame ({@code UPDATE}) vs
 *       lifecycle frame ({@code LIFECYCLE}).
   *   <li>{@link #seq} — source-allocated per-stream sequence number, starting at 1. Replayed
   *       updates retain their original sequence and may follow a newer control-frame sequence.
 *   <li>{@link #ts} — server-side wall-clock timestamp of frame emission, ISO-8601 UTC.
 *   <li>{@link #payload} — frame-specific data (typed object). For {@code UPDATE} frames
 *       this is the controller-defined wire shape. For {@code LIFECYCLE} frames this is a
 *       small object with at minimum a {@code "kind"} field
 *       (connected/heartbeat/closing/error/reset/snapshot).
 *   <li>{@link #resumeToken} — opaque server-encoded cursor. The FE sends it as
 *       {@code ?since=<token>} on reconnect; the server replays frames newer than the
   *       token if within the resume window, or emits {@code reset + snapshot} if outside.
   *       Lifecycle tokens acknowledge delivered state, not their control frame's own sequence;
   *       an empty token means no checkpoint. Tokens bind to one channel incarnation.
 * </ul>
 */
public record SseEnvelope(
    StreamId streamId, SseFrameKind frameKind, long seq, Instant ts, Object payload, String resumeToken) {

  public SseEnvelope {
    Objects.requireNonNull(streamId, "streamId");
    Objects.requireNonNull(frameKind, "frameKind");
    Objects.requireNonNull(ts, "ts");
    Objects.requireNonNull(resumeToken, "resumeToken");
    // payload may be null only for empty lifecycle frames (e.g., heartbeat without data);
    // by convention emitters supply at least {"kind": "..."}, so null is rare but legal.
    if (seq < 1) {
      throw new IllegalArgumentException("seq must be >= 1, got " + seq);
    }
  }
}
