/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.indexing;

import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.core.context.EngineContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable declaration evidence, independent of expiring operation and queue history. */
public record SourceOrigin(String id, Kind kind, RecordedRootPlan.Root root, Approval approval,
    long acceptedAt, RestartBinding restartBinding) {
  /** A successor links immutable provenance; authority still selects the active parent binding. */
  public record RestartBinding(int version, String bindingId, String planHash, String predecessorOriginId) {
    public RestartBinding {
      if (version != io.justsearch.app.api.operations.ImportApprovalBinding.VERSION) {
        throw new IllegalArgumentException("Unknown origin restart binding version");
      }
      io.justsearch.app.api.operations.ImportApprovalBinding.requireId(bindingId);
      if (planHash == null || !planHash.matches("[a-f0-9]{64}")) {
        throw new IllegalArgumentException("Invalid origin plan hash");
      }
      if (predecessorOriginId != null) {
        io.justsearch.app.api.operations.ImportApprovalBinding.requireId(predecessorOriginId);
      }
    }
  }

  /** Existing acceptance paths remain unversioned until import-resume activation. */
  public SourceOrigin(String id, Kind kind, RecordedRootPlan.Root root, Approval approval, long acceptedAt) {
    this(id, kind, root, approval, acceptedAt, null);
  }

  public enum Kind { IMPORT, WATCHED }
  public enum ApprovalClass { DURABLE, SESSION }
  public enum EvidenceSource { GATE, CALLER_DECLARED }

  /** The encoded basis retains the exact grant target and tier used by recovery. Null is absent. */
  public record Approval(String authorizationBasis, String transport, String sourceTier,
      String clientKind, String producingOperationId, String operationKey,
      ApprovalClass approvalClass, EvidenceSource evidenceSource) {
    public Approval {
      Objects.requireNonNull(transport, "transport");
      Objects.requireNonNull(sourceTier, "sourceTier");
      Objects.requireNonNull(clientKind, "clientKind");
      Objects.requireNonNull(approvalClass, "approvalClass");
      Objects.requireNonNull(evidenceSource, "evidenceSource");
      if (evidenceSource == EvidenceSource.GATE) {
        var basis = OperationAuthorizationBasis.decode(authorizationBasis);
        Objects.requireNonNull(producingOperationId, "producingOperationId");
        Objects.requireNonNull(operationKey, "operationKey");
        if (approvalClass != (basis instanceof OperationAuthorizationBasis.EphemeralCapsule
            ? ApprovalClass.SESSION : ApprovalClass.DURABLE)) {
          throw new IllegalArgumentException("Approval class disagrees with authorization basis");
        }
      } else if (authorizationBasis != null || producingOperationId != null || operationKey != null
          || approvalClass != ApprovalClass.DURABLE) {
        throw new IllegalArgumentException("Caller declaration cannot supply gate evidence");
      }
    }

    public static Approval gated(EngineContext context, String operationId, String operationKey) {
      String encoded = context.grantReference().orElseThrow(
          () -> new IllegalArgumentException("Accepted declaration has no authorization basis"));
      var basis = OperationAuthorizationBasis.decode(encoded);
      return new Approval(encoded, context.transport(), context.sourceTier(), context.clientKind().name(),
          operationId, operationKey, basis instanceof OperationAuthorizationBasis.EphemeralCapsule
              ? ApprovalClass.SESSION : ApprovalClass.DURABLE, EvidenceSource.GATE);
    }

    /** A direct watched-root request has attribution, but no server-built authorization basis. */
    public static Approval declared(EngineContext context) {
      return new Approval(null, context.transport(), context.sourceTier(), context.clientKind().name(),
          null, null, ApprovalClass.DURABLE, EvidenceSource.CALLER_DECLARED);
    }
  }

  public SourceOrigin {
    if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid origin id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(approval, "approval");
    if (restartBinding != null && (kind != Kind.IMPORT || approval.evidenceSource() != EvidenceSource.GATE
        || id.equals(restartBinding.predecessorOriginId()))) {
      throw new IllegalArgumentException("Invalid versioned import origin");
    }
    if (acceptedAt < 0) throw new IllegalArgumentException("Invalid acceptance time");
  }

  public static SourceOrigin accepted(Kind kind, RecordedRootPlan.Root root, Approval approval,
      long acceptedAt) {
    // Replay of one accepted operation must not create another origin. Direct declarations have
    // no operation identity and receive a fresh identity at their actual acceptance callback.
    String id = approval.operationKey() == null ? UUID.randomUUID().toString()
        : UUID.nameUUIDFromBytes((kind + "\0" + approval.operationKey() + "\0" + root.path())
            .getBytes(StandardCharsets.UTF_8)).toString();
    return new SourceOrigin(id, kind, root, approval, acceptedAt);
  }

  public static RecordedRootPlan.Root watchedRoot(Path path, String collection) {
    return new RecordedRootPlan.Root(path, collection == null || collection.isBlank() ? "default" : collection,
        false, false, List.of(), List.of());
  }
}
