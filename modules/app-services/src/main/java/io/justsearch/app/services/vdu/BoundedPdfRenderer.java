/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import java.awt.Paint;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;

/** Guards source-image allocations, including images drawn inside forms and soft-mask groups. */
final class BoundedPdfRenderer extends PDFRenderer {
  private long decodedPixels;
  private IOException rejected;

  BoundedPdfRenderer(PDDocument document) {
    super(document);
  }

  void requireWithinLimits() throws IOException {
    if (rejected != null) throw rejected;
  }

  void chargeRaster(double width, double height, int copies) throws IOException {
    requireWithinLimits();
    try {
      long pixels = VduImageLimits.checkDimensions(width, height);
      chargePixels(Math.multiplyExact(pixels, copies));
    } catch (IOException failure) {
      rejected = failure;
      throw failure;
    }
  }

  private void chargePixels(long pixels) throws IOException {
    if (pixels < 0 || pixels > VduImageLimits.MAX_DOCUMENT_PIXELS - decodedPixels) {
      throw new IOException("VDU decoded PDF image pixels exceed allocation limit");
    }
    decodedPixels += pixels;
  }

  private void chargeBytes(long bytes) throws IOException {
    // The shared raster budget uses four-byte pixels. Round each byte buffer upward.
    chargePixels(Math.addExact(bytes, 3) / 4);
  }

  private void charge(COSDictionary image, Set<COSDictionary> visiting) throws IOException {
    requireWithinLimits();
    if (!visiting.add(image)) {
      rejected = new IOException("Cyclic PDF image mask");
      throw rejected;
    }
    try {
      int width = image.getInt(COSName.WIDTH, COSName.W);
      int height = image.getInt(COSName.HEIGHT, COSName.H);
      // Source samples, RGB and transfer/stencil conversion; color-key masks also need gray
      // and ARGB rasters before transfer conversion.
      chargeRaster(width, height, image.getDictionaryObject(COSName.MASK) instanceof COSArray ? 5 : 3);
      checkFilters(image);
      if (hasFilter(image, "DCTDecode") || hasFilter(image, "DCT")) {
        if (image instanceof COSStream stream) {
          try (InputStream encoded = new PDStream(stream).createInputStream(List.of("DCTDecode", "DCT"))) {
            checkJpegHeader(encoded, image);
          }
        }
      }
      for (COSName maskKey : List.of(COSName.SMASK, COSName.MASK)) {
        COSBase mask = image.getDictionaryObject(maskKey);
        if (mask instanceof COSDictionary maskImage) {
          charge(maskImage, visiting);
          // PDFBox applyMask independently takes max(width) and max(height), then allocates
          // both the scaled gray mask and the resulting ARGB image. Neither source area bounds
          // that rectangle (opposite aspect ratios are the important case).
          chargeRaster(Math.max(width, maskImage.getInt(COSName.WIDTH, COSName.W)),
              Math.max(height, maskImage.getInt(COSName.HEIGHT, COSName.H)), 2);
        }
      }
    } catch (IOException failure) {
      rejected = failure;
      throw failure;
    } finally {
      visiting.remove(image);
    }
  }

  private static boolean hasFilter(COSDictionary image, String name) {
    COSBase filters = image.getDictionaryObject(COSName.FILTER, COSName.F);
    COSName filter = COSName.getPDFName(name);
    return filter.equals(filters) || filters instanceof COSArray array && array.indexOfObject(filter) >= 0;
  }

  private void checkFilters(COSDictionary image) throws IOException {
    // Filter.getDecodeParams prefers the inline aliases; other PDFBox readers prefer full names.
    // Refuse conflicting representations rather than inspect different parameters than it uses.
    COSBase filters = unambiguousEntry(image, COSName.FILTER, COSName.F);
    COSBase parameters = unambiguousEntry(image, COSName.DECODE_PARMS, COSName.DP);
    if (filters == null) {
      if (parameters != null) throw invalidDecodeParameters();
      return;
    }
    int count = filters instanceof COSArray array ? array.size() : 1;
    if (count < 1 || count > 32) throw invalidDecodeParameters();
    if (parameters != null && !(parameters instanceof COSNull)) {
      if (filters instanceof COSArray) {
        if (!(parameters instanceof COSArray array) || array.size() != count) {
          throw invalidDecodeParameters();
        }
      } else if (!(parameters instanceof COSDictionary)) {
        throw invalidDecodeParameters();
      }
    }
    for (int i = 0; i < count; i++) {
      COSBase filter = filters instanceof COSArray array ? array.getObject(i) : filters;
      COSBase entry = parameters instanceof COSArray array ? array.getObject(i) : parameters;
      if (entry != null && !(entry instanceof COSNull) && !(entry instanceof COSDictionary)) {
        throw invalidDecodeParameters();
      }
      COSDictionary params =
          entry instanceof COSDictionary dictionary ? dictionary : new COSDictionary();
      if (!(filter instanceof COSName name)) throw invalidDecodeParameters();
      switch (name.getName()) {
        case "FlateDecode", "Fl", "LZWDecode", "LZW" -> {
          int predictor = decodeInt(params, COSName.PREDICTOR, 1);
          if (predictor != 1 && predictor != 2 && (predictor < 10 || predictor > 15)) {
            throw invalidDecodeParameters();
          }
          if (predictor > 1) {
            int colors = decodeInt(params, COSName.COLORS, 1);
            int bits = decodeInt(params, COSName.BITS_PER_COMPONENT, 8);
            int columns = decodeInt(params, COSName.COLUMNS, 1);
            if (colors < 1 || colors > 32 || columns < 1
                || columns > VduImageLimits.MAX_DIMENSION
                || (bits != 1 && bits != 2 && bits != 4 && bits != 8 && bits != 16)) {
              throw invalidDecodeParameters();
            }
            // Predictor creates currentRow and lastRow immediately, independently of Width.
            long rowBytes =
                (Math.multiplyExact(Math.multiplyExact((long) colors, bits), columns) + 7) / 8;
            chargeBytes(rowBytes);
            chargeBytes(rowBytes);
          }
          if (name.getName().equals("LZWDecode") || name.getName().equals("LZW")) {
            int earlyChange = decodeInt(params, COSName.EARLY_CHANGE, 1);
            if (earlyChange != 0 && earlyChange != 1) throw invalidDecodeParameters();
          }
        }
        case "CCITTFaxDecode", "CCF" -> {
          int columns = decodeInt(params, COSName.COLUMNS, 1728);
          int rows = decodeInt(params, COSName.ROWS, 0);
          int height = image.getInt(COSName.HEIGHT, COSName.H);
          if (rows < 0) throw invalidDecodeParameters();
          // CCITTFaxFilter uses Height when both values are positive, otherwise their maximum.
          int effectiveRows = rows > 0 && height > 0 ? height : Math.max(rows, height);
          VduImageLimits.checkDimensions(columns, effectiveRows);
          long rowBytes = (Math.addExact((long) columns, 7)) / 8;
          chargeBytes(Math.multiplyExact(rowBytes, effectiveRows));
          chargeBytes(rowBytes);
          // CCITTFaxDecoderStream also allocates two int[columns + 2] change arrays.
          chargeBytes(Math.multiplyExact(Math.addExact((long) columns, 2), 8));
        }
        case "DCTDecode", "DCT", "ASCIIHexDecode", "AHx", "ASCII85Decode", "A85",
            "RunLengthDecode", "RL" -> {
          // JPEG dimensions are inspected separately. The other filters use fixed buffers.
        }
        default -> throw new IOException("VDU cannot safely bound this PDF image codec");
      }
    }
  }

  private static COSBase unambiguousEntry(COSDictionary dictionary, COSName key, COSName alias)
      throws IOException {
    if (dictionary.containsKey(key) && dictionary.containsKey(alias)) {
      throw invalidDecodeParameters();
    }
    return dictionary.getDictionaryObject(key, alias);
  }

  private static int decodeInt(COSDictionary parameters, COSName key, int fallback)
      throws IOException {
    COSBase value = parameters.getDictionaryObject(key);
    if (value == null) return fallback;
    if (!(value instanceof COSNumber number) || !Float.isFinite(number.floatValue())
        || number.longValue() != number.intValue() || number.floatValue() != number.intValue()) {
      throw invalidDecodeParameters();
    }
    return number.intValue();
  }

  private static IOException invalidDecodeParameters() {
    return new IOException(
        "VDU PDF image decode parameters exceed allocation limits or are unsafe");
  }

  private static void checkJpegHeader(InputStream encoded, COSDictionary image) throws IOException {
    try (var stream = new MemoryCacheImageInputStream(encoded)) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw new IOException("Cannot inspect PDF JPEG dimensions");
      var reader = readers.next();
      try {
        reader.setInput(stream, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        VduImageLimits.checkDimensions(width, height);
        if (width != image.getInt(COSName.WIDTH, COSName.W)
            || height != image.getInt(COSName.HEIGHT, COSName.H)) {
          throw new IOException("PDF JPEG dimensions disagree with image dictionary");
        }
      } finally {
        reader.dispose();
      }
    }
  }

  @Override
  protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
    return new PageDrawer(parameters) {
      private void chargeGroup(PDTransparencyGroup group, Matrix ctm, int copies)
          throws IOException {
        if (group.getBBox() == null) return;
        var bounds = new Area(group.getBBox().transform(Matrix.concatenate(ctm, group.getMatrix())));
        bounds.intersect(getGraphicsState().getCurrentClippingPath());
        var rectangle = bounds.getBounds2D();
        if (rectangle.isEmpty()) return;
        // TransparencyGroup rounds its clipped device rectangle outward with one extra pixel.
        chargeRaster(Math.ceil(rectangle.getWidth() * PdfImageRenderer.DEFAULT_DPI / 72f) + 2,
            Math.ceil(rectangle.getHeight() * PdfImageRenderer.DEFAULT_DPI / 72f) + 2, copies);
      }

      private void chargeGraphicsMask() throws IOException {
        var mask = getGraphicsState().getSoftMask();
        if (mask != null && mask.getGroup() != null) {
          // Group, optional backdrop, gray conversion and rotation-adjusted gray raster.
          chargeGroup(mask.getGroup(), mask.getInitialTransformationMatrix(), 4);
        }
      }

      @Override
      protected Paint getPaint(PDColor color) throws IOException {
        chargeGraphicsMask();
        if (color.getColorSpace() instanceof PDPattern space
            && space.getPattern(color) instanceof PDTilingPattern pattern) {
          if (pattern.getBBox() == null) {
            rejected = new IOException("PDF pattern has no bounding box");
            throw rejected;
          }
          var matrix = Matrix.concatenate(getInitialMatrix(), pattern.getMatrix());
          float xStep = pattern.getXStep() == 0 ? pattern.getBBox().getWidth() : pattern.getXStep();
          float yStep = pattern.getYStep() == 0 ? pattern.getBBox().getHeight() : pattern.getYStep();
          chargeRaster(Math.max(1, Math.abs(xStep * matrix.getScalingFactorX())
                  * PdfImageRenderer.DEFAULT_DPI / 72f),
              Math.max(1, Math.abs(yStep * matrix.getScalingFactorY())
                  * PdfImageRenderer.DEFAULT_DPI / 72f), 1);
        }
        return super.getPaint(color);
      }

      @Override
      public void shadingFill(COSName shadingName) throws IOException {
        requireWithinLimits();
        // Unlike ordinary paint, shading applies the graphics mask directly in PageDrawer.
        // Reserve its rasters before delegation, even when the mask group contains no operators.
        chargeGraphicsMask();
        super.shadingFill(shadingName);
      }

      @Override
      public void showTransparencyGroup(PDTransparencyGroup group) throws IOException {
        chargeGroup(group, getGraphicsState().getCurrentTransformationMatrix(), 2);
        chargeGraphicsMask();
        super.showTransparencyGroup(group);
      }

      @Override
      protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        requireWithinLimits();
        // PDInlineImage's constructor can inflate data before drawImage. Check and charge the
        // inline dictionary before PDFBox constructs it, using both full and abbreviated keys.
        if ("BI".equals(operator.getName())) {
          COSDictionary dictionary = operator.getImageParameters();
          charge(dictionary, Collections.newSetFromMap(new IdentityHashMap<>()));
          if (hasFilter(dictionary, "DCTDecode") || hasFilter(dictionary, "DCT")) {
            // A preceding filter would need its own bounded decode. Fail closed for that uncommon
            // inline encoding instead of feeding uninspected bytes to the JPEG decoder.
            COSBase filters = dictionary.getDictionaryObject(COSName.FILTER, COSName.F);
            if (filters instanceof COSArray array && array.size() != 1) {
              rejected = new IOException("Cannot inspect filtered inline PDF JPEG");
              throw rejected;
            }
            try {
              checkJpegHeader(new ByteArrayInputStream(operator.getImageData()), dictionary);
            } catch (IOException failure) {
              rejected = failure;
              throw failure;
            }
          }
        }
        super.processOperator(operator, operands);
      }

      @Override
      public void drawImage(PDImage image) throws IOException {
        requireWithinLimits();
        if (image instanceof PDImageXObject xObject) {
          charge(xObject.getCOSObject(), Collections.newSetFromMap(new IdentityHashMap<>()));
        }
        chargeGraphicsMask();
        // The image placement can be much larger than the page (and than the source). Pattern
        // stencils and PDFBox's smooth scaling allocate from the CTM before clipping to the page.
        var ctm = getGraphicsState().getCurrentTransformationMatrix();
        var bounds = ctm.createAffineTransform().createTransformedShape(
            new Rectangle2D.Float(0, 0, 1, 1)).getBounds2D();
        chargeRaster(Math.max(1, Math.ceil(bounds.getWidth() * PdfImageRenderer.DEFAULT_DPI / 72f)),
            Math.max(1, Math.ceil(bounds.getHeight() * PdfImageRenderer.DEFAULT_DPI / 72f)), 3);
        super.drawImage(image);
      }
    };
  }
}
