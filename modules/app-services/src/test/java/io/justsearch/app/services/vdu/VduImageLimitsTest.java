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
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.function.PDFunction;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VduImageLimitsTest {
  @TempDir Path tempDir;

  @Test
  void predictorParametersAreCheckedBeforeXObjectDecoding() throws Exception {
    for (COSName filter : new COSName[] {COSName.FLATE_DECODE, COSName.LZW_DECODE}) {
      for (boolean array : new boolean[] {false, true}) {
        Path path = predictorPdf(filter.getName() + array, filter, predictor(8193), array, null);
        assertRejectedImage(path, "decode parameters");
      }
    }
  }

  @Test
  void predictorParametersAreCheckedBeforeExplicitAndSoftMaskDecoding() throws Exception {
    for (COSName key : new COSName[] {COSName.MASK, COSName.SMASK}) {
      for (COSName filter : new COSName[] {COSName.FLATE_DECODE, COSName.LZW_DECODE}) {
        Path path = predictorPdf(key.getName() + filter.getName(), filter,
            predictor(200_000_000), true, key);
        assertRejectedImage(path, "decode parameters");
      }
    }
  }

  @Test
  void inlinePredictorParametersAndAbbreviatedArraysAreCheckedBeforeConstruction()
      throws Exception {
    for (String filter : new String[] {"Fl", "LZW"}) {
      for (boolean array : new boolean[] {false, true}) {
        Path path = tempDir.resolve("inline-predictor-" + filter + array + ".pdf");
        try (var document = new PDDocument()) {
          var page = new PDPage(new PDRectangle(72, 72));
          document.addPage(page);
          var stream = new PDStream(document);
          String params = "<< /Predictor 12 /Colors 1 /BitsPerComponent 8 /Columns 8193 >>";
          String dictionary = array ? "/F [/AHx /" + filter + "] /DP [null " + params + "]"
              : "/Filter /" + filter + " /DecodeParms " + params;
          var compressed = new ByteArrayOutputStream();
          FilterFactory.INSTANCE.getFilter(filter).encode(
              new ByteArrayInputStream(new byte[] {0, 127}), compressed, new COSDictionary(), 0);
          try (var output = stream.createOutputStream()) {
            output.write(("q 10 0 0 10 0 0 cm BI /W 1 /H 1 /CS /G /BPC 8 "
                + dictionary + " ID\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if (array) {
              output.write(java.util.HexFormat.of().formatHex(compressed.toByteArray())
                  .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
              output.write('>');
            } else {
              output.write(compressed.toByteArray());
            }
            output.write("\nEI Q\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
          }
          page.setContents(stream);
          document.save(path.toFile());
        }
        assertRejectedImage(path, "decode parameters");
      }
    }
  }

  @Test
  void predictorArithmeticAndFaxDimensionsCannotWrapOrHideIndependentAllocations()
      throws Exception {
    var overflow = predictor(1);
    overflow.setItem(COSName.COLUMNS, COSInteger.get(4_294_967_297L));
    assertRejectedImage(predictorPdf("overflow", COSName.FLATE_DECODE, overflow, false, null),
        "decode parameters");
    for (COSName parameter : new COSName[] {COSName.COLORS, COSName.BITS_PER_COMPONENT}) {
      var params = predictor(1);
      params.setInt(parameter, Integer.MAX_VALUE);
      assertRejectedImage(
          predictorPdf(parameter.getName(), COSName.LZW_DECODE, params, false, null),
          "decode parameters");
    }
    var fax = new COSDictionary();
    fax.setInt(COSName.COLUMNS, 200_000_000);
    assertRejectedImage(predictorPdf("fax", COSName.CCITTFAX_DECODE, fax, false, null),
        "allocation limit");
  }

  @Test
  void predictorBuffersShareTheDocumentAllocationBudget() throws Exception {
    try (var document = new PDDocument()) {
      var renderer = new BoundedPdfRenderer(document);
      renderer.chargeRaster(8000, 1999, 4); // Leaves 32,000 pixels for source and decode buffers.
      var image = image(document, 1, 1, true);
      var params = predictor(8192);
      params.setInt(COSName.COLORS, 32);
      params.setInt(COSName.BITS_PER_COMPONENT, 16);
      image.getCOSObject().setItem(COSName.DECODE_PARMS, params);
      var page = new PDPage(new PDRectangle(72, 72));
      document.addPage(page);
      try (var content = new PDPageContentStream(document, page)) {
        content.drawImage(image, 0, 0, 10, 10);
      }
      IOException failure = assertThrows(IOException.class, () -> {
        renderer.renderImageWithDPI(0, PdfImageRenderer.DEFAULT_DPI);
        renderer.requireWithinLimits();
      });
      assertTrue(failure.getMessage().contains("decoded PDF image pixels"));
    }
  }

  @Test
  void ordinaryPredictorParametersStillRender() throws Exception {
    for (COSName filter : new COSName[] {COSName.FLATE_DECODE, COSName.LZW_DECODE}) {
      for (boolean array : new boolean[] {false, true}) {
        String name = "ordinary-predictor-" + filter.getName() + array;
        Path path = predictorPdf(name, filter, predictor(1), array, null);
        try (var files = new TempFileManager(tempDir.resolve(name + "-render"));
            var renderer = new PdfImageRenderer(files)) {
          var pages = renderer.render(path);
          assertEquals(1, pages.size());
          // Verify gray samples, rather than PDFBox swallowing a decode error.
          var rendered = ImageIO.read(pages.getFirst().toFile());
          assertTrue((rendered.getRGB(5, rendered.getHeight() - 5) & 0xff) < 240);
        }
      }
    }
  }

  private static COSDictionary predictor(int columns) {
    var params = new COSDictionary();
    params.setInt(COSName.PREDICTOR, 12);
    params.setInt(COSName.COLORS, 1);
    params.setInt(COSName.BITS_PER_COMPONENT, 8);
    params.setInt(COSName.COLUMNS, columns);
    return params;
  }

  @Test
  void conflictingDecodeParameterAliasesAreRefusedBeforeDecoding() throws Exception {
    Path path = tempDir.resolve("conflicting-predictor-aliases.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage(new PDRectangle(72, 72));
      document.addPage(page);
      var image = image(document, 1, 1, true);
      image.getCOSObject().setItem(COSName.DECODE_PARMS, predictor(1));
      image.getCOSObject().setItem(COSName.DP, predictor(8193));
      try (var content = new PDPageContentStream(document, page)) {
        content.drawImage(image, 0, 0, 10, 10);
      }
      document.save(path.toFile());
    }
    assertRejectedImage(path, "decode parameters");
  }

  @Test
  void ordinaryFaxDimensionsStillRender() throws Exception {
    Path path = tempDir.resolve("ordinary-fax.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage(new PDRectangle(72, 72));
      document.addPage(page);
      var image = CCITTFactory.createFromImage(document,
          new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_BINARY));
      try (var content = new PDPageContentStream(document, page)) {
        content.drawImage(image, 0, 0, 10, 10);
      }
      document.save(path.toFile());
    }
    try (var files = new TempFileManager(tempDir.resolve("ordinary-fax-render"));
        var renderer = new PdfImageRenderer(files)) {
      var pages = renderer.render(path);
      assertEquals(1, pages.size());
      var rendered = ImageIO.read(pages.getFirst().toFile());
      assertTrue((rendered.getRGB(5, rendered.getHeight() - 5) & 0xff) < 240);
    }
  }

  private Path predictorPdf(String name, COSName filter, COSDictionary params, boolean array,
      COSName maskKey) throws Exception {
    Path path = tempDir.resolve("predictor-" + name + ".pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage(new PDRectangle(72, 72));
      document.addPage(page);
      var bytes = new ByteArrayOutputStream();
      // The fax case deliberately has no usable fax data: preflight must reject its dimensions
      // before trying that decoder. Predictor fixtures contain real Flate or LZW data.
      COSName encoding = COSName.CCITTFAX_DECODE.equals(filter) ? COSName.FLATE_DECODE : filter;
      FilterFactory.INSTANCE.getFilter(encoding).encode(
          new ByteArrayInputStream(new byte[] {0, 127}), bytes, new COSDictionary(), 0);
      COSArray filters = new COSArray();
      filters.add(COSName.ASCII_HEX_DECODE);
      filters.add(filter);
      COSArray parameters = new COSArray();
      parameters.add(COSNull.NULL);
      parameters.add(params);
      byte[] encoded = array ? (java.util.HexFormat.of().formatHex(bytes.toByteArray()) + ">")
          .getBytes(java.nio.charset.StandardCharsets.US_ASCII) : bytes.toByteArray();
      var source = new PDImageXObject(document, new ByteArrayInputStream(encoded),
          array ? filters : filter, 1, 1, 8, PDDeviceGray.INSTANCE);
      source.getCOSObject().setItem(COSName.DECODE_PARMS, array ? parameters : params);
      var drawn = source;
      if (maskKey != null) {
        drawn = image(document, 1, 1, false);
        if (COSName.MASK.equals(maskKey)) {
          source.setStencil(true);
          source.setBitsPerComponent(1);
        }
        drawn.getCOSObject().setItem(maskKey, source);
      }
      try (var content = new PDPageContentStream(document, page)) {
        content.drawImage(drawn, 0, 0, 10, 10);
      }
      document.save(path.toFile());
    }
    return path;
  }

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
  void oppositeAspectRatioMasksRejectCompositionBeforeDecoding() throws Exception {
    for (COSName maskKey : new COSName[] {COSName.SMASK, COSName.MASK}) {
      Path path = maskedPdf("opposite-" + maskKey.getName(), maskKey, 8192, 1, 1, 8192, 1);
      assertRejectedImage(path, "image pixels exceed allocation limit");
    }
  }

  @Test
  void compositionAndScalingShareTheDocumentAllocationBudget() throws Exception {
    // Both sources are small. Each composition nevertheless creates two 4000x4000 rasters;
    // repeated draws must charge these against the same budget as page/source allocations.
    Path path = maskedPdf("aggregate-composition", COSName.SMASK, 4000, 1, 1, 4000, 3);
    assertRejectedImage(path, "decoded PDF image pixels");
  }

  @Test
  void ordinaryOppositeAspectRatioMasksStillRender() throws Exception {
    for (COSName maskKey : new COSName[] {COSName.SMASK, COSName.MASK}) {
      Path path = maskedPdf("ordinary-" + maskKey.getName(), maskKey, 8, 1, 1, 8, 1);
      try (var files = new TempFileManager(tempDir.resolve("ordinary-" + maskKey.getName()));
          var renderer = new PdfImageRenderer(files)) {
        assertEquals(1, renderer.render(path).size());
      }
    }
  }

  @Test
  void transparencyGroupsAndGraphicsMasksChargeIntermediateRasters() throws Exception {
    for (boolean graphicsMask : new boolean[] {false, true}) {
      Path path = tempDir.resolve("group-budget-" + graphicsMask + ".pdf");
      try (var document = new PDDocument()) {
        var page = new PDPage(new PDRectangle(2000, 2000));
        document.addPage(page);
        var group = new PDTransparencyGroup(document);
        group.setBBox(new PDRectangle(2000, 2000));
        group.setResources(new PDResources());
        var attributes = new COSDictionary();
        attributes.setItem(COSName.S, COSName.TRANSPARENCY);
        attributes.setBoolean(COSName.I, true);
        group.getCOSObject().setItem(COSName.GROUP, attributes);
        try (var groupContent = new PDFormContentStream(group)) {
          groupContent.addRect(0, 0, 2000, 2000);
          groupContent.fill();
        }
        try (var content = new PDPageContentStream(document, page)) {
          if (graphicsMask) {
            var mask = new COSDictionary();
            mask.setItem(COSName.S, COSName.ALPHA);
            mask.setItem(COSName.G, group);
            var state = new PDExtendedGraphicsState();
            state.getCOSObject().setItem(COSName.SMASK, mask);
            content.setGraphicsStateParameters(state);
            for (int i = 0; i < 3; i++) {
              content.addRect(0, 0, 2000, 2000);
              content.fill();
            }
          } else {
            for (int i = 0; i < 4; i++) content.drawForm(group);
          }
        }
        document.save(path.toFile());
      }
      assertRejectedImage(path, "decoded PDF image pixels");
    }
  }

  @Test
  void repeatedShadingWithEmptyGraphicsMaskChargesEveryAllocation() throws Exception {
    for (COSName subtype : new COSName[] {COSName.ALPHA, COSName.LUMINOSITY}) {
      Path path = shadedPdf("shading-budget-" + subtype.getName(), 2000, 10, subtype);
      assertRejectedImage(path, "decoded PDF image pixels");
    }
  }

  @Test
  void ordinaryShadingWithEmptyGraphicsMaskStillRenders() throws Exception {
    Path path = shadedPdf("ordinary-shading", 72, 1, COSName.ALPHA);
    try (var files = new TempFileManager(tempDir.resolve("ordinary-shading-render"));
        var renderer = new PdfImageRenderer(files)) {
      assertEquals(1, renderer.render(path).size());
    }
  }

  @Test
  void tilingPatternIsCheckedBeforeCellAllocation() throws Exception {
    Path path = tempDir.resolve("pattern-budget.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage(new PDRectangle(72, 72));
      page.setResources(new PDResources());
      document.addPage(page);
      var pattern = new PDTilingPattern();
      pattern.setBBox(new PDRectangle(72, 72));
      pattern.setXStep(28000);
      pattern.setYStep(1);
      pattern.setPaintType(PDTilingPattern.PAINT_COLORED);
      pattern.setResources(new PDResources());
      var name = page.getResources().add(pattern);
      try (var content = new PDPageContentStream(document, page)) {
        content.setNonStrokingColor(new PDColor(name, new PDPattern(page.getResources())));
        content.addRect(0, 0, 72, 72);
        content.fill();
      }
      document.save(path.toFile());
    }
    assertRejectedImage(path, "allocation limit");
  }

  @Test
  void oversizedImagePlacementIsRejectedBeforeSmoothScaling() throws Exception {
    Path path = tempDir.resolve("oversized-placement.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage(new PDRectangle(72, 72));
      document.addPage(page);
      try (var content = new PDPageContentStream(document, page)) {
        content.drawImage(image(document, 10, 10, false), 0, 0, 1, 28000);
      }
      document.save(path.toFile());
    }
    assertRejectedImage(path, "allocation limit");
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

  private Path maskedPdf(String name, COSName maskKey, int width, int height,
      int maskWidth, int maskHeight, int draws) throws IOException {
    Path path = tempDir.resolve(name + ".pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage(new PDRectangle(72, 72));
      document.addPage(page);
      var source = image(document, width, height, false);
      var mask = image(document, maskWidth, maskHeight, true);
      if (maskKey.equals(COSName.MASK)) {
        mask.setStencil(true);
        mask.setBitsPerComponent(1);
      }
      source.getCOSObject().setItem(maskKey, mask);
      try (var content = new PDPageContentStream(document, page)) {
        for (int i = 0; i < draws; i++) content.drawImage(source, 0, 0, 10, 10);
      }
      document.save(path.toFile());
    }
    return path;
  }

  private Path shadedPdf(String name, float side, int draws, COSName maskSubtype) throws IOException {
    Path path = tempDir.resolve(name + ".pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage(new PDRectangle(side, side));
      page.setResources(new PDResources());
      document.addPage(page);
      var group = new PDTransparencyGroup(document);
      group.setBBox(new PDRectangle(side, side));
      group.setResources(new PDResources());
      var attributes = new COSDictionary();
      attributes.setItem(COSName.S, COSName.TRANSPARENCY);
      attributes.setBoolean(COSName.I, true);
      group.getCOSObject().setItem(COSName.GROUP, attributes);
      // No painting operators: incidental getPaint/drawImage calls cannot charge this mask.
      try (var _ = group.getCOSObject().createOutputStream()) {}
      var mask = new COSDictionary();
      mask.setItem(COSName.S, maskSubtype);
      mask.setItem(COSName.G, group);
      var state = new PDExtendedGraphicsState();
      state.getCOSObject().setItem(COSName.SMASK, mask);

      var function = new COSDictionary();
      function.setInt(COSName.FUNCTION_TYPE, 2);
      function.setItem(COSName.DOMAIN, floats(0, 1));
      function.setItem(COSName.C0, floats(0, 0, 0));
      function.setItem(COSName.C1, floats(1, 1, 1));
      function.setInt(COSName.N, 1);
      var shading = new PDShadingType2(new COSDictionary());
      shading.setShadingType(2);
      shading.setColorSpace(PDDeviceRGB.INSTANCE);
      shading.setCoords(floats(0, 0, side, 0));
      shading.setFunction(PDFunction.create(function));
      try (var content = new PDPageContentStream(document, page)) {
        content.setGraphicsStateParameters(state);
        for (int i = 0; i < draws; i++) content.shadingFill(shading);
      }
      document.save(path.toFile());
    }
    return path;
  }

  private static COSArray floats(float... values) {
    var array = new COSArray();
    array.setFloatArray(values);
    return array;
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
