/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Spec E revision 4 §2.2 "Call shapes": the packer cuts calls by the plan read for the call, and
 * without a plan (or on a profile that keeps today's constants) takes today's count. The reference
 * shapes are those of the §2.2 profile table, from the provisional M2b coefficients.
 */
final class GpuCallPackerTest {

  private static final Optional<GpuArenaPlan> NO_PLAN = Optional.empty();
  private static final Optional<GpuArenaPlan> P16 =
      Optional.of(GpuArenaPlanTest.resolve(16_376, GpuArenaPlanTest.STANDARD_ONLY));
  private static final Optional<GpuArenaPlan> P12 =
      Optional.of(GpuArenaPlanTest.resolve(12_282, GpuArenaPlanTest.STANDARD_ONLY));
  private static final Optional<GpuArenaPlan> P8 =
      Optional.of(GpuArenaPlanTest.resolve(8_188, GpuArenaPlanTest.WITH_COMPACT));
  private static final Optional<GpuArenaPlan> UNPLANNED =
      Optional.of(GpuArenaPlanTest.resolve(10_907, GpuArenaPlanTest.STANDARD_ONLY));

  private static int embedding(Optional<GpuArenaPlan> plan, int remaining, int longest) {
    return GpuCallPacker.itemsPerCall(plan, EncoderRole.EMBEDDING, 8, remaining, i -> longest);
  }

  private static int splade(Optional<GpuArenaPlan> plan, int remaining, int bucket) {
    return GpuCallPacker.itemsPerCall(plan, EncoderRole.SPLADE, 4, remaining, i -> bucket);
  }

  /** The reranker's padded pair buckets (CrossEncoderReranker.bucketBatchSize). */
  private static final IntUnaryOperator RERANK_BUCKET =
      n -> {
        for (int bucket : new int[] {4, 8, 16, 24, 32, 48, 64}) {
          if (n <= bucket) return bucket;
        }
        return n;
      };

  private static List<Integer> rerankCalls(Optional<GpuArenaPlan> plan, int pairs) {
    List<Integer> calls = new ArrayList<>();
    int remaining = pairs;
    while (remaining > 0) {
      int items =
          GpuCallPacker.itemsPerCall(
              plan, EncoderRole.RERANKER, Integer.MAX_VALUE, remaining, i -> 512, l -> l,
              RERANK_BUCKET);
      calls.add(items);
      remaining -= items;
    }
    return calls;
  }

  @Test
  void withoutAPlanEveryCallTakesTodaysCount() {
    for (Optional<GpuArenaPlan> plan : List.of(NO_PLAN, UNPLANNED)) {
      assertEquals(8, embedding(plan, 20, 2_048));
      assertEquals(3, embedding(plan, 3, 8_192));
      assertEquals(4, splade(plan, 9, 512));
      assertEquals(16, GpuCallPacker.itemsPerCall(plan, EncoderRole.NER, 16, 40, i -> 512));
      assertEquals(List.of(50), rerankCalls(plan, 50), "today's rerank is one call");
      assertEquals(6_000, GpuCallPacker.lateChunkingMaxTokens(plan, 6_000));
    }
  }

  @Test
  void p16PlusKeepsEveryOneOfTodaysShapes() {
    assertEquals(8, embedding(P16, 20, 2_048));
    assertEquals(4, splade(P16, 9, 512));
    assertEquals(16, GpuCallPacker.itemsPerCall(P16, EncoderRole.NER, 16, 40, i -> 512));
    assertEquals(List.of(20), rerankCalls(P16, 20));
    assertEquals(List.of(50), rerankCalls(P16, 50));
    assertEquals(8_192, GpuCallPacker.lateChunkingMaxTokens(P16, 8_192));
    assertEquals(4_096, GpuCallPacker.lateChunkingMaxTokens(P16, 4_096),
        "the plan only lowers the configured limit");
  }

  @Test
  void p12ShapesMatchTheProfileTable() {
    assertEquals(8, embedding(P12, 20, 1_770));
    assertEquals(6, embedding(P12, 20, 2_048));
    assertEquals(3, splade(P12, 9, 512));
    assertEquals(4, splade(P12, 9, 256));
    assertEquals(16, GpuCallPacker.itemsPerCall(P12, EncoderRole.NER, 16, 40, i -> 512));
    assertEquals(List.of(20), rerankCalls(P12, 20), "top-20 is one call (bucket 24)");
    assertEquals(List.of(48, 2), rerankCalls(P12, 50));
    assertEquals(5_632, GpuCallPacker.lateChunkingMaxTokens(P12, 8_192));
  }

  @Test
  void p8ShapesMatchTheProfileTable() {
    assertEquals(8, embedding(P8, 20, 860));
    assertEquals(2, embedding(P8, 20, 2_048));
    assertEquals(1, splade(P8, 9, 512));
    assertEquals(16, GpuCallPacker.itemsPerCall(P8, EncoderRole.NER, 16, 40, i -> 512));
    assertEquals(List.of(16, 4), rerankCalls(P8, 20));
    assertEquals(List.of(16, 16, 16, 2), rerankCalls(P8, 50));
    assertEquals(3_072, GpuCallPacker.lateChunkingMaxTokens(P8, 8_192));
  }

  @Test
  void aCallPadsToItsLongestItemSoAPrefixStopsAtTheFirstItemThatDoesNotFit() {
    int[] lengths = {128, 256, 2_048, 128, 128, 128};
    // P8: the third item pads the call to 2,048, and three items at 2,048 (915) do not fit.
    assertEquals(2,
        GpuCallPacker.itemsPerCall(P8, EncoderRole.EMBEDDING, 8, lengths.length, i -> lengths[i]));
    int[] shortFirst = {128, 128, 128, 128, 2_048, 128};
    assertEquals(4,
        GpuCallPacker.itemsPerCall(P8, EncoderRole.EMBEDDING, 8, shortFirst.length,
            i -> shortFirst[i]));
  }

  @Test
  void aSingleItemAlwaysRunsAndUnmeasuredRolesAreNotPacked() {
    GpuArenaPlan tiny =
        GpuArenaPlan.resolve(
            GpuArenaPlanTest.facts(12_282, GpuArenaPlanTest.STANDARD_ONLY),
            new GpuArenaPlan.Settings(Optional.empty(), OptionalInt.empty(), OptionalInt.empty(),
                Map.of(EncoderRole.SPLADE, 474.2 + 10), false));
    assertEquals(1, splade(Optional.of(tiny), 9, 512),
        "a call the budget cannot hold still takes one item");

    GpuArenaPlan withBgeM3 =
        GpuArenaPlan.resolve(
            GpuArenaPlanTest.facts(8_188, GpuArenaPlanTest.WITH_COMPACT,
                GpuArenaPlan.ExternalChat.NONE, Map.of(EncoderRole.BGE_M3, 1_100.0)),
            GpuArenaPlan.Settings.none());
    assertEquals(4,
        GpuCallPacker.itemsPerCall(Optional.of(withBgeM3), EncoderRole.BGE_M3, 4, 9, i -> 8_192));
    assertEquals(Optional.of(GpuArenaPlan.BGE_M3_UNMEASURED),
        GpuCallPacker.bulkCpuReason(Optional.of(withBgeM3), EncoderRole.BGE_M3));
    assertEquals(Optional.empty(), GpuCallPacker.bulkCpuReason(NO_PLAN, EncoderRole.BGE_M3));
  }
}
