/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.app.api.operations.OperationPreparedPayload;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.configuration.model.ChatModelProfile;
import java.util.Objects;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Private accepted intent for a settings effect that cannot be reconstructed from settings.json. */
final class SettingsCandidatePreparation {
  static final String OPERATION_REF = "settings.apply-internal-candidate";
  private static final String SCHEMA = "settings-candidate-v1";
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private SettingsCandidatePreparation() {}

  static OperationPreparedPayload encode(SettingsCandidateContext context) {
    Objects.requireNonNull(context, "context");
    if (SettingsCandidateContext.NONE.equals(context)) {
      throw new IllegalArgumentException("Candidate preparation requires physical intent");
    }
    return new OperationPreparedPayload(false, JSON.writeValueAsString(new Stored(SCHEMA,
        context.hasChatProfile() ? context.chatProfile().id() : null,
        context.forceGenerativeRefresh())));
  }

  static SettingsCandidateContext decode(OperationRecord row, OperationStore.Preparation preparation) {
    try {
      Objects.requireNonNull(row, "row");
      Objects.requireNonNull(preparation, "preparation");
      if (row.descriptor().kind() != io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY
          || !OPERATION_REF.equals(row.descriptor().operationRef()) || preparation.payload().sealed()) {
        throw new IllegalArgumentException("Candidate preparation binding mismatch");
      }
      var tree = JSON.readTree(preparation.payload().value());
      if (tree == null || !tree.isObject() || tree.size() != 3
          || !tree.has("schema") || !tree.has("chatProfileId") || !tree.has("forceGenerativeRefresh")
          || !tree.get("schema").isTextual() || !SCHEMA.equals(tree.get("schema").asText())
          || !(tree.get("chatProfileId").isNull() || tree.get("chatProfileId").isTextual())
          || !tree.get("forceGenerativeRefresh").isBoolean()) {
        throw new IllegalArgumentException("Invalid candidate preparation fields");
      }
      String profileId = tree.get("chatProfileId").isNull() ? null : tree.get("chatProfileId").asText();
      ChatModelProfile profile = null;
      if (profileId != null) {
        for (ChatModelProfile known : ChatModelProfile.values()) {
          if (known.id().equals(profileId)) { profile = known; break; }
        }
        if (profile == null) throw new IllegalArgumentException("Unknown chat profile");
      }
      var context = new SettingsCandidateContext(profile,
          tree.get("forceGenerativeRefresh").booleanValue());
      if (SettingsCandidateContext.NONE.equals(context)) {
        throw new IllegalArgumentException("Empty candidate preparation");
      }
      return context;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid accepted settings candidate preparation");
    }
  }

  private record Stored(String schema, String chatProfileId, boolean forceGenerativeRefresh) {}
}
