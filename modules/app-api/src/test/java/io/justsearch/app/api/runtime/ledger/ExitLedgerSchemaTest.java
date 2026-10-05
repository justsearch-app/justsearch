/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ExitClass;
import io.justsearch.app.api.runtime.child.ExitOutcome;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopMode;
import io.justsearch.app.api.runtime.child.StopReason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code governance/exit-ledger.v1.schema.json} describes what the Java writer writes, and its enums
 * are the app-api enums: the schema restates them for non-JVM readers, so drift fails here.
 */
@DisplayName("exit ledger line schema")
final class ExitLedgerSchemaTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static JsonNode schemaNode;
  private static Schema schema;

  @TempDir Path dataDir;

  @BeforeAll
  static void load() throws IOException {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.isDirectory(root.resolve("governance"))) root = root.getParent();
    schemaNode = JSON.readTree(
        Files.readString(root.resolve("governance/exit-ledger.v1.schema.json")));
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    SchemaContext context = new SchemaContext(
        registry.getDialect(SpecificationVersion.DRAFT_2020_12.getDialectId()), registry);
    schema = context.newSchema(
        SchemaLocation.of("https://ssot.justsearch/v1/governance/exit-ledger.v1.schema.json"),
        schemaNode, null);
  }

  @Test
  @DisplayName("every record the Java writers write conforms")
  void writtenRecordsConform() throws IOException {
    Path dir = LedgerFamily.directory(dataDir);
    var writer = ExitLedgerWriter.rotating(dir, LedgerFamily.CHILDREN, ExitLedgerWriterTest.ENGINE,
        ExitLedgerWriterTest.CLOCK);
    writer.append(LedgerRecordType.CHILD_SPAWN, ExitLedgerWriterTest.spawn("c-1"));
    writer.append(LedgerRecordType.STOP_INTENT, ExitLedgerWriterTest.stopIntent("c-1"));
    writer.append(LedgerRecordType.CHILD_NOTE, Map.of("childId", "c-1", "token", "x"));
    new EngineSelfIntentWriter(dataDir,
        new EngineSelfIntentWriter.EngineIdentity(4242L, Optional.empty(), "inst-1"),
        ExitLedgerWriterTest.ENGINE, ExitLedgerWriterTest.CLOCK)
        .record(StopReason.QUIT, Optional.empty(), 0);

    List<String> lines = new ArrayList<>();
    lines.addAll(Files.readAllLines(LedgerFamily.CHILDREN.current(dir)));
    lines.addAll(Files.readAllLines(LedgerFamily.ENGINE.current(dir)));
    assertEquals(4, lines.size());
    for (String line : lines) assertConforms(line);
  }

  @Test
  @DisplayName("a gap record written after a failure conforms")
  void gapRecordConforms() throws IOException {
    Path dir = dataDir.resolve("blocked");
    Files.writeString(dir, "file");
    var writer = ExitLedgerWriter.rotating(dir, LedgerFamily.CHILDREN, ExitLedgerWriterTest.ENGINE,
        ExitLedgerWriterTest.CLOCK);
    writer.append(LedgerRecordType.CHILD_NOTE, Map.of("childId", "c-1", "token", "x"));
    Files.delete(dir);
    writer.append(LedgerRecordType.CHILD_NOTE, Map.of("childId", "c-1", "token", "y"));
    for (String line : Files.readAllLines(LedgerFamily.CHILDREN.current(dir))) assertConforms(line);
  }

  @Test
  @DisplayName("the schema rejects what a writer must never write")
  void rejectsBadRecords() {
    String envelope = "\"schemaVersion\":1,\"seq\":1,\"at\":\"2026-10-05T10:00:00.000Z\","
        + "\"writer\":{\"role\":\"engine\",\"pid\":7,\"startedAt\":\"2026-10-05T10:00:00Z\"}";
    assertRejected("{" + envelope + ",\"type\":\"child-spawn\",\"childId\":\"c\",\"kind\":\"MCP_SERVER\","
        + "\"label\":\"x\",\"owner\":{\"pid\":7,\"creationFileTime\":null,\"instanceId\":\"i\"},\"gpu\":false}");
    assertRejected("{" + envelope + ",\"type\":\"child-spawn\",\"childId\":\"c\",\"kind\":\"EXTRACTION\","
        + "\"label\":\"C:\\\\models\\\\x.gguf\",\"owner\":{\"pid\":7,\"creationFileTime\":null,\"instanceId\":\"i\"},\"gpu\":false}");
    assertRejected("{" + envelope + ",\"type\":\"engine-self-intent\",\"engine\":{\"pid\":7,"
        + "\"creationFileTime\":null,\"instanceId\":\"i\"},\"reason\":\"RECYCLE\",\"detail\":null,\"expectedCode\":0}");
    assertRejected("{" + envelope + ",\"type\":\"ledger-gap\",\"reason\":\"WRITE_FAILED\",\"count\":0,"
        + "\"since\":\"2026-10-05T10:00:00Z\"}");
    assertRejected("{\"schemaVersion\":1,\"type\":\"child-note\",\"childId\":\"c\",\"token\":\"x\"}");
  }

  @Test
  @DisplayName("the schema's enums are exactly the app-api enums")
  void enumsMatchJava() {
    JsonNode defs = schemaNode.get("$defs");
    assertEquals(names(StopReason.values()), values(defs.get("stopReason")));
    assertEquals(names(StopMode.values()), values(defs.get("stopMode")));
    assertEquals(names(ExitClass.values()), values(defs.get("exitClass")));
    assertEquals(names(ChildKind.values()), values(defs.get("childKind")));
    assertEquals(names(ExitOutcome.Phase.values()), values(defs.get("phase")));
    Set<String> details = new LinkedHashSet<>();
    for (StopDetail d : StopDetail.values()) details.add(d.token());
    details.add("null");
    assertEquals(details, values(defs.get("stopDetailOrNull")));

    Set<String> types = new LinkedHashSet<>();
    for (LedgerRecordType t : LedgerRecordType.values()) types.add(t.token());
    assertEquals(types, values(schemaNode.get("properties").get("type")));
    Set<String> roles = new LinkedHashSet<>();
    for (LedgerWriterId.Role r : LedgerWriterId.Role.values()) roles.add(r.token());
    assertEquals(roles, values(schemaNode.at("/properties/writer/properties/role")));
    Set<String> ending = new LinkedHashSet<>();
    for (StopReason r : StopReason.values()) if (r.endsEngine()) ending.add(r.name());
    JsonNode selfIntent = null;
    for (JsonNode branch : schemaNode.get("allOf")) {
      if ("engine-self-intent".equals(branch.at("/if/properties/type/const").asString())) {
        selfIntent = branch;
      }
    }
    assertEquals(ending, values(selfIntent.at("/then/properties/reason")));
    JsonNode gap = null;
    for (JsonNode branch : schemaNode.get("allOf")) {
      if ("ledger-gap".equals(branch.at("/if/properties/type/const").asString())) gap = branch;
    }
    assertEquals(names(LedgerGapReason.values()), values(gap.at("/then/properties/reason")));
    assertEquals(ExitLedger.SCHEMA_VERSION, schemaNode.at("/properties/schemaVersion/const").asInt());
  }

  private static void assertConforms(String line) {
    var errors = schema.validate(JSON.readTree(line));
    assertTrue(errors.isEmpty(), () -> line + " -> " + errors);
  }

  private static void assertRejected(String line) {
    assertFalse(schema.validate(JSON.readTree(line)).isEmpty(), line);
  }

  private static Set<String> names(Enum<?>[] values) {
    Set<String> out = new LinkedHashSet<>();
    Arrays.stream(values).forEach(v -> out.add(v.name()));
    return out;
  }

  private static Set<String> values(JsonNode enumHolder) {
    Set<String> out = new LinkedHashSet<>();
    for (JsonNode v : enumHolder.get("enum")) out.add(v.isNull() ? "null" : v.asString());
    return out;
  }
}
