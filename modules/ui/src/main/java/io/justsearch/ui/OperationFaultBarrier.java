/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/** File barrier for the identity-verified installed supervisor tests, selected only at boot. */
final class OperationFaultBarrier {
  private OperationFaultBarrier() {}

  static boolean automaticRootProducersEnabled(Function<String, String> env,
      Consumer<OperationAttemptRunnerImpl.FaultBoundary> hook) {
    if (hook != OperationAttemptRunnerImpl.NO_FAULT_HOOK) return false;
    // These installed proofs must claim the explicitly accepted recorded revision, rather than
    // a watcher revision that is legitimately superseded by the later recorded admission.
    if (!"1".equals(env.apply("JUSTSEARCH_SUPERVISOR_HARNESS"))) return true;
    String scenario = env.apply("JUSTSEARCH_REAL_RECOVERY_SCENARIO");
    return !"processing".equals(scenario) && !"operation".equals(scenario);
  }

  static Consumer<OperationAttemptRunnerImpl.FaultBoundary> fromEnvironment(Path data, Function<String, String> env) {
    String phase = env.apply("JUSTSEARCH_OPERATION_FAULT_POINT");
    String key = env.apply("JUSTSEARCH_OPERATION_FAULT_KEY");
    String kind = env.apply("JUSTSEARCH_OPERATION_FAULT_KIND");
    if (phase == null && key == null && kind == null) return OperationAttemptRunnerImpl.NO_FAULT_HOOK;
    if (!"1".equals(env.apply("JUSTSEARCH_SUPERVISOR_HARNESS"))) {
      throw new IllegalArgumentException("Operation fault selection requires supervisor harness mode");
    }
    boolean bulk = "reindex".equals(kind);
    Set<String> phases = bulk
        ? Set.of("bulk-partial-capture", "bulk-before-building-checkpoint", "bulk-after-promotion",
            "installer-before-marker", "installer-before-arm", "installer-before-pointer",
            "installer-pointer-before-settings", "installer-settings-before-publication",
            "installer-before-receipt")
        : Set.of("before-accept", "after-accept", "after-effect");
    if (phase == null || !phases.contains(phase)
        || key == null || kind == null || !Set.of("ingest", "settings-apply", "reconfigure", "reindex").contains(kind)) {
      throw new IllegalArgumentException("Invalid operation fault selection");
    }
    OperationKeys.timestampMillis(key);
    OperationKind selectedKind = OperationKind.fromWire(kind);
    Path runtime = data.resolve("runtime");
    Path reached = runtime.resolve("operation-fault-reached.json");
    Path release = runtime.resolve("operation-fault-release");
    var claimed = new AtomicBoolean();
    return boundary -> {
      if (!phase.equals(boundary.phase()) || !key.equals(boundary.parentKey())
          || selectedKind != boundary.parentKind() || Files.exists(reached)
          || !claimed.compareAndSet(false, true)) return;
      try {
        Files.createDirectories(runtime);
        var json = JsonMapper.builder().build();
        var marker = json.valueToTree(boundary);
        ((tools.jackson.databind.node.ObjectNode) marker).put("pid", ProcessHandle.current().pid());
        Path pending = runtime.resolve("operation-fault-reached.pending");
        Files.writeString(pending, json.writeValueAsString(marker));
        Files.move(pending, reached, StandardCopyOption.ATOMIC_MOVE);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(180);
        while (!Files.exists(release)) {
          if (System.nanoTime() >= deadline) throw new IllegalStateException("Operation fault barrier was not released");
          Thread.sleep(10);
        }
      } catch (IOException failure) {
        throw new IllegalStateException("Operation fault barrier evidence failed", failure);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Operation fault barrier interrupted", interrupted);
      }
    };
  }
}
