/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Both managed launch owners must obtain device argv and environment from the same plan. */
final class LlamaDevicePlanArchitectureTest {
  @Test
  void noLaunchOwnerConstructsDeviceArgumentsOrVisibilityOutsideThePlan() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("modules/app-inference"))) {
      root = root.getParent();
    }
    if (root == null) throw new IllegalStateException("repository root unavailable");
    Pattern forbidden = Pattern.compile(
        "\\\"(?:CUDA_VISIBLE_DEVICES|--device|-sm|--no-mmproj-offload|--mmproj-offload)\\\""
            + "|\\.add\\(\\\"-ngl\\\"\\)");
    List<String> violations = new ArrayList<>();
    for (String module : List.of("app-inference", "app-services")) {
      try (var sources = Files.walk(root.resolve("modules/" + module + "/src/main/java"))) {
        for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
          if (source.getFileName().toString().equals("LlamaDevicePlan.java")) continue;
          String text = Files.readString(source, StandardCharsets.UTF_8);
          if (forbidden.matcher(text).find()) violations.add(root.relativize(source).toString());
        }
      }
    }
    assertTrue(violations.isEmpty(), "device policy built outside the launch plan: " + violations);
  }
}
