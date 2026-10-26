/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.chunking;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/** Frozen a5ff output is the oracle; unsafe discarded windows are deliberately not observed. */
class EmittedPlanCompatibilityTest {
  private record Span(int start, int end) {}

  @Test
  void discardedCsvWindowCannotChangeAnAlreadySafeEmittedPlan() {
    String source = "aaaaaa\uD83D\uDE00a\n" + "b".repeat(20);
    var chunks = ChunkSplitter.splitWithMetadata(source, 1, 1, ChunkSplitter.Mode.CSV);
    assertEquals(List.of(new Span(0, 9), new Span(10, 13), new Span(13, 16), new Span(16, 19),
        new Span(19, 22), new Span(22, 25), new Span(25, 28), new Span(28, 30)), spans(chunks));
    boolean[] covered = coverage(source, spans(chunks));
    for (int i = 0; i < source.length(); i++) {
      if (!Character.isWhitespace(source.charAt(i))) assertTrue(covered[i], "lost source unit " + i);
    }
  }

  @Test
  void expansionCollisionsKeepWholePairsWithoutMergingPartialOverlap() {
    for (var mode : ChunkSplitter.Mode.values()) {
      String emoji = "\uD83D\uDE00";
      assertEquals(List.of(emoji), ChunkSplitter.split(emoji, 0, 0, mode));
      assertEquals(List.of(ChunkSplitter.Chunk.of(0, emoji, 0, 2)),
          ChunkSplitter.splitWithMetadata(emoji, 0, 0, mode));
      assertEquals(List.of(emoji, emoji), ChunkSplitter.split(emoji.repeat(2), 0, 0, mode));
      assertEquals(List.of(new Span(0, 2), new Span(2, 4)),
          spans(ChunkSplitter.splitWithMetadata(emoji.repeat(2), 0, 0, mode)));
    }
    String crossing = "\uD83D\uDE00" + "a".repeat(99) + "\ntail";
    assertEquals(List.of(crossing.substring(0, 101), "t", "a", "i", "l"),
        ChunkSplitter.split(crossing, 0, 0, ChunkSplitter.Mode.CSV));
    assertEquals(List.of(new Span(0, 101), new Span(102, 103), new Span(103, 104),
        new Span(104, 105), new Span(105, 106)),
        spans(ChunkSplitter.splitWithMetadata(crossing, 0, 0, ChunkSplitter.Mode.CSV)));
    assertEquals(List.of(new Span(0, 4), new Span(2, 6)),
        spans(ChunkSplitter.splitWithMetadata("aa\uD83D\uDE00bb", 1, 0, ChunkSplitter.Mode.DEFAULT)));
  }

  @Test
  void frozenV1PlansPreserveSafeRecordsAndNeverLoseCoveredSource() throws Exception {
    int safePlans = 0;
    int repairedPlans = 0;
    try (var resource = getClass().getResourceAsStream("/chunking/v1-emitted-plans.bin.gz")) {
      assertNotNull(resource);
      try (var input = new DataInputStream(new GZIPInputStream(resource))) {
        assertEquals("a5ff12b4001ea33c5b5846888d6c8fbf07f8c926", input.readUTF());
        int cases = input.readInt();
        assertTrue(cases > 800);
        for (int scenario = 0; scenario < cases; scenario++) {
          String source = input.readUTF();
          int target = input.readInt();
          int overlap = input.readInt();
          var mode = ChunkSplitter.Mode.valueOf(input.readUTF());
          var oldPlain = new ArrayList<String>();
          var oldPlainSpans = new ArrayList<Span>();
          int plainCount = input.readInt();
          for (int i = 0; i < plainCount; i++) {
            oldPlain.add(input.readUTF());
            oldPlainSpans.add(new Span(input.readInt(), input.readInt()));
          }
          var oldMetadata = new ArrayList<ChunkSplitter.Chunk>();
          int count = input.readInt();
          for (int i = 0; i < count; i++) oldMetadata.add(new ChunkSplitter.Chunk(
              input.readInt(), input.readUTF(), input.readInt(), input.readInt(), input.readInt()));
          var actual = ChunkSplitter.splitWithMetadata(source, target, overlap, mode);
          String label = "case " + scenario + " " + mode + " " + target + "/" + overlap;
          if (pairSafe(source, spans(oldMetadata))) {
            assertEquals(oldMetadata, actual, label + " complete pair-safe record identity");
            safePlans++;
          } else {
            repairedPlans++;
            assertTrue(pairSafe(source, spans(actual)), label);
            boolean[] before = coverage(source, spans(oldMetadata));
            boolean[] after = coverage(source, spans(actual));
            for (int i = 0; i < before.length; i++) if (before[i]) assertTrue(after[i], label + " lost " + i);
            for (int i = 0; i < actual.size(); i++) {
              var c = actual.get(i);
              assertEquals(i, c.index(), label);
              assertEquals(source.substring(c.startChar(), c.endChar()), c.content(), label);
              assertEquals(ChunkSplitter.estimateTokens(c.content()), c.estimatedTokens(), label);
              for (int j = 0; j < i; j++) assertFalse(contains(spans(actual).get(j), spans(actual).get(i))
                  || contains(spans(actual).get(i), spans(actual).get(j)), label + " new redundant span");
            }
          }
          var plain = ChunkSplitter.split(source, target, overlap, mode);
          if (pairSafe(source, oldPlainSpans)) assertEquals(oldPlain, plain, label + " plain identity");
          else {
            // Mathematical outward expansion of true frozen SOURCE coordinates, never indexOf.
            var expanded = oldPlainSpans.stream().map(s -> new Span(
                s.start() - (cut(source, s.start()) ? 1 : 0), s.end() + (cut(source, s.end()) ? 1 : 0))).toList();
            var required = new ArrayList<String>();
            for (int i = 0; i < expanded.size(); i++) {
              boolean redundant = false;
              for (int j = 0; j < expanded.size(); j++) {
                if (i == j || !contains(expanded.get(j), expanded.get(i))
                    || contains(oldPlainSpans.get(j), oldPlainSpans.get(i))) continue;
                if (!expanded.get(j).equals(expanded.get(i)) || j < i
                    || contains(oldPlainSpans.get(i), oldPlainSpans.get(j))) redundant = true;
              }
              if (!redundant) required.add(source.substring(expanded.get(i).start(), expanded.get(i).end()));
            }
            assertEquals(required, plain, label + " plain outward source geometry");
          }
        }
        assertEquals(-1, input.read());
      }
    }
    assertTrue(safePlans > 100);
    assertTrue(repairedPlans > 100);
  }

  @Test
  void originalMalformedAndReturnMutabilityContractsRemain() {
    assertThrows(UnsupportedOperationException.class, () -> ChunkSplitter.split(null).add("x"));
    assertThrows(UnsupportedOperationException.class, () -> ChunkSplitter.split(" \n").add("x"));
    assertThrows(UnsupportedOperationException.class,
        () -> ChunkSplitter.splitWithMetadata(null).add(ChunkSplitter.Chunk.of(0, "x", 0, 1)));
    assertThrows(UnsupportedOperationException.class,
        () -> ChunkSplitter.splitWithMetadata(" \n").add(ChunkSplitter.Chunk.of(0, "x", 0, 1)));
    for (String source : List.of("\0", "\uD800x\uDC00", "\uD83D\uDE00\uDE00\uD83D\uDE00\uD83D")) {
      assertDoesNotThrow(() -> ChunkSplitter.split(source).add("x"));
      assertDoesNotThrow(() -> ChunkSplitter.splitWithMetadata(source).add(ChunkSplitter.Chunk.of(0, "x", 0, 1)));
    }
  }

  private static List<Span> spans(List<ChunkSplitter.Chunk> chunks) {
    return chunks.stream().map(c -> new Span(c.startChar(), c.endChar())).toList();
  }
  private static boolean cut(String source, int i) {
    return i > 0 && i < source.length() && Character.isHighSurrogate(source.charAt(i - 1))
        && Character.isLowSurrogate(source.charAt(i));
  }
  private static boolean pairSafe(String source, List<Span> spans) {
    return spans.stream().noneMatch(s -> cut(source, s.start()) || cut(source, s.end()));
  }
  private static boolean contains(Span outer, Span inner) {
    return outer.start() <= inner.start() && outer.end() >= inner.end();
  }
  private static boolean[] coverage(String source, List<Span> spans) {
    boolean[] covered = new boolean[source.length()];
    for (Span span : spans) java.util.Arrays.fill(covered, span.start(), span.end(), true);
    return covered;
  }
}
