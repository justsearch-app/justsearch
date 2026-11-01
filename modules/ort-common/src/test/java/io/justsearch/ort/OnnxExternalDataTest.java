/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks tensor traversal and skip boundaries without materializing a real weights payload. */
final class OnnxExternalDataTest {
  @TempDir Path temp;

  @Test
  void rawTensorBytesAreSkippedEvenIfTheyResembleExternalDataTags() throws Exception {
    Path model = Files.write(temp.resolve("model.onnx"),
        new byte[] {58, 6, 42, 4, 74, 2, 112, 1});
    assertFalse(OnnxExternalData.hasExternalData(model));
  }

  @Test
  void tensorExternalDataMetadataIsDetectedWithoutConventionalSidecarName() throws Exception {
    Path model = Files.write(temp.resolve("model.onnx"),
        new byte[] {58, 4, 42, 2, 106, 0});
    assertTrue(OnnxExternalData.hasExternalData(model));
  }

  @Test
  void tensorsInsideNestedGraphAttributesAreInspected() throws Exception {
    // Model.graph -> Graph.node -> Node.attribute -> Attribute.graph -> Graph.initializer.
    Path model = Files.write(temp.resolve("model.onnx"),
        new byte[] {58, 10, 10, 8, 42, 6, 50, 4, 42, 2, 112, 1});
    assertTrue(OnnxExternalData.hasExternalData(model));
  }

  @Test
  void sparseInitializerTensorsAreInspected() throws Exception {
    Path model = Files.write(temp.resolve("model.onnx"),
        new byte[] {58, 6, 122, 4, 10, 2, 112, 1});
    assertTrue(OnnxExternalData.hasExternalData(model));
  }

  @Test
  void collectsLocationsAcrossEveryTensorBearingFieldAndSkipsRawData() throws Exception {
    byte[] entry = {10, 8, 'l', 'o', 'c', 'a', 't', 'i', 'o', 'n', 18, 1, 'w'};
    byte[] tensor = field(13, entry);
    byte[][] graphs = {
        field(5, tensor),
        field(1, field(5, field(5, tensor))),
        field(1, field(5, field(6, field(5, tensor)))),
        field(15, field(1, tensor)),
        field(15, field(2, tensor)),
        field(1, field(5, field(22, field(1, tensor))))
    };
    for (byte[] graph : graphs) {
      Path model = Files.write(temp.resolve("model.onnx"), field(7, graph));
      assertEquals(Set.of("w"), OnnxExternalData.externalDataLocations(model));
    }
    Path raw = Files.write(temp.resolve("raw.onnx"), field(7, field(5, field(9, tensor))));
    assertTrue(OnnxExternalData.externalDataLocations(raw).isEmpty());
    Path training = Files.write(temp.resolve("training.onnx"), join(field(7, new byte[0]),
        field(20, field(1, field(5, tensor)))));
    assertEquals(Set.of("w"), OnnxExternalData.externalDataLocations(training));
    Path function = Files.write(temp.resolve("function.onnx"), join(field(7, new byte[0]),
        field(25, field(7, field(5, field(5, tensor))))));
    assertEquals(Set.of("w"), OnnxExternalData.externalDataLocations(function));
  }

  @Test
  void incompleteLocationsAreUninspectable() throws Exception {
    for (byte[] tensor : new byte[][] {{112, 1}, {106, 0}}) {
      Path model = Files.write(temp.resolve("model.onnx"), field(7, field(5, tensor)));
      assertThrows(IOException.class, () -> OnnxExternalData.externalDataLocations(model));
    }
  }

  private static byte[] field(int number, byte[] value) {
    var bytes = new java.io.ByteArrayOutputStream();
    int tag = (number << 3) | 2;
    while (tag >= 128) {
      bytes.write((tag & 127) | 128); tag >>>= 7;
    }
    bytes.write(tag); bytes.write(value.length); bytes.writeBytes(value);
    return bytes.toByteArray();
  }

  private static byte[] join(byte[] first, byte[] second) {
    var bytes = new java.io.ByteArrayOutputStream();
    bytes.writeBytes(first); bytes.writeBytes(second);
    return bytes.toByteArray();
  }

  @Test
  void malformedProtobufFailsInspectionRatherThanBeingAcceptedAsSelfContained() throws Exception {
    Path model = Files.write(temp.resolve("model.onnx"), new byte[] {58, 127});
    assertThrows(IOException.class, () -> OnnxExternalData.hasExternalData(model));
  }
}
