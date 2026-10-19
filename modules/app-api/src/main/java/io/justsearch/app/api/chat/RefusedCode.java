/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/** Why chat is {@link ChatState#REFUSED}: a load was declined under the current conditions. */
public enum RefusedCode {
  PROFILE_CANNOT_FIT,
  EXTERNAL_CONFLICT,
  EXTERNAL_POLICY_BLOCKED,
  EXECUTABLE_MISSING,
  INVALID_CONFIG
}
