/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.context;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Identity, attribution and independent work axes carried explicitly across Engine ports.
 *
 * <p>This is a sibling of the operation-dispatch record {@code InvocationProvenance}
 * ({@code modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/InvocationProvenance.java:54}),
 * not its replacement (lane F design section 3.4, C1 decision Q1).
 * Core has no dependency on that module. Transport and source tier carry the canonical registry
 * identifiers; the application adapter validates and projects them, rather than defining another
 * transport or trust vocabulary here. Neither client kind nor a grant reference grants authority.
 * A resumed operation must resolve its grant again. Session identity is attribution only.
 *
 * <p>Survival describes recovery; urgency describes scheduling. Neither defaults or derives the
 * other. The front chooses both for the work it creates, including durable foreground work and
 * interactive background work. The immutable value is safe to carry across executor boundaries.
 */
public record EngineContext(
    ClientKind clientKind,
    String clientId,
    Optional<String> sessionId,
    Optional<String> grantReference,
    String sourceTier,
    String transport,
    Survival survival,
    Urgency urgency,
    Optional<UUID> workId) {

  /** Unattached caller attribution; the Engine mints work identity at admission. */
  public EngineContext(ClientKind clientKind, String clientId, Optional<String> sessionId,
      Optional<String> grantReference, String sourceTier, String transport, Survival survival,
      Urgency urgency) {
    this(clientKind, clientId, sessionId, grantReference, sourceTier, transport, survival, urgency,
        Optional.empty());
  }

  public EngineContext {
    Objects.requireNonNull(clientKind, "clientKind");
    requireIdentifier(clientId, "clientId");
    Objects.requireNonNull(sessionId, "sessionId").ifPresent(s -> requireIdentifier(s, "sessionId"));
    Objects.requireNonNull(grantReference, "grantReference")
        .ifPresent(s -> requireIdentifier(s, "grantReference"));
    requireIdentifier(sourceTier, "sourceTier");
    requireIdentifier(transport, "transport");
    Objects.requireNonNull(survival, "survival");
    Objects.requireNonNull(urgency, "urgency");
    Objects.requireNonNull(workId, "workId");
  }

  /** Exact process-local lifecycle linkage, never a client credential or durable operation key. */
  public EngineContext withWorkId(UUID id) {
    return new EngineContext(clientKind, clientId, sessionId, grantReference, sourceTier, transport,
        survival, urgency, Optional.of(id));
  }

  /** Explicit work-owner transition; survival and attribution remain independent. */
  public EngineContext withUrgency(Urgency next) {
    return new EngineContext(clientKind, clientId, sessionId, grantReference, sourceTier, transport,
        survival, next, workId);
  }

  private static void requireIdentifier(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " must be a non-blank identifier of at most 256 characters");
    }
  }

  public enum ClientKind {
    WEBVIEW, MCP_CLIENT, CLI, PAIRED_DEVICE, SUPERVISOR, INTERNAL
  }

  public enum Survival {
    INTERACTIVE, DURABLE
  }

  public enum Urgency {
    FOREGROUND, BACKGROUND
  }
}
