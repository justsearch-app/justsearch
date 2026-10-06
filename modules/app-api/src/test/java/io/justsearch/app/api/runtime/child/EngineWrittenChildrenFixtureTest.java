/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Engine's own {@code children.v1.jsonl} for the extraction pool's three outcomes, through the
 * production entry point ({@link DefaultChildControl#openForEngine}): a timeout kill, a cooperative
 * close, and a crash with an {@code oom} note. The committed copy under {@code
 * governance/fixtures/exit-ledger/engine-written/} is what the dev census test replays (Spec F §6.1:
 * extraction children accounted from the ledger). Volatile values (times, pids, ids, hashes and the
 * platform's terminate code) are compared as placeholders.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
final class EngineWrittenChildrenFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String FIXTURE =
      "governance/fixtures/exit-ledger/engine-written/children.v1.jsonl";
  private static final Set<String> VOLATILE = Set.of("at", "pid", "startedAt", "processStartedAt",
      "creationFileTime", "executable", "argvHash", "exitedAt", "observedAt", "requestedAt",
      "terminateCalledAt", "cooperativeAt", "instanceId");

  @TempDir Path dataDir;

  @Test
  @DisplayName("openForEngine writes the extraction outcomes the committed fixture records")
  void engineWritesTheFixture() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.isDirectory(root.resolve("governance"))) root = root.getParent();
    Path fixture = root.resolve(FIXTURE);

    try (DefaultChildControl control = DefaultChildControl.openForEngine(
        dataDir, "inst-fixture", ManagedChildRegistry.noop(), VramProbe.none())) {
      ChildSpec.CooperativeStop eof = ChildControlHarness.closeStdin(Duration.ofSeconds(20), 0);
      ChildHandle timedOut = control.start(ChildControlHarness.spec("sleep", eof));
      ChildControlHarness.await(control.stop(timedOut,
          StopRequest.of(StopReason.TIMEOUT, StopMode.NOW, "extraction-pool")
              .withDetail(StopDetail.REQUEST)));
      ChildHandle closed = control.start(ChildControlHarness.spec("eof:0", eof));
      ChildControlHarness.await(control.stop(closed,
          StopRequest.of(StopReason.QUIT, StopMode.COOPERATIVE, "extraction-pool")
              .withDetail(StopDetail.INTERRUPTED)));
      ChildHandle crashed = control.start(ChildControlHarness.spec("exit:1:500", eof));
      ChildControlHarness.await(crashed.exit());
      control.note(crashed, "oom");
    }

    List<String> written = Files.readAllLines(
        LedgerFamily.CHILDREN.current(LedgerFamily.directory(dataDir)));
    for (String line : written) {
      var errors = ChildControlHarness.schema().validate(JSON.readTree(line));
      assertTrue(errors.isEmpty(), () -> line + " -> " + errors);
    }
    if (Boolean.getBoolean("updateSchemas")) {
      Files.createDirectories(fixture.getParent());
      Files.write(fixture, written);
    }
    assertEquals(normalize(Files.readAllLines(fixture)), normalize(written));
  }

  /** Placeholders for what differs between runs and platforms; ids become their order. */
  static List<String> normalize(List<String> lines) {
    Map<String, String> ids = new HashMap<>();
    List<String> out = new ArrayList<>();
    for (String line : lines) {
      if (line.isBlank()) continue;
      ObjectNode node = (ObjectNode) JSON.readTree(line);
      normalize(node, ids);
      out.add(node.toString());
    }
    return out;
  }

  private static void normalize(ObjectNode node, Map<String, String> ids) {
    for (String name : List.copyOf(node.propertyNames())) {
      JsonNode value = node.get(name);
      if (value.isObject()) {
        normalize((ObjectNode) value, ids);
      } else if (name.equals("childId") || name.equals("intentId")) {
        if (value.isString()) {
          node.put(name, ids.computeIfAbsent(value.asString(), v -> name + "#" + ids.size()));
        }
      } else if (VOLATILE.contains(name)) {
        node.put(name, "<volatile>");
      } else if (name.equals("code") && value.isInt() && isOurCode(value.asInt())) {
        node.put(name, "<our-code>");
      } else if (name.equals("codeName") && value.isString()
          && Set.of("JS_CHILD_STOP", "SIGKILL").contains(value.asString())) {
        node.put(name, "<our-code>");
      } else if (name.equals("codeDistinctive")) {
        node.put(name, "<platform>");
      }
    }
  }

  private static boolean isOurCode(int code) {
    return code == TerminateCodes.JS_CHILD_STOP || code == PosixProcessTerminator.SIGKILL_CODE;
  }
}
