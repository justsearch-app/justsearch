/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
final class PrivateDataIngestTest {
  @TempDir Path tempDir;
  @Test
  void dataFolderCannotBeImportedIntoAUserCollection() throws IOException {
    var previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    var config = new io.justsearch.configuration.resolved.ConfigStore(
        io.justsearch.configuration.resolved.ResolvedConfig.builder()
            .putDefault("justsearch.data.dir", tempDir.toString()).build());
    io.justsearch.configuration.resolved.ConfigStore.setGlobal(config);
    try {
      Path history = Files.createDirectories(tempDir.resolve("agent-history"));
      Files.writeString(history.resolve("run.md"), "assistant transcript");
      var tool = new IngestTool(io.justsearch.app.api.operations.RecordedIngestionService.unavailable(),
          ignored -> List.of(), ignored -> "generation-1", List::of);
      String args = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
          Map.of("paths", List.of(history.toString()), "collection", "personal"));
      var refused = assertThrows(io.justsearch.agent.api.registry.OperationPreparationRefused.class,
          () -> tool.prepare(args, io.justsearch.agent.api.registry.InvocationProvenance.agentLoop(java.time.Instant.EPOCH),
              io.justsearch.agent.EngineContextTestFixtures.AGENT_LOOP));
      assertTrue(refused.refusal().message().contains("application data folder cannot be ingested"));
    } finally { io.justsearch.configuration.resolved.ConfigStore.restoreGlobal(config, previous); }
  }

  @Test
  void outsideIngestCannotClaimAReservedCollection() throws IOException {
    Path file = Files.writeString(tempDir.resolve("ordinary.md"), "ordinary content");
    var tool = new IngestTool(io.justsearch.app.api.operations.RecordedIngestionService.unavailable(),
        ignored -> List.of(), ignored -> "generation-1", List::of);
    var outside = new io.justsearch.core.context.EngineContext(
        io.justsearch.core.context.EngineContext.ClientKind.MCP_CLIENT, "outside-test",
        java.util.Optional.empty(), java.util.Optional.empty(), "UNTRUSTED", "MCP",
        io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,
        io.justsearch.core.context.EngineContext.Urgency.FOREGROUND);
    for (String collection : List.of("agent-history", "AGENT-HISTORY", "justsearch-help")) {
      String args = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
          Map.of("paths", List.of(file.toString()), "collection", collection));
      var refused = assertThrows(io.justsearch.agent.api.registry.OperationPreparationRefused.class,
          () -> tool.prepare(args, io.justsearch.agent.api.registry.InvocationProvenance.agentLoop(java.time.Instant.EPOCH), outside));
      assertTrue(refused.refusal().message().contains("is reserved for app-internal documents"));
    }
  }
}
