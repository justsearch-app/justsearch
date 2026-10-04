package io.justsearch.ipc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Descriptors;
import org.junit.jupiter.api.Test;

final class IndexingProtoDeprecationsTest {

  @Test
  void vduStatusFieldIsDeprecated() {
    Descriptors.FieldDescriptor field =
        UpdateVduResultRequest.getDescriptor().findFieldByName("vdu_status");
    assertNotNull(field, "Expected UpdateVduResultRequest.vdu_status to exist");
    assertTrue(field.getOptions().getDeprecated(), "Expected vdu_status to be marked deprecated");
  }

  /**
   * The successor to {@code pruneMissingRpcIsDeprecated}, which asserted that
   * {@code IngestService.PruneMissing} existed and carried {@code deprecated = true}.
   *
   * <p>Lane F stage A item A14 deleted all three {@code service} blocks from
   * {@code indexing.proto} — {@code SearchService}, {@code IngestService} and
   * {@code HealthService}, 49 rpc lines — because items A9 to A11 had already deleted the gRPC
   * server, the gRPC client and the Worker process they described. A deprecation marker on a
   * method that no longer exists has nothing to assert, so that test could only be deleted.
   *
   * <p>What replaces it is the stronger claim in the same place: the file declares NO services at
   * all. That is not a restatement of the deletion — it is the ratchet against undoing it. The
   * messages here are the DTOs at the Engine's in-process ports (design §6, transitional), and the
   * predictable move when someone wants a new call is to add a {@code service} block back, which
   * would silently re-create a transport the Engine has no server for. This fails first.
   */
  @Test
  void indexingProtoDeclaresNoServices() {
    Descriptors.FileDescriptor file = UpdateVduResultRequest.getDescriptor().getFile();
    assertTrue(
        file.getServices().isEmpty(),
        "indexing.proto must declare messages only — lane F stage A item A14 removed its three"
            + " service blocks with the wire they described, and protoc-gen-grpc-java is no longer"
            + " configured for this module, so a service declared here generates no stub and"
            + " serves no caller. Found: "
            + file.getServices().stream().map(Descriptors.ServiceDescriptor::getName).toList());
  }
}
