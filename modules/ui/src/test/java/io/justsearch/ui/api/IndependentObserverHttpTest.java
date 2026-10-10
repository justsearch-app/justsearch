package io.justsearch.ui.api;
import static org.junit.jupiter.api.Assertions.*;
import io.javalin.Javalin;
import io.justsearch.app.engine.EngineAdmissionController;
import java.net.*;import java.net.http.*;import java.time.*;import java.util.*;import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;import org.junit.jupiter.params.provider.ValueSource;
/** Actual HTTP admission: unattended requests must leave the shared pass boundary untouched. */
public class IndependentObserverHttpTest {
 static long version(EngineAdmissionController admission) throws Exception {
  try {Object activity=admission.getClass().getMethod("userActivity").invoke(admission);return (long)activity.getClass().getMethod("version").invoke(activity);}
  catch(NoSuchMethodException legacy){return 0;}
 }
 @ParameterizedTest @ValueSource(strings={"/api/status","/api/inference/status","/api/ai/install/status","/api/inference/transitions","/api/conversations/encryption","/api/chat/agent/tools"})
 void automaticUiTimerDoesNotChangeCapturedActivity(String path) throws Exception {
  var admission=new EngineAdmissionController(20,100,1);var executor=Executors.newSingleThreadExecutor();
  var app=Javalin.create(c->{c.showJavalinBanner=false;c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());});
  new ApiSecurityFilters(false,null,new EventBuffer(),executor,null,admission,admission).install(app);
  app.get(path,c->c.json(Map.of("ok",true)));app.start("127.0.0.1",0);
  try(var http=HttpClient.newHttpClient()) {
   long before=version(admission);
   var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.port()+path)).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(200,response.statusCode());assertEquals(before,version(admission),path+" timer must leave a captured VDU pass and idle window undisturbed");
  } finally {app.stop();executor.shutdownNow();}
 }
 @Test void rejectedRequestDoesNotInventAcceptedActivity() throws Exception {
  var admission=new EngineAdmissionController(1,1,1);var executor=Executors.newSingleThreadExecutor();
  var context=io.justsearch.app.services.intent.EngineProvenance.internal("independent-capacity-holder",io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,io.justsearch.core.context.EngineContext.Urgency.FOREGROUND);
  var app=Javalin.create(c->{c.showJavalinBanner=false;c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());});
  new ApiSecurityFilters(false,null,new EventBuffer(),executor,null,admission,admission).install(app);
  var calls=new java.util.concurrent.atomic.AtomicInteger();app.post("/api/user-action",c->{calls.incrementAndGet();c.json(Map.of());});app.start("127.0.0.1",0);
  try(var held=admission.admit(context,false);var http=HttpClient.newHttpClient()) {
   long before=version(admission);
   var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.port()+"/api/user-action")).timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type","application/json").build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(429,response.statusCode());assertEquals(0,calls.get());assertEquals(before,version(admission));
  } finally {app.stop();executor.shutdownNow();}
 }
 @Test void backgroundInternalAttachDoesNotInventUserInput() throws Exception {
  var admission=new EngineAdmissionController(10,10,1);long before=version(admission);
  var context=io.justsearch.app.services.intent.EngineProvenance.internal("independent-background-owner",io.justsearch.core.context.EngineContext.Survival.DURABLE,io.justsearch.core.context.EngineContext.Urgency.BACKGROUND);
  try(var held=admission.attach(context)){assertEquals(before,version(admission));}
 }
}
