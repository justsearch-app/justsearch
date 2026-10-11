/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.justsearch.ui.api.ActivityRole;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.ui.api.MessageCatalogController;
import java.util.List;
import java.util.Set;

/**
 * Tempdoc 583 Stage 1: the i18n message-catalog surface, expressed as a declarative table
 * instead of nine near-identical field + construction + route triplets in {@link
 * io.justsearch.ui.api.LocalApiServer}.
 *
 * <p>Every namespace is uniform: it serves the classpath properties file {@code
 * /messages/&lt;namespace&gt;.en.properties} at {@code GET /api/messages/&lt;namespace&gt;/{locale}}
 * via one {@link MessageCatalogController} instance (namespace == route segment == file stem). The
 * controllers are stateless request handlers referenced nowhere else, so constructing them here
 * (rather than holding them as LocalApiServer fields) is behaviour-identical.
 *
 * <p>Adding a catalog is now a one-line edit to {@link #NAMESPACES}, not a three-site edit in
 * LocalApiServer — removing this cohort from the pin-treadmill source (tempdoc 583 §B.3).
 */
public final class MessageCatalogRoutes {
  private MessageCatalogRoutes() {}

  // i18n.ts bootMessageCatalogs hydrates the other six namespaces. These three have no
  // unattended UI/script/tool consumer; an explicit catalog lookup is a deliberate read.
  private static final Set<String> DELIBERATE_READ_NAMESPACES =
      Set.of("registry-prompt", "registry-diagnostic", "inference-failures");

  /**
   * The catalog namespaces, in historical registration order (order is presentation-irrelevant).
   * Each maps to {@code /messages/&lt;ns&gt;.en.properties} and {@code
   * /api/messages/&lt;ns&gt;/{locale}}.
   */
  static final List<String> NAMESPACES =
      List.of(
          "errors", // tempdoc 431 slice 1.1.d
          "registry-operation", // tempdoc 429 §E.17 (3 registry-primitive catalogs)
          "registry-resource",
          "registry-prompt",
          "registry-diagnostic", // slice 448 phase 2
          "registry-surface", // slice 449 phase 2
          "health-events", // tempdoc 430 Phase 2
          "inference-failures", // tempdoc 518 Appendix F W2.3
          "registry-workflow"); // tempdoc 565 §27.4

  /** Constructs one controller per namespace and binds its {@code GET .../{locale}} route. */
  public static void register(Javalin app, Telemetry telemetry) {
    for (String namespace : NAMESPACES) {
      MessageCatalogController controller =
          new MessageCatalogController(
              namespace, "/messages/" + namespace + ".en.properties", telemetry);
      app.get("/api/messages/" + namespace + "/{locale}", controller::handle,
          DELIBERATE_READ_NAMESPACES.contains(namespace)
              ? ActivityRole.USER_ACTION : ActivityRole.OBSERVATION);
    }
  }
}
