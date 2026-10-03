/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationKind;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class RecordedIngestChildTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T09:00:00Z"),
      ZoneOffset.UTC);
  private static final Path ROOT_PATH = Path.of("recorded-child-root").toAbsolutePath().normalize();

  @Test
  void descriptorAndPayloadRoundTripAsDigestOnlyBinding() {
    RecordedIngestChild child = child(plan(ROOT_PATH, "docs", false));

    OperationDescriptor descriptor = child.descriptor();
    assertEquals(OperationKind.INGEST, descriptor.kind());
    assertNull(descriptor.operationRef());
    assertTrue(descriptor.identityJson().contains("rootPlanSha256"));
    assertFalse(descriptor.identityJson().contains(ROOT_PATH.toString()));
    assertEquals(child, RecordedIngestChild.from(descriptor, child.payload()));
  }

  @Test
  void parentKeyReadsOnlyStrictIdentityAndLeavesPayloadAuthorityToFrom() {
    String parentKey = OperationKeys.generate(CLOCK);
    String identity = "{\"mode\":\"ingest-child\",\"parentOperationKey\":\""
        + parentKey + "\",\"replaySchema\":\"" + RecordedRootPlan.SCHEMA
        + "\",\"rootPlanSha256\":\"" + "0".repeat(64) + "\"}";
    OperationDescriptor descriptor = new OperationDescriptor(OperationKind.INGEST, null, identity);

    // A syntactically valid identity is enough for the fencing read, even with no payload and
    // with a digest that cannot be the valid plan below.
    assertEquals(parentKey, RecordedIngestChild.parentKey(descriptor));
    assertThrows(IllegalArgumentException.class, () -> RecordedIngestChild.from(descriptor,
        new OperationPreparedPayload(false, plan(ROOT_PATH, "docs", false).toReplayPayload())));

    assertThrows(IllegalArgumentException.class, () -> RecordedIngestChild.parentKey(
        new OperationDescriptor(OperationKind.INGEST, null,
            identity.replace("\"replaySchema\":\"" + RecordedRootPlan.SCHEMA,
                "\"replaySchema\":\"root-plan.v0"))));
    assertThrows(IllegalArgumentException.class, () -> RecordedIngestChild.parentKey(
        new OperationDescriptor(OperationKind.INGEST, null,
            identity.replace("\"rootPlanSha256\":\"" + "0".repeat(64),
                "\"rootPlanSha256\":\"" + "z".repeat(64)))));
    assertThrows(IllegalArgumentException.class, () -> RecordedIngestChild.parentKey(
        new OperationDescriptor(OperationKind.INGEST, null,
            identity.replace(parentKey, UUID.randomUUID().toString()))));
    assertThrows(IllegalArgumentException.class, () -> RecordedIngestChild.parentKey(
        new OperationDescriptor(OperationKind.REINDEX, null, identity)));
    assertThrows(IllegalArgumentException.class, () -> RecordedIngestChild.parentKey(
        new OperationDescriptor(OperationKind.INGEST, "core.ingest", identity)));
    assertThrows(IllegalArgumentException.class, () -> RecordedIngestChild.parentKey(
        new OperationDescriptor(OperationKind.INGEST, null,
            identity.replace("}", ",\"unexpected\":1}"))));
    String duplicate = identity.replace("\"mode\":\"ingest-child\"",
        "\"mode\":\"ingest-child\",\"mode\":\"ingest-child\"");
    assertThrows(IllegalArgumentException.class, () -> RecordedIngestChild.parentKey(
        new OperationDescriptor(OperationKind.INGEST, null, duplicate)));
  }

  @Test
  void requiresOneRootAndCanonicalParentKey() {
    assertThrows(IllegalArgumentException.class,
        () -> new RecordedIngestChild(OperationKeys.generate(CLOCK),
            new RecordedRootPlan("g1", List.of())));
    assertThrows(IllegalArgumentException.class,
        () -> new RecordedIngestChild(OperationKeys.generate(CLOCK), new RecordedRootPlan("g1",
            List.of(root(ROOT_PATH, "a", false), root(ROOT_PATH.resolve("child"), "b", false)))));
    assertThrows(IllegalArgumentException.class,
        () -> new RecordedIngestChild(UUID.randomUUID().toString(), plan(ROOT_PATH, "docs", false)));
  }

  @Test
  void rejectsWrongKindReferenceAndSealedPayload() {
    RecordedIngestChild child = child(plan(ROOT_PATH, "docs", false));
    OperationDescriptor wrongKind = new OperationDescriptor(
        OperationKind.REINDEX, null, child.descriptor().identityJson());
    OperationDescriptor wrongReference = new OperationDescriptor(
        OperationKind.INGEST, "core.ingest", child.descriptor().identityJson());

    assertThrows(IllegalArgumentException.class,
        () -> RecordedIngestChild.from(wrongKind, child.payload()));
    assertThrows(IllegalArgumentException.class,
        () -> RecordedIngestChild.from(wrongReference, child.payload()));
    assertThrows(IllegalArgumentException.class,
        () -> RecordedIngestChild.from(child.descriptor(),
            new OperationPreparedPayload(true, child.payload().value())));
  }

  @Test
  void rejectsStrictIdentityAndPayloadMismatches() {
    RecordedIngestChild child = child(plan(ROOT_PATH, "docs", false));
    String identity = child.descriptor().identityJson();
    String digest = CanonicalOperationArguments.digest(child.plan().toReplayPayload());

    assertThrows(IllegalArgumentException.class,
        () -> RecordedIngestChild.from(new OperationDescriptor(OperationKind.INGEST, null,
            identity.replace("\"mode\":\"ingest-child\"", "\"mode\":\"wrong\"")),
            child.payload()));
    assertThrows(IllegalArgumentException.class,
        () -> RecordedIngestChild.from(new OperationDescriptor(OperationKind.INGEST, null,
            identity.replace("\"rootPlanSha256\":\"" + digest,
                "\"rootPlanSha256\":\"" + "0".repeat(64))), child.payload()));
    assertThrows(IllegalArgumentException.class,
        () -> RecordedIngestChild.from(new OperationDescriptor(OperationKind.INGEST, null,
            identity.replace("\"replaySchema\":\"root-plan.v1\"",
                "\"replaySchema\":\"root-plan.v0\"")), child.payload()));
    assertThrows(IllegalArgumentException.class,
        () -> RecordedIngestChild.from(new OperationDescriptor(OperationKind.INGEST, null,
            withoutReplaySchema(identity)), child.payload()));
    assertThrows(IllegalArgumentException.class,
        () -> RecordedIngestChild.from(new OperationDescriptor(OperationKind.INGEST, null,
            identity.replace("\"mode\":\"ingest-child\"",
                "\"mode\":\"ingest-child\",\"unknown\":1")), child.payload()));

    RecordedRootPlan differentPlan = plan(ROOT_PATH.resolve("other"), "docs", false);
    assertThrows(IllegalArgumentException.class,
        () -> RecordedIngestChild.from(child.descriptor(),
            new OperationPreparedPayload(false, differentPlan.toReplayPayload())));
    assertThrows(RuntimeException.class,
        () -> RecordedIngestChild.from(child.descriptor(), new OperationPreparedPayload(false, "{}")));
    assertThrows(RuntimeException.class,
        () -> RecordedIngestChild.from(new OperationDescriptor(OperationKind.INGEST, null, "{"),
            child.payload()));
  }

  @Test
  void rejectsDuplicateIdentityKeys() {
    RecordedIngestChild child = child(plan(ROOT_PATH, "docs", false));
    String identity = child.descriptor().identityJson();
    String duplicate = identity.replace("\"mode\":\"ingest-child\"",
        "\"mode\":\"ingest-child\",\"mode\":\"ingest-child\"");

    assertThrows(RuntimeException.class,
        () -> RecordedIngestChild.from(new OperationDescriptor(OperationKind.INGEST, null, duplicate),
            child.payload()));
  }

  private static RecordedIngestChild child(RecordedRootPlan plan) {
    return new RecordedIngestChild(OperationKeys.generate(CLOCK), plan);
  }

  private static String withoutReplaySchema(String identity) {
    JsonMapper mapper = JsonMapper.builder().build();
    ObjectNode object = (ObjectNode) mapper.readTree(identity);
    object.remove("replaySchema");
    return mapper.writeValueAsString(object);
  }

  private static RecordedRootPlan plan(Path path, String collection, boolean singleFile) {
    return new RecordedRootPlan("generation-child-1", List.of(root(path, collection, singleFile)));
  }

  private static RecordedRootPlan.Root root(Path path, String collection, boolean singleFile) {
    return new RecordedRootPlan.Root(path, collection, true, singleFile,
        List.of("*.tmp"), List.of());
  }
}
