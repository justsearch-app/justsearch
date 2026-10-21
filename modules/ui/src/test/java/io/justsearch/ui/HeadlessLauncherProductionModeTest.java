/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HeadlessLauncherProductionModeTest {
  @ParameterizedTest
  @ValueSource(strings = {"cmd", "ps1"})
  void shippedJavaInvocationEnablesProductionAndPassesMode(String extension) throws Exception {
    Path launcher = Path.of("../../packaging/headless/justsearch-headless." + extension);
    String prefix = extension.equals("cmd") ? "\"%JAVA%\" " : "& $java ";
    var invocations = Files.readAllLines(launcher).stream()
        .map(String::strip)
        .filter(line -> line.startsWith(prefix))
        .toList();
    assertEquals(1, invocations.size(), launcher + " must invoke Java exactly once");

    String invocation = invocations.getFirst();
    String mainClass = "io.justsearch.ui.HeadlessApp";
    assertTrue(invocation.contains(mainClass), launcher + " must start HeadlessApp");
    String jvmArguments = invocation.substring(0, invocation.indexOf(mainClass));
    assertTrue(jvmArguments.contains("\"-Djustsearch.prod=true\""),
        launcher + " Java invocation must enable production mode before the main class");
    String mode = extension.equals("cmd") ? "%MODE%" : "$Mode";
    assertTrue(jvmArguments.contains("\"-Djustsearch.mode=" + mode + "\""),
        launcher + " Java invocation must pass the selected headless or mcp-lite mode");
  }
}
