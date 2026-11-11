/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

class ExifImageOrientationTest {
  @TempDir Path tempDir;

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
  void jpegOrientationIsAppliedToDerivedImageAndSourceIsUntouched(int orientation) throws Exception {
    var pixels = new BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB);
    var g = pixels.createGraphics();
    Color[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW};
    for (int i = 0; i < colors.length; i++) {
      g.setColor(colors[i]);
      g.fillRect(i % 3 * 32, i / 3 * 32, 32, 32);
    }
    g.dispose();
    var encoded = new ByteArrayOutputStream();
    ImageIO.write(pixels, "jpeg", encoded);
    byte[] jpeg = encoded.toByteArray();
    Path source = tempDir.resolve("orientation-" + orientation + ".jpg");
    Files.write(source, withExif(jpeg, orientation, orientation % 2 == 0));
    Files.setLastModifiedTime(source, FileTime.fromMillis(1_600_000_000_000L));
    byte[] before = Files.readAllBytes(source);
    FileTime beforeTime = Files.getLastModifiedTime(source);
    var decoded = ImageIO.read(source.toFile());
    String[] layouts = {"", "abcdef", "cbafed", "fedcba", "defabc", "adbecf", "daebfc", "fcebda", "cfbead"};
    var config = new OcrRoutingConfig(true, List.of("eng"), 5_000, 1, 4096, 40_000_000, 72, 1);
    Process process = mock(Process.class);
    when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
    try (var engine = new PdfOcrEngine(Executors::newFixedThreadPool, config, command -> {
      Path input = Path.of(command.get(1));
      assertEquals(orientation == 1, input.equals(source), "Only oriented images need a derived file");
      var actual = ImageIO.read(input.toFile());
      int columns = orientation < 5 ? 3 : 2;
      int rows = orientation < 5 ? 2 : 3;
      assertEquals(columns * 32, actual.getWidth());
      assertEquals(rows * 32, actual.getHeight());
      for (int i = 0; i < 6; i++) {
        int original = layouts[orientation].charAt(i) - 'a';
        assertEquals(decoded.getRGB(original % 3 * 32 + 16, original / 3 * 32 + 16),
            actual.getRGB(i % columns * 32 + 16, i / columns * 32 + 16));
      }
      Files.writeString(Path.of(command.get(2) + ".txt"), "Recognised heading");
      return process;
    }, "stub", 72, 1, null)) {
      assertEquals("Recognised heading", engine.ocrImage(source, 1000).text());
    }
    assertArrayEquals(before, Files.readAllBytes(source));
    assertEquals(beforeTime, Files.getLastModifiedTime(source));
  }

  static byte[] withExif(byte[] jpeg, int orientation, boolean littleEndian) throws Exception {
    var payload = ByteBuffer.allocate(32).order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    payload.put(new byte[] {'E', 'x', 'i', 'f', 0, 0});
    payload.putShort((short) (littleEndian ? 0x4949 : 0x4d4d));
    payload.putShort((short) 42).putInt(8).putShort((short) 1);
    payload.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0).putInt(0);
    var out = new ByteArrayOutputStream();
    // ImageIO writes a JFIF APP0 segment, which must remain immediately after SOI.
    int exifAt = 4 + ((jpeg[4] & 0xff) << 8 | (jpeg[5] & 0xff));
    out.write(jpeg, 0, exifAt);
    out.write(new byte[] {(byte) 0xff, (byte) 0xe1, 0, 34});
    out.write(payload.array());
    out.write(jpeg, exifAt, jpeg.length - exifAt);
    return out.toByteArray();
  }
}
