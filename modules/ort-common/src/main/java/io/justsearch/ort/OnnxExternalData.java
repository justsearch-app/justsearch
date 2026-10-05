/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streaming inspection of the tensor-bearing ONNX protobuf fields. Large raw_data payloads are
 * skipped, never materialized. Field numbers come from https://github.com/onnx/onnx/blob/main/onnx/onnx.proto.
 * Unknown fields are skipped; malformed or uninspectable models bypass the disk cache.
 */
final class OnnxExternalData {
  private OnnxExternalData() {}

  static boolean hasExternalData(Path model) throws IOException {
    Path parent = model.toAbsolutePath().getParent();
    try (var files = Files.newDirectoryStream(parent, "*.onnx_data")) {
      if (files.iterator().hasNext()) return true;
    }
    try (InputStream input = new BufferedInputStream(Files.newInputStream(model))) {
      return new Inspector(input).message(Kind.MODEL, Files.size(model), 0);
    }
  }

  private enum Kind { MODEL, GRAPH, NODE, ATTRIBUTE, TENSOR, SPARSE, TRAINING, FUNCTION }

  private static final class Inspector {
    private final InputStream input;
    private long position;

    private Inspector(InputStream input) {
      this.input = input;
    }

    private boolean message(Kind kind, long end, int depth) throws IOException {
      if (depth > 100) throw new IOException("ONNX nesting limit exceeded");
      while (position < end) {
        long tag = varint(end);
        int field = (int) (tag >>> 3);
        int wire = (int) (tag & 7);
        if (field == 0) throw new IOException("Invalid ONNX field");
        if (wire == 0) {
          long value = varint(end);
          if (kind == Kind.TENSOR && field == 14 && value != 0) return true;
        } else if (wire == 2) {
          long length = varint(end);
          if (length < 0 || length > end - position) throw new IOException("Invalid ONNX length");
          if (kind == Kind.TENSOR && field == 13) return true;
          Kind child = child(kind, field);
          if (child != null) {
            if (message(child, position + length, depth + 1)) return true;
          } else {
            skip(length, end);
          }
        } else if (wire == 1 || wire == 5) {
          skip(wire == 1 ? 8 : 4, end);
        } else {
          throw new IOException("Unsupported ONNX protobuf wire type");
        }
      }
      return false;
    }

    private static Kind child(Kind kind, int field) {
      return switch (kind) {
        case MODEL -> switch (field) {
          case 7 -> Kind.GRAPH;
          case 20 -> Kind.TRAINING;
          case 25 -> Kind.FUNCTION;
          default -> null;
        };
        case GRAPH -> switch (field) {
          case 1 -> Kind.NODE;
          case 5 -> Kind.TENSOR;
          case 15 -> Kind.SPARSE;
          default -> null;
        };
        case NODE -> field == 5 ? Kind.ATTRIBUTE : null;
        case ATTRIBUTE -> switch (field) {
          case 5, 10 -> Kind.TENSOR;
          case 6, 11 -> Kind.GRAPH;
          case 22, 23 -> Kind.SPARSE;
          default -> null;
        };
        case SPARSE -> field == 1 || field == 2 ? Kind.TENSOR : null;
        case TRAINING -> field == 1 || field == 2 ? Kind.GRAPH : null;
        case FUNCTION -> switch (field) {
          case 7 -> Kind.NODE;
          case 11 -> Kind.ATTRIBUTE;
          default -> null;
        };
        case TENSOR -> null;
      };
    }

    private long varint(long end) throws IOException {
      long value = 0;
      for (int shift = 0; shift < 64; shift += 7) {
        if (position >= end) throw new IOException("Truncated ONNX varint");
        int next = input.read();
        if (next < 0) throw new IOException("Truncated ONNX model");
        position++;
        if (shift == 63 && (next & 0xfe) != 0) throw new IOException("Invalid ONNX varint");
        value |= (long) (next & 127) << shift;
        if ((next & 128) == 0) return value;
      }
      throw new IOException("Invalid ONNX varint");
    }

    private void skip(long count, long end) throws IOException {
      if (count > end - position) throw new IOException("Truncated ONNX field");
      input.skipNBytes(count);
      position += count;
    }
  }
}
