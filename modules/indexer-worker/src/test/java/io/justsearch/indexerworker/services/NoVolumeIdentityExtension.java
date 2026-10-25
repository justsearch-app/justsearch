/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Explicitly injects volume evidence for opted-in policy tests, restoring real lookup afterwards.
 * With {@code justsearch.test.noVolumeIdentity=true}, injects missing evidence instead.
 * Native held-directory and mount checks remain real. Scoped overrides cover both outcomes.
 */
public final class NoVolumeIdentityExtension implements BeforeEachCallback, AfterEachCallback {
  /** The system property that switches the seam on. */
  public static final String PROPERTY = "justsearch.test.noVolumeIdentity";
  private static final String PRESENT_VOLUME = System.getProperty("os.name", "").startsWith("Windows")
      ? "windows-volume:739" : "linux-volume-v1:739:ext4:Lw";

  @Override
  public void beforeEach(ExtensionContext context) {
    var restore = withVolumeIdentity(!Boolean.getBoolean(PROPERTY));
    RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(Boolean.getBoolean(PROPERTY));
    context.getStore(ExtensionContext.Namespace.create(getClass(), context.getUniqueId()))
        .put("restore", restore);
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    var restore = context.getStore(ExtensionContext.Namespace.create(getClass(), context.getUniqueId()))
        .remove("restore", AutoCloseable.class);
    if (restore != null) restore.close();
  }

  /** Raw process-kill fixture drivers use the same evidence as their JUnit parent. */
  public static void installVolumeLookupIfAbsent() {
    if (RootIdentity.VOLUME_IDENTITY_LOOKUP != null) return;
    RootIdentity.VOLUME_IDENTITY_LOOKUP = ignored -> PRESENT_VOLUME;
    RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(Boolean.getBoolean(PROPERTY));
  }

  /** Inject both lookup results without relying on the host's /dev/disk/by-uuid. */
  public static AutoCloseable withVolumeIdentity(boolean available) {
    var previous = RootIdentity.VOLUME_IDENTITY_LOOKUP;
    boolean unavailable = RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.getAndSet(false);
    RootIdentity.VOLUME_IDENTITY_LOOKUP = ignored -> available ? PRESENT_VOLUME : null;
    return () -> {
      RootIdentity.VOLUME_IDENTITY_LOOKUP = previous;
      RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(unavailable);
    };
  }

  /** Whether the platform, or the seam, gives {@code folder} a volume identity now. */
  public static boolean volumeIdentityAvailable(Path folder) {
    try {
      return RootIdentity.capture(folder).volume() != null;
    } catch (IOException unreadable) {
      return false;
    }
  }
}
