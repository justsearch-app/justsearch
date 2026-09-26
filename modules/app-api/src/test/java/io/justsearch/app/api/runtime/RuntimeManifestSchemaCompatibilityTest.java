package io.justsearch.app.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 501 §12.3 — backward-compatibility regression guard for the runtime manifest
 * schema.
 *
 * <p>The §12.3 rule: the manifest is a projection of canonical state, schema evolution
 * is view-shape widening, and the compatibility class is *backward* — an older reader
 * tolerates fields it doesn't know about. This test asserts that property at the
 * record-API level:
 *
 * <ol>
 *   <li>A "v1-only" reader (Jackson configured to <em>fail</em> on unknown properties)
 *       refuses to parse a manifest with fields beyond schema v1. Sanity check that the
 *       guard would actually fire if the producer started writing structurally
 *       incompatible fields.
 *   <li>A "tolerant" reader (Jackson configured to ignore unknown properties — the FE
 *       and our own Jackson defaults) parses a manifest with future fields cleanly. This
 *       is the property the §12.3 widening rule depends on.
 *   <li>An older v1 manifest body (no lifecycle / worker.state / ai fields) parses into
 *       the current `RuntimeManifest` record with absent fields surfaced as null — the
 *       backward direction.
 * </ol>
 *
 * <p>If a future contributor adds a required field without a default to {@link
 * RuntimeManifest} or its sub-records, this test fails — the contributor is forced to
 * either make the field nullable, give it a default, or bump {@code CURRENT_SCHEMA_VERSION}
 * and document the break.
 */
class RuntimeManifestSchemaCompatibilityTest {

  private static final ObjectMapper TOLERANT =
      JsonMapper.builder()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .build();

  private static final ObjectMapper STRICT =
      JsonMapper.builder()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
          .build();

  @Test
  void v1OnlyBodyParsesIntoCurrentRecord() throws Exception {
    // Hand-written "v1 minimal" body — the manifest as it shipped in Phase 1, before
    // Phase 12 added lifecycle and Phase 13 added ai. Such a body must still parse cleanly
    // into the current `RuntimeManifest` record under the tolerant reader.
    String v1Body =
        "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"instanceId\": \"00000000-0000-0000-0000-000000000001\",\n"
            + "  \"pid\": 1234,\n"
            + "  \"startedAt\": \"2026-01-01T00:00:00Z\",\n"
            + "  \"dataDir\": \"/tmp/v1\",\n"
            + "  \"head\": {\n"
            + "    \"apiPort\": 12345,\n"
            + "    \"apiBaseUrl\": \"http://127.0.0.1:12345\",\n"
            + "    \"readyAt\": \"2026-01-01T00:00:01Z\"\n"
            + "  }\n"
            + "}";

    RuntimeManifest parsed = TOLERANT.readValue(v1Body, RuntimeManifest.class);

    assertEquals(1, parsed.schemaVersion());
    assertEquals(12345, parsed.head().apiPort());
    // The new (Phase 12+13) fields are null when an older body is read.
    assertEquals(null, parsed.lifecycle(), "older bodies have no lifecycle field");
    assertEquals(null, parsed.worker(), "older bodies have no worker sub-record");
    assertEquals(null, parsed.ai(), "older bodies have no ai sub-record");
    assertEquals(null, parsed.mode(), "older bodies have no mode sub-record (tempdoc 657)");
    assertEquals(null, parsed.chat(), "older bodies have no chat sub-record (tempdoc 842)");
    assertEquals(null, parsed.children(), "v1 bodies have no managed-child registry");
    assertEquals(null, parsed.shutdownHandoff(), "v1 bodies have no shutdown handoff");
  }

  @Test
  void modeSubRecordRoundTripsAndStaysOptional() throws Exception {
    // Tempdoc 657: the mode sub-record is a new OPTIONAL field. A body carrying it must round-trip;
    // a body omitting it must still parse (mode == null), so the schema version stays 1.
    String withMode =
        "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"instanceId\": \"00000000-0000-0000-0000-000000000009\",\n"
            + "  \"pid\": 99,\n"
            + "  \"startedAt\": \"2026-07-01T00:00:00Z\",\n"
            + "  \"dataDir\": \"/tmp/mode\",\n"
            + "  \"head\": {\n"
            + "    \"apiPort\": 40404,\n"
            + "    \"apiBaseUrl\": \"http://127.0.0.1:40404\",\n"
            + "    \"readyAt\": \"2026-07-01T00:00:01Z\"\n"
            + "  },\n"
            + "  \"mode\": {\"intent\": \"mcp-lite\", \"realized\": \"retrieval-only\"}\n"
            + "}";

    RuntimeManifest parsed = TOLERANT.readValue(withMode, RuntimeManifest.class);

    assertNotNull(parsed.mode());
    assertEquals("mcp-lite", parsed.mode().intent());
    assertEquals("retrieval-only", parsed.mode().realized());
  }

  @Test
  void chatSubRecordRoundTripsAndStaysOptional() throws Exception {
    // Tempdoc 842 §2.5: the realized chat-identity sub-record is a new OPTIONAL field, added in
    // exactly the shape `mode` was (657). A body carrying it must round-trip; a body omitting it
    // must still parse (chat == null), so the schema version stays 1.
    String withChat =
        "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"instanceId\": \"00000000-0000-0000-0000-000000000842\",\n"
            + "  \"pid\": 842,\n"
            + "  \"startedAt\": \"2026-08-18T00:00:00Z\",\n"
            + "  \"dataDir\": \"/tmp/842\",\n"
            + "  \"head\": {\n"
            + "    \"apiPort\": 40842,\n"
            + "    \"apiBaseUrl\": \"http://127.0.0.1:40842\",\n"
            + "    \"readyAt\": \"2026-08-18T00:00:01Z\"\n"
            + "  },\n"
            + "  \"chat\": {\"profileId\": \"compact\","
            + " \"modelFile\": \"Qwen3.5-4B-Q4_K_M.gguf\", \"mmprojActive\": true}\n"
            + "}";

    RuntimeManifest parsed = TOLERANT.readValue(withChat, RuntimeManifest.class);

    assertNotNull(parsed.chat());
    assertEquals("compact", parsed.chat().profileId());
    assertEquals("Qwen3.5-4B-Q4_K_M.gguf", parsed.chat().modelFile());
    assertEquals(Boolean.TRUE, parsed.chat().mmprojActive());
    assertEquals(1, parsed.schemaVersion(), "an additive nullable block does not bump the schema");
  }

  @Test
  void chatMmprojActiveIsTriStateNotBoolean() throws Exception {
    // The defect this block exists to expose is a swap that silently DROPS the projector, so
    // "vision is off" and "we have no observation" must be distinguishable. A boxed Boolean plus
    // NON_NULL gives three states on the wire; a primitive would have collapsed the last two into
    // `false` and made "unknown" read as a positive claim that vision is off.
    RuntimeManifest.ChatInfo unknown = new RuntimeManifest.ChatInfo("compact", "m.gguf", null);
    String written = TOLERANT.writeValueAsString(unknown);
    assertTrue(
        !written.contains("mmprojActive"),
        "an unknown projector state must be omitted, not written as false: " + written);
    assertEquals(null, TOLERANT.readValue(written, RuntimeManifest.ChatInfo.class).mmprojActive());

    RuntimeManifest.ChatInfo dropped = new RuntimeManifest.ChatInfo("compact", "m.gguf", false);
    String droppedJson = TOLERANT.writeValueAsString(dropped);
    assertTrue(
        droppedJson.contains("\"mmprojActive\":false"),
        "an OBSERVED absent projector must be written explicitly: " + droppedJson);
  }

  @Test
  void chatProfileIdNullSurvivesAsNullNotDefaultProfile() throws Exception {
    // A bare operator path carries no profile claim. Round-tripping that as null (rather than
    // materializing "standard") is what stops the manifest from asserting a profile the engine
    // never selected.
    RuntimeManifest.ChatInfo unattributed = new RuntimeManifest.ChatInfo(null, "custom.gguf", true);
    String written = TOLERANT.writeValueAsString(unattributed);
    assertTrue(!written.contains("profileId"), "an absent claim must be omitted: " + written);
    assertEquals(null, TOLERANT.readValue(written, RuntimeManifest.ChatInfo.class).profileId());
  }

  @Test
  void aiServerBuildFieldsRoundTripAndStayOptional() throws Exception {
    // Tempdoc 682 Item 2: serverBuildExpected/serverBuildActual are new OPTIONAL AiInfo fields.
    // A body carrying them must round-trip; a body with an ai sub-record omitting them must
    // still parse (fields == null), so the schema version stays 1.
    String withBuilds =
        "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"instanceId\": \"00000000-0000-0000-0000-00000000682a\",\n"
            + "  \"pid\": 682,\n"
            + "  \"startedAt\": \"2026-07-06T00:00:00Z\",\n"
            + "  \"dataDir\": \"/tmp/682\",\n"
            + "  \"head\": {\n"
            + "    \"apiPort\": 40682,\n"
            + "    \"apiBaseUrl\": \"http://127.0.0.1:40682\",\n"
            + "    \"readyAt\": \"2026-07-06T00:00:01Z\"\n"
            + "  },\n"
            + "  \"ai\": {\"phase\": \"READY\", \"required\": true,\n"
            + "    \"serverBuildExpected\": \"b8571\", \"serverBuildActual\": \"b8600\"}\n"
            + "}";

    RuntimeManifest parsed = TOLERANT.readValue(withBuilds, RuntimeManifest.class);
    assertNotNull(parsed.ai());
    assertEquals("b8571", parsed.ai().serverBuildExpected());
    assertEquals("b8600", parsed.ai().serverBuildActual());

    // Omitted fields stay null and are omitted on write (NON_NULL) — older readers unaffected.
    RuntimeManifest.AiInfo bare =
        new RuntimeManifest.AiInfo(
            "OFFLINE", false, "Inference not configured", null, null, null, null, null);
    String written = TOLERANT.writeValueAsString(bare);
    assertTrue(
        !written.contains("serverBuild"),
        "absent build fields must be omitted (NON_NULL): " + written);
    RuntimeManifest.AiInfo reparsed = TOLERANT.readValue(written, RuntimeManifest.AiInfo.class);
    assertEquals(null, reparsed.serverBuildExpected());
    assertEquals(null, reparsed.serverBuildActual());
  }

  @Test
  void aiThinkingSupportRoundTripsAndStaysOptional() throws Exception {
    // Tempdoc 835 §9c.2: thinkingSupport is a new OPTIONAL AiInfo field carrying the running
    // server's reasoning-capability verdict. Same additive contract as the build pin — a body
    // carrying it round-trips, a body omitting it parses with null, and null is not written.
    String withVerdict =
        "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"instanceId\": \"00000000-0000-0000-0000-000000000835\",\n"
            + "  \"pid\": 835,\n"
            + "  \"startedAt\": \"2026-08-14T00:00:00Z\",\n"
            + "  \"dataDir\": \"/tmp/835\",\n"
            + "  \"head\": {\n"
            + "    \"apiPort\": 40835,\n"
            + "    \"apiBaseUrl\": \"http://127.0.0.1:40835\",\n"
            + "    \"readyAt\": \"2026-08-14T00:00:01Z\"\n"
            + "  },\n"
            + "  \"ai\": {\"phase\": \"READY\", \"required\": true,\n"
            + "    \"thinkingSupport\": \"SUPPORTED\"}\n"
            + "}";

    RuntimeManifest parsed = TOLERANT.readValue(withVerdict, RuntimeManifest.class);
    assertNotNull(parsed.ai());
    assertEquals("SUPPORTED", parsed.ai().thinkingSupport());

    RuntimeManifest.AiInfo unknownVerdict =
        new RuntimeManifest.AiInfo("READY", true, null, null, null, null, null, null);
    String written = TOLERANT.writeValueAsString(unknownVerdict);
    assertTrue(
        !written.contains("thinkingSupport"),
        "an absent verdict must be omitted (NON_NULL): " + written);
    assertEquals(
        null,
        TOLERANT.readValue(written, RuntimeManifest.AiInfo.class).thinkingSupport());
  }

  @Test
  void aiContextWindowRoundTripsAndStaysOptional() throws Exception {
    // Tempdoc 883 decision 1: contextWindow is a new OPTIONAL AiInfo sub-record carrying the
    // window this installation's engine was launched with and why. Same additive contract as the
    // build pin and the thinking verdict — a body carrying it round-trips, a body omitting it
    // parses with null, and null is not written, so the schema version stays 1.
    String withWindow =
        "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"instanceId\": \"00000000-0000-0000-0000-000000000883\",\n"
            + "  \"pid\": 883,\n"
            + "  \"startedAt\": \"2026-09-02T00:00:00Z\",\n"
            + "  \"dataDir\": \"/tmp/883\",\n"
            + "  \"head\": {\n"
            + "    \"apiPort\": 40883,\n"
            + "    \"apiBaseUrl\": \"http://127.0.0.1:40883\",\n"
            + "    \"readyAt\": \"2026-09-02T00:00:01Z\"\n"
            + "  },\n"
            + "  \"ai\": {\"phase\": \"READY\", \"required\": true,\n"
            + "    \"contextWindow\": {\"rung\": 16384, \"reason\": \"stepped-from:32768\",\n"
            + "      \"freeVramBytes\": 5368709120, \"slots\": 2, \"kvType\": \"q8_0\"}}\n"
            + "}";

    RuntimeManifest parsed = TOLERANT.readValue(withWindow, RuntimeManifest.class);
    assertNotNull(parsed.ai());
    assertNotNull(parsed.ai().contextWindow());
    assertEquals(16384, parsed.ai().contextWindow().rung());
    assertEquals(
        "stepped-from:32768",
        parsed.ai().contextWindow().reason(),
        "the reason is the point of recording the window at all: a rung alone cannot tell an"
            + " operator whether the machine fit it or fell back to it");
    assertEquals(2, parsed.ai().contextWindow().slots());
    assertEquals("q8_0", parsed.ai().contextWindow().kvType());
    assertEquals(Long.valueOf(5368709120L), parsed.ai().contextWindow().freeVramBytes());

    RuntimeManifest.AiInfo noWindow =
        new RuntimeManifest.AiInfo("OFFLINE", false, null, null, null, null, null, null);
    String written = TOLERANT.writeValueAsString(noWindow);
    assertTrue(
        !written.contains("contextWindow"),
        "an absent window must be omitted (NON_NULL) — an adopted server's window is not ours to"
            + " claim: "
            + written);
    assertEquals(
        null, TOLERANT.readValue(written, RuntimeManifest.AiInfo.class).contextWindow());
  }

  @Test
  void futureFieldsAreToleratedByForwardCompatibleReader() throws Exception {
    // Same producer body BUT with a hypothetical future field at the top level + nested.
    // The §12.3 rule: a tolerant reader must accept these without error so older consumers
    // can keep running against newer producers.
    String futureBody =
        "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"instanceId\": \"00000000-0000-0000-0000-000000000002\",\n"
            + "  \"pid\": 5678,\n"
            + "  \"startedAt\": \"2026-06-01T00:00:00Z\",\n"
            + "  \"dataDir\": \"/tmp/future\",\n"
            + "  \"lifecycle\": \"READY\",\n"
            + "  \"hypotheticalFutureField\": {\"shape\": \"unknown\"},\n"
            + "  \"head\": {\n"
            + "    \"apiPort\": 59999,\n"
            + "    \"apiBaseUrl\": \"http://127.0.0.1:59999\",\n"
            + "    \"readyAt\": \"2026-06-01T00:00:01Z\",\n"
            + "    \"unknownHeadField\": 42\n"
            + "  }\n"
            + "}";

    RuntimeManifest parsed = TOLERANT.readValue(futureBody, RuntimeManifest.class);

    assertNotNull(parsed.head());
    assertEquals("READY", parsed.lifecycle());
    assertEquals(59999, parsed.head().apiPort());
  }

  @Test
  void strictReaderRefusesUnknownFields() {
    // Sanity check that the guard would actually fire if a reader needed strict parsing.
    // This is the failure shape an over-conservative consumer would see.
    String body =
        "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"instanceId\": \"00000000-0000-0000-0000-000000000003\",\n"
            + "  \"pid\": 1,\n"
            + "  \"startedAt\": \"2026-01-01T00:00:00Z\",\n"
            + "  \"dataDir\": \"/tmp/strict\",\n"
            + "  \"unknownField\": true,\n"
            + "  \"head\": {\n"
            + "    \"apiPort\": 1,\n"
            + "    \"apiBaseUrl\": \"x\",\n"
            + "    \"readyAt\": \"2026-01-01T00:00:01Z\"\n"
            + "  }\n"
            + "}";

    boolean threw = false;
    try {
      STRICT.readValue(body, RuntimeManifest.class);
    } catch (Exception e) {
      threw = true;
    }
    assertTrue(
        threw,
        "Strict (FAIL_ON_UNKNOWN_PROPERTIES) reader must refuse unknown fields. "
            + "This sanity-checks that the tolerance the tempdoc §12.3 backward-compat rule "
            + "depends on is an explicit Jackson choice, not a default the codebase happens "
            + "to inherit.");
  }

  @Test
  void runtimeContractRoundTripsAndIsOmittedWhenAbsent() throws Exception {
    // Tempdoc 654: the runtimeContract field is additive. When absent it must be omitted from JSON
    // (@JsonInclude(NON_NULL)) so older readers are unaffected; when present it must round-trip.
    RuntimeManifest.HeadInfo head =
        RuntimeManifestHeadInfoBuilder.builder()
            .apiPort(12345)
            .apiBaseUrl("http://127.0.0.1:12345")
            .readyAt("2026-07-02T00:00:00Z")
            .build();

    RuntimeManifest without =
        RuntimeManifestBuilder.builder()
            .schemaVersion(1)
            .instanceId("00000000-0000-0000-0000-0000000000c1")
            .pid(1)
            .startedAt("2026-07-02T00:00:00Z")
            .dataDir("/tmp/c1")
            .head(head)
            .build();
    String jsonWithout = TOLERANT.writeValueAsString(without);
    assertTrue(
        !jsonWithout.contains("runtimeContract"),
        "absent runtimeContract must be omitted (NON_NULL): " + jsonWithout);

    RuntimeManifest with =
        RuntimeManifestBuilder.builder()
            .schemaVersion(1)
            .instanceId("00000000-0000-0000-0000-0000000000c2")
            .pid(2)
            .startedAt("2026-07-02T00:00:00Z")
            .dataDir("/tmp/c2")
            .head(head)
            .runtimeContract(RuntimeContract.current())
            .build();
    RuntimeManifest reparsed =
        TOLERANT.readValue(TOLERANT.writeValueAsString(with), RuntimeManifest.class);
    assertNotNull(reparsed.runtimeContract());
    assertEquals(RuntimeContract.CURRENT_VERSION, reparsed.runtimeContract().version());
    assertEquals(
        RuntimeManifest.CURRENT_SCHEMA_VERSION,
        reparsed.runtimeContract().constituents().manifestSchemaVersion());
  }
}
