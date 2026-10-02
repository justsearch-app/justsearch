/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.util.TempFileManager;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VduImageLimitsTest {
  @TempDir Path tempDir;

  @Test
  void rejectsOversizedPdfBeforeRendering() throws Exception {
    Path pdf = pdf("wide.pdf", 6000, 1, 1);
    try (var files = new TempFileManager(tempDir.resolve("render"));
        var renderer = new PdfImageRenderer(files)) {
      IOException failure = assertThrows(IOException.class, () -> renderer.render(pdf));
      assertTrue(failure.getMessage().contains("allocation limit"));
      assertEquals(0, renderer.getRenderedCount());
    }
  }

  @Test
  void rejectsAggregatePixelsBeforeRenderingFirstPage() throws Exception {
    Path pdf = pdf("aggregate.pdf", 2800, 2800, 5);
    try (var files = new TempFileManager(tempDir.resolve("aggregate-render"));
        var renderer = new PdfImageRenderer(files)) {
      IOException failure = assertThrows(IOException.class, () -> renderer.render(pdf));
      assertTrue(failure.getMessage().contains("document pixels"));
      assertEquals(0, renderer.getRenderedCount());
    }
  }

  @Test
  void rejectsDirectImageDimensionsBeforePreparing() throws Exception {
    Path png = tempDir.resolve("wide.png");
    ImageIO.write(new BufferedImage(8193, 1, BufferedImage.TYPE_INT_RGB), "PNG", png.toFile());
    IOException failure = assertThrows(IOException.class, () -> new ImagePreparer().prepare(png));
    assertTrue(failure.getMessage().contains("allocation limit"));
    assertThrows(IOException.class, () -> VduImageLimits.read(png));
  }

  @Test
  void pixelLimitRejectsSquarePdfAndOrdinaryInputsStillRender() throws Exception {
    Path oversized = pdf("square.pdf", 3000, 3000, 1);
    Path ordinary = pdf("ordinary.pdf", 72, 72, 1);
    try (var files = new TempFileManager(tempDir.resolve("square-render"));
        var renderer = new PdfImageRenderer(files)) {
      IOException failure = assertThrows(IOException.class, () -> renderer.render(oversized));
      assertTrue(failure.getMessage().contains("image pixels"));
      Path rendered = renderer.render(ordinary).getFirst();
      assertTrue(new ImagePreparer().prepare(rendered).length > 0);
    }
  }

  private Path pdf(String name, float width, float height, int pages) throws IOException {
    Path path = tempDir.resolve(name);
    try (var document = new PDDocument()) {
      for (int i = 0; i < pages; i++) {
        document.addPage(new PDPage(new PDRectangle(width, height)));
      }
      document.save(path.toFile());
    }
    return path;
  }
}
