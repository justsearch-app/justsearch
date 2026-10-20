/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import org.junit.jupiter.api.Test;
class HeadlessTokenOutputTest {
  @Test
  void stdoutDoesNotEmitSessionCredential() throws Exception {
    java.lang.reflect.Method method;
    try {
      method = HeadlessApp.class.getDeclaredMethod("emitPortSignals", int.class);
    } catch (NoSuchMethodException baseline) {
      // Run this same output assertion against the pre-change signature too.
      method = HeadlessApp.class.getDeclaredMethod("emitPortSignals", int.class, String.class, boolean.class);
    }
    method.setAccessible(true);
    var previous = System.out;
    var output = new ByteArrayOutputStream();
    try (var capture = new PrintStream(output)) {
      System.setOut(capture);
      if (method.getParameterCount() == 1) method.invoke(null, 54321);
      else method.invoke(null, 54321, "secret-output-token", true);
    } finally { System.setOut(previous); }
    assertTrue(output.toString().contains("JUSTSEARCH_API_PORT=54321"));
    assertFalse(output.toString().contains("secret-output-token"));
    assertFalse(output.toString().contains("JUSTSEARCH_SESSION_TOKEN="));
  }
}
