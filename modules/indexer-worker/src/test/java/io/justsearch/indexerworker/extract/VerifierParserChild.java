/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;
public final class VerifierParserChild {
 public static void main(String[] args) throws Exception {
  var mapper=JsonMapper.builder().build(); byte[] frame;
  while((frame=SandboxFrames.read(System.in,SandboxFrames.MAX_FRAME_BYTES))!=null) {
   var request=mapper.readValue(frame,SandboxExtractionRequest.class);
   Path file=Path.of(request.path());
   Files.writeString(file.getParent().resolve("parser-events.tsv"),ProcessHandle.current().pid()+"\t"+file.getFileName()+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
   String content=Files.readString(file);
   if(content.equals("CRASH")) Runtime.getRuntime().halt(23);
   if(content.equals("HANG")) Thread.sleep(600000);
   SandboxExtractionResponse response;
   if(content.equals("REJECT")) response=SandboxExtractionResponse.failed(request.requestId(),ExtractionStatus.FAILED,request.policy(),"verifier-child","document rejected","PARSER_FAILED");
   else response=SandboxExtractionResponse.fromArtifact(request.requestId(),ExtractionArtifact.full(
       new ContentExtractor.ExtractionResult(content,null,"text/plain"),request.policy(),"verifier-child",false));
   SandboxFrames.write(System.out,mapper.writeValueAsBytes(response));
  }
 }
}
