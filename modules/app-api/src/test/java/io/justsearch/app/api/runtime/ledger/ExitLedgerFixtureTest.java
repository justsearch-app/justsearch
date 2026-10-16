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
import io.justsearch.app.api.runtime.child.StopDetail;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The shared fixtures under {@code governance/fixtures/exit-ledger/} are what every language's
 * reader must accept: each line conforms to the v1 schema and the Java reader reads all of them,
 * with every detail token known. The Node and Rust readers read the same files (Spec F step 5).
 */
@DisplayName("exit ledger shared fixtures")
final class ExitLedgerFixtureTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Every fixture line conforms and reads back with a known detail. */
  @Test
  void fixturesConformAndRead() throws IOException {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.isDirectory(root.resolve("governance"))) root = root.getParent();
    Path fixtures = root.resolve("governance/fixtures/exit-ledger");
    Schema schema = schema(root);

    Set<StopDetail> seen = EnumSet.noneOf(StopDetail.class);
    for (LedgerFamily family : LedgerFamily.values()) {
      Path file = family.current(fixtures);
      if (!Files.exists(file)) continue;
      for (String line : Files.readAllLines(file)) {
        if (line.isBlank()) continue;
        var errors = schema.validate(JSON.readTree(line));
        assertTrue(errors.isEmpty(), () -> line + " -> " + errors);
      }
      LedgerReadResult read = ExitLedgerReader.read(fixtures, family);
      assertEquals(0, read.skippedLines(), family + " fixture lines are all readable");
      assertFalse(read.tornTail(), family + " fixture ends with a newline");
      for (LedgerRecord record : read.records()) {
        Optional<String> raw = record.text("detail");
        if (raw.isEmpty()) continue;
        Optional<StopDetail> known = StopDetail.fromToken(raw.get());
        assertTrue(known.isPresent(), () -> "fixture detail is a known token: " + raw.get());
        seen.add(known.get());
      }
    }
    for (String token : new String[] {"session_end", "takeover", "interrupted"}) {
      Optional<StopDetail> detail = StopDetail.fromToken(token);
      assertTrue(detail.isPresent() && seen.contains(detail.get()),
          () -> "a fixture line carries " + token);
    }
  }

  private static Schema schema(Path root) throws IOException {
    JsonNode node = JSON.readTree(
        Files.readString(root.resolve("governance/exit-ledger.v1.schema.json")));
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    SchemaContext context = new SchemaContext(
        registry.getDialect(SpecificationVersion.DRAFT_2020_12.getDialectId()), registry);
    return context.newSchema(
        SchemaLocation.of("https://ssot.justsearch/v1/governance/exit-ledger.v1.schema.json"),
        node, null);
  }
}
