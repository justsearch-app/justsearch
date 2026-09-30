/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.engine.EngineExit.ExitClass;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Stage B item B7 — the {@code engine} row of {@code governance/supervision-contract.v1.json} is
 * held to code, in both directions.
 *
 * <p>The register is the authority and {@link EngineSupervisionPolicy} is the mirror (see that
 * class), so "held to code" here means two separate agreements, each of which can fail on its own:
 *
 * <ol>
 *   <li><b>The policy block equals the mirror.</b> Bidirectionally: a number changed on either side
 *       fails, and so does a key added on either side. A one-directional check would let the
 *       register grow a parameter the mirror never learns about — which is the drift the mirror
 *       exists to catch, arriving through the door nobody watched.
 *   <li><b>The exit table equals {@link EngineExit}.</b> The two non-JVM implementations budget on
 *       these integers and read them from the register, so a constant added to {@code EngineExit}
 *       without a register row would be invisible to both supervisors. That direction is the one
 *       that matters and it is asserted explicitly.
 * </ol>
 *
 * <p><b>Why this is not in {@code SupervisionContractTest}</b>, which owns the equivalent check for
 * the Brain: that class lives in {@code app-services}, and the module edge runs {@code app-engine ->
 * app-services}. A test-only edge back would invert it. The register row names this class in its
 * {@code driftCheck} field and {@code SupervisionContractTest} asserts that name resolves, so the
 * relocation cannot become a way to delete the check.
 */
@DisplayName("supervision contract: the engine row <-> EngineSupervisionPolicy + EngineExit")
final class EngineSupervisionPolicyTest {

  private static final String REGISTER = "governance/supervision-contract.v1.json";

  /** Keys in the register's policy block that are prose, not parameters. */
  private static final Set<String> POLICY_PROSE_KEYS = Set.of("note");

  private static Path repoRoot() {
    Path p = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 10 && p != null; i++) {
      if (Files.exists(p.resolve(REGISTER))) {
        return p;
      }
      p = p.getParent();
    }
    throw new IllegalStateException("repo root with " + REGISTER + " not found from " + Paths.get("").toAbsolutePath());
  }

  private static JsonNode engineRow() throws IOException {
    JsonNode register = new ObjectMapper().readTree(repoRoot().resolve(REGISTER).toFile());
    for (JsonNode process : register.get("processes")) {
      if ("engine".equals(process.path("id").asText())) {
        return process;
      }
    }
    throw new IllegalStateException("the supervision register has no `engine` process row");
  }

  /** {@code MAX_RESTART_ATTEMPTS} -> {@code maxRestartAttempts}. */
  private static String camelCase(String constantName) {
    StringBuilder out = new StringBuilder(constantName.length());
    boolean upper = false;
    for (int i = 0; i < constantName.length(); i++) {
      char c = constantName.charAt(i);
      if (c == '_') {
        upper = true;
      } else if (upper) {
        out.append(Character.toUpperCase(c));
        upper = false;
      } else {
        out.append(Character.toLowerCase(c));
      }
    }
    return out.toString();
  }

  private static Map<String, Object> mirrorConstants() {
    Map<String, Object> out = new TreeMap<>();
    for (Field f : EngineSupervisionPolicy.class.getDeclaredFields()) {
      if (!Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers()) || f.isSynthetic()) {
        continue;
      }
      try {
        out.put(camelCase(f.getName()), f.get(null));
      } catch (IllegalAccessException e) {
        throw new IllegalStateException("mirror constant is not readable: " + f.getName(), e);
      }
    }
    return out;
  }

  @Test
  @DisplayName("the engine row names this mirror as its authority")
  void rowNamesTheMirror() throws IOException {
    assertEquals(
        EngineSupervisionPolicy.class.getName(),
        engineRow().path("authority").asText(),
        "the row's `authority` must name the class this test compares it against, or the drift"
            + " check is comparing the register to something the register does not claim");
    assertEquals(
        EngineSupervisionPolicyTest.class.getName(),
        engineRow().path("driftCheck").asText(),
        "the row's `driftCheck` must name THIS class: SupervisionContractTest only checks that the"
            + " name resolves to a file, so a stale name there would resolve to a test that checks"
            + " something else");
  }

  @Test
  @DisplayName("policy block and mirror agree on every parameter, in both directions")
  void policyMatchesTheMirror() throws IOException {
    JsonNode policy = engineRow().get("policy");
    Map<String, Object> mirror = mirrorConstants();

    Set<String> declared = new TreeSet<>();
    policy.propertyNames().forEach(n -> declared.add(n));
    declared.removeAll(POLICY_PROSE_KEYS);

    assertEquals(
        mirror.keySet(),
        declared,
        "the register's policy parameters and EngineSupervisionPolicy's constants must be the same"
            + " SET. A parameter on one side only is a number one of the two supervisors budgets on"
            + " and nothing checks.");

    Map<String, String> disagreements = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : mirror.entrySet()) {
      JsonNode declaredValue = policy.get(entry.getKey());
      Object code = entry.getValue();
      boolean agrees;
      if (code instanceof String s) {
        agrees = declaredValue.isString() && s.equals(declaredValue.asText());
      } else if (code instanceof Long l) {
        agrees = declaredValue.isIntegralNumber() && l == declaredValue.asLong();
      } else if (code instanceof Integer i) {
        agrees = declaredValue.isIntegralNumber() && i == declaredValue.asInt();
      } else {
        agrees = false;
      }
      if (!agrees) {
        disagreements.put(entry.getKey(), "register=" + declaredValue + " code=" + code);
      }
    }
    assertTrue(disagreements.isEmpty(), "engine policy drift: " + disagreements);
  }

  @Test
  @DisplayName("the declared exit table equals EngineExit's, including every constant EngineExit has")
  void exitTableMatchesEngineExit() throws IOException {
    JsonNode row = engineRow();

    Set<String> declaredNames = new LinkedHashSet<>();
    for (JsonNode entry : row.get("exitCodes")) {
      String name = entry.get("name").asText();
      int code = entry.get("code").asInt();
      declaredNames.add(name);

      int fromCode;
      try {
        fromCode = EngineExit.class.getField(name).getInt(null);
      } catch (ReflectiveOperationException e) {
        throw new AssertionError(
            "the register declares exit code `" + name + "`, which EngineExit does not define", e);
      }
      assertEquals(fromCode, code, "declared value of EngineExit." + name + " disagrees with code");
      assertEquals(
          ExitClass.valueOf(entry.get("class").asText()),
          EngineExit.classify(code),
          "declared class of EngineExit." + name + " disagrees with EngineExit.classify");
    }

    // The direction that matters: a new deliberate exit code must reach the two supervisors, and
    // they only read this register. Adding a constant without a row would leave both classifying it
    // through the unknown fallback, which is a quieter bug than a missing row looks.
    Set<String> missing = new TreeSet<>();
    for (Field f : EngineExit.class.getDeclaredFields()) {
      if (Modifier.isStatic(f.getModifiers())
          && Modifier.isFinal(f.getModifiers())
          && f.getType() == int.class
          && !f.isSynthetic()
          && !declaredNames.contains(f.getName())) {
        missing.add(f.getName());
      }
    }
    assertTrue(
        missing.isEmpty(),
        "EngineExit declares exit code constant(s) the supervision register does not carry: "
            + missing
            + " — the dev-runner and the Tauri shell classify from the register, so an unlisted"
            + " code is one neither supervisor can name");

    assertEquals(
        EngineExit.classify(-1073741819),
        ExitClass.valueOf(row.get("unknownExitClass").asText()),
        "the declared fallback class must be the one EngineExit actually applies to an unknown code");
    assertFalse(
        declaredNames.isEmpty(), "an empty exit table would make every assertion above vacuous");
  }
}
