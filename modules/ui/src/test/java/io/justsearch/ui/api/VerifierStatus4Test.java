package io.justsearch.ui.api;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.mockito.Mockito.*;
class VerifierStatus4Test extends VerifierStatusIsolationTest {
 @ParameterizedTest @ValueSource(strings={"at-rest","agent","vision"})
 void additionalProducersCannotHoldRealStatusHttp(String owner) throws Exception {
  try(var f=new Fixture(true)){
   var block=new AtomicBoolean(false);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
   Runnable probe=()->{if(block.get()){entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}}};
   if(owner.equals("at-rest")){
    var producer=mock(io.justsearch.app.services.atrest.DiskEncryptionProbe.class);
    when(producer.current()).thenAnswer(a->{probe.run();return new io.justsearch.app.services.atrest.AtRestProtection(io.justsearch.app.services.atrest.AtRestProtection.State.ENCRYPTED,"verifier",io.justsearch.app.services.atrest.AtRestProtection.Confidence.HIGH);});
    VerifierStatus2Test.set(f.handler,"diskEncryptionProbe",producer);VerifierStatus2Test.set(f.handler,"cachedAtRestAtMs",0L);
   }else if(owner.equals("agent")){
    var producer=mock(io.justsearch.agent.api.AgentService.class);
    when(producer.isAvailable()).thenAnswer(a->{probe.run();return true;});when(producer.activeSessionCount()).thenReturn(3);
    VerifierStatus2Test.set(f.handler,"agentService",producer);
   }else{
    var producer=mock(io.justsearch.app.api.OnlineAiService.class,withSettings().extraInterfaces(io.justsearch.app.api.OnlineAiRuntimeIntrospection.class));
    when(((io.justsearch.app.api.OnlineAiRuntimeIntrospection)producer).hasVisionCapability()).thenAnswer(a->{probe.run();return true;});
    VerifierStatus2Test.set(f.handler,"onlineAi",producer);
   }
   f.handler.sampleAndBuildStatusSnapshot();block.set(true);
   if(owner.equals("at-rest"))VerifierStatus2Test.set(f.handler,"cachedAtRestAtMs",0L);
   var sampler=Thread.ofPlatform().daemon().start(f.handler::sampleAndBuildStatusSnapshot);
   try{
    assertTrue(entered.await(2,TimeUnit.SECONDS),"producer must really be reached");Thread.sleep(1200);
    var s=get(f,"");assertFalse(s.path("meta").path("workerRpcStale").asBoolean());
    assertFalse(s.path("readiness").path("components").path("ai").path("stale").asBoolean());
    assertFalse(s.path("readiness").path("components").path("telemetry").path("stale").asBoolean());
    if(owner.equals("at-rest"))assertEquals("UNKNOWN",s.path("atRestProtection").path("diskEncryption").asText());
   }finally{block.set(false);release.countDown();sampler.join(5000);assertFalse(sampler.isAlive());}
   f.handler.sampleAndBuildStatusSnapshot();var recovered=get(f,"");
   if(owner.equals("at-rest"))assertEquals("ENCRYPTED",recovered.path("atRestProtection").path("diskEncryption").asText());
   assertFalse(recovered.path("meta").path("workerRpcStale").asBoolean());
   System.out.println("OWN-ADDITIONAL-OWNER="+owner+" recovered=true");
  }
 }
 @Test void olderPendingFailureCannotOverrideQueuedHealthyConversation() throws Exception {
  try(var f=new Fixture(true)){
   f.handler.setConversationProtectionSupplier(()->"locked");f.handler.sampleAndBuildStatusSnapshot();
   var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var failed=new AtomicInteger();
   f.handler.setConversationProtectionSupplier(()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}failed.incrementAndGet();throw new IllegalStateException("old pending failure");});
   get(f,"?fresh=true");assertTrue(entered.await(2,TimeUnit.SECONDS));
   assertEquals("unknown",get(f,"").path("conversationProtection").path("state").asText());
   f.handler.setConversationProtectionSupplier(()->"unlocked");get(f,"?fresh=true");
   release.countDown();
   boolean healthy=false;
   for(int i=0;i<50&&!healthy;i++){Thread.sleep(20);healthy="unlocked".equals(get(f,"").path("conversationProtection").path("state").asText());}
   assertTrue(healthy,"queued current producer must supersede old failed attempt");assertEquals(1,failed.get());
   for(int i=0;i<5;i++){Thread.sleep(30);assertEquals("unlocked",get(f,"").path("conversationProtection").path("state").asText());}
  }
 }
 @Test void eachObservationRetainsItsOwnCadenceAcrossBusyPeriodChange() throws Exception {
  var now=new AtomicLong(100000);var idle=new StatusObservation<String>();var busy=new StatusObservation<String>();
  idle.refresh("own-idle",()->"idle",now::get,10000,null,false).get(2,TimeUnit.SECONDS);
  busy.refresh("own-busy",()->"busy",now::get,2000,null,false).get(2,TimeUnit.SECONDS);
  now.addAndGet(7100);assertFalse(idle.stale(now.get()));assertTrue(busy.stale(now.get()));
  busy.refresh("own-busy",()->"busy recovered",now::get,10000,null,false).get(2,TimeUnit.SECONDS);
  assertFalse(busy.stale(now.get()));assertFalse(idle.stale(now.get()));
  now.addAndGet(23100);assertTrue(idle.stale(now.get()));assertFalse(busy.stale(now.get()));
  idle.refresh("own-idle",()->"now busy",now::get,2000,null,false).get(2,TimeUnit.SECONDS);
  now.addAndGet(7100);assertTrue(idle.stale(now.get()));assertTrue(busy.stale(now.get()));
 }
}

