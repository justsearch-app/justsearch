/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import io.justsearch.app.api.stream.StreamId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Opaque server-side codec for SSE envelope resume tokens.
 *
 * <p>New tokens bind the stream and sequence to one channel incarnation. Legacy tokens
 * still decode for multiplex routing, but their absent incarnation cannot validate a resume.
 *
 * <p>Token format (intentionally undocumented at the wire boundary): the colon-separated
 * tuple {@code "<streamId>:<seq>:<incarnation>"}, base64-URL-encoded. {@link #encode} produces the
 * token; {@link #decode} parses it. Decoding rejects malformed tokens by returning
 * {@link Optional#empty()} — the controller treats this identically to "token outside
 * the resume window" (emits {@code reset + snapshot}).
 *
 * <p>The streamId portion ensures that a token from one stream cannot be used to resume
 * a different stream — a minor defense in depth against client misuse.
 */
public final class ResumeTokenCodec {

  private ResumeTokenCodec() {}

  /** Encodes a checkpoint owned by one channel incarnation. */
  public static String encode(StreamId streamId, long seq, UUID incarnation) {
    return encodeRaw(streamId, seq, ":" + Objects.requireNonNull(incarnation, "incarnation"));
  }

  private static String encodeRaw(StreamId streamId, long seq, String suffix) {
    Objects.requireNonNull(streamId, "streamId");
    if (seq < 0) {
      throw new IllegalArgumentException("seq must be >= 0");
    }
    String raw = streamId.value() + ":" + seq + suffix;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Attempts to decode an opaque token into its {@code (streamId, seq)} parts. Returns
   * {@link Optional#empty()} on any decode failure (malformed base64, missing separator,
   * non-numeric seq, invalid streamId pattern). Callers should treat decode failure
   * identically to "token outside resume window."
   */
  public static Optional<Decoded> decode(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(token);
      String raw = new String(decoded, StandardCharsets.UTF_8);
      String[] parts = raw.split(":", -1);
      if (parts.length != 3 && parts.length != 4) {
        return Optional.empty();
      }
      long seq = Long.parseLong(parts[2]);
      if (seq < 0) {
        return Optional.empty();
      }
      StreamId streamId = new StreamId(parts[0] + ":" + parts[1]);
      UUID incarnation = parts.length == 4 ? UUID.fromString(parts[3]) : null;
      return Optional.of(new Decoded(streamId, seq, incarnation));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** Decoded resume-token contents. */
  public record Decoded(StreamId streamId, long seq, UUID incarnation) {
    public Decoded {
      Objects.requireNonNull(streamId, "streamId");
      if (seq < 0) {
        throw new IllegalArgumentException("seq must be >= 0");
      }
    }
  }
}
