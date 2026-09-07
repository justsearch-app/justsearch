package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 418 Phase B — gRPC streaming contract for ScanRoot.
 *
 * <p>Verifies that a {@link io.justsearch.ipc.IngestServiceGrpc.IngestServiceBlockingStub}
 * iterates the server-streaming response correctly: the iterator surfaces every
 * {@link ScanRootProgress} in order, terminates cleanly on {@code complete=true}, and the
 * client's "iterate-until-complete-then-return-last" pattern (used by
 * {@link KnowledgeClient#scanRoot}) yields the terminal event.
 *
 * <p>This is the streaming companion to {@code GrpcVduOperationsTest}. Phase B's
 * {@code KnowledgeClient.scanRoot} wraps the same iterator with a
 * {@link java.util.function.Consumer} forward — the integration test in B4 covers the
 * Head→Worker round-trip end-to-end; this test pins the wire contract.
 */
@DisplayName("gRPC ScanRoot streaming contract")
final class ScanRootStreamingContractTest {

  private String serverName;
  private Server server;
  private ManagedChannel channel;
  private IngestServiceGrpc.IngestServiceBlockingStub stub;
  private StubIngestService stubService;

  @BeforeEach
  void setUp() throws IOException {
    serverName = InProcessServerBuilder.generateName();
    stubService = new StubIngestService();
    server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(stubService)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    stub = IngestServiceGrpc.newBlockingStub(channel);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    if (channel != null) {
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
    }
    if (server != null) {
      server.shutdownNow();
      server.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("iterator surfaces every progress event and terminates on complete=true")
  void iteratesAllProgressEventsAndTerminates() {
    stubService.emit(progress(50, 50, 0, "/dir-a", false, ""));
    stubService.emit(progress(120, 100, 20, "/dir-b", false, ""));
    stubService.emit(progress(150, 130, 20, "/dir-b", true, ""));

    var iterator = stub.scanRoot(ScanRootRequest.newBuilder().setRootPath("/x").build());
    List<ScanRootProgress> received = new ArrayList<>();
    while (iterator.hasNext()) {
      received.add(iterator.next());
    }

    assertEquals(3, received.size());
    assertEquals(150, received.get(2).getFilesWalked());
    assertEquals(130, received.get(2).getFilesAdmitted());
    assertTrue(received.get(2).getComplete());
    assertEquals("", received.get(2).getTerminalReasonCode());
  }

  @Test
  @DisplayName("client iterate-until-complete pattern returns the terminal event")
  void clientIterationPatternReturnsTerminalEvent() {
    stubService.emit(progress(10, 10, 0, "/dir", false, ""));
    stubService.emit(progress(10, 10, 0, "/dir", true, "ROOT_NOT_DIRECTORY"));

    var iterator = stub.scanRoot(ScanRootRequest.newBuilder().setRootPath("/x").build());
    ScanRootProgress last = null;
    while (iterator.hasNext()) {
      ScanRootProgress event = iterator.next();
      last = event;
      if (event.getComplete()) break;
    }

    assertEquals("ROOT_NOT_DIRECTORY", last.getTerminalReasonCode());
    assertTrue(last.getComplete());
  }

  @Test
  @DisplayName("empty stream completes without emitting events")
  void emptyStreamCompletesCleanly() {
    // No emit() — the stub completes immediately.
    var iterator = stub.scanRoot(ScanRootRequest.newBuilder().setRootPath("/x").build());
    assertEquals(false, iterator.hasNext(), "Empty stream must terminate without emitting");
  }

  private static ScanRootProgress progress(
      long walked,
      long admitted,
      long skipped,
      String currentDirectory,
      boolean complete,
      String terminalReasonCode) {
    return ScanRootProgress.newBuilder()
        .setFilesWalked(walked)
        .setFilesAdmitted(admitted)
        .setFilesSkipped(skipped)
        .setBytesWalked(0L)
        .setCurrentDirectory(currentDirectory)
        .setComplete(complete)
        .setTerminalReasonCode(terminalReasonCode)
        .build();
  }

  /** In-process gRPC service that streams a queued sequence of ScanRootProgress events. */
  private static final class StubIngestService extends IngestServiceGrpc.IngestServiceImplBase {
    private final List<ScanRootProgress> queued = new ArrayList<>();

    void emit(ScanRootProgress event) {
      queued.add(event);
    }

    @Override
    public void scanRoot(ScanRootRequest request, StreamObserver<ScanRootProgress> responseObserver) {
      for (ScanRootProgress event : queued) {
        responseObserver.onNext(event);
      }
      responseObserver.onCompleted();
    }
  }
}
