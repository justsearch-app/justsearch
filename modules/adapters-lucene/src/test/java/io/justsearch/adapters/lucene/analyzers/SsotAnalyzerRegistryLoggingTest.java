/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.analyzers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SsotAnalyzerRegistryLoggingTest {
  @Test
  void manyBundledCatalogConstructionsWarnOnce() throws Exception {
    String registryName = SsotAnalyzerRegistry.class.getName();
    Logger logger = (Logger) LoggerFactory.getLogger(registryName);
    Level previousLevel = logger.getLevel();
    ListAppender<ILoggingEvent> events = new ListAppender<>();
    events.start();
    logger.addAppender(events);
    logger.setLevel(Level.WARN);
    // A fresh registry class isolates its process-lifetime flag from other tests without resetting it.
    URL classes = SsotAnalyzerRegistry.class.getProtectionDomain().getCodeSource().getLocation();
    try (var loader = new URLClassLoader(new URL[] {classes}, getClass().getClassLoader()) {
      @Override
      protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.equals(registryName) || name.startsWith(registryName + "$")) {
          synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
              loaded = findClass(name);
            }
            if (resolve) {
              resolveClass(loaded);
            }
            return loaded;
          }
        }
        return super.loadClass(name, resolve);
      }
    }) {
      var constructor = loader.loadClass(registryName).getDeclaredConstructor(File.class);
      constructor.setAccessible(true);
      for (int i = 0; i < 64; i++) {
        constructor.newInstance((File) null);
      }
      assertEquals(
          List.of("Using bundled analyzers catalog (SSOT repo root not found). "
              + "This is expected in packaged deployments but may indicate misconfiguration in development. "
              + "Loaded from classpath: /SSOT/catalogs/analyzers.v1.json"),
          events.list.stream()
              .filter(event -> event.getLevel() == Level.WARN)
              .map(ILoggingEvent::getFormattedMessage)
              .toList());
    } finally {
      logger.setLevel(previousLevel);
      logger.detachAppender(events);
      events.stop();
    }
  }
}
