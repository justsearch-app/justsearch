package io.justsearch.app.services.wire;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 564 (proto-parity X-cut) — record↔proto conformance guard for the operation-history wire
 * surface, extending the parity discipline that {@code KnowledgeWireContractConformanceTest}
 * applies to the knowledge surface (563 §3 V3: that test covered only knowledge/SearchTrace, so
 * {@code OperationHistoryEntry.provenance}, added later in slice 490 §4.B, drifted with no proto
 * counterpart).
 *
 * <p>For every operation-history record, every JSON field the record can emit must have a
 * corresponding field in the generated proto descriptor (matched by proto {@code json_name}).
 * Adding a field to the record (or to {@code InvocationProvenance}) without adding it to
 * {@code contracts/wire/operation_history.proto} now fails here. The reverse direction (proto
 * carries a field the record does not) is intentionally permitted (forward-compat / deprecated
 * aliases).
 *
 * <p>Lives in app-services because its test classpath transitively carries both the
 * {@code app-observability} {@code OperationHistoryEntry} record and the generated
 * {@code io.justsearch.contract.wire} proto (via app-api's {@code api}-exposed projection),
 * without needing a new dependency.
 */
@DisplayName("operation_history wire contract: every record field is described by the proto")
final class OperationHistoryWireContractConformanceTest {

  @Test
  @DisplayName("OperationHistoryEntry + InvocationProvenance ⊆ operation_history.proto")
  void everyRecordFieldHasAProtoField() {
    assertRecordSubsetOfProto(
        io.justsearch.app.observability.operations.OperationHistoryEntry.class,
        io.justsearch.contract.wire.OperationHistoryEntry.getDescriptor());
    assertRecordSubsetOfProto(
        io.justsearch.agent.api.registry.InvocationProvenance.class,
        io.justsearch.contract.wire.InvocationProvenance.getDescriptor());
  }

  @Test
  @DisplayName("Every Java operation outcome, including undo, has a proto enum value")
  void everyProducedOutcomeHasAProtoValue() {
    Set<String> declared = io.justsearch.contract.wire.OperationOutcome.getDescriptor()
        .getValues().stream().map(value -> value.getName()).collect(Collectors.toUnmodifiableSet());
    List<String> missing = Arrays.stream(io.justsearch.app.observability.operations.OperationOutcome.values())
        .map(value -> "OPERATION_OUTCOME_" + value.name())
        .filter(value -> !declared.contains(value)).toList();
    assertTrue(missing.isEmpty(), () -> "Operation outcomes missing from the retained proto: " + missing);
  }

  /**
   * Asserts every JSON field name the record can emit is present in the proto descriptor (matched
   * by {@code json_name}). The record component name is the Jackson JSON key for these records
   * (none use {@code @JsonProperty} overrides); proto {@code json_name} is the wire key the proto
   * declares.
   */
  private static void assertRecordSubsetOfProto(Class<?> recordClass, Descriptor protoDescriptor) {
    assertTrue(recordClass.isRecord(), recordClass + " must be a record");
    Set<String> protoJsonNames =
        protoDescriptor.getFields().stream()
            .map(FieldDescriptor::getJsonName)
            .collect(Collectors.toUnmodifiableSet());
    List<String> missing =
        Arrays.stream(recordClass.getRecordComponents())
            .map(RecordComponent::getName)
            .filter(name -> !protoJsonNames.contains(name))
            .collect(Collectors.toList());
    assertTrue(
        missing.isEmpty(),
        () ->
            recordClass.getSimpleName()
                + " has field(s) with no matching json_name in "
                + protoDescriptor.getFullName()
                + ": "
                + missing
                + ". Add them to contracts/wire/operation_history.proto (the gated wire contract). "
                + "Proto json_names present: "
                + protoJsonNames.stream().sorted().collect(Collectors.toList()));
  }
}
