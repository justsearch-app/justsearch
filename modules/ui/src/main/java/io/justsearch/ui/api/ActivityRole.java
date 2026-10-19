/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.security.RouteRole;

/** Operation-owned input semantics, independent of HTTP method and cooperative attribution. */
public enum ActivityRole implements RouteRole {
  USER_ACTION,
  OBSERVATION,
  /** The protocol owner declares activity after parsing the operation in the message. */
  PROTOCOL;

  static boolean isUserActivity(Context request) {
    return request.routeRoles().contains(USER_ACTION);
  }

  /** Refuse incomplete production wiring; a newly added poll cannot silently become input. */
  static void validate(Javalin app) {
    for (var entry : app.unsafeConfig().pvt.internalRouter.allHttpHandlers()) {
      var endpoint = entry.getEndpoint();
      if (!endpoint.getMethod().isHttpMethod()) continue;
      long declarations = endpoint.getRoles().stream().filter(ActivityRole.class::isInstance).count();
      if (declarations != 1) {
        throw new IllegalStateException("Declare exactly one activity property for "
            + endpoint.getMethod() + " " + endpoint.getPath());
      }
    }
  }
}
