package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.telemetry.Telemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SchemaController")
final class SchemaControllerTest {

  private SchemaController controller;
  private Telemetry telemetry;

  @BeforeEach
  void setUp() {
    telemetry = mock(Telemetry.class);
    controller = new SchemaController(telemetry);
  }

  @Test
  @DisplayName("known schema returns 200 with body and strong ETag")
  void knownSchemaReturns200WithBodyAndEtag() {
    Context ctx = mock(Context.class);
    when(ctx.pathParam("name")).thenReturn("indexing-job-view.v1.json");
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.header(anyString(), anyString())).thenReturn(ctx);

    controller.handle(ctx);

    verify(ctx).contentType("application/schema+json");
    verify(ctx).header(eq("ETag"), anyString());
    verify(ctx).header("Cache-Control", "public, max-age=3600");
    verify(ctx).result(any(byte[].class));
    verify(ctx, never()).status(404);
  }

  @Test
  @DisplayName("unknown schema returns 404 envelope without leaking ETag")
  void unknownSchemaReturns404() {
    Context ctx = mock(Context.class);
    when(ctx.pathParam("name")).thenReturn("not-a-schema.v1.json");
    when(ctx.status(anyInt())).thenReturn(ctx);

    controller.handle(ctx);

    verify(ctx).status(404);
    verify(ctx, never()).contentType("application/schema+json");
    verify(ctx, never()).header(eq("ETag"), anyString());
  }

  @Test
  @DisplayName("invalid name format returns 404 (not 400)")
  void invalidNameReturns404() {
    Context ctx = mock(Context.class);
    // Pattern requires [a-z0-9-]+\.v1\.json — underscores and uppercase rejected.
    when(ctx.pathParam("name")).thenReturn("BadName_v2.json");
    when(ctx.status(anyInt())).thenReturn(ctx);

    controller.handle(ctx);

    verify(ctx).status(404);
  }

  @Test
  @DisplayName("conditional GET with matching ETag returns 304 Not Modified")
  void conditionalGet304() {
    // First, get the live ETag by issuing a 200 GET.
    Context first = mock(Context.class);
    when(first.pathParam("name")).thenReturn("indexing-job-view.v1.json");
    when(first.contentType(anyString())).thenReturn(first);
    when(first.header(anyString(), anyString())).thenReturn(first);
    org.mockito.ArgumentCaptor<String> etagCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    controller.handle(first);
    verify(first).header(eq("ETag"), etagCaptor.capture());
    String etag = etagCaptor.getValue();
    assertTrue(etag.startsWith("\"") && etag.endsWith("\""), "etag is strong");

    // Second request with If-None-Match: <etag> → 304.
    Context second = mock(Context.class);
    when(second.pathParam("name")).thenReturn("indexing-job-view.v1.json");
    when(second.header("If-None-Match")).thenReturn(etag);
    when(second.header(anyString(), anyString())).thenReturn(second);
    when(second.status(anyInt())).thenReturn(second);

    controller.handle(second);

    verify(second).status(304);
    verify(second, never()).result(any(byte[].class));
  }

  @Test
  @DisplayName("served names cover all expected schemas")
  void servedNamesIncludeKnownSchemas() {
    var names = controller.servedNames();
    assertTrue(names.contains("health-event.v1.json"));
    assertTrue(names.contains("indexing-job-view.v1.json"));
    assertTrue(names.contains("operation-history-entry.v1.json"));
    assertTrue(names.contains("operation.v1.json"));
    assertTrue(names.contains("prompt.v1.json"));
    assertTrue(names.contains("resource.v1.json"));
    assertTrue(names.contains("runtime-context.v1.json"));
    // Slice 3a.1.9 §B.B.B D1: the timeseries-snapshot schema closes
    // the dangling-URL defect. Four TIMESERIES metric catalogs
    // reference this schema name.
    assertTrue(names.contains("timeseries-snapshot.v1.json"));
    // Tempdoc 583 §D.3a / §C review: three wire schemas the route-manifest responseSchema $refs point
    // at — served here so the OpenAPI $refs resolve (RouteContractPolicyCoverageTest enforces this).
    assertTrue(names.contains("knowledge-search-response.v1.json"));
    assertTrue(names.contains("ai-runtime-status-response.v1.json"));
    assertTrue(names.contains("effective-policy.v1.json"));
    // Tempdoc 911 (885 UL.9): the substrate failed-jobs response, referenced by RouteContractPolicy
    // for both /api/indexing-jobs/failed routes — same reason as the three above.
    assertTrue(names.contains("failed-indexing-jobs-response.v1.json"));
    // Tempdoc 899 D6: canonical public response schemas embedded into the generated runtime SDK.
    assertTrue(names.contains("api-error-response.v1.json"));
    assertTrue(names.contains("lifecycle-snapshot.v1.json"));
    assertTrue(names.contains("runtime-live-response.v1.json"));
    assertTrue(names.contains("runtime-manifest-public.v2.json"));
    assertTrue(names.contains("runtime-ready-response.v1.json"));
    assertEquals(17, names.size());
    assertFalse(names.contains("nonexistent.v1.json"));
  }
}
