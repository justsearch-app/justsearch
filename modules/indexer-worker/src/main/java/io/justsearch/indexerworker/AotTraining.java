/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker;

/**
 * AOT Cache training entry point for the Worker (Knowledge Server) process.
 *
 * <p>Loads representative classes from the Worker classpath to populate the JDK 25 AOT Cache
 * (JEP 514). This class is used only at build time — it is NOT the production entry point.
 *
 * <p>The real {@link IndexerWorker#main} blocks on the MMF signal bus waiting for the Head
 * process, making it unsuitable for training runs that require a clean exit. This class loads
 * the same libraries without blocking. JEP 483 explicitly supports using a different main
 * class for training vs production.
 *
 * <p>Usage (Gradle): {@code java -XX:AOTMode=record -XX:AOTConfiguration=worker.aotconf -cp ... io.justsearch.indexerworker.AotTraining}
 *
 * @see <a href="https://openjdk.org/jeps/514">JEP 514: Ahead-of-Time Command-Line Ergonomics</a>
 */
public final class AotTraining {

  private AotTraining() {}

  public static void main(String[] args) {
    // Touch representative classes from each major dependency to ensure they are loaded
    // and recorded in the AOT configuration.

    // --- SLF4J + Logback (load without initializing to avoid logback config I/O) ---
    touch("org.slf4j.LoggerFactory");
    touch("ch.qos.logback.classic.Logger");
    touch("ch.qos.logback.classic.LoggerContext");

    // --- Lucene (dominates Worker classloading) ---
    touch("org.apache.lucene.index.IndexWriter");
    touch("org.apache.lucene.index.DirectoryReader");
    touch("org.apache.lucene.search.IndexSearcher");
    touch("org.apache.lucene.search.KnnFloatVectorQuery");
    touch("org.apache.lucene.store.MMapDirectory");
    touch("org.apache.lucene.analysis.standard.StandardAnalyzer");
    touch("org.apache.lucene.codecs.lucene100.Lucene100Codec");
    touch("org.apache.lucene.util.hnsw.HnswGraphSearcher");

    // Lane F stage A item A9: the three gRPC touches (ServerBuilder, NettyServerBuilder,
    // StreamObserver) are gone with the server they warmed. Warming a class the process no longer
    // loads costs AOT-cache space and buys nothing. Item A13 folds what is left of this list into
    // the Head's AotTraining, which is the one cache the Engine has.

    // --- Protobuf ---
    touch("com.google.protobuf.GeneratedMessage");

    // --- Tika (content extraction) ---
    touch("org.apache.tika.Tika");
    touch("org.apache.tika.parser.AutoDetectParser");
    touch("org.apache.tika.metadata.Metadata");

    // --- SQLite ---
    touch("org.sqlite.JDBC");
    touch("org.sqlite.SQLiteConnection");

    // --- ONNX Runtime (SPLADE, reranker) ---
    touch("ai.onnxruntime.OrtEnvironment");
    touch("ai.onnxruntime.OrtSession");

    // --- OpenTelemetry ---
    touch("io.opentelemetry.api.GlobalOpenTelemetry");
    touch("io.opentelemetry.sdk.trace.SdkTracerProvider");

    // --- JustSearch core classes ---
    touch("io.justsearch.indexerworker.IndexerWorker");
    touch("io.justsearch.indexerworker.server.KnowledgeServer");
    touch("io.justsearch.configuration.resolved.ConfigStore");
    touch("io.justsearch.telemetry.LocalTelemetry");

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
