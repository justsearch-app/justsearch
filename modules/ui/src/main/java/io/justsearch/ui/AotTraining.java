/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

/**
 * AOT Cache training entry point for the Head process.
 *
 * <p>Loads representative classes from the Head classpath to populate the JDK 25 AOT Cache
 * (JEP 514). This class is used only at build time — it is NOT the production entry point.
 *
 * <p>The training run must use the same classpath as production. It loads classes from key
 * libraries (Jackson, Javalin, SLF4J, OTel, etc.) so that AOT class linking can pre-verify
 * and pre-link them. JEP 515 method profiling captures execution profiles automatically.
 *
 * <p>Usage (Gradle): {@code java -XX:AOTMode=record -XX:AOTConfiguration=head.aotconf -cp ... io.justsearch.ui.AotTraining}
 *
 * @see <a href="https://openjdk.org/jeps/514">JEP 514: Ahead-of-Time Command-Line Ergonomics</a>
 */
public final class AotTraining {

  private AotTraining() {}

  public static void main(String[] args) {
    // Touch representative classes from each major dependency to ensure they are loaded
    // and recorded in the AOT configuration. The JVM records all classes loaded by the
    // standard classloader during the training run.

    // --- SLF4J + Logback (load without initializing to avoid logback config I/O) ---
    touch("org.slf4j.LoggerFactory");
    touch("ch.qos.logback.classic.Logger");
    touch("ch.qos.logback.classic.LoggerContext");

    // --- Jackson 3 ---
    touch("tools.jackson.databind.ObjectMapper");
    touch("tools.jackson.databind.JsonNode");
    touch("tools.jackson.core.JsonParser");

    // --- Javalin ---
    touch("io.javalin.Javalin");
    touch("io.javalin.http.Context");

    // --- OpenTelemetry ---
    touch("io.opentelemetry.api.GlobalOpenTelemetry");
    touch("io.opentelemetry.sdk.trace.SdkTracerProvider");

    // --- gRPC (Head is a client) ---
    touch("io.grpc.ManagedChannelBuilder");
    touch("io.grpc.StatusRuntimeException");

    // --- Protobuf ---
    touch("com.google.protobuf.GeneratedMessage");

    // --- JustSearch core classes ---
    touch("io.justsearch.ui.HeadlessApp");
    touch("io.justsearch.ui.api.LocalApiServer");
    touch("io.justsearch.configuration.resolved.ConfigStore");
    // Item A11 removed the WorkerSpawner touch: the class is deleted, so warming it warmed
    // nothing. Item A13 folds the (former) Worker cache's remaining touches in here.
    touch("io.justsearch.telemetry.LocalTelemetry");
    touch("io.justsearch.app.api.UiSettings");
    touch("io.justsearch.app.services.settings.UiSettingsStore");

    System.exit(0);
  }

  /**
   * Load a class by name without running static initializers. AOT recording only needs
   * the class to be loaded by the classloader — initialization can trigger native library
   * loading, service discovery, or blocking I/O that hangs the training run.
   */
  private static void touch(String className) {
    try {
      Class.forName(className, false, AotTraining.class.getClassLoader());
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      // Expected for optional dependencies — skip silently.
    }
  }
}
