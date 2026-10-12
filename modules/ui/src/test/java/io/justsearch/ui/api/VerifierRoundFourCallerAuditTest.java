package io.justsearch.ui.api;
import org.junit.jupiter.api.Test;
/** Added after auditing every route's callers. No product implementation changes or process cleanup. */
public class VerifierRoundFourCallerAuditTest {
  @Test void explicitIngestionEventReadIsUserInput() throws Exception {
    new VerifierRoundFourActivityTest().http("get", "/api/diagnostics/ingestion/recent", true);
  }
  @Test void deliberatelyPostedDispositionIsUserInput() throws Exception {
    // recordInspectorOpen has no production caller. Its dormant timer is not evidence of
    // an unattended consumer; a deliberate client's disposition remains user action.
    new VerifierRoundFourActivityTest().http("post", "/api/knowledge/disposition", true,
        "{\"interactionId\":\"fixture\",\"docId\":\"fixture\",\"kind\":\"dwelled\"}");
  }
  @Test void explicitResultOpenStillIsUserInput() throws Exception {
    new VerifierRoundFourActivityTest().http("post", "/api/knowledge/disposition", true,
        "{\"interactionId\":\"fixture\",\"docId\":\"fixture\",\"kind\":\"opened\"}");
  }
}
