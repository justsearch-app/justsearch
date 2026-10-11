/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import io.javalin.Javalin;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ObservationRegistryScopeTest {
  private static Path api() {
    Path root = Path.of(System.getProperty("user.dir"));
    while (!Files.isDirectory(root.resolve("modules/ui/src/main/java"))) root = root.getParent();
    return root.resolve("modules/ui/src/main/java/io/justsearch/ui/api");
  }

  @Test void optionalGroupsExactlyMatchTheirRealRouteDeclarations() throws Exception {
    var field = ObservationJustifications.class.getDeclaredField("OPTIONAL_GROUPS");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    var groups = (Map<ObservationJustifications.Group, Set<String>>) field.get(null);
    var files = Map.of(ObservationJustifications.Group.SUBSTRATE,
        List.of("ResourceApiModule.java", "routes/InfraRoutes.java", "routes/BootRoutes.java"),
        ObservationJustifications.Group.RUNTIME, List.of("routes/RuntimeApiRoutes.java"),
        ObservationJustifications.Group.KNOWLEDGE, List.of("routes/KnowledgeRoutes.java"),
        ObservationJustifications.Group.TELEMETRY, List.of("routes/StatusRoutes.java"));
    var pattern = Pattern.compile("app\\.(get|post|head|sse)\\(\\s*\"([^\"]+)\"[\\s\\S]*?ActivityRole\\.(\\w+)\\)");
    for (var group : files.entrySet()) {
      Set<String> actual = new HashSet<>();
      for (String file : group.getValue()) {
        var source = Files.readString(api().resolve(file), StandardCharsets.UTF_8)
            .replaceAll("/\\*[\\s\\S]*?\\*/|//[^\\n]*", "");
        if (group.getKey() == ObservationJustifications.Group.TELEMETRY) {
          source = source.split("if \\(telemetry instanceof LocalTelemetry lt\\) \\{", 2)[1].split("\\n    \\}", 2)[0];
        }
        var matches = pattern.matcher(source);
        while (matches.find()) {
          if (matches.group(3).equals("OBSERVATION")) actual.add(
              (matches.group(1).equals("sse") ? "GET" : matches.group(1).toUpperCase(Locale.ROOT)) + " " + matches.group(2));
        }
      }
      assertEquals(actual, groups.get(group.getKey()), "Scope must follow actual declaring owner " + group.getKey());
    }
  }

  @Test void missingCoreRouteFailsEvenWithoutOptionalOwners() {
    var app = Javalin.create();
    var failure = assertThrows(IllegalStateException.class, () ->
        ObservationJustifications.validate(app, Set.of()));
    assertTrue(failure.getMessage().contains("unregistered observation"));
  }

  @Test void removedMcpToolCannotLeaveAStaleJustification() {
    var tools = new HashMap<String, Boolean>();
    tools.put("justsearch_status", false);
    tools.put("justsearch_operation_outcome", false);
    assertThrows(IllegalStateException.class, () -> ObservationJustifications.validateMcp(tools));
    tools.put("justsearch_runtime_manifest", false);
    assertDoesNotThrow(() -> ObservationJustifications.validateMcp(tools));
  }
}
