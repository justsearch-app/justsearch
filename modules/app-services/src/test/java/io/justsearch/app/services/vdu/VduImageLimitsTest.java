/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.util.TempFileManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VduImageLimitsTest {
  @TempDir Path tempDir;

  @Test
  void smallPageRejectsOversizedImageXObjectAndImageInsideForm() throws Exception {
    for (boolean insideForm : new boolean[] {false, true}) {
      Path path = tempDir.resolve("image-" + insideForm + ".pdf");
      try (var document = new PDDocument()) {
        var page = new PDPage(new PDRectangle(72, 72));
        document.addPage(page);
        var image = image(document, 8193, 1, false);
        try (var content = new PDPageContentStream(document, page)) {
          if (insideForm) {
            var form = new PDFormXObject(document);
            form.setBBox(new PDRectangle(72, 72));
            form.setResources(new PDResources());
            try (var formContent = new PDFormContentStream(form)) {
              formContent.drawImage(image, 0, 0, 10, 10);
            }
            content.drawForm(form);
          } else {
            content.drawImage(image, 0, 0, 10, 10);
          }
        }
        document.save(path.toFile());
      }
      assertRejectedImage(path, "allocation limit");
    }
  }

  @Test
  void smallImageRejectsOversizedSoftAndExplicitMasks() throws Exception {
    for (COSName maskKey : new COSName[] {COSName.SMASK, COSName.MASK}) {
      Path path = tempDir.resolve(maskKey.getName() + ".pdf");
      try (var document = new PDDocument()) {
        var page = new PDPage(new PDRectangle(72, 72));
        document.addPage(page);
        var image = image(document, 1, 1, false);
        var mask = image(document, 8193, 1, true);
        if (maskKey.equals(COSName.MASK)) {
          mask.setStencil(true);
          mask.setBitsPerComponent(1);
        }
        image.getCOSObject().setItem(maskKey, mask);
        try (var content = new PDPageContentStream(document, page)) {
          content.drawImage(image, 0, 0, 10, 10);
        }
        document.save(path.toFile());
      }
      assertRejectedImage(path, "allocation limit");
    }
  }

  @Test
  void rejectsInlineImageBeforePdfBoxConstructsIt() throws Exception {
    Path path = tempDir.resolve("inline.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage(new PDRectangle(72, 72));
      document.addPage(page);
      var stream = new PDStream(document);
      try (var output = stream.createOutputStream()) {
        output.write("q 10 0 0 10 0 0 cm BI /W 8193 /H 1 /CS /RGB /BPC 8 ID\n"
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.write(new byte[8193 * 3]);
        output.write("\nEI Q\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      }
      page.setContents(stream);
      document.save(path.toFile());
    }
    assertRejectedImage(path, "allocation limit");
  }

  @Test
  void boundsAggregateSourceImagePixelsOnSmallPage() throws Exception {
    Path path = tempDir.resolve("source-aggregate.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage(new PDRectangle(72, 72));
      document.addPage(page);
      var image = image(document, 4000, 4000, false);
      try (var content = new PDPageContentStream(document, page)) {
        for (int i = 0; i < 5; i++) content.drawImage(image, 0, 0, 10, 10);
      }
      document.save(path.toFile());
    }
    assertRejectedImage(path, "decoded PDF image pixels");
  }

  @Test
  void rejectsJpegCodestreamDimensionsEvenWhenDictionaryIsSmall() throws Exception {
    Path path = tempDir.resolve("jpeg-header.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage(new PDRectangle(72, 72));
      document.addPage(page);
      var encoded = new ByteArrayOutputStream();
      ImageIO.write(new BufferedImage(8193, 1, BufferedImage.TYPE_INT_RGB), "JPEG", encoded);
      var image = new PDImageXObject(document, new ByteArrayInputStream(encoded.toByteArray()),
          COSName.DCT_DECODE, 1, 1, 8, PDDeviceRGB.INSTANCE);
      try (var content = new PDPageContentStream(document, page)) {
        content.drawImage(image, 0, 0, 10, 10);
      }
      document.save(path.toFile());
    }
    assertRejectedImage(path, "allocation limit");
  }

  @Test
  void ordinaryPdfJpegStillRenders() throws Exception {
    Path path = tempDir.resolve("ordinary-jpeg.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage(new PDRectangle(72, 72));
      document.addPage(page);
      var encoded = new ByteArrayOutputStream();
      ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "JPEG", encoded);
      var image = new PDImageXObject(document, new ByteArrayInputStream(encoded.toByteArray()),
          COSName.DCT_DECODE, 10, 10, 8, PDDeviceRGB.INSTANCE);
      try (var content = new PDPageContentStream(document, page)) {
        content.drawImage(image, 0, 0, 10, 10);
      }
      document.save(path.toFile());
    }
    try (var files = new TempFileManager(tempDir.resolve("ordinary-jpeg-render"));
        var renderer = new PdfImageRenderer(files)) {
      assertEquals(1, renderer.render(path).size());
    }
  }

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

  private void assertRejectedImage(Path path, String reason) throws Exception {
    try (var files = new TempFileManager(tempDir.resolve("render-" + path.getFileName()));
        var renderer = new PdfImageRenderer(files)) {
      IOException failure = assertThrows(IOException.class, () -> renderer.render(path));
      assertTrue(failure.getMessage().contains(reason), failure.getMessage());
      assertEquals(0, renderer.getRenderedCount());
    }
  }

  private static PDImageXObject image(PDDocument document, int width, int height, boolean gray)
      throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var compressed = new DeflaterOutputStream(bytes)) {
      byte[] chunk = new byte[8192];
      long remaining = (long) width * height * (gray ? 1 : 3);
      while (remaining > 0) {
        int count = (int) Math.min(chunk.length, remaining);
        compressed.write(chunk, 0, count);
        remaining -= count;
      }
    }
    return new PDImageXObject(document, new ByteArrayInputStream(bytes.toByteArray()),
        COSName.FLATE_DECODE, width, height, 8, gray ? PDDeviceGray.INSTANCE : PDDeviceRGB.INSTANCE);
  }
}
