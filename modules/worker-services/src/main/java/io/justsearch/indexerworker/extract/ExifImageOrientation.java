/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.FileImageInputStream;
import org.w3c.dom.Node;

/** JPEG preparation owned by the OCR document; the original file is only ever read. */
final class ExifImageOrientation {
  private ExifImageOrientation() {}

  static Path prepare(Path source, Path tempDirectory) throws IOException {
    int orientation = orientation(source);
    if (orientation < 2 || orientation > 8) return source;
    BufferedImage decoded = ImageIO.read(source.toFile());
    if (decoded == null) throw new IOException("Cannot decode EXIF-oriented image");
    Path prepared = tempDirectory.resolve("oriented.png");
    ImageIO.write(transform(decoded, orientation), "png", prepared.toFile());
    return prepared;
  }

  static int orientation(Path source) throws IOException {
    try (var input = new FileImageInputStream(source.toFile())) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) return 1;
      var reader = readers.next();
      try {
        if (!"JPEG".equalsIgnoreCase(reader.getFormatName())) return 1;
        reader.setInput(input, true, false);
        Node tree = reader.getImageMetadata(0).getAsTree("javax_imageio_jpeg_image_1.0");
        return orientation(tree);
      } finally {
        reader.dispose();
      }
    }
  }

  private static int orientation(Node node) {
    if (node instanceof IIOMetadataNode metadata && "unknown".equals(node.getNodeName())
        && "225".equals(metadata.getAttribute("MarkerTag"))
        && metadata.getUserObject() instanceof byte[] bytes) {
      int value = exifOrientation(bytes);
      if (value != 1) return value;
    }
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      int value = orientation(child);
      if (value != 1) return value;
    }
    return 1;
  }

  /** Only the bounded APP1 payload is inspected. Ignore malformed/unsupported TIFF metadata. */
  private static int exifOrientation(byte[] bytes) {
    if (bytes.length < 14 || bytes[0] != 'E' || bytes[1] != 'x' || bytes[2] != 'i'
        || bytes[3] != 'f' || bytes[4] != 0 || bytes[5] != 0) return 1;
    ByteBuffer tiff = ByteBuffer.wrap(bytes, 6, bytes.length - 6).slice();
    int order = tiff.getShort(0) & 0xffff;
    if (order != 0x4949 && order != 0x4d4d) return 1;
    tiff.order(order == 0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    if (tiff.getShort(2) != 42) return 1;
    long offset = Integer.toUnsignedLong(tiff.getInt(4));
    if (offset < 8 || offset > tiff.limit() - 2) return 1;
    int count = Short.toUnsignedInt(tiff.getShort((int) offset));
    for (long at = offset + 2, end = at + 12L * count; at < end; at += 12) {
      if (at > tiff.limit() - 12) return 1;
      int entry = (int) at;
      if (Short.toUnsignedInt(tiff.getShort(entry)) == 0x0112
          && tiff.getShort(entry + 2) == 3 && tiff.getInt(entry + 4) == 1) {
        int value = Short.toUnsignedInt(tiff.getShort(entry + 8));
        return value >= 1 && value <= 8 ? value : 1;
      }
    }
    return 1;
  }

  static BufferedImage transform(BufferedImage source, int orientation) {
    int w = source.getWidth();
    int h = source.getHeight();
    AffineTransform transform = switch (orientation) {
      case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
      case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
      case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
      case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
      case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
      case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
      case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
      default -> new AffineTransform();
    };
    boolean swap = orientation >= 5 && orientation <= 8;
    var result = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
    var graphics = result.createGraphics();
    try {
      graphics.drawImage(source, transform, null);
    } finally {
      graphics.dispose();
    }
    return result;
  }
}
