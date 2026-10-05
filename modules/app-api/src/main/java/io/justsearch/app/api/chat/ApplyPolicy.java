/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/** Whether applying a new chat configuration restarts a running server (today's restart policy). */
public enum ApplyPolicy {
  APPLY_ONLY,
  RESTART_IF_ONLINE,
  RESTART_ALWAYS
}
