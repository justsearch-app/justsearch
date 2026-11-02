/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenWindowsTest {
  @TempDir Path temporaryDirectory;

  @Test
  void everyWindowRepeatsModelFramingAndCoversAllContent() {
    long[] ids = sequence(1703, 1);
    long[] mask = sequence(1703, 2);
    long[] types = sequence(1703, 3);
    var windows = new TokenWindows(ids, mask, types, 512, 128, 512);
    var covered = new java.util.HashSet<Long>();
    for (long[][] window : windows) {
      assertTrue(window[0].length <= 512);
      for (int field = 0; field < 3; field++) {
        assertEquals(new long[][] {ids, mask, types}[field][0], window[field][0]);
        assertEquals(new long[][] {ids, mask, types}[field][1702], window[field][window[field].length - 1]);
        assertEquals(window[0].length, window[field].length);
      }
      for (int i = 1; i < window[0].length - 1; i++) covered.add(window[0][i]);
    }
    assertEquals(1701, covered.size());
  }

  @Test
  void matchesFramedGeometryAcrossSmallShapesAndTailBoundaries() {
    for (int chunkSize = 3; chunkSize <= 20; chunkSize++) {
      for (int overlap : new int[] {0, chunkSize / 4, chunkSize - 1, chunkSize, chunkSize + 1}) {
        for (int maxSeqLen : new int[] {3, chunkSize, chunkSize + 7}) {
          for (int tokens = 2; tokens <= chunkSize * 4; tokens++) {
            assertGeometry(tokens, chunkSize, overlap, maxSeqLen);
          }
        }
      }
    }
    for (int maxSeqLen : new int[] {512, 8192}) {
      for (int chunkSize : new int[] {384, 512, maxSeqLen}) {
        int stride = chunkSize - chunkSize / 4;
        for (int boundary : new int[] {1, chunkSize, stride, stride + chunkSize / 4, stride * 3}) {
          for (int delta = -1; delta <= 1; delta++) {
            assertGeometry(boundary + delta, chunkSize, chunkSize / 4, maxSeqLen);
          }
        }
      }
    }
  }

  @Test
  void sublistsPreserveOrderAndEachGetReturnsIndependentFieldCopies() {
    long[] ids = sequence(1600, 1);
    long[] masks = sequence(1600, 2);
    long[] types = sequence(1600, 3);
    TokenWindows windows = new TokenWindows(ids, masks, types, 512, 128, 8192);
    List<long[][]> eager = TokenWindowsHeapProbe.eagerOracle(ids, masks, types, 512, 128, 8192);
    List<long[][]> slice = windows.subList(1, windows.size()).subList(1, windows.size() - 1);
    for (int index = slice.size() - 1; index >= 0; index--) {
      for (int field = 0; field < 3; field++) {
        assertArrayEquals(eager.get(index + 2)[field], slice.get(index)[field]);
      }
    }
    long[][] first = windows.get(0);
    long[][] second = windows.get(0);
    for (int field = 0; field < 3; field++) {
      assertNotSame(first[field], second[field]);
      first[field][0] = -99;
      assertEquals(field + 1, second[field][0]);
    }
    assertEquals(1, ids[0]);
    assertEquals(2, masks[0]);
    assertEquals(3, types[0]);
    assertThrows(IndexOutOfBoundsException.class, () -> windows.get(-1));
    assertThrows(IndexOutOfBoundsException.class, () -> windows.get(windows.size()));
  }

  @Test
  void countHandlesIntegerLimitWithoutTokenArraysOrWindowMetadata() {
    assertEquals(Integer.MAX_VALUE - 2, TokenWindows.count(Integer.MAX_VALUE, 3, 0, 3));
    assertEquals(0, TokenWindows.count(0, 512, 128, 8192));
    TokenWindows empty = new TokenWindows(new long[0], new long[0], new long[0], 512, 128, 8192);
    assertEquals(0, empty.size());
    assertThrows(IndexOutOfBoundsException.class, () -> empty.get(0));
    assertThrows(IllegalArgumentException.class, () -> TokenWindows.count(-1, 512, 128, 8192));
    assertThrows(IllegalArgumentException.class, () -> TokenWindows.count(1, 0, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> TokenWindows.count(1, 1, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TokenWindows(new long[1], new long[0], new long[1], 512, 128, 8192));
  }

  @Test
  void threeMillionTokensFitWithEightLazyWindowsButEagerCopiesExhaust128MiB() throws Exception {
    assertTrue(runHeapProbe("lazy").contains("LAZY_OK"));
    assertTrue(runHeapProbe("eager").contains("EAGER_OOM"));
  }

  private String runHeapProbe(String mode) throws Exception {
    String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath =
        Path.of(TokenWindowsHeapProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            + File.pathSeparator
            + Path.of(TokenWindows.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Path output = temporaryDirectory.resolve(mode + ".log");
    Process child =
        new ProcessBuilder(
                javaBinary, "-Xmx128m", "-XX:+UseSerialGC", "-cp", classpath,
                TokenWindowsHeapProbe.class.getName(), mode)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(child.waitFor(45, TimeUnit.SECONDS), "Heap probe did not exit");
      String text = Files.readString(output);
      assertEquals(0, child.exitValue(), text);
      return text;
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  private static void assertGeometry(int tokens, int chunkSize, int overlap, int maxSeqLen) {
    long[] ids = sequence(tokens, 1);
    long[] masks = sequence(tokens, 2);
    long[] types = sequence(tokens, 3);
    List<long[][]> eager =
        TokenWindowsHeapProbe.eagerOracle(ids, masks, types, chunkSize, overlap, maxSeqLen);
    TokenWindows lazy = new TokenWindows(ids, masks, types, chunkSize, overlap, maxSeqLen);
    String shape =
        "tokens=" + tokens + " chunk=" + chunkSize + " overlap=" + overlap + " max=" + maxSeqLen;
    assertEquals(eager.size(), lazy.size(), shape);
    assertEquals(eager.size(), TokenWindows.count(tokens, chunkSize, overlap, maxSeqLen), shape);
    for (int index = 0; index < eager.size(); index++) {
      long[][] window = lazy.get(index);
      for (int field = 0; field < 3; field++) {
        assertArrayEquals(
            eager.get(index)[field], window[field], shape + " window=" + index + " field=" + field);
      }
    }
  }

  private static long[] sequence(int size, int field) {
    long[] result = new long[size];
    for (int index = 0; index < size; index++) {
      result[index] = index * 10L + field;
    }
    return result;
  }
}
