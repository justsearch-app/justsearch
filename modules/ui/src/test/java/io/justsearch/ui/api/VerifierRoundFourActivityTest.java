package io.justsearch.ui.api;
import static org.junit.jupiter.api.Assertions.*;
import io.javalin.Javalin;
import io.javalin.security.RouteRole;
import io.justsearch.app.engine.EngineAdmissionController;
import java.net.*;import java.net.http.*;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.nio.file.*;import java.util.regex.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;import org.junit.jupiter.params.provider.ValueSource;
/** Intent written before supplied checks were read. Mounts the production operation declaration
 * on real HTTP admission. Controllers are deliberately replaced, so controller validity is separate. */
public class VerifierRoundFourActivityTest {
 static long version(EngineAdmissionController a) {return a.userActivity().version();}
 static RouteRole[] declared(String method,String path) throws Exception {
   try {Class<?> role=Class.forName("io.justsearch.ui.api.ActivityRole");
     Path base=Path.of(System.getProperty("user.dir"));while(!Files.isDirectory(base.resolve("modules")))base=base.getParent();
     Pattern p=Pattern.compile("app\\."+method+"\\(\\s*\""+Pattern.quote(path)+"\"(?s:.*?)ActivityRole\\.(USER_ACTION|OBSERVATION|PROTOCOL)\\)");
     try(var files=Files.walk(base.resolve("modules/ui/src/main/java/io/justsearch/ui/api"))) {
       for(Path f:files.filter(f->f.toString().endsWith(".java")).toList()) {var m=p.matcher(Files.readString(f));if(m.find())return new RouteRole[]{(RouteRole)Enum.valueOf((Class)role,m.group(1))};}
     }
     throw new AssertionError("No literal production declaration found: "+method+" "+path);
   }catch(ClassNotFoundException old){return new RouteRole[0];}
 }
 void http(String method,String route,boolean activity) throws Exception { http(method,route,activity,"{}"); }
 void http(String method,String route,boolean activity,String payload) throws Exception {
   var a=new EngineAdmissionController(20,100,1);var ex=Executors.newSingleThreadExecutor();
   var app=Javalin.create(c->{c.showJavalinBanner=false;c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());});
   new ApiSecurityFilters(false,null,new EventBuffer(),ex,null,a,a).install(app);
   var roles=declared(method,route);
   if(method.equals("get"))app.get(route,c->c.json(Map.of("ok",true)),roles);else app.post(route,c->c.json(Map.of("ok",true)),roles);
   app.start("127.0.0.1",0);
   try(var client=HttpClient.newHttpClient()) {
     for(boolean hints:new boolean[]{false,true}) {
       long b=version(a);var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.port()+route.replace("{id}","core.trigger-offline-processing").replace("{batchId}","example"))).timeout(Duration.ofSeconds(10));
       if(hints)req.header("X-JustSearch-Client-Kind","INTERNAL").header("X-JustSearch-Transport","SYSTEM_INTERNAL");
       if(method.equals("get"))req.GET();else req.POST(HttpRequest.BodyPublishers.ofString(payload));
       var res=client.send(req.build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,res.statusCode(),res.body());
       assertEquals(activity,b!=version(a),method+" "+route+" hints="+hints+" must follow user intent, production role="+Arrays.toString(roles));
     }
   }finally{app.stop();ex.shutdownNow();}
 }
 @ParameterizedTest @ValueSource(strings={"/api/inference/transitions","/api/conversations/encryption","/api/chat/agent/tools","/api/indexing/failed-jobs","/api/runtime/manifest"})
 void unattendedReadDoesNotAdvanceCapturedBoundary(String p)throws Exception{http("get",p,false);}
 @ParameterizedTest @ValueSource(strings={"/api/knowledge/suggest","/api/preview","/api/operations/{id}/preview","/api/chat/sessions/last","/api/chat/sessions/{id}/transcript","/api/chat/agent/history","/api/chat/agent/history/{batchId}","/api/meta/openapi.json","/api/registry/prompts","/api/debug/events","/api/debug/engine-log","/api/debug/chunks","/api/indexing/suggested-roots"})
 void personRequestedReadAdvancesBoundary(String p)throws Exception{http("get",p,true);}
 @ParameterizedTest @ValueSource(strings={"/api/knowledge/folders","/api/knowledge/folder-files","/api/conversations/encryption/unlock","/api/conversations/encryption/lock","/api/ui/activity"})
 void personRequestedActionAdvancesBoundary(String p)throws Exception{http("post",p,true);}
 @Test void automaticLockIsNotExplicitLock()throws Exception{http("post","/api/conversations/encryption/auto-lock",false);}
 @Test void undeclaredAndAmbiguousRoutesCannotShip()throws Exception{
   Class<?> role;try{role=Class.forName("io.justsearch.ui.api.ActivityRole");}catch(ClassNotFoundException old){fail("No declared activity wiring guard on baseline");return;}
   var validate=role.getDeclaredMethod("validate",Javalin.class);validate.setAccessible(true);
   for(boolean ambiguous:new boolean[]{false,true}){
     var app=Javalin.create();RouteRole[] roles=ambiguous?new RouteRole[]{(RouteRole)Enum.valueOf((Class)role,"USER_ACTION"),(RouteRole)Enum.valueOf((Class)role,"OBSERVATION")}:new RouteRole[0];
     app.get("/future-route",c->{},roles);
     var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->validate.invoke(null,app));
     assertTrue(failure.getCause() instanceof IllegalStateException);assertTrue(failure.getCause().getMessage().contains("/future-route"));
   }
 }
 @Test void deniedAdmissionDoesNotReportUserInput()throws Exception{
   var a=new EngineAdmissionController(1,1,1);var ex=Executors.newSingleThreadExecutor();var app=Javalin.create();
   new ApiSecurityFilters(false,null,new EventBuffer(),ex,null,a,a).install(app);app.post("/api/ui/activity",c->c.result("ok"),declared("post","/api/ui/activity"));app.start("127.0.0.1",0);
   var ctx=io.justsearch.app.services.intent.EngineProvenance.internal("independent-capacity",io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,io.justsearch.core.context.EngineContext.Urgency.FOREGROUND);
   try(var held=a.admit(ctx,false);var client=HttpClient.newHttpClient()){
     assertNotNull(held, "the capacity holder must be admitted before testing refusal");
     long b=version(a);var res=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.port()+"/api/ui/activity")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
     assertEquals(429,res.statusCode());assertEquals(b,version(a));
   }finally{app.stop();ex.shutdownNow();}
 }
}
