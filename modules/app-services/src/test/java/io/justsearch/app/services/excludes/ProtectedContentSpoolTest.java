/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.excludes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.ProtectedContentPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

final class ProtectedContentSpoolTest {
  @TempDir Path directory;

  @Test void separatedParentsAndChunksAreDeletedAndCountedOnceAcrossSpoolRuns() throws Exception {
    var context = TestEngineContexts.durableInternal();
    var indexing = mock(IndexingService.class);
    doAnswer(call -> {
      Consumer<IndexingService.IndexedSource> visit = call.getArgument(0);
      // The public rows force several bounded sort runs, including an incomplete final run.
      for (int pass = 0; pass < 3; pass++) {
        for (int i = 0; i < 12; i++) {
          int id = pass == 1 ? 11 - i : i;
          visit.accept(new IndexingService.IndexedSource(
              directory.resolve("private-" + id + ".key").toString(), id % 2 == 0 ? null : "docs"));
        }
        if (pass < 2) {
          for (int i = 0; i < 10_000; i++) {
            visit.accept(new IndexingService.IndexedSource(
                directory.resolve("ordinary-" + i + ".txt").toString(), "docs"));
          }
        }
      }
      return 0L;
    }).when(indexing).forEachIndexedSourceWithCollection(any(), eq(context));
    when(indexing.deleteDocByIdWithOutcome(anyString(), eq(context)))
        .thenReturn(new IndexingService.DeletionOutcome(true, ""));
    var logger = (Logger) LoggerFactory.getLogger(ExcludesServiceImpl.class);
    var messages = new ListAppender<ILoggingEvent>();
    messages.start();
    logger.addAppender(messages);
    try {
      assertTrue(new ExcludesServiceImpl(() -> indexing)
          .removeProtectedSources(directory.resolve("data"), context, path -> fail("not history")));
      assertAll(
          () -> verify(indexing, times(12)).deleteDocByIdWithOutcome(anyString(), eq(context)),
          () -> assertEquals(java.util.List.of(
              "Protected content upgrade: 12 confirmed source removals, 0 failed, 0 unchecked"),
              messages.list.stream().map(ILoggingEvent::getFormattedMessage)
                  .filter(message -> message.startsWith("Protected content upgrade:")).toList()));
      for (int i = 0; i < 12; i++) {
        verify(indexing).deleteDocByIdWithOutcome(directory.resolve("private-" + i + ".key").toString(), context);
      }
      assertNoSpools(directory.resolve("data"));
    } finally {
      logger.detachAppender(messages);
      messages.stop();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void abandonedSpoolsAreDeletedBeforeScanningOrHonoringCompletion(boolean completed) throws Exception {
    Path data = Files.createDirectory(directory.resolve("data"));
    Path abandoned = Files.writeString(data.resolve(".protected-content-killed.scan"), "/private/secret.key");
    Path second = Files.writeString(data.resolve(".protected-content-other.scan"), "/private/other.key");
    Path unrelated = Files.writeString(data.resolve("unrelated.scan"), "keep");
    Path nearMatch = Files.writeString(data.resolve(".protected-content-keep.txt"), "keep");
    if (completed) Files.writeString(data.resolve(".protected-content-version"), ProtectedContentPolicy.VERSION);
    var context = TestEngineContexts.durableInternal();
    var indexing = mock(IndexingService.class);
    doAnswer(call -> {
      assertFalse(Files.exists(abandoned), "a killed run's protected paths must be removed before the next scan");
      assertFalse(Files.exists(second));
      return 0L;
    }).when(indexing).forEachIndexedSourceWithCollection(any(), eq(context));

    assertTrue(new ExcludesServiceImpl(() -> indexing).removeProtectedSources(data, context, path -> fail("empty index")));
    assertNoSpools(data);
    assertEquals("keep", Files.readString(unrelated));
    assertEquals("keep", Files.readString(nearMatch));
    if (completed) verifyNoInteractions(indexing);
    else verify(indexing).forEachIndexedSourceWithCollection(any(), eq(context));
  }

  private static void assertNoSpools(Path data) throws Exception {
    try (var files = Files.newDirectoryStream(data, ".protected-content-*.scan")) {
      assertFalse(files.iterator().hasNext(), "completed cleanup must leave no protected-path spool");
    }
  }
}
