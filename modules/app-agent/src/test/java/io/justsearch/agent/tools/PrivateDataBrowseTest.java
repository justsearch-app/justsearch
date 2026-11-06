/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;
import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.agent.EngineContextTestFixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
final class PrivateDataBrowseTest {
  @Test
  void genericBrowseRefusesPrivateDataAndNeverListsItAsARoot() {
    var store = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    var configured = store == null ? null : store.get().paths().dataDir();
    String data = (configured == null ? io.justsearch.configuration.PlatformPaths.resolveDataDir() : configured)
        .toAbsolutePath().normalize().toString();
    String history = java.nio.file.Path.of(data).resolve("agent-history").toString();
    var tool = new BrowseTool((request, context) -> {
      fail("private paths must not reach folder projection"); return null;
    }, context -> List.of(new BrowseTool.RootInfo(data, "private-app-data")));
    String args = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
        Map.of("parent_path", history));
    var refused = tool.execute(args, EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(refused.success());
    assertTrue(refused.message().contains("private"));
    assertFalse(tool.execute("{}", EngineContextTestFixtures.AGENT_LOOP).message().contains("private-app-data"));
  }

}
