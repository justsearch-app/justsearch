/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;
/** A live parser child with a content-independent broken shared protocol configuration. */
public final class VerifierProtocolChild {
 public static void main(String[] args) throws Exception {
  Path root=Path.of(args[0]);
  var mapper=JsonMapper.builder().build();
  try(var parser=new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool)) {
   byte[] frame;
   while((frame=SandboxFrames.read(System.in,SandboxFrames.MAX_FRAME_BYTES))!=null) {
    var request=mapper.readValue(frame,SandboxExtractionRequest.class);
    Path source=Path.of(request.path());
    Files.writeString(root.resolve("protocol-events.tsv"),ProcessHandle.current().pid()+"\t"+source+"\t"+Files.exists(root.resolve("broken-protocol"))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    if(Files.exists(root.resolve("broken-protocol"))) {
     // A successfully spawned child emits a malformed frame because its shared protocol
     // configuration is broken. This is neither an unavailable-type signal nor source IO.
     SandboxFrames.write(System.out,"malformed JSON protocol configuration".getBytes(StandardCharsets.UTF_8));
     return;
    }
    SandboxFrames.write(System.out,mapper.writeValueAsBytes(SandboxExtractionResponse.fromArtifact(request.requestId(),parser.extractArtifact(source))));
   }
  }
 }
}
