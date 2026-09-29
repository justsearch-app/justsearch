package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.systemtests.harness.IsolatedBackendFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Live-stack verification for tempdoc 550 Slice A1 (Authorize face): the gated-action
 * dead-end the spine targets is recoverable end-to-end via a consent capsule.
 *
 * <p>The goal names this exact check ("reproduce the gated dead-end and confirm it is
 * resolved end-to-end"). Runs against a real {@code HeadlessApp} child JVM (lite mode).
 * The trust gate keys on source <i>tier</i>, not the LLM literally, so an UNTRUSTED
 * source is simulated with the {@code X-JustSearch-Transport: LLM_EMISSION} header — the
 * same lattice path an LLM URL emission hits.
 *
 * <p>Operation under test: {@code core.remove-watched-root} (MEDIUM risk → UNTRUSTED ×
 * MEDIUM = TYPED_CONFIRM). Chosen because the fixture starts with a fresh empty data dir
 * (no watched roots), so even if the gate is passed and the handler runs, there is
 * nothing to remove — the test is side-effect-free.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>Invoke from UNTRUSTED with no token → HTTP 428 {@code CONFIRMATION_REQUIRED}
 *       (the dead-end reproduced).
 *   <li>Approve → receive a capsule bound to (op, args).
 *   <li>Re-invoke from UNTRUSTED with the capsule → no longer 428 (the gate is
 *       satisfied; the dead-end is resolved).
 * </ol>
 *
 * <p>Note (per 550 A1 §): while the legacy non-blank-token path remains (pre-C2), the
 * live path cannot distinguish "capsule verified" from "legacy non-blank accepted" — both
 * satisfy the gate. The capsule's cryptographic binding/single-use are unit-verified in
 * {@code ConsentCapsuleServiceTest}; this test proves the end-to-end <i>recovery</i>.
 */
@DisplayName("Consent Capsule Recovery E2E (tempdoc 550 Slice A1)")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class ConsentCapsuleRecoveryE2ETest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final IsolatedBackendFixture BACKEND = new IsolatedBackendFixture();
  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private static final String OP = "core.remove-watched-root";

  @BeforeAll
  static void startBackend() throws Exception {
    BACKEND.start();
  }

  @AfterAll
  static void stopBackend() {
    BACKEND.stop();
  }

  @Test
  @DisplayName("A fabricated (non-capsule) token from an UNTRUSTED source is rejected")
  void fabricatedTokenIsRejectedForUntrustedSource() throws Exception {
    // Tempdoc 550 C2 step 4: the capsule has replaced the nominal token for UNTRUSTED
    // sources — a non-blank placeholder no longer satisfies the gate.
    HttpResponse<String> resp = invoke("{\"args\":{},\"confirmationToken\":\"not-a-capsule\"}");
    assertEquals(
        428,
        resp.statusCode(),
        "a fabricated token from an UNTRUSTED source no longer passes the gate: " + resp.body());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("CONFIRMATION_REQUIRED", json.path("errorClass").asText());
  }

  @Test
  @DisplayName("C3: capsule binds + verifies end-to-end for non-empty args via approve-by-pendingId")
  void capsuleBindsForNonEmptyArgsEndToEnd() throws Exception {
    // Tempdoc 550 C3: the pending captures the gating invoke's args; the approve mints a
    // capsule bound to THOSE stored args. The re-invoke sends the SAME args, so the
    // serialization agrees and the capsule verifies. Driven UNTRUSTED (no legacy fallback),
    // so passing the gate PROVES the capsule verified.
    String args = "{\"path\":\"/tmp/does-not-exist\"}";
    // 1. Gate on the real args → 428 carrying a pendingId that stored those args.
    HttpResponse<String> gated = invoke("{\"args\":" + args + "}");
    assertEquals(428, gated.statusCode(), "non-empty-args op gates with no token: " + gated.body());
    String pendingId = MAPPER.readTree(gated.body()).path("pendingId").asText();
    assertTrue(pendingId != null && !pendingId.isBlank(), "428 carries a pendingId: " + gated.body());

    // 2. Approve by id → capsule bound to the STORED {path:...} args.
    HttpResponse<String> approve =
        post("/api/authorizations/approve", "{\"pendingId\":\"" + pendingId + "\"}", null);
    assertEquals(200, approve.statusCode(), "approve mints for the stored args: " + approve.body());
    String capsule = MAPPER.readTree(approve.body()).path("capsule").asText();

    // 3. Re-invoke with the SAME args + capsule → verifies (serializations agree).
    HttpResponse<String> recovered =
        invoke("{\"args\":" + args + ",\"confirmationToken\":\"" + capsule + "\"}");
    assertNotEquals(
        428,
        recovered.statusCode(),
        "capsule bound to the stored non-empty args verifies at dispatch: " + recovered.body());
    assertNotEquals(
        "CONFIRMATION_REQUIRED",
        MAPPER.readTree(recovered.body()).path("errorClass").asText(""),
        "the gate was satisfied by the capsule");
  }

  @Test
  @DisplayName("C3: gated UNTRUSTED action recovers via approve-by-pendingId; pending is single-use")
  void gatedActionRecoversViaPendingId() throws Exception {
    // Tempdoc 550 C3 + WA-5: the hardened recovery flow. The 428 now carries a
    // backend-issued pendingId; the FE approves by that id (not by presenting an arbitrary
    // op+args), and the capsule is minted against the STORED (op, args).
    // 1. UNTRUSTED, no token → 428 carrying a pendingId.
    HttpResponse<String> gated = invoke("{\"args\":{}}");
    assertEquals(428, gated.statusCode(), "UNTRUSTED MEDIUM op with no token gates: " + gated.body());
    JsonNode gatedJson = MAPPER.readTree(gated.body());
    String pendingId = gatedJson.path("pendingId").asText();
    assertTrue(
        pendingId != null && !pendingId.isBlank(),
        "the 428 carries a backend-issued pendingId: " + gated.body());

    // 2. Approve BY pendingId (no op/args supplied) → capsule bound to the stored (op, args).
    HttpResponse<String> approve =
        post("/api/authorizations/approve", "{\"pendingId\":\"" + pendingId + "\"}", null);
    assertEquals(200, approve.statusCode(), "approve-by-pendingId mints a capsule: " + approve.body());
    String capsule = MAPPER.readTree(approve.body()).path("capsule").asText();
    assertTrue(capsule != null && !capsule.isBlank(), "a capsule is returned");

    // 3. Re-invoke the SAME action with the capsule → no longer gated.
    HttpResponse<String> recovered =
        invoke("{\"args\":{},\"confirmationToken\":\"" + capsule + "\"}");
    assertNotEquals(
        428, recovered.statusCode(), "the approved capsule resolves the gate: " + recovered.body());

    // 4. The pending is single-use: re-approving the consumed id fails closed with 410 Gone.
    HttpResponse<String> reapprove =
        post("/api/authorizations/approve", "{\"pendingId\":\"" + pendingId + "\"}", null);
    assertEquals(
        410, reapprove.statusCode(), "a consumed pendingId cannot be replayed: " + reapprove.body());
  }

  @Test
  @DisplayName("thesis IV: an allow-always durable grant auto-approves future invocations sans capsule")
  void durableAllowAlwaysAutoApprovesEndToEnd() throws Exception {
    // 1. Gate the UNTRUSTED action → 428 + pendingId.
    HttpResponse<String> gated = invoke("{\"args\":{}}");
    assertEquals(428, gated.statusCode(), "UNTRUSTED MEDIUM op gates with no token: " + gated.body());
    String pendingId = MAPPER.readTree(gated.body()).path("pendingId").asText();
    assertTrue(pendingId != null && !pendingId.isBlank(), "428 carries a pendingId: " + gated.body());

    // 2. Approve WITH allowAlways → records a durable grant for (op, UNTRUSTED).
    HttpResponse<String> approve =
        post(
            "/api/authorizations/approve",
            "{\"pendingId\":\"" + pendingId + "\",\"allowAlways\":true}",
            null);
    assertEquals(200, approve.statusCode(), "approve with allowAlways succeeds: " + approve.body());

    // 3. Re-invoke the SAME op with NO capsule → the durable grant satisfies the gate (no re-prompt).
    HttpResponse<String> auto = invoke("{\"args\":{}}");
    assertNotEquals(
        428,
        auto.statusCode(),
        "durable allow-always auto-approves the gate without a capsule: " + auto.body());
  }

  @Test
  @DisplayName("C3: approving an unknown pendingId fails closed (410)")
  void unknownPendingIdIsRejected() throws Exception {
    HttpResponse<String> resp =
        post("/api/authorizations/approve", "{\"pendingId\":\"pa-does-not-exist\"}", null);
    assertEquals(
        410, resp.statusCode(), "an unknown pending authorization is gone, not mintable: " + resp.body());
  }

  @Test
  @DisplayName("C2 step 3: a TRUSTED (BUTTON) HIGH op with a nominal token is rejected live")
  void trustedButtonHighNominalTokenIsRejected() throws Exception {
    // Tempdoc 550 C2 step 3 closes the nominal-token weakness for ALL tiers, not just
    // UNTRUSTED. core.apply-excludes is HIGH-risk; with no X-JustSearch-Transport header the
    // backend defaults to BUTTON => TRUSTED (OperationsController.resolveProvenance), and
    // TRUSTED × HIGH = TYPED_CONFIRM. The V1 nominal (op-id) token no longer satisfies the
    // gate, so this 428s and the handler never runs (excludes are NOT applied — the gate
    // precedes dispatch). Pre-C2-step-3 the op-id stand-in passed here; this pins the flip
    // end-to-end against a real HeadlessApp.
    HttpResponse<String> resp =
        post(
            "/api/operations/core.apply-excludes/invoke",
            "{\"args\":{},\"confirmationToken\":\"core.apply-excludes\"}",
            null);
    assertEquals(
        428,
        resp.statusCode(),
        "a TRUSTED BUTTON HIGH op with a nominal token is gated after C2 step 3: " + resp.body());
    assertEquals(
        "CONFIRMATION_REQUIRED",
        MAPPER.readTree(resp.body()).path("errorClass").asText(),
        "the rejection is the trust gate, not another failure");
  }

  @Test
  @DisplayName("Outcome: a gated UNTRUSTED invocation appears in the action ledger as a GATED row")
  void gatedInvocationIsRecordedInTheActionLedger() throws Exception {
    // Tempdoc 550 Outcome face: a gated dispatch throws before any OperationHistoryEntry, so
    // historically the firing left no trace. The gate-decision sink now records it, and the
    // action ledger projects it — so the gate firing is a visible, attributed row (the row the
    // 538 trust-firing audit reads). Live-verified against a real HeadlessApp.
    HttpResponse<String> gated = invoke("{\"args\":{}}");
    assertEquals(428, gated.statusCode(), "UNTRUSTED MEDIUM op with no token gates: " + gated.body());

    HttpResponse<String> ledger = get("/api/action-ledger");
    assertEquals(200, ledger.statusCode(), "ledger readable: " + ledger.body());
    JsonNode entries = MAPPER.readTree(ledger.body()).path("entries");
    boolean found = false;
    for (JsonNode e : entries) {
      if ("gate".equals(e.path("kind").asText())
          && OP.equals(e.path("operationId").asText())
          && "GATED".equals(e.path("disposition").asText())) {
        found = true;
        assertEquals("agent", e.path("originator").asText(), "LLM_EMISSION attributes to agent");
        break;
      }
    }
    assertTrue(found, "the gate firing is a ledger row (no longer invisible): " + ledger.body());
  }

  @Test
  @DisplayName("E2: Global Hard Stop denies agent dispatch, leaves user (BUTTON) dispatch alone")
  void globalHardStopDeniesAgentNotUser() throws Exception {
    // Tempdoc 550 E2: engaging the lattice-level hard stop denies UNTRUSTED (agent/LLM) dispatch
    // (403 TRUST_DENIED) while user-driven (BUTTON) dispatch is unaffected; releasing restores the
    // normal gate. core.remove-watched-root is MEDIUM (BUTTON×MEDIUM=AUTO runs; LLM×MEDIUM gates).
    HttpResponse<String> on = post("/api/agent/hard-stop", "{\"engaged\":true}", null);
    assertEquals(200, on.statusCode(), "engage: " + on.body());
    assertTrue(MAPPER.readTree(on.body()).path("engaged").asBoolean(), "engaged: " + on.body());
    try {
      HttpResponse<String> agent = invoke("{\"args\":{}}"); // LLM_EMISSION transport
      assertEquals(403, agent.statusCode(), "hard stop denies the agent dispatch: " + agent.body());

      HttpResponse<String> user = post("/api/operations/" + OP + "/invoke", "{\"args\":{}}", null);
      assertNotEquals(403, user.statusCode(), "user/BUTTON dispatch is unaffected: " + user.body());
    } finally {
      HttpResponse<String> off = post("/api/agent/hard-stop", "{\"engaged\":false}", null);
      assertEquals(200, off.statusCode(), "release: " + off.body());
      assertTrue(!MAPPER.readTree(off.body()).path("engaged").asBoolean(), "released: " + off.body());
    }
    // Released → the agent dispatch returns to the normal lattice gate (428), not a hard-stop 403.
    HttpResponse<String> agentAfter = invoke("{\"args\":{}}");
    assertEquals(428, agentAfter.statusCode(), "released → normal gate: " + agentAfter.body());
  }

  @Test
  @DisplayName("P1: the 428 carries the prompt's decision context (risk + reversibility + args)")
  void confirmationRequiredBodyCarriesDecisionContext() throws Exception {
    // Tempdoc 550 P1: a gated invoke's 428 now includes the context the ceremony shows — the
    // op's risk, whether it's reversible, and a short args summary — sourced from what the gate
    // already knows. core.remove-watched-root is MEDIUM risk.
    HttpResponse<String> gated = invoke("{\"args\":{\"path\":\"/tmp/x\"}}");
    assertEquals(428, gated.statusCode(), "gated: " + gated.body());
    JsonNode json = MAPPER.readTree(gated.body());
    assertEquals("MEDIUM", json.path("riskTier").asText(), "risk surfaced: " + gated.body());
    assertTrue(json.has("undoSupported"), "reversibility surfaced: " + gated.body());
    assertTrue(
        json.path("argsSummary").asText().contains("/tmp/x"),
        "args summary surfaced: " + gated.body());
  }

  @Test
  @DisplayName("Outcome G3/G4/G5: the unified action-ledger SSE serves a snapshot then a LIVE gate UPDATE")
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void actionLedgerStreamDeliversSnapshotThenLiveGateFiring() throws Exception {
    // Tempdoc 550 G3/G4/G5: the receipt/timeline/undo/trust-audit are LIVE read-views of one
    // ledger. This proves the new SSE endpoint against a real HeadlessApp: a snapshot frame on
    // connect, then — when a gated UNTRUSTED invoke fires — a live UPDATE row carrying the gate
    // decision is pushed without a poll. (Re-confirms the gate-fire emit reaches the stream.)
    java.util.List<String> dataLines = new java.util.concurrent.CopyOnWriteArrayList<>();
    java.util.concurrent.CompletableFuture<Void> pump =
        CLIENT.sendAsync(
                HttpRequest.newBuilder(URI.create(base() + "/api/action-ledger/stream"))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofLines())
            .thenAccept(
                resp ->
                    resp.body()
                        .filter(l -> l.startsWith("data:"))
                        .forEach(l -> dataLines.add(l.substring("data:".length()).trim())));
    try {
      // 1. A frame arrives on connect (the snapshot lifecycle frame) — the route is wired + serves.
      awaitUntil(() -> !dataLines.isEmpty(), "an SSE frame arrives on connect: " + dataLines);

      // 2. Fire a gated UNTRUSTED invoke → a live UPDATE gate row is pushed onto the stream.
      HttpResponse<String> gated = invoke("{\"args\":{}}");
      assertEquals(428, gated.statusCode(), "the invoke gates: " + gated.body());

      awaitUntil(
          () -> dataLines.stream().anyMatch(ConsentCapsuleRecoveryE2ETest::isLiveGateFiring),
          "a live gate UPDATE frame (kind=gate, GATED) is pushed after the gated invoke: " + dataLines);
    } finally {
      pump.cancel(true);
    }
  }

  /** True when the SSE data line is an UPDATE envelope carrying a GATED gate row. */
  private static boolean isLiveGateFiring(String data) {
    try {
      JsonNode env = MAPPER.readTree(data);
      JsonNode payload = env.path("payload");
      return "UPDATE".equals(env.path("frameKind").asText())
          && "gate".equals(payload.path("kind").asText())
          && "GATED".equals(payload.path("disposition").asText())
          && OP.equals(payload.path("operationId").asText());
    } catch (Exception e) {
      return false;
    }
  }

  @Test
  @DisplayName("Outcome G6: a TRUSTED (BUTTON) AUTO op that runs leaves an operation row in the ledger")
  void completedOperationAppearsAsLedgerRow() throws Exception {
    // Tempdoc 550 G6: completed operations are rows in the same ledger (the FE collapses its
    // Effect Journal entry into this backend row via executionId). core.remove-watched-root from
    // a BUTTON source is TRUSTED × MEDIUM = AUTO, so it runs to completion (side-effect-free on
    // the fixture's empty data dir) and emits an OperationHistoryEntry. Live-verified end-to-end.
    HttpResponse<String> ran = post("/api/operations/" + OP + "/invoke", "{\"args\":{}}", null);
    assertNotEquals(428, ran.statusCode(), "BUTTON AUTO op runs (not gated): " + ran.body());

    HttpResponse<String> ledger = get("/api/action-ledger");
    assertEquals(200, ledger.statusCode(), "ledger readable: " + ledger.body());
    boolean found = false;
    for (JsonNode e : MAPPER.readTree(ledger.body()).path("entries")) {
      if ("operation".equals(e.path("kind").asText()) && OP.equals(e.path("operationId").asText())) {
        found = true;
        // executionId is present only for undo-supported ops; when present it must be non-blank
        // (the G6 correlation key the FE collapses on).
        if (e.has("executionId")) {
          assertTrue(!e.path("executionId").asText().isBlank(), "executionId, when present, is non-blank");
        }
        break;
      }
    }
    assertTrue(found, "the completed operation is a ledger row: " + ledger.body());
  }

  /** Poll until {@code cond} holds or a 15s deadline elapses. */
  private static void awaitUntil(java.util.function.BooleanSupplier cond, String msg)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      if (cond.getAsBoolean()) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("timed out waiting: " + msg);
  }

  @Test
  @DisplayName("Preview G7/F3: core.search-index declares availability that evaluates live (available when index ready)")
  void searchIndexAvailabilityEvaluatesLive() throws Exception {
    // Tempdoc 550 F3: core.search-index now declares Not(ConditionMatches("index.unavailable")) —
    // the first real producer of the Preview availability channel. The preview endpoint evaluates
    // it against the LIVE ConditionStore (same evaluator the agent-tool emitter uses). In the
    // fixture's ready state the index is serving, so "index.unavailable" is absent and the op is
    // available — proving the producer→evaluator→live-store path AND that the primary search tool
    // is NOT wrongly hidden when ready (the blast-radius guard). The hidden-when-unavailable
    // transition is unit-covered (AgentOperationEmitterTest negation cases).
    HttpResponse<String> resp = get("/api/operations/core.search-index/preview");
    assertEquals(200, resp.statusCode(), "preview readable: " + resp.body());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals(
        "Not", json.path("availabilityKind").asText(), "search-index declares a Not(...) gate: " + resp.body());

    // Tempdoc 876 §C.8 — this AWAITS instead of sampling once, and the change strengthens it.
    // Availability is now genuinely reconciled rather than accidentally absent. Before 876 nothing
    // reconciled the ConditionStore unless something called GET /api/status, and this test calls
    // none — so the old instantaneous assertion passed because the store was EMPTY, not because the
    // index was serving. It would have passed with the worker on fire.
    //
    // Reconciliation is asynchronous by construction (a capability transition, or the worker-health
    // poll the head already runs), so the honest assertion is that it CONVERGES without a request,
    // which is precisely 876's claim. Sampling at one arbitrary instant tests a race, not the
    // invariant. The fixture's terminal state is INDEX_SERVING DEGRADED / index.dense_unavailable —
    // the dense leg is down, keyword search works — which §C.8 maps to its own condition so it no
    // longer holds core.search-index's Not(index.unavailable) gate shut.
    awaitUntil(
        () -> {
          try {
            return MAPPER
                .readTree(get("/api/operations/core.search-index/preview").body())
                .path("availableNow")
                .asBoolean();
          } catch (Exception e) {
            return false;
          }
        },
        "core.search-index must become available with NO /api/status call — a degraded-but-serving"
            + " index must not leave the model's primary search tool hidden");
  }

  private static HttpResponse<String> get(String path) throws Exception {
    return CLIENT.send(
        HttpRequest.newBuilder(URI.create(base() + path))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** POST the invoke endpoint as an UNTRUSTED source (LLM_EMISSION transport). */
  private static HttpResponse<String> invoke(String body) throws Exception {
    return CLIENT.send(
        HttpRequest.newBuilder(URI.create(base() + "/api/operations/" + OP + "/invoke"))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("X-JustSearch-Transport", "LLM_EMISSION")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(String path, String body, String transport)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(base() + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (transport != null) {
      b.header("X-JustSearch-Transport", transport);
    }
    return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String base() {
    return "http://127.0.0.1:" + BACKEND.port();
  }
}
