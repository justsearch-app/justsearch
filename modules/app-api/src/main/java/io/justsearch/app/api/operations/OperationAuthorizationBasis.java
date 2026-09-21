/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.SourceTier;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Server-built evidence describing which authorization basis admitted an operation.
 *
 * <p>This value is a locator for later revalidation, never authority. Callers cannot authorize
 * themselves by supplying an encoded basis; only the server-side gate may create one.
 */
public sealed interface OperationAuthorizationBasis
    permits OperationAuthorizationBasis.StructuralAuto,
        OperationAuthorizationBasis.EphemeralCapsule,
        OperationAuthorizationBasis.PreparedContinuation,
        OperationAuthorizationBasis.OperationGrant,
        OperationAuthorizationBasis.FamilyGrant {
  int MAX_ENCODED_CHARS = 256;
  String VERSION = "jsa1";

  /** Returns the canonical, versioned evidence wire value. */
  String encode();

  /** Decodes one canonical evidence value, failing closed on every malformed representation. */
  static OperationAuthorizationBasis decode(String encoded) {
    if (encoded == null || encoded.length() > MAX_ENCODED_CHARS) {
      throw invalid("Invalid authorization basis");
    }
    String[] parts = encoded.split(":", -1);
    if (parts.length == 2) {
      if (!VERSION.equals(parts[0])) throw invalid("Unknown authorization basis version");
      return switch (parts[1]) {
        case "auto" -> new StructuralAuto();
        case "capsule" -> new EphemeralCapsule();
        default -> throw invalid("Unknown authorization basis kind");
      };
    }
    if (parts.length != 4 || !VERSION.equals(parts[0])) {
      throw invalid("Malformed authorization basis");
    }
    if ("continuation".equals(parts[1])) {
      return new PreparedContinuation(parts[2], canonicalNonce(parts[3]));
    }
    SourceTier sourceTier;
    try {
      sourceTier = SourceTier.valueOf(parts[2]);
    } catch (IllegalArgumentException | NullPointerException invalidTier) {
      throw invalid("Unknown authorization source tier", invalidTier);
    }
    String target = decodeTarget(parts[3]);
    return switch (parts[1]) {
      case "op" -> new OperationGrant(target, sourceTier);
      case "family" -> new FamilyGrant(target, sourceTier);
      default -> throw invalid("Unknown authorization basis kind");
    };
  }

  /** Structural AUTO admission selected by the shared server-side evaluator. */
  record StructuralAuto() implements OperationAuthorizationBasis {
    @Override public String encode() { return VERSION + ":auto"; }
  }

  /** One-time process-local capsule admission marker. */
  record EphemeralCapsule() implements OperationAuthorizationBasis {
    @Override public String encode() { return VERSION + ":capsule"; }
  }

  /** Locator for the single accepted prepared bulk invocation approved across its restarts. */
  record PreparedContinuation(String operationKey, java.util.UUID preparationNonce)
      implements OperationAuthorizationBasis {
    public PreparedContinuation {
      OperationKeys.timestampMillis(operationKey);
      Objects.requireNonNull(preparationNonce, "preparationNonce");
    }

    @Override public String encode() {
      return VERSION + ":continuation:" + operationKey + ":" + preparationNonce;
    }
  }

  private static java.util.UUID canonicalNonce(String value) {
    final java.util.UUID parsed;
    try { parsed = java.util.UUID.fromString(value); }
    catch (IllegalArgumentException | NullPointerException malformed) {
      throw invalid("Invalid prepared continuation identity");
    }
    if (!parsed.toString().equals(value)) {
      throw invalid("Non-canonical prepared continuation identity");
    }
    return parsed;
  }

  /** Exact durable operation grant selected by the server-side gate. */
  record OperationGrant(String target, SourceTier sourceTier)
      implements OperationAuthorizationBasis {
    public OperationGrant {
      target = validateTarget(target);
      Objects.requireNonNull(sourceTier, "sourceTier");
      ensureEncodable(wire("op", sourceTier, target));
    }

    @Override public String encode() { return wire("op", sourceTier, target); }
  }

  /** Exact durable family grant selected by the server-side gate. */
  record FamilyGrant(String target, SourceTier sourceTier) implements OperationAuthorizationBasis {
    public FamilyGrant {
      target = validateTarget(target);
      Objects.requireNonNull(sourceTier, "sourceTier");
      ensureEncodable(wire("family", sourceTier, target));
    }

    @Override public String encode() { return wire("family", sourceTier, target); }
  }

  private static String wire(String kind, SourceTier sourceTier, String target) {
    String locator = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(strictUtf8(target));
    return VERSION + ":" + kind + ":" + sourceTier.name() + ":" + locator;
  }

  private static String decodeTarget(String encoded) {
    if (encoded.isEmpty() || encoded.indexOf('=') >= 0
        || !encoded.matches("[A-Za-z0-9_-]+")) {
      throw invalid("Non-canonical authorization target encoding");
    }
    byte[] bytes;
    try {
      bytes = Base64.getUrlDecoder().decode(encoded);
    } catch (IllegalArgumentException malformed) {
      throw invalid("Malformed authorization target encoding", malformed);
    }
    String target;
    try {
      var decoder = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT);
      CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
      target = chars.toString();
    } catch (CharacterCodingException malformed) {
      throw invalid("Malformed UTF-8 authorization target", malformed);
    }
    String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(strictUtf8(target));
    if (!canonical.equals(encoded)) {
      throw invalid("Non-canonical authorization target encoding");
    }
    return validateTarget(target);
  }

  private static String validateTarget(String target) {
    Objects.requireNonNull(target, "target");
    if (target.isBlank() || target.chars().anyMatch(Character::isISOControl)) {
      throw invalid("Invalid authorization target");
    }
    return target;
  }

  private static byte[] strictUtf8(String target) {
    try {
      var encoder = StandardCharsets.UTF_8.newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT);
      ByteBuffer bytes = encoder.encode(CharBuffer.wrap(target));
      byte[] result = new byte[bytes.remaining()];
      bytes.get(result);
      return result;
    } catch (CharacterCodingException malformed) {
      throw invalid("Authorization target is not well-formed UTF-8", malformed);
    }
  }

  private static void ensureEncodable(String encoded) {
    if (encoded.length() > MAX_ENCODED_CHARS) {
      throw invalid("Authorization basis exceeds encoded length limit");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }
}
