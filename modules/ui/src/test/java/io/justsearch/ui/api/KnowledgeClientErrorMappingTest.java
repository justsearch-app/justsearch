package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.services.worker.KnowledgeClientException;
import io.justsearch.app.services.worker.KnowledgeClientException.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Lane F review B1: the API front's translation of a port failure into an HTTP answer.
 *
 * <p>This replaces {@code KnowledgeSearchControllerGrpcErrorTest}, which tested the same property
 * through {@code io.grpc.StatusRuntimeException}. That test kept passing after item A6 while the
 * behaviour it described stopped happening: the client no longer threw the transport's exception,
 * so the controllers' catch blocks were unreachable and every worker error fell through to a 500 —
 * an expired pagination cursor stopped being a 4xx, a deadline stopped being a 504, an unavailable
 * index stopped being a 503. Nothing failed. The answers just got worse.
 *
 * <p>That is why the mapping is asserted here as a table rather than at two points: the failure
 * mode was a whole vocabulary going missing at once, and a test that checks one status cannot see
 * it.
 */
@DisplayName("port failure -> HTTP status")
final class KnowledgeClientErrorMappingTest {

  /**
   * The mapping is carried across from the deleted {@code mapGrpcToHttp} <b>unchanged</b>, which is
   * the point: the fix restores the answers the product used to give, it does not improve them.
   * ABORTED, UNIMPLEMENTED and CANCELLED land on 500 because that is where the old {@code default}
   * arm put them.
   */
  @ParameterizedTest(name = "{0} -> HTTP {1}")
  @CsvSource({
    "INVALID_ARGUMENT, 400",
    "FAILED_PRECONDITION, 409",
    "RESOURCE_EXHAUSTED, 429",
    "UNAVAILABLE, 503",
    "DEADLINE_EXCEEDED, 504",
    "ABORTED, 500",
    "UNIMPLEMENTED, 500",
    "CANCELLED, 500",
    "INTERNAL, 500",
  })
  void statusMapsToTheSameHttpCodeItAlwaysDid(String status, int expectedHttp) {
    assertEquals(expectedHttp, ApiErrorHandler.mapClientStatusToHttp(Status.valueOf(status)));
  }

  @Test
  @DisplayName("every status is covered by the table above")
  void theTableIsExhaustive() {
    // A status added without a row would otherwise inherit 500 silently — which is exactly how the
    // whole vocabulary went missing in the first place.
    assertEquals(
        9,
        Status.values().length,
        "add the new status to the @CsvSource above (and decide its HTTP code deliberately)");
  }

  @Test
  @DisplayName("a null status is 500, not a crash")
  void nullStatusIsInternalError() {
    assertEquals(500, ApiErrorHandler.mapClientStatusToHttp(null));
  }

  @Test
  @DisplayName("an INVALID_ARGUMENT mentioning a cursor is the pagination case")
  void invalidCursorIsDetected() {
    assertTrue(
        new KnowledgeClientException(Status.INVALID_ARGUMENT, "invalid cursor token")
            .isInvalidCursor());
    assertEquals(400, ApiErrorHandler.mapClientStatusToHttp(Status.INVALID_ARGUMENT));
  }

  @Test
  @DisplayName("an INVALID_ARGUMENT not mentioning a cursor is not the pagination case")
  void invalidArgumentNonCursorIsNotDetected() {
    assertFalse(
        new KnowledgeClientException(Status.INVALID_ARGUMENT, "invalid request").isInvalidCursor());
  }

  @Test
  @DisplayName("a cursor mention under a different status is not the pagination case")
  void cursorTextUnderAnotherStatusIsNotDetected() {
    // The status is half the predicate: an INTERNAL that happens to mention a cursor is a bug
    // report, not "your page token expired, ask again from the start".
    assertFalse(
        new KnowledgeClientException(Status.INTERNAL, "cursor decode blew up").isInvalidCursor());
  }

  @Test
  @DisplayName("the resolved error code keeps its pre-A6 classification")
  void errorCodeClassificationIsUnchanged() {
    assertEquals(
        ApiErrorCode.TIMEOUT,
        ApiErrorHandler.resolve(
            new KnowledgeClientException(Status.DEADLINE_EXCEEDED, "too slow")));
    assertEquals(
        ApiErrorCode.SERVICE_UNAVAILABLE,
        ApiErrorHandler.resolve(new KnowledgeClientException(Status.UNAVAILABLE, "not up")));
    assertEquals(
        ApiErrorCode.INVALID_REQUEST,
        ApiErrorHandler.resolve(new KnowledgeClientException(Status.INVALID_ARGUMENT, "bad")));
    assertEquals(
        ApiErrorCode.SERVICE_UNAVAILABLE,
        ApiErrorHandler.resolve(new KnowledgeClientException(Status.RESOURCE_EXHAUSTED, "full")));
    assertEquals(
        ApiErrorCode.INTERNAL_ERROR,
        ApiErrorHandler.resolve(new KnowledgeClientException(Status.INTERNAL, "boom")));
  }
}
