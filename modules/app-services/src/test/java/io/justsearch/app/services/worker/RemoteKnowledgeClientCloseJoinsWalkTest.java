/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 932 item 7 — {@link RemoteKnowledgeClient#close()} must not return while its walk thread
 * is still unwinding a ScanRoot RPC and persisting watched-root state.
 *
 * <p>Before the fix, {@code close()} called {@code walkExecutor.shutdownNow()} and returned at once.
 * The walk thread was still writing {@code watched_roots.json} under the data dir, and on Windows
 * that open handle made JUnit's {@code @TempDir} cleanup fail in
 * {@link WatchedRootScanCollectionTest.ProductionWireForwarding} under full-suite load (the
 * {@code app-services-watched-root-scan-collection-flaky} pin). Here the Worker-side handler holds
 * the RPC open, so the walk thread is parked inside the RPC when {@code close()} is called; the
 * interrupt makes it unwind through {@code walkAndSubmit}'s failure path (log + persist), and a
 * {@code close()} that does not join sees that unwinding still in progress.
 */
@DisplayName("RemoteKnowledgeClient.close() joins the walk thread")
final class RemoteKnowledgeClientCloseJoinsWalkTest {

  @TempDir Path tempDir;

  private Server server;
  private ManagedChannel channel;
  private HoldingIngestService ingest;
  private RemoteKnowledgeClient client;
  private String prevDataDir;

  @BeforeEach
  void setUp() throws Exception {
    prevDataDir = System.getProperty("justsearch.data.dir");
    Path dataDir = Files.createDirectories(tempDir.resolve("data"));
    System.setProperty("justsearch.data.dir", dataDir.toString());

    String name = InProcessServerBuilder.generateName();
    ingest = new HoldingIngestService();
    server =
        InProcessServerBuilder.forName(name).directExecutor().addService(ingest).build().start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();

    MainSignalBus signalBus = new MainSignalBus(tempDir.resolve("data/signals/worker-signal.mmf"));
    client = new RemoteKnowledgeClient(signalBus, /* deadlineMs= */ 5000, /* maxRetries= */ 0);
    client.connectForTesting(channel);
  }

  @AfterEach
  void tearDown() throws Exception {
    ingest.release.countDown();
    if (client != null) {
      client.close();
    }
    if (channel != null) {
      channel.shutdownNow();
      channel.awaitTermination(2, TimeUnit.SECONDS);
    }
    if (server != null) {
      server.shutdownNow();
      server.awaitTermination(2, TimeUnit.SECONDS);
    }
    if (prevDataDir == null) {
      System.clearProperty("justsearch.data.dir");
    } else {
      System.setProperty("justsearch.data.dir", prevDataDir);
    }
  }

  @Test
  @DisplayName("close() returns only after the in-flight walk has left the walk thread")
  void closeJoinsAnInFlightWalk() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("held"));

    client.addWatchedRoot("my-notes", root);
    assertTrue(
        ingest.entered.await(10, TimeUnit.SECONDS), "the background walk must reach the RPC");

    // The walk thread is the one parked inside the client's ScanRoot iterator. Identify it by its
    // stack, not by name alone: an idle walk-bg worker leaked by an unrelated test is not it.
    Thread walkThread = awaitWalkThreadInsideScan();

    client.close();

    // Without the join, close() returns while walk-bg is still unwinding the cancelled RPC
    // (markWalkFailed + persist to the data dir) — the write that races @TempDir cleanup. The
    // executor reaches TERMINATED only after that unwind has run to completion.
    assertTrue(
        client.isWalkExecutorTerminated(),
        "close() returned while the walk executor was still running — its data-dir write is what"
            + " made @TempDir cleanup fail on Windows");
    // TERMINATED is flagged by the worker just before its run() returns; give the OS thread that
    // last step, then it must be gone.
    walkThread.join(TimeUnit.SECONDS.toMillis(2));
    assertFalse(walkThread.isAlive(), "the walk thread must be dead once close() has joined it");
  }

  private static Thread awaitWalkThreadInsideScan() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      for (Thread t : Thread.getAllStackTraces().keySet()) {
        if (!"walk-bg".equals(t.getName())) {
          continue;
        }
        List<StackTraceElement> frames = Arrays.asList(t.getStackTrace());
        boolean insideScan =
            frames.stream().anyMatch(f -> "drainScanIterator".equals(f.getMethodName()));
        if (insideScan) {
          return t;
        }
      }
      Thread.sleep(5);
    }
    throw new AssertionError("no walk-bg thread reached drainScanIterator within 10 s");
  }

  /** Holds the ScanRoot RPC open (never completes it) until the test releases it at teardown. */
  private static final class HoldingIngestService extends IngestServiceGrpc.IngestServiceImplBase {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void scanRoot(ScanRootRequest req, StreamObserver<ScanRootProgress> responseObserver) {
      entered.countDown();
      try {
        release.await(15, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      try {
        responseObserver.onNext(ScanRootProgress.newBuilder().setComplete(true).build());
        responseObserver.onCompleted();
      } catch (RuntimeException ignored) {
        // The client cancelled the call at close(); completing a cancelled call is a no-op here.
      }
    }
  }
}
