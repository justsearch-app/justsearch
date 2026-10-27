package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.knowledge.CrossEncoderSkipReason;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.api.knowledge.SearchTrace;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 549 Slice 5 (U5): producer-side stage-completeness. The compile-time guard is the
 * exhaustive {@code switch} over {@link SearchTraceMapper.HeadStage} in
 * {@code buildHeadStages} (no {@code default}) — adding a stage breaks the build until it is
 * emitted. This test is the runtime backstop: it asserts every declared {@code HeadStage}
 * actually appears in the emitted trace (catching a regression where someone re-adds a
 * {@code default} branch or otherwise stops emitting a stage), and that the wire IDs are stable.
 */
@DisplayName("SearchTraceMapper.buildHeadStages: every HeadStage emits a node (U5)")
final class KnowledgeHttpApiAdapterHeadStagesTest {

  @Test
  void everyHeadStageEmitsExactlyOneNodeWithItsWireId() {
    List<SearchTrace.TraceStage> stages =
        SearchTraceMapper.buildHeadStages(
            PipelineConfig.TEXT,
            0L,
            0L,
            false,
            "skip",
            false,
            CrossEncoderSkipReason.PIPELINE_NOT_ELIGIBLE,
            false,
            "skip",
            null,
            "DISABLED");

    // One node per declared stage — no more (no duplicates), no fewer (none dropped).
    assertEquals(SearchTraceMapper.HeadStage.values().length, stages.size());

    List<String> emittedIds = stages.stream().map(s -> s.id().wireId()).toList();
    List<String> expectedWireIds =
        java.util.Arrays.stream(SearchTraceMapper.HeadStage.values())
            .map(s -> s.wireId)
            .collect(Collectors.toList());
    // Exhaustive + ordered: the emitted wire IDs match the closed vocabulary exactly.
    assertEquals(expectedWireIds, emittedIds);
  }

  @Test
  @DisplayName("F-052: a cross-encoder drop reaches the trace stage as its governed wire string")
  void crossEncoderDropCarriesGovernedWireString() {
    for (CrossEncoderSkipReason reason :
        List.of(
            CrossEncoderSkipReason.DEADLINE_EXCEEDED,
            CrossEncoderSkipReason.RPC_FAILED,
            CrossEncoderSkipReason.MODEL_NOT_LOADED,
            // F-054: the inference-failure drop must reach the stage under its own wire string,
            // not folded back into DEADLINE_EXCEEDED.
            CrossEncoderSkipReason.INFERENCE_FAILED,
            CrossEncoderSkipReason.UNKNOWN)) {
      SearchTrace.TraceStage ce =
          SearchTraceMapper.buildHeadStages(
                  PipelineConfig.TEXT, 0L, 0L, false, "skip", false, reason, false, "skip", null, "DISABLED")
              .stream()
              .filter(s -> "cross-encoder".equals(s.id().wireId()))
              .findFirst()
              .orElseThrow();
      assertEquals(SearchTrace.StageStatus.SKIPPED, ce.status());
      assertEquals(reason.wire(), ce.reason());
      assertTrue(reason.isDrop(), reason + " must classify as a drop");
    }
  }

  @Test
  @DisplayName("wire IDs are the stable contract the FE renderer reads")
  void wireIdsAreStable() {
    // Pinning the wire contract: a rename here is a breaking FE change and must be deliberate.
    List<String> ids =
        java.util.Arrays.stream(SearchTraceMapper.HeadStage.values())
            .map(s -> s.wireId)
            .toList();
    assertEquals(
        List.of("query-understanding", "expansion", "lambdamart", "cross-encoder", "freshness"),
        ids);
  }
}
