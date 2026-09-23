/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.app.api.UiSettings;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.Map;
import java.util.Set;

/** Fixed composition-root collaborator for affected runtime components. */
public interface SettingsComponentComposer {
  /** Performs all fallible candidate work before the settings-file commitment point. */
  Prepared prepare(UiSettings candidate, ResolvedConfig desired,
      Map<String, Set<String>> affected);

  interface Prepared {
    /** Holds physical owner lifecycle locks before entering the publication write section. */
    default void withOwnerLocks(Runnable publication) {
      publication.run();
    }

    /** Final owner validation under the shared publication write lock, before file replacement. */
    void validate();

    /** Assignment-only publication after file replacement, under that same write lock. */
    void install();

    /** Delivers observation callbacks after the write lock has been released. */
    void notifyObservers();

    /** Retires old resources after a committed publication, outside publication locks. */
    void retire();

    /** Disposes a refused candidate before commitment. Never closes an uncertain candidate. */
    void abort();
  }
}
