/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** JPEG preparation owned by the OCR document; the original file is only ever read. */
final class ExifImageOrientation {
  private ExifImageOrientation() {}

  static Path prepare(Path source, Path tempDirectory) {
    try {
      int orientation = orientation(source);
      if (orientation < 2 || orientation > 8) return source;
      BufferedImage decoded = ImageIO.read(source.toFile());
      if (decoded == null) return source;
      Path prepared = tempDirectory.resolve("oriented.png");
      return ImageIO.write(transform(decoded, orientation), "png", prepared.toFile())
          ? prepared : source;
    } catch (IOException | RuntimeException unsupportedImage) {
      // Preparation is optional: tesseract can read JPEG variants that ImageIO cannot.
      return source;
    }
  }

  /** Read JPEG markers without asking a pixel decoder to interpret the image. */
  static int orientation(Path source) throws IOException {
    try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(source)))) {
      if (input.readUnsignedShort() != 0xffd8) return 1;
      while (input.readUnsignedByte() == 0xff) {
        int marker;
        do {
          marker = input.readUnsignedByte();
        } while (marker == 0xff);
        // Metadata ends at the start of compressed pixels (SOS), or end of image (EOI).
        if (marker == 0xda || marker == 0xd9) return 1;
        if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
        int length = input.readUnsignedShort() - 2;
        if (length < 0) return 1;
        if (marker == 0xe1) {
          byte[] payload = new byte[length];
          input.readFully(payload);
          int value = exifOrientation(payload);
          if (value != 1) return value;
        } else {
          input.skipNBytes(length);
        }
      }
      return 1;
    }
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
