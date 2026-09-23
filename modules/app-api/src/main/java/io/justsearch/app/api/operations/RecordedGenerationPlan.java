/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

/** Exact generation inputs shared by the two accepted recorded REINDEX payload versions. */
public sealed interface RecordedGenerationPlan
    permits RecordedBulkPlan, RecordedInstallerGenerationPlan {
  String source();
  RecordedRootPlan scope();
  IndexTargetSnapshot target();
  String planHash();
}
