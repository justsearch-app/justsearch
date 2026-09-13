package io.justsearch.app.services.mcphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import org.junit.jupiter.api.Test;

/** Verifies the MCP-host handler maps tool calls + failures onto {@link OperationResult}. */
class McpToolHandlerTest {

  private static McpClient initializedClient() {
    McpClient client = new McpClient(new FakeMcpTransport());
    client.initialize();
    return client;
  }

  @Test
  void successfulCallReturnsToolText() {
    McpToolHandler handler = new McpToolHandler(initializedClient(), "echo");
    OperationResult result = handler.execute("{\"message\":\"hi there\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(result.success());
    assertEquals("hi there", result.message());
  }

  @Test
  void emptyArgumentsAreAccepted() {
    McpToolHandler handler = new McpToolHandler(initializedClient(), "add");
    OperationResult result = handler.execute("", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(result.success());
    assertEquals("0", result.message());
  }

  @Test
  void toolReportedErrorBecomesFailureNotException() {
    McpToolHandler handler = new McpToolHandler(initializedClient(), "boom");
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("reported an error"));
  }

  @Test
  void malformedArgumentsJsonBecomesFailure() {
    McpToolHandler handler = new McpToolHandler(initializedClient(), "echo");
    OperationResult result = handler.execute("{not json", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("Invalid arguments JSON"));
  }

  @Test
  void transportFailureBecomesFailure() {
    FakeMcpTransport transport = new FakeMcpTransport();
    McpClient client = new McpClient(transport);
    client.initialize();
    transport.transportFailure = true;
    McpToolHandler handler = new McpToolHandler(client, "echo");
    OperationResult result = handler.execute("{\"message\":\"x\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("call failed"));
  }

  @Test
  void nonTextContentSurfacesInStructuredData() {
    McpToolHandler handler = new McpToolHandler(initializedClient(), "get-image");
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(result.success());
    // The image survives into structuredData.mcpContent (so the FE can render it).
    assertTrue(result.structuredData().containsKey("mcpContent"), "mcpContent must carry non-text blocks");
  }
}
