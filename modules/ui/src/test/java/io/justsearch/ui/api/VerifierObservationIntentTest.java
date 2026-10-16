package io.justsearch.ui.api;
import static org.junit.jupiter.api.Assertions.*;
import io.javalin.Javalin;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Independent startup contract: no subprocesses or cleanup. Reflection keeps old revision compilable. */
public class VerifierObservationIntentTest {
  Class<?> guard() throws Exception {
    try { return Class.forName("io.justsearch.ui.api.ObservationJustifications"); }
    catch (ClassNotFoundException missing) { fail("Startup observation justification guard is absent"); return null; }
  }
  void validate(Javalin app) throws Exception {
    var m=guard().getDeclaredMethod("validate",Javalin.class);m.setAccessible(true);m.invoke(null,app);
  }
  @Test void unknownObserverCannotShip() throws Exception {
    var app=Javalin.create();app.get("/future-unattended-poll",c->{},ActivityRole.OBSERVATION);
    var x=assertThrows(InvocationTargetException.class,()->validate(app));
    assertTrue(x.getCause() instanceof IllegalStateException);
  }
  @Test void actionCannotKeepObserverReason() throws Exception {
    var app=Javalin.create();app.get("/api/health",c->{},ActivityRole.USER_ACTION);
    var x=assertThrows(InvocationTargetException.class,()->validate(app));
    assertTrue(x.getCause() instanceof IllegalStateException);
  }
  @Test void removedObserverCannotLeaveObsoleteTableEntry() throws Exception {
    var f=guard().getDeclaredField("REASONS");f.setAccessible(true);
    var reasons=(Map<String,String>)f.get(null);
    var app=Javalin.create();
    for(var key:reasons.keySet()) {
      if(key.startsWith("MCP ") || key.equals("GET /api/health")) continue;
      var pieces=key.split(" ",2);
      switch(pieces[0]) {
        case "GET" -> app.get(pieces[1],c->{},ActivityRole.OBSERVATION);
        case "POST" -> app.post(pieces[1],c->{},ActivityRole.OBSERVATION);
        case "HEAD" -> app.head(pieces[1],c->{},ActivityRole.OBSERVATION);
        case "OPTIONS" -> app.options(pieces[1],c->{},ActivityRole.OBSERVATION);
        default -> fail("Unrecognized HTTP method in observer table: "+key);
      }
    }
    var x=assertThrows(InvocationTargetException.class,()->validate(app),"GET /api/health removed, but obsolete justification remains");
    assertTrue(x.getCause() instanceof IllegalStateException);
  }
  @Test void ledgerIsDeliberateEvenWithInternalHeaders() throws Exception {
    new VerifierRoundFourActivityTest().http("get","/api/diagnostics/ingestion/recent",true);
  }
  @Test void sessionRosterOnlyRequestedByDrawerActionsIsDeliberate() throws Exception {
    new VerifierRoundFourActivityTest().http("get","/api/chat/sessions",true);
  }
  @Test void folderFailedJobsOnlyRequestedByDrawerActionsIsDeliberate() throws Exception {
    new VerifierRoundFourActivityTest().http("get","/api/indexing-jobs/failed/by-prefix",true);
  }
}
