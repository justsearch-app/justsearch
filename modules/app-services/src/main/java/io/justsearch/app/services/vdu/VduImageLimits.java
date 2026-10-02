/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Allocation limits applied to untrusted image headers before decoding or PDF rendering. */
final class VduImageLimits {
  static final int MAX_DIMENSION = 8192;
  static final long MAX_IMAGE_PIXELS = 16_000_000;
  static final long MAX_DOCUMENT_PIXELS = 64_000_000;

  private VduImageLimits() {}

  static long checkDimensions(double width, double height) throws IOException {
    if (!Double.isFinite(width) || !Double.isFinite(height)
        || width <= 0 || height <= 0
        || width > MAX_DIMENSION || height > MAX_DIMENSION) {
      throw new IOException("VDU image dimensions exceed allocation limit");
    }
    long pixels = (long) Math.ceil(width) * (long) Math.ceil(height);
    if (pixels > MAX_IMAGE_PIXELS) {
      throw new IOException("VDU image pixels exceed allocation limit");
    }
    return pixels;
  }

  static BufferedImage read(Path path) throws IOException {
    // Header inspection and decoding use the same stream and reader. Reopening the path between
    // them would let a replacement bypass the preflight check.
    try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
      if (input == null) throw new IOException("Cannot open VDU image");
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IOException("Unsupported VDU image format");
      ImageReader reader = readers.next();
      try {
        reader.setInput(input, true, true);
        checkDimensions(reader.getWidth(0), reader.getHeight(0));
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    }
  }
}
