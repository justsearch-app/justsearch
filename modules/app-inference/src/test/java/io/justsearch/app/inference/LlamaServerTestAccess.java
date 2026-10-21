/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.StopMode;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.child.StopRequest;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** Drives the production owner-guarded callback paths without test-only production wrappers. */
final class LlamaServerTestAccess {
  /** The stop a test makes when the reason is not what it checks. */
  static final StopRequest TEST_STOP =
      StopRequest.of(StopReason.DEACTIVATE, StopMode.NOW, "test");

  private LlamaServerTestAccess() {}

  static void crashCurrent(LlamaServerOps ops) throws Exception {
    invokeCurrent(ops, "handleServerCrash");
  }

  static void checkCurrentHealth(LlamaServerOps ops) throws Exception {
    invokeCurrent(ops, "runPeriodicHealthCheck");
  }

  static void installLogicalOwner(LlamaServerOps ops, LlamaServerOps.StartResult start)
      throws Exception {
    installActive(ops, start, null, start.context().inference().serverPort());
  }

  /**
   * Starts {@code argv} through the {@link ChildControl} {@code ops} stops its servers with, as a
   * {@link ChildKind#LLAMA_SERVER} that is not yet any server's owner. Its streams are pipes the
   * test does not read, so {@code argv} must write little.
   */
  static ChildHandle startThroughControl(LlamaServerOps ops, List<String> argv) throws Exception {
    var field = LlamaServerOps.class.getDeclaredField("childControl");
    field.setAccessible(true);
    ChildControl control = (ChildControl) field.get(ops);
    return control.start(
        new ChildSpec(ChildKind.LLAMA_SERVER, argv, null, Map.of(), new ChildSpec.Stdio.Pipes(),
            false, OptionalLong.empty(), Optional.empty(), ChildSpec.CooperativeStop.NONE,
            "llama-server"),
        new ChildControl.ManifestEntry(null, null, null));
  }

  /** Makes {@code child} (may be null) the launched server of a newly installed owner. */
  static void installChild(
      LlamaServerOps ops, LlamaServerOps.StartResult start, ChildHandle child) throws Exception {
    installChild(ops, start, child,
        new LlamaServerEndpoint(start.context().inference().serverPort(), null));
  }

  static void installChild(LlamaServerOps ops, LlamaServerOps.StartResult start, ChildHandle child,
      LlamaServerEndpoint endpoint) throws Exception {
    var field = LlamaServerOps.class.getDeclaredField("child");
    field.setAccessible(true);
    field.set(ops, child);
    var install = LlamaServerOps.class.getDeclaredMethod(
        "installActive", LlamaServerOps.StartResult.class, ChildHandle.class, LlamaServerEndpoint.class);
    install.setAccessible(true);
    install.invoke(ops, start, child, endpoint);
  }

  /** A java launch of {@code main} on the test classpath. */
  static List<String> javaArgv(Class<?> main) {
    return List.of(
        Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
        "-cp", System.getProperty("java.class.path"), main.getName());
  }

  private static void installActive(
      LlamaServerOps ops, LlamaServerOps.StartResult start, ChildHandle child, int port)
      throws Exception {
    var install = LlamaServerOps.class.getDeclaredMethod(
        "installActive", LlamaServerOps.StartResult.class, ChildHandle.class, int.class);
    install.setAccessible(true);
    install.invoke(ops, start, child, port);
  }

  private static void invokeCurrent(LlamaServerOps ops, String methodName) throws Exception {
    var field = LlamaServerOps.class.getDeclaredField("activeServer");
    field.setAccessible(true);
    Object owner = field.get(ops);
    if (owner == null) throw new AssertionError("Fixture must install a captured server owner");
    var method = LlamaServerOps.class.getDeclaredMethod(methodName, field.getType());
    method.setAccessible(true);
    try {
      method.invoke(ops, owner);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception cause) throw cause;
      if (failure.getCause() instanceof Error cause) throw cause;
      throw failure;
    }
  }
}
