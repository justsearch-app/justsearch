/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Projects agent history into the list a model request sends. Chat templates such as Qwen3.5's
 * accept a system message only at the beginning, and strict ones refuse an assistant tool call
 * without its result, so the outgoing list differs from history in exactly two ways:
 *
 * <ul>
 *   <li>Every system entry after the leading one (a user's direction, the resume warning, a note an
 *       older build left in history) goes out as a user turn at its place, labelled by kind. One
 *       that sits between an assistant tool call and its results waits until the calls are answered.
 *   <li>A tool call that history leaves unanswered (a run interrupted at an approval, or partway
 *       through a batch) is answered with {@link #NOT_EXECUTED}, after the answers that exist.
 * </ul>
 *
 * <p>Session history, checkpoints and events keep their messages; this is applied at send time, to
 * completion and prompt-token counting alike, so both measure the same list.
 */
final class AgentModelMessages {
  private AgentModelMessages() {}

  /** How a user's mid-run direction is written into history (and recognised in saved history). */
  static final String STEERING_PREFIX = "Mid-run steering directive from the user: ";

  static final String RESUME_PREFIX = "Resume note: ";

  static final String RESUME_NOTE =
      RESUME_PREFIX
          + "previous pending approvals must be re-requested after restart before any write or"
          + " destructive action.";

  static final String DIRECTION_LABEL = "Direction from the user, sent while you were working: ";

  static final String RESUME_LABEL =
      "Note from JustSearch (this run was resumed after an interruption): ";

  static final String NOTE_LABEL = "Note from JustSearch: ";

  /** The answer sent for an assistant tool call that never ran. */
  static final String NOT_EXECUTED =
      "Not executed: the run stopped before this call ran, so it had no effect. Call it again if it"
          + " is still needed; an action that needs approval will ask for approval again.";

  static Map<String, Object> steeringNote(String direction) {
    return Map.of(
        "role",
        "system",
        "content",
        STEERING_PREFIX + direction + " Adjust your plan accordingly for the remaining steps.");
  }

  /**
   * A user direction or the resume warning: control text that governs the rest of the run, so
   * handoff pruning, profile switches, compaction and forks keep it. Recognised by text, so
   * snapshots written before these notes were kept are recognised too.
   */
  static boolean isUserControlNote(Map<String, Object> message) {
    if (!"system".equals(message.get("role"))) {
      return false;
    }
    String text = Objects.toString(message.get("content"), "");
    return text.startsWith(STEERING_PREFIX) || text.startsWith(RESUME_PREFIX);
  }

  /** The list a model request sends; see the class comment. */
  static List<Map<String, Object>> forModel(List<Map<String, Object>> messages) {
    var out = new ArrayList<Map<String, Object>>(messages.size());
    for (int i = 0; i < messages.size(); i++) {
      var message = messages.get(i);
      if (i > 0 && "system".equals(message.get("role"))) {
        addNote(out, message);
        continue;
      }
      out.add(message);
      if (!"assistant".equals(message.get("role"))
          || !(message.get("tool_calls") instanceof List<?> calls)) {
        continue;
      }
      var outstanding = new LinkedHashSet<String>();
      for (Object call : calls) {
        if (call instanceof Map<?, ?> map && map.get("id") != null) {
          outstanding.add(map.get("id").toString());
        }
      }
      var waiting = new ArrayList<Map<String, Object>>();
      while (!outstanding.isEmpty() && i + 1 < messages.size()) {
        var next = messages.get(i + 1);
        if ("tool".equals(next.get("role"))) {
          out.add(next);
          outstanding.remove(Objects.toString(next.get("tool_call_id"), ""));
        } else if ("system".equals(next.get("role"))) {
          waiting.add(next);
        } else {
          break;
        }
        i++;
      }
      for (String id : outstanding) {
        out.add(Map.of("role", "tool", "tool_call_id", id, "content", NOT_EXECUTED));
      }
      waiting.forEach(note -> addNote(out, note));
    }
    return out;
  }

  private static void addNote(List<Map<String, Object>> out, Map<String, Object> system) {
    String text = Objects.toString(system.get("content"), "");
    if (text.isBlank()) {
      return;
    }
    String labelled;
    if (text.startsWith(STEERING_PREFIX)) {
      labelled = DIRECTION_LABEL + text.substring(STEERING_PREFIX.length());
    } else if (text.startsWith(RESUME_PREFIX)) {
      labelled = RESUME_LABEL + text.substring(RESUME_PREFIX.length());
    } else {
      labelled = NOTE_LABEL + text;
    }
    out.add(Map.of("role", "user", "content", labelled));
  }
}
