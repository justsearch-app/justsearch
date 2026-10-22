/* SPDX-License-Identifier: Apache-2.0 */
// Run ONLY with the v0.3.0 worker distribution classpath (see the fixture README).
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.ingest.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
class SeedRelease030Queue {
 public static void main(String[] args) throws Exception {
  if(args.length==1) {
   var channel=io.grpc.ManagedChannelBuilder.forAddress("127.0.0.1",Integer.parseInt(args[0])).usePlaintext().build();
   try {
    var reply=io.justsearch.ipc.IngestServiceGrpc.newBlockingStub(channel)
      .withDeadlineAfter(10,java.util.concurrent.TimeUnit.SECONDS)
      .deleteById(io.justsearch.ipc.DeleteByIdRequest.newBuilder()
        .setDocId("/fixture/roots/beta/file-01.txt").build());
    if(!reply.getSuccess())throw new AssertionError(reply.getError());
    System.out.println("acknowledged DELETE during SWITCHING");
   } finally {channel.shutdownNow();}
   return;
  }
  Path base=Path.of("/fixture");
  Path processing=base.resolve("roots/beta/processing.txt"), pending=base.resolve("roots/alpha/pending.txt"), write=base.resolve("roots/alpha/file-00.txt"), exhausted=base.resolve("orphan/exhausted.txt"), failed=base.resolve("orphan/failed.bin"), explicit=base.resolve("explicit.txt"), parser=base.resolve("roots/alpha/parser.txt"), io=base.resolve("roots/beta/io.txt");
  for(Path p:List.of(processing,pending,write,exhausted,failed,explicit,parser,io))Files.writeString(p,"New content after the original index commit: copper lighthouse "+p.getFileName());
  try(var q=new SqliteJobQueue(base.resolve("data/jobs.db"))) {
   q.open();
   q.enqueue(List.of(processing),"default");
   if(q.pollPending(1).size()!=1)throw new AssertionError("PROCESSING claim");
   q.enqueue(List.of(pending,write,exhausted,failed,parser,io),"default");
   q.enqueue(List.of(explicit),"mcp-ingest");
   var writeFailure=IngestionOutcome.of(IngestionOutcomeClass.WRITE_FAILED,"WRITE_FAILED",IngestionRetryPolicy.RETRY_WITH_BACKOFF,"fixture writer failure");
   for(int i=0;i<4;i++)q.markFailed(write,writeFailure);
   q.markFailed(exhausted,writeFailure);
   // Move only the retry-run origin seven days back, then let the release transition exhaust it.
   try(var c=DriverManager.getConnection("jdbc:sqlite:"+base.resolve("data/jobs.db"));var s=c.prepareStatement("UPDATE jobs SET first_failed_at=? WHERE path=?")) {
    s.setLong(1,System.currentTimeMillis()-IngestionRetryLadder.MAX_RETRY_WINDOW_MS-1000);s.setString(2,exhausted.toString());s.executeUpdate();
   }
   q.markFailed(exhausted,writeFailure);
   q.markFailed(failed,IngestionOutcome.of(IngestionOutcomeClass.UNSUPPORTED,"UNSUPPORTED_FORMAT",IngestionRetryPolicy.NONE));
   q.markFailed(parser,IngestionOutcome.of(IngestionOutcomeClass.PARSER_TIMEOUT,"PARSER_TIMEOUT",IngestionRetryPolicy.RETRY_WITH_BACKOFF));
   q.markFailed(io,IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED,"IO_FAILED",IngestionRetryPolicy.RETRY_WITH_BACKOFF));
   System.out.println("release queue seeded: "+q.jobStateCounts());
  }
 }
}
