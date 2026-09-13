/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.HistoryPolicy;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.OnOverflow;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.SubscriptionMode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Durable operation-history Resource, with bounded snapshots and a five-minute live frame window. */
public final class OperationHistoryResourceCatalog implements ResourceCatalog {

  /** Stable namespace for the operation-history Resource entry. */
  public static final String NAMESPACE = "core";

  /** Stable id for the Resource entry. */
  public static final ResourceRef OPERATION_HISTORY_ID =
      new ResourceRef("core.operation-history");

  /** SSE endpoint advertised by the Resource entry. */
  public static final String ENDPOINT = "/api/operation-history/stream";

  /** Schema URL for the wire payload (OperationHistoryEntry record). */
  public static final String SCHEMA_URL =
      "https://ssot.justsearch/v1/schemas/operation-history-entry.v1.json";

  /** Discriminates the renderer in the FE generic dispatcher. */
  public static final String KIND = "operation-history";

  /** Retention belongs to the operations table; the recent-read limit is not a persistence cap. */
  public static final Duration RETENTION = io.justsearch.app.api.operations.OperationStore.HISTORY_RETENTION;

  /** Resume window — same shape as {@link io.justsearch.app.observability.health.HealthResourceCatalog}. */
  public static final Duration RESUME_WINDOW = Duration.ofMinutes(5);

  private static final List<Resource> DEFINITIONS =
      List.of(
          new Resource(
              OPERATION_HISTORY_ID,
              Presentation.of(
                  new I18nKey("registry-resource.operation-history.label"),
                  new I18nKey("registry-resource.operation-history.description")),
              SCHEMA_URL,
              Category.EVENT_STREAM,
              SubscriptionMode.SSE_STREAM,
              ENDPOINT,
              KIND,
              Optional.of(
                  new HistoryPolicy(
                      HistoryPolicy.Mode.DURABLE,
                      Optional.empty(),
                      Optional.of(RETENTION),
                      OnOverflow.EVICT_OLDEST,
                      RESUME_WINDOW)),
              Optional.empty(),
              Provenance.core("1.0"),
              // Slice 445 substrate-extension. OperationHistoryEntry has no
              // path-typed fields.
              Privacy.noPaths(),
              Set.of(),
              Set.of(),
              ""));

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Resource> definitions() {
    return DEFINITIONS;
  }
}
