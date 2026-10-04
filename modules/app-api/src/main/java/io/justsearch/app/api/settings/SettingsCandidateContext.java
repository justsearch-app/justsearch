/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

import io.justsearch.configuration.model.ChatModelProfile;

/** Internal, attempt-scoped intent that cannot be represented by persisted UI settings. */
public record SettingsCandidateContext(ChatModelProfile chatProfile, boolean forceGenerativeRefresh) {
  public static final SettingsCandidateContext NONE = new SettingsCandidateContext(null, false);

  public SettingsCandidateContext(ChatModelProfile chatProfile) {
    this(chatProfile, false);
  }

  public boolean hasChatProfile() {
    return chatProfile != null;
  }
}
