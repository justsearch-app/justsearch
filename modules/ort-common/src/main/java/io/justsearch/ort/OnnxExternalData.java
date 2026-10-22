/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OnnxJavaType;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Streaming inspection of the tensor-bearing ONNX protobuf fields. Large raw_data payloads are
 * skipped, never materialized. Field numbers come from https://github.com/onnx/onnx/blob/main/onnx/onnx.proto.
 * Unknown fields are skipped; malformed or uninspectable models bypass the disk cache.
 */
public final class OnnxExternalData {
  private OnnxExternalData() {}

  /** Graph declarations, without reading weights or opening a runtime session. */
  record Metadata(Set<String> inputs, Set<String> outputs,
      Map<String, OrtSessionAssembler.ProbedTensorInfo> tensors, Set<String> unresolvedOutputs,
      Map<String, String> properties) {}

  static Metadata readMetadata(Path model) throws IOException {
    try (InputStream input = new BufferedInputStream(Files.newInputStream(model))) {
      Inspector inspector = new Inspector(input);
      inspector.metadata = true;
      inspector.message(Kind.MODEL, Files.size(model), 0);
      if (!inspector.graph) throw new IOException("ONNX graph missing");
      inspector.inputs.removeAll(inspector.initializers);
      return new Metadata(inspector.inputs, inspector.outputs, inspector.tensors,
          inspector.unresolvedOutputs, inspector.properties);
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

  private enum Kind { MODEL, GRAPH, NODE, ATTRIBUTE, TENSOR, SPARSE, TRAINING, FUNCTION }

  private static final class Inspector {
    private final InputStream input;
    private long position;
    private boolean metadata;
    private boolean graph;
    private long metadataBytes;
    private int fields;
    private boolean unresolvedTensor;
    private final Set<String> unresolvedOutputs = new LinkedHashSet<>();
    private final Set<String> inputs = new LinkedHashSet<>();
    private final Set<String> outputs = new LinkedHashSet<>();
    private final Set<String> initializers = new LinkedHashSet<>();
    private final Map<String, OrtSessionAssembler.ProbedTensorInfo> tensors = new LinkedHashMap<>();
    private final Map<String, String> properties = new LinkedHashMap<>();

    private Inspector(InputStream input) {
      this.input = input;
    }

    private boolean message(Kind kind, long end, int depth) throws IOException {
      if (depth > 100) throw new IOException("ONNX nesting limit exceeded");
      while (position < end) {
        long tag = tag(end);
        int field = (int) (tag >>> 3);
        int wire = (int) (tag & 7);
        if (field == 0) throw new IOException("Invalid ONNX field");
        if (wire == 0) {
          long value = varint(end);
          if (!metadata && kind == Kind.TENSOR && field == 14 && value != 0) return true;
        } else if (wire == 2) {
          long length = varint(end);
          if (length < 0 || length > end - position) throw new IOException("Invalid ONNX length");
          if (!metadata && kind == Kind.TENSOR && field == 13) return true;
          long childEnd = position + length;
          if (metadata && kind == Kind.MODEL && field == 7) {
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
      return false;
    }

    private void readProperty(long end) throws IOException {
      String key = "";
      String value = "";
      while (position < end) {
        long tag = tag(end);
        if (tag == 10) key = string(end);
        else if (tag == 18) value = string(end);
        else skipField(tag, end);
      }
      properties.put(key, value);
    }

    private void readValue(long end, boolean inputValue) throws IOException {
      String name = "";
      OrtSessionAssembler.ProbedTensorInfo tensor = null;
      unresolvedTensor = false;
      boolean typed = false;
      while (position < end) {
        long tag = tag(end);
        if (tag == 10) name = string(end);
        else if (tag == 18) { tensor = readType(messageEnd(end)); typed = true; }
        else skipField(tag, end);
      }
      if (name.isEmpty()) throw new IOException("Unnamed ONNX value");
      if (!(inputValue ? inputs : outputs).add(name)) throw new IOException("Duplicate ONNX value");
      if (!inputValue && (unresolvedTensor || !typed)) unresolvedOutputs.add(name);
      if (!inputValue && tensor != null) tensors.put(name, tensor);
    }

    private OrtSessionAssembler.ProbedTensorInfo readType(long end) throws IOException {
      OrtSessionAssembler.ProbedTensorInfo tensor = null;
      boolean supported = false;
      while (position < end) {
        long tag = tag(end);
        if (tag == 10) { tensor = readTensorType(messageEnd(end)); supported = true; }
        else {
          if (tag == 34 || tag == 42) supported = true; // sequence/map, not TensorInfo
          skipField(tag, end);
        }
      }
      if (!supported) unresolvedTensor = true;
      return tensor;
    }

    private OrtSessionAssembler.ProbedTensorInfo readTensorType(long end) throws IOException {
      int type = 0;
      long[] shape = null;
      while (position < end) {
        long tag = tag(end);
        if (tag == 8) {
          long value = varint(end);
          if (value < 0 || value > Integer.MAX_VALUE) throw new IOException("Invalid ONNX tensor type");
          type = (int) value;
        }
        else if (tag == 18) shape = readShape(messageEnd(end));
        else skipField(tag, end);
      }
      // An absent shape can be resolved by ORT's graph inference. Do not guess scalar here.
      if (shape == null || type == 0) {
        unresolvedTensor = true;
        return null;
      }
      // ONNX protobuf enum values differ from ORT Java's OnnxTensorType values.
      OnnxJavaType javaType = switch (type) {
        case 1 -> OnnxJavaType.FLOAT;
        case 2 -> OnnxJavaType.UINT8;
        case 3 -> OnnxJavaType.INT8;
        case 4, 5 -> OnnxJavaType.INT16;
        case 6, 12 -> OnnxJavaType.INT32;
        case 7, 13 -> OnnxJavaType.INT64;
        case 8 -> OnnxJavaType.STRING;
        case 9 -> OnnxJavaType.BOOL;
        case 10 -> OnnxJavaType.FLOAT16;
        case 11 -> OnnxJavaType.DOUBLE;
        case 16 -> OnnxJavaType.BFLOAT16;
        default -> OnnxJavaType.UNKNOWN;
      };
      return new OrtSessionAssembler.ProbedTensorInfo(shape, javaType);
    }

    private long[] readShape(long end) throws IOException {
      List<Long> dimensions = new ArrayList<>();
      while (position < end) {
        long tag = tag(end);
        if (tag != 10) { skipField(tag, end); continue; }
        long dimensionEnd = messageEnd(end);
        Long value = null;
        while (position < dimensionEnd) {
          long dimensionTag = tag(dimensionEnd);
          if (dimensionTag == 8) value = varint(dimensionEnd);
          else if (dimensionTag == 18) {
            string(dimensionEnd);
            value = -1L;
            // ORT can resolve a symbolic output axis from its producing node. Names/metadata
            // remain readable; a caller asking for this tensor's shape needs the old probe.
            unresolvedTensor = true;
          }
          else skipField(dimensionTag, dimensionEnd);
        }
        if (value == null) {
          unresolvedTensor = true;
          value = -1L;
        }
        if (dimensions.size() >= 1024) throw new IOException("ONNX rank limit exceeded");
        dimensions.add(value);
      }
      return dimensions.stream().mapToLong(Long::longValue).toArray();
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
      if (metadata && ++fields > 1_000_000) throw new IOException("ONNX field limit exceeded");
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
