/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentProfile;
import io.justsearch.agent.api.ToolCallRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Multi-agent handoff helpers (tempdoc 240 W1-handoff — extracted from
 * {@code AgentLoopService}). Builds {@code handoff_to_*} tools, recognizes and
 * parses handoff calls, resolves profiles, and prunes the outgoing agent's
 * exploration history into a compact research brief on handoff. All pure
 * statics; the instance-coupled {@code swapSystemPrompt} stays in AgentLoopService.
 */
final class AgentHandoff {

  private static final Logger LOG = LoggerFactory.getLogger(AgentHandoff.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private AgentHandoff() {}

  static List<Map<String, Object>> buildHandoffTools(
      List<AgentProfile> profiles, String activeAgentId) {
    return profiles.stream()
        .filter(p -> !p.agentId().equals(activeAgentId))
        .map(p -> Map.<String, Object>of(
            "type", "function",
            "function", Map.of(
                "name", "handoff_to_" + p.agentId(),
                "description", "Hand off to the " + p.name() + " agent.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "reason", Map.of(
                            "type", "string",
                            "description", "Why this handoff is needed.")),
                    "required", List.of("reason")))))
        .toList();
  }

  static AgentProfile findProfile(List<AgentProfile> profiles, String agentId) {
    return profiles.stream()
        .filter(p -> p.agentId().equals(agentId))
        .findFirst()
        .orElse(null);
  }

  static boolean isHandoffCall(ToolCallRequest call) {
    return call.toolName().startsWith("handoff_to_");
  }

  static String extractReason(String arguments) {
    try {
      JsonNode node = MAPPER.readTree(arguments);
      JsonNode reason = node.get("reason");
      return reason != null && !reason.isNull() ? reason.asText() : "";
    } catch (Exception e) {
      return "";
    }
  }

  /**
   * Strips the outgoing agent's exploration tool-call / tool-result message pairs from the
   * conversation history, replacing them with a compact research brief injected as a system
   * message. This prevents context explosion when a token-rich Researcher session is inherited
   * verbatim by the Organizer.
   *
   * <p>Only messages between the user turn (index 1) and the handoff assistant message (index K)
   * are affected. The handoff call itself, the "Handoff from…" system message, and the "confirmed"
   * tool result are all preserved, and so are the user's directions and the resume warning in that
   * range (WP6.2): they stay, in order, before the handoff call.
   */
  static void pruneHandoffMessages(AgentSession session) {
    List<Map<String, Object>> msgs = session.messages();
    // Need at least: [0]=system [1]=user [K]=handoff-assistant [K+1]=handoff-system [K+2]=result
    if (msgs.size() < 5) return;

    // Walk backward to find the assistant message that contains the handoff call (index K)
    int handoffAssistantIdx = -1;
    for (int i = msgs.size() - 1; i >= 2; i--) {
      Map<String, Object> msg = msgs.get(i);
      if (!"assistant".equals(msg.get("role"))) continue;
      Object toolCallsObj = msg.get("tool_calls");
      if (!(toolCallsObj instanceof List)) continue;
      for (Object tc : (List<?>) toolCallsObj) {
        if (!(tc instanceof Map)) continue;
        Object fn = ((Map<?, ?>) tc).get("function");
        if (!(fn instanceof Map)) continue;
        Object name = ((Map<?, ?>) fn).get("name");
        if (name instanceof String s && s.startsWith("handoff_to_")) {
          handoffAssistantIdx = i;
          break;
        }
      }
      if (handoffAssistantIdx >= 0) break;
    }

    // Nothing to prune if handoff call is right after the user turn (no exploration occurred)
    if (handoffAssistantIdx < 0 || handoffAssistantIdx <= 2) return;

    // Collect tool call/result content from indices [2 .. handoffAssistantIdx-1]
    var brief = new StringBuilder("Research findings:\n");
    var kept = new java.util.ArrayList<Map<String, Object>>();
    for (int i = 2; i < handoffAssistantIdx; i++) {
      Map<String, Object> msg = msgs.get(i);
      if (AgentModelMessages.isUserControlNote(msg)) {
        kept.add(msg);
        continue;
      }
      String role = String.valueOf(msg.get("role"));
      if ("assistant".equals(role)) {
        Object toolCallsObj = msg.get("tool_calls");
        if (toolCallsObj instanceof List<?> tcs) {
          for (Object tc : tcs) {
            if (!(tc instanceof Map)) continue;
            Object fn = ((Map<?, ?>) tc).get("function");
            if (!(fn instanceof Map)) continue;
            String toolName = String.valueOf(((Map<?, ?>) fn).get("name"));
            if (toolName.startsWith("handoff_to_")) continue;  // skip handoff infrastructure
            String args = String.valueOf(((Map<?, ?>) fn).get("arguments"));
            brief.append("- ").append(toolName).append(": ")
                .append(args.length() > 100 ? args.substring(0, 100) + "…" : args)
                .append("\n");
          }
        }
      } else if ("tool".equals(role)) {
        String content = String.valueOf(msg.getOrDefault("content", ""));
        brief.append("  → ").append(
                content.length() > 200 ? content.substring(0, 200) + "…" : content)
            .append("\n");
      }
    }

    // Only directions before the handoff call: no exploration to brief, nothing to prune.
    if (kept.size() == handoffAssistantIdx - 2) return;

    // Remove exploration messages in reverse order to preserve indices
    for (int i = handoffAssistantIdx - 1; i >= 2; i--) {
      msgs.remove(i);
    }

    // Inject the brief as a system message immediately after the user turn
    msgs.add(2, Map.of("role", "system", "content", brief.toString().trim()));
    msgs.addAll(3, kept);
    LOG.debug("pruneHandoffMessages: stripped {} exploration messages, injected brief ({} chars)",
        handoffAssistantIdx - 2 - kept.size(), brief.length());
  }
}
