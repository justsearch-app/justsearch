/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.TestEngineContexts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, scenario S8: a watched folder that still exists but can no longer be
 * listed (expired share credentials, a changed permission) shows the one root-level "unavailable"
 * state, the same one a missing folder shows, instead of reading as available while its files fail
 * one by one. Real conditions: a deny ACE for listing on a folder this test creates, removed before
 * the test returns.
 */
@Timeout(30)
final class UnreadableRootAvailabilityTest {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  /** The existing root-level reason the wire reports as "unavailable". */
  private static final String ROOT_UNAVAILABLE_REASON = "ROOT_NOT_DIRECTORY";

  @TempDir Path temp;

  @Test
  void aRootThatCannotBeListedShowsTheRootLevelUnavailableState() throws Exception {
    assumeTrue(WINDOWS, "the deny ACE is set through the Windows ACL view");
    Path root = Files.createDirectories(temp.resolve("share")).toAbsolutePath().normalize();
    Files.writeString(root.resolve("report.txt"), "indexed");
    ExecutorService walkExecutor = Executors.newSingleThreadExecutor();
    try (DenyListing ignoredDeny = DenyListing.on(root)) {
      RootLifecycleOps ops = ops(root, walkExecutor);

      String walkError = settledWalkError(ops, walkExecutor);

      assertEquals(ROOT_UNAVAILABLE_REASON, walkError,
          "a folder that exists but cannot be listed is unavailable at the root level");
    } finally {
      walkExecutor.shutdownNow();
      assertTrue(walkExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void aListableRootStaysAvailable() throws Exception {
    // Must not break: a healthy folder carries no root-level error.
    Path root = Files.createDirectories(temp.resolve("local")).toAbsolutePath().normalize();
    Files.writeString(root.resolve("notes.txt"), "indexed");
    ExecutorService walkExecutor = Executors.newSingleThreadExecutor();
    try {
      RootLifecycleOps ops = ops(root, walkExecutor);

      assertNull(settledWalkError(ops, walkExecutor));
    } finally {
      walkExecutor.shutdownNow();
      assertTrue(walkExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static RootLifecycleOps ops(Path root, ExecutorService walkExecutor) {
    var roots = new ConcurrentHashMap<Path, Instant>();
    roots.put(root, Instant.now());
    var state = new WatchedRootsState(roots, new WatchedRootsStore(null, null));
    return new RootLifecycleOps(roots, state, ExcludeMatcher::empty,
        (r, collection, mode, excludes, progress, context) -> null,
        mock(RootLifecycleOps.WorkerWatchFn.class), (r, context) -> null,
        (id, context) -> null, mock(SyncOps.class), walkExecutor, (body, context) -> { });
  }

  /**
   * The root's walk error once the background availability check has landed: the first read
   * schedules the check on {@code walkExecutor}; a no-op task queued behind it marks its end.
   */
  private static String settledWalkError(RootLifecycleOps ops, ExecutorService walkExecutor)
      throws Exception {
    ops.getWatchedRoots(TestEngineContexts.internal());
    walkExecutor.submit(() -> { }).get(10, TimeUnit.SECONDS);
    List<IndexingService.WatchedRoot> roots = ops.getWatchedRoots(TestEngineContexts.internal());
    assertEquals(1, roots.size());
    return roots.get(0).walkError();
  }

  /** A deny ACE for listing (READ_DATA) for the current user on a folder; removed on close. */
  private static final class DenyListing implements AutoCloseable {
    private final AclFileAttributeView view;
    private final List<AclEntry> original;

    private DenyListing(AclFileAttributeView view, List<AclEntry> original) {
      this.view = view;
      this.original = original;
    }

    static DenyListing on(Path folder) throws IOException {
      AclFileAttributeView view = Files.getFileAttributeView(folder, AclFileAttributeView.class);
      List<AclEntry> original = view.getAcl();
      UserPrincipal me =
          folder.getFileSystem().getUserPrincipalLookupService()
              .lookupPrincipalByName(System.getProperty("user.name"));
      List<AclEntry> denied = new ArrayList<>();
      denied.add(
          AclEntry.newBuilder()
              .setType(AclEntryType.DENY)
              .setPrincipal(me)
              .setPermissions(AclEntryPermission.READ_DATA)
              .build());
      denied.addAll(original);
      view.setAcl(denied);
      return new DenyListing(view, original);
    }

    @Override
    public void close() throws IOException {
      view.setAcl(original);
    }
  }
}
