package io.justsearch.ui.api;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
class VerifierStatus3Test extends VerifierStatusIsolationTest {
  static long statusThreads(){return Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive() && t.getName().startsWith("status-")).count();}
  @Test void persistentHungWorkerDoesNotGrowProducerThreadsAndClearsAfterRelease() throws Exception {
    try(var f=new Fixture(true)){
      f.block.set(true);get(f,"?fresh=true");assertTrue(f.entered.await(2,TimeUnit.SECONDS));
      StatusLaneAging.ageHeldLanes(f.handler);
      long high=0;int calls=f.calls.get();
      try{
        for(int i=0;i<50;i++){
          var s=get(f,"?fresh=true");high=Math.max(high,statusThreads());
          assertFalse(s.path("readiness").path("components").path("ai").path("stale").asBoolean());
          assertTrue(s.path("meta").path("workerRpcStale").asBoolean());
          assertEquals(calls,f.calls.get(),"hung worker has only one producer invocation");
          assertTrue(statusThreads()<40,"sampler threads must stay bounded");
        }
      }finally{f.block.set(false);f.release.countDown();}
      f.handler.sampleAndBuildStatusSnapshot();
      var recovered=get(f,"?fresh=true");assertFalse(recovered.path("meta").path("workerRpcStale").asBoolean());
      Thread.sleep(1200);assertTrue(statusThreads()<10,"finished producer threads must clear");
      System.out.println("OWN-LONG-HANG maxStatusThreads="+high+" recovered=true");
    }
  }
}
