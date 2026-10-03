/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import java.lang.reflect.InvocationTargetException;

/** Drives the production owner-guarded callback paths without test-only production wrappers. */
final class LlamaServerTestAccess {
  private LlamaServerTestAccess() {}

  static void crashCurrent(LlamaServerOps ops) throws Exception {
    invokeCurrent(ops, "handleServerCrash");
  }

  static void checkCurrentHealth(LlamaServerOps ops) throws Exception {
    invokeCurrent(ops, "runPeriodicHealthCheck");
  }

  static void installLogicalOwner(LlamaServerOps ops, LlamaServerOps.StartResult start)
      throws Exception {
    var install = LlamaServerOps.class.getDeclaredMethod(
        "installActive", LlamaServerOps.StartResult.class, Process.class, ProcessHandle.class);
    install.setAccessible(true);
    install.invoke(ops, start, null, null);
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
