/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.ui.api.ObservationJustifications;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every MCP method the server answers and every resource it reads declares exactly one activity
 * role, and each one declared an observation has a named reason. The declarations are compared
 * with the dispatch the server really runs, so a new method or resource cannot ship undeclared.
 */
class McpActivityDeclarationTest {
  private static String source(String file) throws Exception {
    Path root = Path.of(System.getProperty("user.dir"));
    while (!Files.isDirectory(root.resolve("modules/ui/src/main/java"))) root = root.getParent();
    return Files.readString(root.resolve("modules/ui/src/main/java/io/justsearch/ui/api/mcp/" + file),
        StandardCharsets.UTF_8).replaceAll("/\\*[\\s\\S]*?\\*/|(?m)^\\s*//[^\\n]*", "");
  }

  private static Set<String> cases(String source, String switchHead) {
    String body = source.split(Pattern.quote(switchHead), 2)[1].split("default ->", 2)[0];
    Set<String> cases = new TreeSet<>();
    var matcher = Pattern.compile("case ((?:\"[^\"]+\"\\s*,?\\s*)+)->").matcher(body);
    while (matcher.find()) {
      var names = Pattern.compile("\"([^\"]+)\"").matcher(matcher.group(1));
      while (names.find()) cases.add(names.group(1));
    }
    return cases;
  }

  @Test
  void everyAnsweredMethodDeclaresOneRole() throws Exception {
    Set<String> declared = new TreeSet<>(McpProtocolHandler.METHOD_USER_ACTIVITY.keySet());
    declared.add("tools/call");
    declared.add("resources/read");
    assertEquals(cases(source("McpProtocolHandler.java"), "Object result = switch (method) {"), declared);
  }

  @Test
  void everyReadableResourceDeclaresOneRole() throws Exception {
    Set<String> read = cases(source("McpToolSurface.java"), "return switch (uri) {");
    read.add("justsearch://resource/*");
    assertEquals(read, new TreeSet<>(McpToolSurface.RESOURCE_USER_ACTIVITY.keySet()));
  }

  @Test
  void observationNeedsAReasonAndAReasonNeedsAnObservation() {
    var methods = new HashMap<>(McpProtocolHandler.METHOD_USER_ACTIVITY);
    var resources = new HashMap<>(McpToolSurface.RESOURCE_USER_ACTIVITY);
    assertDoesNotThrow(() -> ObservationJustifications.validateMcpProtocol(methods, resources));
    resources.put("justsearch://index/unattended-poll", false);
    assertThrows(IllegalStateException.class, () -> ObservationJustifications.validateMcpProtocol(methods, resources));
    resources.remove("justsearch://index/unattended-poll");
    resources.put("justsearch://index/summary", true);
    assertThrows(IllegalStateException.class, () -> ObservationJustifications.validateMcpProtocol(methods, resources));
  }

  @Test
  void anUndeclaredReadIsDeliberate() {
    assertEquals(true, McpToolSurface.isResourceReadUserActivity("justsearch://document/pending"));
    assertEquals(true, McpToolSurface.isResourceReadUserActivity(null));
    assertEquals(false, McpToolSurface.isResourceReadUserActivity("justsearch://resource/core.index-status"));
  }
}
