package io.justsearch.indexerworker.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.FetchDocumentSliceRequest;
import io.justsearch.ipc.FetchDocumentSliceResponse;
import io.justsearch.ipc.SearchServiceGrpc;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wire-level regression coverage for UTF-16 paging through {@code FetchDocumentSlice}.
 *
 * <p>Lane F stage A item A3 moved this class from {@code modules/worker-services}: the service no
 * longer extends the generated {@code ImplBase}, so the wire it is asserting about is now reached
 * through {@link DelegatingSearchService}, which lives here. The assertions are unchanged — this
 * is still the end-to-end serialized round trip, including the INVALID_ARGUMENT status the adapter
 * maps out of {@code WorkerServiceException}.
 */
@DisplayName("WorkerSearchService FetchDocumentSlice wire contract")
final class WorkerSearchServiceDocumentSliceWireTest {

  private static final String DOC_ID = "doc-unicode-wire";
  private static final String CONTENT = "A\uD83D\uDE00B\uD834\uDD1EC";

  @TempDir Path tempDir;

  private RunningRuntime lifecycle;
  private Server server;
  private ManagedChannel channel;
  private SearchServiceGrpc.SearchServiceBlockingStub client;

  @BeforeEach
  void setUp() throws Exception {
    System.clearProperty("justsearch.config");
    lifecycle = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).open();
    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, DOC_ID,
                SchemaFields.DOC_UID, DOC_ID + "#0",
                SchemaFields.PATH, "C:/docs/unicode-wire.txt",
                SchemaFields.CONTENT, CONTENT)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    server =
        NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .addService(new DelegatingSearchService(new WorkerSearchService(lifecycle)))
            .build()
            .start();
    channel =
        NettyChannelBuilder.forAddress(InetAddress.getLoopbackAddress().getHostAddress(), server.getPort())
            .usePlaintext()
            .build();
    client = SearchServiceGrpc.newBlockingStub(channel);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (channel != null) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
    if (server != null) {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  @Test
  @DisplayName("an emoji straddling max_chars round-trips intact across serialized pages")
  void roundTripsSurrogatePairAcrossSerializedPages() {
    FetchDocumentSliceResponse first = fetch(/* offsetChars= */ 0, /* maxChars= */ 2);
    FetchDocumentSliceResponse second =
        fetch(first.getNextOffsetChars(), /* maxChars= */ 2);
    FetchDocumentSliceResponse third =
        fetch(second.getNextOffsetChars(), /* maxChars= */ 2);

    assertEquals("A\uD83D\uDE00", first.getContent());
    assertEquals(3, first.getNextOffsetChars());
    assertTrue(first.getTruncated());
    assertEquals("B\uD834\uDD1E", second.getContent());
    assertEquals(6, second.getNextOffsetChars());
    assertTrue(second.getTruncated());
    assertEquals("C", third.getContent());
    assertEquals(CONTENT.length(), third.getNextOffsetChars());
    assertFalse(third.getTruncated());
    assertEquals(CONTENT, first.getContent() + second.getContent() + third.getContent());
  }

  @Test
  @DisplayName("an offset inside a serialized surrogate pair is INVALID_ARGUMENT")
  void rejectsSerializedOffsetInsideSurrogatePair() {
    StatusRuntimeException failure =
        assertThrows(StatusRuntimeException.class, () -> fetch(/* offsetChars= */ 2, /* maxChars= */ 1));

    assertEquals(Status.Code.INVALID_ARGUMENT, failure.getStatus().getCode());
    assertEquals(
        "offset_chars splits a Unicode surrogate pair", failure.getStatus().getDescription());
  }

  private FetchDocumentSliceResponse fetch(int offsetChars, int maxChars) {
    return client.fetchDocumentSlice(
        FetchDocumentSliceRequest.newBuilder()
            .setDocId(DOC_ID)
            .setOffsetChars(offsetChars)
            .setMaxChars(maxChars)
            .build());
  }
}
