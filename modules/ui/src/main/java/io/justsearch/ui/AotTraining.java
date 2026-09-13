/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

/**
 * AOT Cache training entry point for the Engine JVM.
 *
 * <p>Loads representative classes from the Engine classpath to populate the JDK 25 AOT Cache
 * (JEP 514). This class is used only at build time — it is NOT the production entry point.
 *
 * <p>The training run must use the same classpath as production. It loads classes from key
 * libraries (Jackson, Javalin, SLF4J, OTel, Lucene, Tika, SQLite, ONNX Runtime) so that AOT class
 * linking can pre-verify and pre-link them. JEP 515 method profiling captures execution profiles
 * automatically.
 *
 * <p>Lane F stage A item A13: this is now the ONE cache. {@code io.justsearch.indexerworker
 * .AotTraining} and the {@code worker.aot} it trained were deleted with the second distribution,
 * and its touch list folded in below — the index half runs in this JVM since item A6, so those
 * classes are loaded on THIS classpath and warming them here is what stops the collapse from being
 * a cold-start regression.
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

    // --- gRPC: none, because there is none. ---
    // Item A10 deleted the Head's gRPC client (ManagedChannelBuilder went at item A13) and item
    // A14 deleted the rest: the three services in indexing.proto, the infra-health service and its
    // Netty server, the protoc-gen-grpc-java codegen, and every libs.grpc line in every module
    // build file. io.grpc is not on the Engine's classpath at all, so there is nothing here to
    // warm -- and the io.grpc.StatusRuntimeException touch that used to sit here would now fail to
    // resolve. Its stated justification, GplJobCoordinator's classifier, was retargeted onto
    // KnowledgeClientException in the same item (GplJobCoordinator.java:776-802) because no gRPC
    // status type could reach it after A6.

    // --- Protobuf ---
    touch("com.google.protobuf.GeneratedMessage");

    // ==========================================================================================
    // Folded in from the deleted io.justsearch.indexerworker.AotTraining (item A13). The index
    // half is composed in THIS JVM by EngineRoot, so these dominate Engine classloading exactly
    // as they used to dominate the Worker's.
    // ==========================================================================================

    // --- Lucene ---
    touch("org.apache.lucene.index.IndexWriter");
    touch("org.apache.lucene.index.DirectoryReader");
    touch("org.apache.lucene.search.IndexSearcher");
    touch("org.apache.lucene.search.KnnFloatVectorQuery");
    touch("org.apache.lucene.store.MMapDirectory");
    touch("org.apache.lucene.analysis.standard.StandardAnalyzer");
    // The Worker list carried `lucene100.Lucene100Codec`, which has not existed since the Lucene
    // 10.4 bump — `touch` swallows ClassNotFoundException, so it silently warmed nothing. Probed
    // against modules/ui/build/install/ui/lib at item A13 and corrected to the codec the product
    // actually opens indexes with (JustSearchCodec wraps Lucene104Codec,
    // adapters-lucene/runtime/JustSearchCodec.java:7,48).
    touch("org.apache.lucene.codecs.lucene104.Lucene104Codec");
    touch("io.justsearch.adapters.lucene.runtime.JustSearchCodec");
    touch("org.apache.lucene.util.hnsw.HnswGraphSearcher");

    // --- Tika (content extraction) ---
    touch("org.apache.tika.Tika");
    touch("org.apache.tika.parser.AutoDetectParser");
    touch("org.apache.tika.metadata.Metadata");

    // --- SQLite (job queue) ---
    touch("org.sqlite.JDBC");
    touch("org.sqlite.SQLiteConnection");

    // --- ONNX Runtime (embedding, SPLADE, reranker) ---
    touch("ai.onnxruntime.OrtEnvironment");
    touch("ai.onnxruntime.OrtSession");

    // --- JustSearch core classes ---
    touch("io.justsearch.ui.HeadlessApp");
    touch("io.justsearch.ui.api.LocalApiServer");
    touch("io.justsearch.configuration.resolved.ConfigStore");
    // Item A11 removed the WorkerSpawner touch: the class is deleted, so warming it warmed
    // nothing. Item A13 replaced it with the Engine composition root and the index-half server
    // the (former) Worker cache used to warm.
    touch("io.justsearch.app.engine.EngineRoot");
    touch("io.justsearch.indexerworker.server.KnowledgeServer");
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
