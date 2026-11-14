package io.justsearch.app.api.knowledge;

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
 * Tempdoc 551 Part 1 — record↔proto conformance guard for the knowledge wire surface.
 *
 * <p>The app-api records ({@link KnowledgeSearchResponse} and friends) are the runtime source of
 * truth for the FE↔Head JSON; {@code contracts/wire/knowledge.proto} is the gated contract that
 * <em>describes</em> that JSON (buf breaking-change detection + protovalidate invariants). The
 * proto is hand-mirrored against the records, so nothing mechanical kept them in sync — and that is
 * exactly how tempdoc 549's {@code SearchTrace} shipped on the records + the frozen {@code
 * wire-types.ts} barrel but never reached {@code knowledge.proto}, leaving it ungated (551 §B.3).
 *
 * <p>This test closes that omission class: for every knowledge record, every JSON field the record
 * can emit must have a corresponding field in the generated proto descriptor (matched by proto
 * {@code json_name}). Adding a field to a record without adding it to the proto now fails here. The
 * reverse direction (proto carries a field the record does not) is intentionally permitted — the
 * contract may declare deprecated aliases or forward-compat fields the record has not adopted yet.
 */
@DisplayName("Knowledge wire contract: every app-api record field is described by knowledge.proto")
final class KnowledgeWireContractConformanceTest {

  @Test
  @DisplayName("KnowledgeSearchResponse + nested records ⊆ knowledge.proto (incl. the 549 SearchTrace)")
  void everyRecordFieldHasAProtoField() {
    assertRecordSubsetOfProto(
        KnowledgeSearchResponse.class, io.justsearch.contract.wire.KnowledgeSearchResponse.getDescriptor());
    assertRecordSubsetOfProto(
        KnowledgeSearchResponse.HelpMatches.class, io.justsearch.contract.wire.HelpMatches.getDescriptor());
    assertRecordSubsetOfProto(
        KnowledgeSearchResponse.Hit.class, io.justsearch.contract.wire.Hit.getDescriptor());
    assertRecordSubsetOfProto(
        KnowledgeSearchResponse.IndexCapabilities.class,
        io.justsearch.contract.wire.IndexCapabilities.getDescriptor());
    assertRecordSubsetOfProto(
        KnowledgeSearchResponse.MatchSpan.class, io.justsearch.contract.wire.MatchSpan.getDescriptor());
    assertRecordSubsetOfProto(
        KnowledgeSearchResponse.ExcerptRegion.class,
        io.justsearch.contract.wire.ExcerptRegion.getDescriptor());
    assertRecordSubsetOfProto(
        KnowledgeSearchResponse.EntityVariantBreakdown.class,
        io.justsearch.contract.wire.EntityVariantBreakdown.getDescriptor());
    assertRecordSubsetOfProto(
        KnowledgeSearchResponse.QueryUnderstanding.class,
        io.justsearch.contract.wire.QueryUnderstanding.getDescriptor());
    assertRecordSubsetOfProto(
        KnowledgeSearchResponse.FilterNormalization.class,
        io.justsearch.contract.wire.FilterNormalization.getDescriptor());
    // Tempdoc 366 §1b: the filter echo. Its payload is the REQUEST's Filters record, so the echo
    // puts a request type on the response wire — gate it here too, or a new filter member would
    // reach the response JSON undescribed.
    assertRecordSubsetOfProto(
        KnowledgeSearchResponse.AppliedFilters.class,
        io.justsearch.contract.wire.AppliedFilters.getDescriptor());
    assertRecordSubsetOfProto(
        KnowledgeSearchRequest.Filters.class,
        io.justsearch.contract.wire.SearchFilters.getDescriptor());
    assertRecordSubsetOfProto(
        KnowledgeSearchRequest.TimeRangeMs.class,
        io.justsearch.contract.wire.TimeRangeMs.getDescriptor());
    // Tempdoc 549 SearchTrace family — the fields whose omission this test exists to prevent.
    assertRecordSubsetOfProto(SearchTrace.class, io.justsearch.contract.wire.SearchTrace.getDescriptor());
    assertRecordSubsetOfProto(
        SearchTrace.Qpp.class, io.justsearch.contract.wire.TraceQpp.getDescriptor());
    assertRecordSubsetOfProto(
        SearchTrace.Degradation.class, io.justsearch.contract.wire.TraceDegradation.getDescriptor());
    assertRecordSubsetOfProto(
        SearchTrace.TraceStage.class, io.justsearch.contract.wire.TraceStage.getDescriptor());
    assertRecordSubsetOfProto(
        SearchTrace.HitStage.class, io.justsearch.contract.wire.HitStage.getDescriptor());
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
                + ". Add them to contracts/wire/knowledge.proto (the gated wire contract). "
                + "Proto json_names present: "
                + protoJsonNames.stream().sorted().collect(Collectors.toList()));
  }
}
