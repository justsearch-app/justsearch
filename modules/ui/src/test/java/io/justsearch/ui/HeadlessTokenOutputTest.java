/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class HeadlessTokenOutputTest {
  @Test
  void stdoutDoesNotEmitSessionCredential() throws Exception {
    var signals = capturePortSignals();
    assertTrue(signals.stdout().contains("JUSTSEARCH_API_PORT=54321"));
    assertFalse(signals.stdout().contains("secret-output-token"));
    assertFalse(signals.stdout().contains("JUSTSEARCH_SESSION_TOKEN="));
  }

  @Test
  void stderrDoesNotEmitSessionCredential() throws Exception {
    var signals = capturePortSignals();
    assertFalse(signals.stderr().contains("secret-output-token"));
    assertFalse(signals.stderr().contains("JUSTSEARCH_SESSION_TOKEN="));
  }

  private static Signals capturePortSignals() throws Exception {
    java.lang.reflect.Method method;
    try {
      method = HeadlessApp.class.getDeclaredMethod("emitPortSignals", int.class);
    } catch (NoSuchMethodException baseline) {
      method = HeadlessApp.class.getDeclaredMethod("emitPortSignals", int.class, String.class, boolean.class);
    }
    method.setAccessible(true);
    var previousOut = System.out;
    var previousErr = System.err;
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    try (var out = new PrintStream(stdout); var err = new PrintStream(stderr)) {
      System.setOut(out);
      System.setErr(err);
      if (method.getParameterCount() == 1) method.invoke(null, 54321);
      else method.invoke(null, 54321, "secret-output-token", true);
    } finally {
      System.setOut(previousOut);
      System.setErr(previousErr);
    }
    return new Signals(stdout.toString(), stderr.toString());
  }

  private record Signals(String stdout, String stderr) {}
}
