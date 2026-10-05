/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/** Why chat is {@link ChatState#FAILED}: the controller does not retry this configuration. */
public enum FailedCode {
  RESOURCE_EXHAUSTED,
  CRASH_LOOP,
  STARTUP_TIMEOUT,
  START_FAILED,
  MISSING_DLL,
  MODEL_LOAD_FAILED,
  STOP_FAILED,
  EXTERNAL_UNHEALTHY,
  DEVICE_LOST_LIMIT
}
