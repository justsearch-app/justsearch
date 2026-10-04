/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import java.util.Map;

/** Build support projected directly from the register-bound store constants, without opening stores. */
public final class SupportedDataVersions {
  private SupportedDataVersions() {}

  public static Map<String, Integer> current() {
    return Map.ofEntries(
        Map.entry("ui-settings", io.justsearch.app.services.settings.UiSettingsStore.CURRENT_SCHEMA_VERSION),
        Map.entry("durable-grants", io.justsearch.app.services.intent.DurableGrantStore.CURRENT_SCHEMA_VERSION),
        Map.entry("plugin-allowlist", io.justsearch.app.services.settings.PluginAllowlistStore.CURRENT_SCHEMA_VERSION),
        Map.entry("feedback-capture-preference", io.justsearch.app.services.feedback.FeedbackCaptureSettings.CURRENT_SCHEMA_VERSION),
        Map.entry("installed-packs", io.justsearch.app.services.ai.pack.InstalledPacksStore.CURRENT_SCHEMA_VERSION),
        Map.entry("ai-install-contract", io.justsearch.configuration.model.InstallContractIO.CURRENT_SCHEMA_VERSION),
        Map.entry("memories", io.justsearch.agent.FileMemoryStore.CURRENT_SCHEMA_VERSION),
        Map.entry("conversations", io.justsearch.app.services.conversation.FileConversationStore.CURRENT_SCHEMA_VERSION),
        Map.entry("agent-runs", io.justsearch.agent.AgentRunStore.CURRENT_SCHEMA_VERSION),
        Map.entry("run-events", io.justsearch.agent.RunEventStore.CURRENT_SCHEMA_VERSION),
        Map.entry("file-operation-journal", io.justsearch.agent.tools.FileOperationLog.CURRENT_SCHEMA_VERSION),
        Map.entry("feedback-records", io.justsearch.app.services.feedback.NdjsonAppendStore.CURRENT_SCHEMA_VERSION),
        Map.entry("watched-roots", io.justsearch.configuration.persistence.WatchedRootsFormat.CURRENT_SCHEMA_VERSION),
        Map.entry("jobs-db", io.justsearch.indexerworker.queue.SqliteSchema.TARGET_VERSION),
        Map.entry("index-generations", io.justsearch.indexerworker.index.IndexGenerationManager.STATE_FORMAT_VERSION),
        Map.entry("entity-clusters", io.justsearch.indexerworker.disambiguation.EntityClusterStore.CURRENT_SCHEMA_VERSION),
        Map.entry("ai-install-attempt-memory", io.justsearch.app.services.ai.install.InstallAttemptMemory.VERSION),
        Map.entry("operations-db", io.justsearch.app.observability.operations.OperationSchema.VERSION));
  }
}
