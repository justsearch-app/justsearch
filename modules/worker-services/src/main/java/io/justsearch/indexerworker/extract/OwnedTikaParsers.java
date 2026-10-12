/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.parser.AutoDetectParser;

/** Tika's external OCR parser joins a directory with a fixed executable name. Exclude that
 * independent launch path: the owned PDF engine does OCR, and the recorded runtime checks the
 * exact executable once. Parser configuration remains independent of capability/probe seams. */
final class OwnedTikaParsers {
  private OwnedTikaParsers() {}

  static AutoDetectParser create(Detector detector) {
    TikaOcrRuntime.resolve();
    return new AutoDetectParser(detector, Configuration.CONFIG.getParser());
  }

  private static final class Configuration {
    private static final TikaConfig CONFIG = create();

    private static TikaConfig create() {
      String xml = """
          <properties><parsers><parser class="org.apache.tika.parser.DefaultParser">
          <parser-exclude class="org.apache.tika.parser.ocr.TesseractOCRParser"/>
          </parser></parsers></properties>
          """;
      try {
        return new TikaConfig(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
      } catch (Exception e) { throw new IllegalStateException("Cannot configure owned Tika OCR", e); }
    }
  }
}
