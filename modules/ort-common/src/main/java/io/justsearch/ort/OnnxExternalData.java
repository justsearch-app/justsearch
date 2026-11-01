/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Streaming inspection of the tensor-bearing ONNX protobuf fields. Large raw_data payloads are
 * skipped, never materialized. Field numbers come from https://github.com/onnx/onnx/blob/main/onnx/onnx.proto.
 * Unknown fields are skipped; malformed or uninspectable models bypass the disk cache.
 */
public final class OnnxExternalData {
  private OnnxExternalData() {}

  /** Graph names and model properties, without reading weights or opening a runtime session. */
  record Metadata(Set<String> inputs, Set<String> outputs,
      Map<String, String> properties) {}

  static Metadata readMetadata(Path model) throws IOException {
    try (InputStream input = new BufferedInputStream(Files.newInputStream(model))) {
      Inspector inspector = new Inspector(input);
      inspector.metadata = true;
      inspector.message(Kind.MODEL, Files.size(model), 0);
      if (!inspector.graph) throw new IOException("ONNX graph missing");
      inspector.inputs.removeAll(inspector.initializers);
      return new Metadata(inspector.inputs, inspector.outputs, inspector.properties);
    }
  }

  /** Whether graph-file size alone omits external tensors; unreadable layouts throw. */
  public static boolean hasExternalData(Path model) throws IOException {
    Path parent = model.toAbsolutePath().getParent();
    try (var files = Files.newDirectoryStream(parent, "*.onnx_data")) {
      if (files.iterator().hasNext()) return true;
    }
    try (InputStream input = new BufferedInputStream(Files.newInputStream(model))) {
      return new Inspector(input).message(Kind.MODEL, Files.size(model), 0);
    }
  }

  /** All TensorProto external-data locations; malformed or incomplete references throw. */
  public static Set<String> externalDataLocations(Path model) throws IOException {
    try (InputStream input = new BufferedInputStream(Files.newInputStream(model))) {
      Inspector inspector = new Inspector(input);
      inspector.collectExternal = true;
      inspector.message(Kind.MODEL, Files.size(model), 0);
      if (!inspector.graph) throw new IOException("ONNX graph missing");
      return Set.copyOf(inspector.locations);
    }
  }

  private enum Kind { MODEL, GRAPH, NODE, ATTRIBUTE, TENSOR, SPARSE, TRAINING, FUNCTION }

  private static final class Inspector {
    private final InputStream input;
    private long position;
    private boolean metadata;
    private boolean collectExternal;
    private boolean graph;
    private long metadataBytes;
    private int fields;
    private final Set<String> inputs = new LinkedHashSet<>();
    private final Set<String> outputs = new LinkedHashSet<>();
    private final Set<String> initializers = new LinkedHashSet<>();
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final Set<String> locations = new LinkedHashSet<>();

    private Inspector(InputStream input) {
      this.input = input;
    }

    private boolean message(Kind kind, long end, int depth) throws IOException {
      if (depth > 100) throw new IOException("ONNX nesting limit exceeded");
      boolean externalTensor = false;
      boolean locatedTensor = false;
      while (position < end) {
        long tag = tag(end);
        int field = (int) (tag >>> 3);
        int wire = (int) (tag & 7);
        if (field == 0) throw new IOException("Invalid ONNX field");
        if (wire == 0) {
          long value = varint(end);
          if (!metadata && kind == Kind.TENSOR && field == 14 && value != 0) {
            if (!collectExternal) return true;
            externalTensor = true;
          }
        } else if (wire == 2) {
          long length = varint(end);
          if (length < 0 || length > end - position) throw new IOException("Invalid ONNX length");
          if (!metadata && !collectExternal && kind == Kind.TENSOR && field == 13) return true;
          long childEnd = position + length;
          if (collectExternal && kind == Kind.TENSOR && field == 13) {
            externalTensor = true;
            Map.Entry<String, String> entry = readEntry(childEnd);
            if ("location".equals(entry.getKey())) {
              if (entry.getValue().isBlank() || locatedTensor) {
                throw new IOException("Invalid ONNX external-data location");
              }
              locations.add(entry.getValue());
              locatedTensor = true;
            }
            continue;
          }
          if ((metadata || collectExternal) && kind == Kind.MODEL && field == 7) {
            if (graph) throw new IOException("Multiple ONNX graphs");
            graph = true;
          }
          if (metadata && kind == Kind.MODEL && field == 14) {
            readProperty(childEnd);
            continue;
          }
          if (metadata && kind == Kind.GRAPH && depth == 1) {
            if (field == 11 || field == 12) {
              readValue(childEnd, field == 11);
              continue;
            }
            if (field == 5) {
              initializers.add(readTensorName(childEnd));
              continue;
            }
            if (field == 15) {
              readSparseName(childEnd);
              continue;
            }
          }
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
      if (externalTensor && !locatedTensor) throw new IOException("ONNX external-data location missing");
      return false;
    }

    private void readProperty(long end) throws IOException {
      Map.Entry<String, String> entry = readEntry(end);
      properties.put(entry.getKey(), entry.getValue());
    }

    private Map.Entry<String, String> readEntry(long end) throws IOException {
      String key = "";
      String value = "";
      while (position < end) {
        long tag = tag(end);
        if (tag == 10) key = string(end);
        else if (tag == 18) value = string(end);
        else skipField(tag, end);
      }
      return Map.entry(key, value);
    }

    private void readValue(long end, boolean inputValue) throws IOException {
      String name = "";
      while (position < end) {
        long tag = tag(end);
        if (tag == 10) name = string(end);
        else skipField(tag, end);
      }
      if (name.isEmpty()) throw new IOException("Unnamed ONNX value");
      if (!(inputValue ? inputs : outputs).add(name)) throw new IOException("Duplicate ONNX value");
    }

    private String readTensorName(long end) throws IOException {
      String name = "";
      while (position < end) {
        long tag = tag(end);
        if (tag == 66) name = string(end);
        else skipField(tag, end);
      }
      return name;
    }

    private void readSparseName(long end) throws IOException {
      while (position < end) {
        long tag = tag(end);
        if (tag == 10) initializers.add(readTensorName(messageEnd(end)));
        else skipField(tag, end);
      }
    }

    private String string(long end) throws IOException {
      long stringEnd = messageEnd(end);
      long length = stringEnd - position;
      metadataBytes += length;
      if (length > 1_048_576 || metadataBytes > 16_777_216) {
        throw new IOException("ONNX metadata size limit exceeded");
      }
      byte[] value = input.readNBytes((int) length);
      if (value.length != length) throw new IOException("Truncated ONNX string");
      position = stringEnd;
      return new String(value, StandardCharsets.UTF_8);
    }

    private long messageEnd(long end) throws IOException {
      long length = varint(end);
      if (length < 0 || length > end - position) throw new IOException("Invalid ONNX length");
      return position + length;
    }

    private void skipField(long tag, long end) throws IOException {
      if (tag >>> 3 == 0) throw new IOException("Invalid ONNX field");
      switch ((int) (tag & 7)) {
        case 0 -> varint(end);
        case 1 -> skip(8, end);
        case 2 -> skip(messageEnd(end) - position, end);
        case 5 -> skip(4, end);
        default -> throw new IOException("Unsupported ONNX protobuf wire type");
      }
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

    private long tag(long end) throws IOException {
      long tag = varint(end);
      if (tag >>> 3 == 0 || tag >>> 3 > 0x1fffffffL) throw new IOException("Invalid ONNX field");
      if ((metadata || collectExternal) && ++fields > 1_000_000) throw new IOException("ONNX field limit exceeded");
      return tag;
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
