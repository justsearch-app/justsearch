package io.justsearch.ui.api;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class VerifierStatus5Test extends VerifierStatusIsolationTest {
  @Test void realEncryptionOwnerPublishesItsCanonicalStateChange(@org.junit.jupiter.api.io.TempDir java.nio.file.Path data) throws Exception {
    try(var f=new Fixture(true)) {
      var keys=new io.justsearch.app.services.encryption.DataKeyManager(new io.justsearch.app.services.encryption.EncryptionKeystore(data));
      keys.setup("verifier-only-fixture-passphrase".toCharArray());
      f.handler.setConversationProtectionSupplier(()->keys.state().name().toLowerCase(java.util.Locale.ROOT));
      f.handler.sampleAndBuildStatusSnapshot();
      try {var headers=Class.forName("io.justsearch.ui.api.StatusChangeHeaders");var install=headers.getDeclaredMethod("install",io.javalin.Javalin.class);install.setAccessible(true);install.invoke(null,f.app);}
      catch(ClassNotFoundException baseline){}
      var controller=new ConversationEncryptionController(keys);
      f.app.post("/api/conversations/encryption/lock",controller::handleLock);
      f.app.get("/api/own-readonly",ctx->ctx.json(java.util.Map.of("ok",true)));
      var base="http://127.0.0.1:"+f.app.port();
      var before=client.send(HttpRequest.newBuilder(URI.create(base+"/api/own-readonly")).GET().build(),HttpResponse.BodyHandlers.ofString());
      assertEquals("unlocked",get(f,"?fresh=true").path("conversationProtection").path("state").asText());
      var action=client.send(HttpRequest.newBuilder(URI.create(base+"/api/conversations/encryption/lock")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
      assertEquals(200,action.statusCode());assertEquals("locked",json.readTree(action.body()).path("state").asText());
      var read=client.send(HttpRequest.newBuilder(URI.create(base+"/api/own-readonly")).GET().build(),HttpResponse.BodyHandlers.ofString());
      String oldCounter=before.headers().firstValue("X-JustSearch-Status-Change").orElse("0");
      String newCounter=read.headers().firstValue("X-JustSearch-Status-Change").orElse("0");
      var canonical=get(f,"?fresh=true");assertEquals("locked",canonical.path("conversationProtection").path("state").asText());
      System.out.println("OWN-ENCRYPTION-OWNER before="+oldCounter+" after="+newCounter+" action="+action.body()+" canonical="+canonical);
      assertTrue(Long.parseLong(newCounter)>Long.parseLong(oldCounter),"the real owner changed canonical status but the next read-only response carries no change signal");
    }
  }
  @Test void samplerFailureAndRecoveryPublishCanonicalFreshnessChanges() throws Exception {
    try(var f=new Fixture(true)) {
      try {var headers=Class.forName("io.justsearch.ui.api.StatusChangeHeaders");var install=headers.getDeclaredMethod("install",io.javalin.Javalin.class);install.setAccessible(true);install.invoke(null,f.app);}
      catch(ClassNotFoundException baseline){}
      f.app.get("/api/own-readonly",ctx->ctx.json(java.util.Map.of("ok",true)));
      var uri=URI.create("http://127.0.0.1:"+f.app.port()+"/api/own-readonly");
      var before=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());
      assertFalse(get(f,"").path("meta").path("workerRpcStale").asBoolean());
      f.block.set(true);get(f,"?fresh=true");assertTrue(f.entered.await(2,TimeUnit.SECONDS));Thread.sleep(1200);var stale=get(f,"");
      assertTrue(stale.path("meta").path("workerRpcStale").asBoolean());
      var after=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());
      long oldCounter=Long.parseLong(before.headers().firstValue("X-JustSearch-Status-Change").orElse("0"));
      long staleCounter=Long.parseLong(after.headers().firstValue("X-JustSearch-Status-Change").orElse("0"));
      f.block.set(false);f.release.countDown();f.handler.sampleAndBuildStatusSnapshot();
      var recovered=get(f,"?fresh=true");assertFalse(recovered.path("meta").path("workerRpcStale").asBoolean());
      var last=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());
      long recoveredCounter=Long.parseLong(last.headers().firstValue("X-JustSearch-Status-Change").orElse("0"));
      System.out.println("OWN-SAMPLER-PUBLICATION healthy="+oldCounter+" stale="+staleCounter+" recovered="+recoveredCounter+" staleBody="+stale+" recoveredBody="+recovered);
      assertAll(()->assertTrue(staleCounter>oldCounter,"canonical freshness changed to stale but a read-only response carries no publication"),
        ()->assertTrue(recoveredCounter>staleCounter,"canonical freshness recovered but a read-only response carries no publication"));
    }
  }
  @Test void unknownUnrelatedOwnerDoesNotHideHealthyStateOrOverclaimReflectedRevision() throws Exception {
    try(var f=new Fixture(true)) {
      var current=new AtomicReference<>("locked");
      f.handler.setConversationProtectionSupplier(current::get);
      f.handler.sampleAndBuildStatusSnapshot();
      f.block.set(true);
      get(f,"?fresh=true");assertTrue(f.entered.await(2,TimeUnit.SECONDS));
      current.set("unlocked");
      Class<?> type;
      try {type=Class.forName("io.justsearch.core.status.StatusChangeCounter");}
      catch(ClassNotFoundException baseline){fail("server publication metadata is absent on baseline");return;}
      var counter=type.getMethod("process").invoke(null);type.getMethod("changed").invoke(counter);
      long published=(Long)type.getMethod("current").invoke(counter);
      var r=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+f.app.port()+"/api/status?fresh=true")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
      assertEquals(200,r.statusCode());
      var body=json.readTree(r.body());
      assertEquals("unlocked",body.path("conversationProtection").path("state").asText());
      assertTrue(body.path("meta").path("workerRpcStale").asBoolean());
      long reflected=Long.parseLong(r.headers().firstValue("X-JustSearch-Status-Reflected").orElseThrow());
      System.out.println("OWN-PARTIAL-PUBLICATION published="+published+" reflected="+reflected+" body="+r.body());
      assertTrue(reflected<published,"an explicitly stale owner must retain its older reflected revision while an independent healthy state is served correctly");
    }
  }
}



