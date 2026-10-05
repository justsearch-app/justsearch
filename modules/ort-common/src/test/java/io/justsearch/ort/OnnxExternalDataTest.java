/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
  void malformedProtobufFailsInspectionRatherThanBeingAcceptedAsSelfContained() throws Exception {
    Path model = Files.write(temp.resolve("model.onnx"), new byte[] {58, 127});
    assertThrows(IOException.class, () -> OnnxExternalData.hasExternalData(model));
  }
}
