/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractEncodingDetectorParser;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.CompositeParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ParserDecorator;

/** Shared parser graph with worker-owned OCR and text-decoding policy.
 *
 * <p>Tika's external OCR parser joins a directory with a fixed executable name. Exclude that
 * independent launch path: the owned PDF engine does OCR, and the recorded runtime checks the
 * exact executable once. Parser configuration remains independent of capability/probe seams. */
final class OwnedTikaParsers {
  private OwnedTikaParsers() {}

  static AutoDetectParser create(Detector detector) {
    TikaOcrRuntime.resolve();
    return new AutoDetectParser(detector, Configuration.CONFIG.getParser());
  }

  private static void configureHtmlParsers(Parser parser, EncodingDetector encodingDetector) {
    if (parser instanceof CompositeParser composite) {
      for (Parser child : composite.getAllComponentParsers()) configureHtmlParsers(child, encodingDetector);
    } else if (parser instanceof ParserDecorator decorator) {
      configureHtmlParsers(decorator.getWrappedParser(), encodingDetector);
    } else if (parser instanceof AbstractEncodingDetectorParser text
        && text.getSupportedTypes(new ParseContext()).contains(MediaType.TEXT_HTML)) {
      // Inline MIME bodies call the HTML leaf with empty metadata. Its parser identity supplies
      // the missing type so HTML declarations still precede statistical guessing on that path.
      text.setEncodingDetector((input, metadata) -> {
        if (metadata.get(Metadata.CONTENT_TYPE) == null) metadata.set(Metadata.CONTENT_TYPE, "text/html");
        return encodingDetector.detect(input, metadata);
      });
    }
  }

  private static final class Configuration {
    private static final TikaConfig CONFIG = create();

    private static TikaConfig create() {
      String xml = """
          <properties>
          <encodingDetectors>
            <encodingDetector class="%s">
              <encodingDetector class="org.apache.tika.parser.html.charsetdetector.StandardHtmlEncodingDetector"/>
              <encodingDetector class="org.apache.tika.detect.DefaultEncodingDetector"/>
            </encodingDetector>
          </encodingDetectors>
          <parsers><parser class="org.apache.tika.parser.DefaultParser">
          <parser-exclude class="org.apache.tika.parser.ocr.TesseractOCRParser"/>
          </parser>
          <parser class="org.apache.tika.parser.csv.SurrogateSafeTextAndCSVParser"/>
          </parsers></properties>
          """.formatted(OwnedTextEncodingDetector.class.getName());
      try {
        TikaConfig config = new TikaConfig(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        configureHtmlParsers(config.getParser(), config.getEncodingDetector());
        return config;
      } catch (Exception e) { throw new IllegalStateException("Cannot configure owned Tika parsers", e); }
    }
  }
}
