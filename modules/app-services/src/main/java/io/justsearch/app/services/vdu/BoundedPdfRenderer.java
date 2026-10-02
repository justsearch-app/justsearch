/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

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
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;

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

  private void charge(COSDictionary image, Set<COSDictionary> visiting) throws IOException {
    requireWithinLimits();
    if (!visiting.add(image)) {
      rejected = new IOException("Cyclic PDF image mask");
      throw rejected;
    }
    try {
      decodedPixels += VduImageLimits.checkDimensions(
          image.getInt(COSName.WIDTH, COSName.W), image.getInt(COSName.HEIGHT, COSName.H));
      if (decodedPixels > VduImageLimits.MAX_DOCUMENT_PIXELS) {
        throw new IOException("VDU decoded PDF image pixels exceed allocation limit");
      }
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
        if (mask instanceof COSDictionary maskImage) charge(maskImage, visiting);
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

  private static void checkFilters(COSDictionary image) throws IOException {
    // These codecs can derive allocation dimensions from their codestream rather than the PDF
    // dictionary. Until rendering runs in a resource-limited child, reject them before decoding.
    if (hasFilter(image, "JPXDecode") || hasFilter(image, "JBIG2Decode")) {
      throw new IOException("VDU cannot safely bound this PDF image codec");
    }
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
        super.drawImage(image);
      }
    };
  }
}
