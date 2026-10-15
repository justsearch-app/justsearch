/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Runs a test suite as on a platform that exposes no volume identity (Linux, macOS), so a run on
 * Windows covers that path (task t-20261013-ef3c41, round 9). Registered for JUnit's extension
 * autodetection; active only when the test JVM has both {@code
 * -Djunit.jupiter.extensions.autodetection.enabled=true} and {@code -D}{@value #PROPERTY}{@code
 * =true}, for example through {@code JAVA_TOOL_OPTIONS}. It sets {@link
 * RootIdentity#VOLUME_IDENTITY_UNAVAILABLE} for the whole run.
 */
public final class NoVolumeIdentityExtension implements BeforeAllCallback {
  /** The system property that switches the seam on. */
  public static final String PROPERTY = "justsearch.test.noVolumeIdentity";

  @Override
  public void beforeAll(ExtensionContext context) {
    if (Boolean.getBoolean(PROPERTY)) {
      RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(true);
    }
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
