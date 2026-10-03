/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.mcphost;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link OperationHandler} that executes an MCP-host operation by calling the backing tool on
 * its external server. Registered against the projected {@link
 * McpToolProjection#toOperation}'s ref, it is invoked by {@code OperationExecutorImpl} <i>after</i>
 * the trust-lattice consent gate has passed — so by the time we reach here the call is authorized.
 *
 * <p>Failure discipline (tempdoc 560 §4.5, host owns truth): a tool-reported error or a transport
 * failure becomes an {@link OperationResult#failure} the model can see and recover from — it never
 * escapes as an exception that would tear down the agent loop.
 */
public final class McpToolHandler implements OperationHandler {
  private final McpClient client;
  private final String toolName;
  private final ObjectMapper mapper;

  public McpToolHandler(McpClient client, String toolName) {
    this(client, toolName, JsonMapper.builder().build());
  }

  public McpToolHandler(McpClient client, String toolName, ObjectMapper mapper) {
    this.client = Objects.requireNonNull(client, "client");
    this.toolName = Objects.requireNonNull(toolName, "toolName");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    JsonNode args;
    try {
      args =
          (argumentsJson == null || argumentsJson.isBlank())
              ? mapper.createObjectNode()
              : mapper.readTree(argumentsJson);
    } catch (RuntimeException e) {
      return OperationResult.failure("Invalid arguments JSON for MCP tool '" + toolName + "': " + e.getMessage());
    }
    try {
      McpToolResult result = client.callTool(toolName, args);
      if (result.isError()) {
        return OperationResult.failure("MCP tool '" + toolName + "' reported an error: " + result.text());
      }
      Map<String, Object> structured = new LinkedHashMap<>();
      structured.put("mcpTool", toolName);
      structured.put("text", result.text());
      // Carry the full typed content array (incl. image/resource blocks) so non-text content survives
      // to the agent UI instead of being flattened to text (tempdoc 560 Phase 1).
      if (result.hasNonTextContent()) {
        structured.put("mcpContent", result.blocks().stream().map(McpToolHandler::blockToMap).toList());
      }
      return OperationResult.success(result.text(), structured);
    } catch (McpException e) {
      return OperationResult.failure("MCP tool '" + toolName + "' call failed: " + e.getMessage());
    }
  }

  private static Map<String, Object> blockToMap(McpContentBlock block) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", block.type());
    if (!block.text().isEmpty()) {
      m.put("text", block.text());
    }
    if (!block.data().isEmpty()) {
      m.put("data", block.data());
    }
    if (!block.mimeType().isEmpty()) {
      m.put("mimeType", block.mimeType());
    }
    if (!block.uri().isEmpty()) {
      m.put("uri", block.uri());
    }
    return m;
  }

  @Override
  public OperationResult execute(String argumentsJson, InvocationProvenance provenance, EngineContext engineContext) {
    // Provenance is consumed by the lattice before dispatch; the MCP call itself is provenance-agnostic.
    return execute(argumentsJson, engineContext);
  }
}
