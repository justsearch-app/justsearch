/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.Javalin;
import io.justsearch.ui.api.routes.RuntimeApiRoutes;
import io.justsearch.ui.api.routes.StatusRoutes;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.nio.file.Path;
import java.util.List;

/** Registers the SDK routes through their production route registrars. */
final class SdkOpenApiFixture {
  private SdkOpenApiFixture() {}

  static Javalin app() {
    Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.manifestPath()).thenReturn(Path.of("build", "sdk-openapi-fixture", "manifest.json"));
    new RuntimeApiRoutes(new io.justsearch.core.execution.TestEngineExecutors(), publisher).register(app);
    StatusRoutes.registerLifecycleRoutes(app, ctx -> {}, ctx -> {});
    return app;
  }

  static byte[] document() throws java.io.IOException {
    return SdkOpenApiProjection.write(app(), List.of());
  }
}
