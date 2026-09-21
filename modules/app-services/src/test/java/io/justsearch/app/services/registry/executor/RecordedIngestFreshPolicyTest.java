/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pure policy proof for fresh recorded child admission; runner origin is outside this test. */
final class RecordedIngestFreshPolicyTest {
  private static final Instant OCCURRED_AT = Instant.parse("2026-09-14T00:00:00Z");
  private static final String GENERATION = "fresh-generation";
  private static final String PLAN_ARGUMENTS =
      "{\"paths\":[\"C:/public-requested-root\"],\"generation\":\"public-generation\"}";
  private static final String KEY = "01994180-0000-7000-8000-000000000501";

  @Test
  void structuralAutoRequiresCurrentAutoButDoesNotRequireWatchedContainment(@TempDir Path temp)
      throws IOException {
    Path watched = Files.createDirectory(temp.resolve("watched"));
    Path outside = Files.createDirectory(temp.resolve("outside"));
    Fixture trustedIngest = fixture(temp.resolve("trusted"), watched, outside,
        OperationKind.INGEST, "core.ingest-files", TransportTag.SYSTEM_INTERNAL,
        new OperationAuthorizationBasis.StructuralAuto());
    assertTrue(trustedIngest.authority().allowsFreshRecordedIngest(
        trustedIngest.parent(), trustedIngest.plan()),
        "trusted medium ingest StructuralAuto is current AUTO even outside watched roots");

    Fixture untrustedIngest = fixture(temp.resolve("untrusted"), watched, outside,
        OperationKind.INGEST, "core.ingest-files", TransportTag.AGENT_LOOP,
        new OperationAuthorizationBasis.StructuralAuto());
    assertFalse(untrustedIngest.authority().allowsFreshRecordedIngest(
        untrustedIngest.parent(), untrustedIngest.plan()),
        "untrusted medium ingest StructuralAuto requires current AUTO");
  }

  @Test
  void freshCapsuleAllowsCurrentNonDenyOutsideRoots(@TempDir Path temp) throws IOException {
    Path watched = Files.createDirectory(temp.resolve("watched"));
    Path outside = Files.createDirectory(temp.resolve("outside"));
    Fixture fixture = fixture(temp.resolve("capsule"), watched, outside, OperationKind.INGEST,
        "core.ingest-files", TransportTag.AGENT_LOOP,
        new OperationAuthorizationBasis.EphemeralCapsule());
    assertTrue(fixture.authority().allowsFreshRecordedIngest(fixture.parent(), fixture.plan()),
        "a fresh capsule follows the current non-DENY gate without root containment");
  }

  @Test
  void exactOperationGrantCannotSubstituteFamilyGrantAfterRevoke(
      @TempDir Path temp) throws IOException {
    Path watched = Files.createDirectory(temp.resolve("watched"));
    Fixture fixture = fixture(temp.resolve("operation-grant"), watched, watched,
        OperationKind.INGEST, "core.ingest-files", TransportTag.AGENT_LOOP,
        new OperationAuthorizationBasis.OperationGrant("core.ingest-files", SourceTier.UNTRUSTED));
    fixture.authority().grants().grantAllowAlways("core.ingest-files", SourceTier.UNTRUSTED);
    assertTrue(fixture.authority().allowsFreshRecordedIngest(fixture.parent(), fixture.plan()));

    fixture.authority().grants().revoke("core.ingest-files", SourceTier.UNTRUSTED);
    fixture.authority().grants().grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
    assertFalse(fixture.authority().allowsFreshRecordedIngest(fixture.parent(), fixture.plan()),
        "revoked exact operation grant cannot be replaced by family grant");
  }

  @Test
  void revokedExactGrantCannotUseCurrentAuto(@TempDir Path temp) throws IOException {
    Path watched = Files.createDirectory(temp.resolve("watched"));
    Fixture fixture = fixture(temp.resolve("low-auto"), watched, watched,
        OperationKind.REINDEX, "core.reindex", TransportTag.AGENT_LOOP,
        new OperationAuthorizationBasis.OperationGrant("core.reindex", SourceTier.UNTRUSTED));
    fixture.authority().grants().grantAllowAlways("core.reindex", SourceTier.UNTRUSTED);
    assertTrue(fixture.authority().allowsFreshRecordedIngest(fixture.parent(), fixture.plan()));
    fixture.authority().grants().revoke("core.reindex", SourceTier.UNTRUSTED);
    assertFalse(fixture.authority().allowsFreshRecordedIngest(fixture.parent(), fixture.plan()),
        "current low-risk AUTO cannot replace the revoked selected grant");
  }

  @Test
  void exactFamilyGrantCannotSubstituteOperationGrantAfterRevoke(@TempDir Path temp)
      throws IOException {
    Path watched = Files.createDirectory(temp.resolve("watched"));
    Fixture fixture = fixture(temp.resolve("family-grant"), watched, watched,
        OperationKind.INGEST, "core.ingest-files", TransportTag.AGENT_LOOP,
        new OperationAuthorizationBasis.FamilyGrant("file-operations", SourceTier.UNTRUSTED));
    fixture.authority().grants().grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
    assertTrue(fixture.authority().allowsFreshRecordedIngest(fixture.parent(), fixture.plan()));

    fixture.authority().grants().revokeFamily("file-operations", SourceTier.UNTRUSTED);
    fixture.authority().grants().grantAllowAlways("core.ingest-files", SourceTier.UNTRUSTED);
    assertFalse(fixture.authority().allowsFreshRecordedIngest(fixture.parent(), fixture.plan()),
        "revoked exact family grant cannot be replaced by an operation grant");
  }

  @Test
  void grantScopeRequiresCurrentWatchedContainment(@TempDir Path temp) throws IOException {
    Path watched = Files.createDirectory(temp.resolve("watched"));
    Fixture fixture = fixture(temp.resolve("scope"), watched, watched, OperationKind.INGEST,
        "core.ingest-files", TransportTag.AGENT_LOOP,
        new OperationAuthorizationBasis.OperationGrant("core.ingest-files", SourceTier.UNTRUSTED));
    fixture.authority().grants().grantAllowAlways("core.ingest-files", SourceTier.UNTRUSTED);
    assertTrue(fixture.authority().allowsFreshRecordedIngest(fixture.parent(), fixture.plan()));

    fixture.authority().scope().bindIndexedRoots(ignored -> List.of());
    assertFalse(fixture.authority().allowsFreshRecordedIngest(fixture.parent(), fixture.plan()),
        "removing the watched root denies a durable grant for its child effect");
  }

  @Test
  void unsupportedOrMalformedBindingAndPlanAreDenied(@TempDir Path temp) throws IOException {
    Path watched = Files.createDirectory(temp.resolve("watched"));
    Fixture fixture = fixture(temp.resolve("invalid"), watched, watched, OperationKind.INGEST,
        "core.ingest-files", TransportTag.SYSTEM_INTERNAL,
        new OperationAuthorizationBasis.StructuralAuto());
    assertFalse(fixture.authority().allowsFreshRecordedIngest(null, fixture.plan()));
    assertFalse(fixture.authority().allowsFreshRecordedIngest(fixture.parent(), null));
    assertFalse(fixture.authority().allowsFreshRecordedIngest(fixture.parent(),
        new RecordedRootPlan(GENERATION, List.of())));
    assertFalse(fixture.authority().allowsFreshRecordedIngest(fixture.parent(), new RecordedRootPlan(
        GENERATION, List.of(new RecordedRootPlan.Root(watched, "documents", true, false,
            List.of(), List.of()), new RecordedRootPlan.Root(
                watched.resolve("second"), "documents", true, false, List.of(), List.of())))));

    assertFalse(fixture.authority().allowsFreshRecordedIngest(withDescriptor(fixture.parent(),
        OperationKind.INGEST, "core.reindex"), fixture.plan()),
        "the supported operation and operation kind must be paired exactly");
    assertFalse(fixture.authority().allowsFreshRecordedIngest(withDescriptor(fixture.parent(),
        OperationKind.REINDEX, "core.no-such-operation"), fixture.plan()));
    assertFalse(fixture.authority().allowsFreshRecordedIngest(withContext(fixture.parent(),
        "TRUSTED", TransportTag.AGENT_LOOP, fixture.parent().context().grantReference()),
        fixture.plan()), "source tier must agree with registered transport");
    assertFalse(fixture.authority().allowsFreshRecordedIngest(withContext(fixture.parent(),
        "UNTRUSTED", TransportTag.PLUGIN_EMITTED, fixture.parent().context().grantReference()),
        fixture.plan()), "the transport must be registered");
    assertFalse(fixture.authority().allowsFreshRecordedIngest(withContext(fixture.parent(),
        "TRUSTED", TransportTag.SYSTEM_INTERNAL, Optional.empty()), fixture.plan()));
    assertFalse(fixture.authority().allowsFreshRecordedIngest(withContext(fixture.parent(),
        "TRUSTED", TransportTag.SYSTEM_INTERNAL, Optional.of("jsa1:unknown")), fixture.plan()));
  }

  @Test
  void preparedBulkContinuationCannotAuthorizeAFreshOrdinaryIngest(@TempDir Path temp)
      throws IOException {
    Path watched = Files.createDirectory(temp.resolve("watched"));
    var continuation = new OperationAuthorizationBasis.PreparedContinuation(
        KEY, UUID.fromString("00000000-0000-4000-8000-000000000501"));
    Fixture fixture = fixture(temp.resolve("continuation"), watched, watched,
        OperationKind.INGEST, "core.ingest-files", TransportTag.SYSTEM_INTERNAL, continuation);

    assertFalse(fixture.authority().allowsFreshRecordedIngest(fixture.parent(), fixture.plan()),
        "bulk approval continuation is scoped to its bulk producer, never a fresh ingest child");
  }

  @Test
  void hardStopDeniesEveryUntrustedFreshBasis(@TempDir Path temp) throws IOException {
    Path watched = Files.createDirectory(temp.resolve("watched"));
    List<OperationAuthorizationBasis> bases = List.of(
        new OperationAuthorizationBasis.StructuralAuto(),
        new OperationAuthorizationBasis.EphemeralCapsule(),
        new OperationAuthorizationBasis.OperationGrant("core.reindex", SourceTier.UNTRUSTED),
        new OperationAuthorizationBasis.FamilyGrant("file-operations", SourceTier.UNTRUSTED));
    for (int i = 0; i < bases.size(); i++) {
      OperationAuthorizationBasis basis = bases.get(i);
      boolean ingest = basis instanceof OperationAuthorizationBasis.FamilyGrant;
      Fixture fixture = fixture(temp.resolve("hard-stop-" + i), watched, watched,
          ingest ? OperationKind.INGEST : OperationKind.REINDEX,
          ingest ? "core.ingest-files" : "core.reindex", TransportTag.AGENT_LOOP, basis);
      if (basis instanceof OperationAuthorizationBasis.OperationGrant) {
        fixture.authority().grants().grantAllowAlways(
            ingest ? "core.ingest-files" : "core.reindex", SourceTier.UNTRUSTED);
      } else if (basis instanceof OperationAuthorizationBasis.FamilyGrant) {
        fixture.authority().grants().grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
      }
      assertTrue(fixture.authority().allowsFreshRecordedIngest(fixture.parent(), fixture.plan()),
          "each basis is otherwise currently permitted before the hard stop");
      fixture.authority().hardStop().engage();
      // Retain an otherwise valid grant after the stop so DENY, not revocation, is tested.
      if (basis instanceof OperationAuthorizationBasis.OperationGrant) {
        fixture.authority().grants().grantAllowAlways("core.reindex", SourceTier.UNTRUSTED);
      } else if (basis instanceof OperationAuthorizationBasis.FamilyGrant) {
        fixture.authority().grants().grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
      }
      assertFalse(fixture.authority().allowsFreshRecordedIngest(fixture.parent(), fixture.plan()),
          "engaged hard stop denies untrusted " + basis.getClass().getSimpleName());
    }
  }

  private static Fixture fixture(Path dataDirectory, Path watchedRoot, Path planRoot,
      OperationKind kind, String operationRef, TransportTag transport,
      OperationAuthorizationBasis basis) throws IOException {
    writeWatchedRoots(dataDirectory, List.of(watchedRoot));
    OperationAuthority authority = OperationAuthority.load(dataDirectory);
    EngineContext context = EngineProvenance.context(EngineContext.ClientKind.INTERNAL,
        "fresh-owner", Optional.of("fresh-session"), Optional.of(basis.encode()), transport,
        EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
    OperationDescriptor descriptor = OperationDescriptor.invocation(kind, operationRef,
        kind == OperationKind.INGEST ? PLAN_ARGUMENTS : "{\"force\":true}", false);
    RecordedRootPlan plan = new RecordedRootPlan(GENERATION, List.of(
        new RecordedRootPlan.Root(planRoot, "documents", true, false, List.of(), List.of())));
    OperationRecord parent = new OperationRecord(1L, KEY, descriptor, context, "AGENT",
        "fresh-owner", "fresh-session", OperationState.ACCEPTED, "accepted", null, 0L, 0L,
        0, 1L, null, 1L, null, null, null,
        io.justsearch.app.api.operations.OperationHistoryMode.NONE, OCCURRED_AT, null);
    return new Fixture(authority, parent, plan, dataDirectory);
  }

  private static void writeWatchedRoots(Path dataDirectory, List<Path> roots) throws IOException {
    Files.createDirectories(dataDirectory);
    String encoded = roots.stream().map(path -> "{\"path\":\""
        + path.toAbsolutePath().normalize().toString().replace("\\", "\\\\")
            .replace("\"", "\\\"") + "\"}").collect(java.util.stream.Collectors.joining(","));
    Files.writeString(dataDirectory.resolve("watched_roots.json"),
        "{\"schemaVersion\":1,\"roots\":[" + encoded + "]}");
  }

  private static OperationRecord withDescriptor(OperationRecord parent, OperationKind kind,
      String operationRef) {
    return new OperationRecord(parent.id(), parent.key(), OperationDescriptor.invocation(kind,
        operationRef, kind == OperationKind.INGEST ? PLAN_ARGUMENTS : "{\"force\":true}", false),
        parent.context(), parent.executor(), parent.initiator(), parent.correlationId(),
        parent.state(), parent.phase(), parent.checkpointCursor(), parent.unitsCompleted(),
        parent.unitsFailed(), parent.attempts(), parent.acceptedAt(), parent.startedAt(),
        parent.updatedAt(), parent.completedAt(), parent.failureReason(), parent.receipt(),
        parent.historyMode(), parent.provenanceOccurredAt(), parent.expectedSettingsRevision());
  }

  private static OperationRecord withContext(OperationRecord parent, String sourceTier,
      TransportTag transport, Optional<String> basis) {
    EngineContext context = new EngineContext(parent.context().clientKind(),
        parent.context().clientId(), parent.context().sessionId(), basis, sourceTier,
        transport.name(), parent.context().survival(), parent.context().urgency());
    return new OperationRecord(parent.id(), parent.key(), parent.descriptor(), context,
        parent.executor(), parent.initiator(), parent.correlationId(), parent.state(),
        parent.phase(), parent.checkpointCursor(), parent.unitsCompleted(), parent.unitsFailed(),
        parent.attempts(), parent.acceptedAt(), parent.startedAt(), parent.updatedAt(),
        parent.completedAt(), parent.failureReason(), parent.receipt(), parent.historyMode(),
        parent.provenanceOccurredAt(), parent.expectedSettingsRevision());
  }

  private record Fixture(OperationAuthority authority, OperationRecord parent,
      RecordedRootPlan plan, Path dataDirectory) {}
}
