/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import io.justsearch.indexerworker.services.LanguageUtils;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.memory.MemoryIndex;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SplitSurrogateExtractionTest {
  @TempDir Path tempDir;

  @Test
  void utf8PairsAtReadBoundariesSurvive() throws Exception {
    assertBoundaries(StandardCharsets.UTF_8, false);
  }

  @Test
  void utf8BomPairsAtReadBoundariesSurvive() throws Exception {
    assertBoundaries(StandardCharsets.UTF_8, true);
  }

  @Test
  void utf16LeBomPairsAtReadBoundariesSurvive() throws Exception {
    assertBoundaries(StandardCharsets.UTF_16LE, true);
  }

  @Test
  void utf16BeBomPairsAtReadBoundariesSurvive() throws Exception {
    assertBoundaries(StandardCharsets.UTF_16BE, true);
  }

  @Test
  void utf16LeWithoutBomPairsAtReadBoundariesSurvive() throws Exception {
    assertBoundaries(StandardCharsets.UTF_16LE, false);
  }

  @Test
  void utf16BeWithoutBomPairsAtReadBoundariesSurvive() throws Exception {
    assertBoundaries(StandardCharsets.UTF_16BE, false);
  }

  @Test
  void gb18030PairsAtReadBoundariesSurvive() throws Exception {
    // Enough Chinese text to establish GB18030 before the supplementary character.
    String prefix = "这是一个中文文本，今天我们在北京的图书馆阅读新的书籍。".repeat(900);
    for (int offset : List.of(4095, 8191, 20479)) {
      String expected = prefix.substring(0, offset) + "𠀀" + "这是结尾。";
      assertLocalExtraction("gb18030.txt", expected.getBytes(Charset.forName("GB18030")), expected);
    }
  }

  @Test
  void childProcessPairsAtReadBoundariesSurviveAndAreSearchable() throws Exception {
    try (var sandbox = child(TikaExtractionPolicy.defaults())) {
      for (Charset encoding : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE,
          StandardCharsets.UTF_16BE, Charset.forName("GB18030"))) {
        for (boolean bom : List.of(false, true)) {
          if (bom && encoding.name().equals("GB18030")) continue;
          for (int offset : List.of(4095, 8191, 20479)) {
            String expected = boundaryText(offset);
            // GB18030 needs a Chinese prefix to avoid a statistical UTF-8 misclassification.
            if (encoding.name().equals("GB18030")) {
              expected = "这是一个中文文本，今天我们在北京的图书馆阅读新的书籍。".repeat(900)
                  .substring(0, offset - 1) + " 😀target ending";
            }
            Path file = write("child.txt", ((bom ? "\uFEFF" : "") + expected).getBytes(encoding));
            String actual = sandbox.extract(file).result().content().strip();
            assertEquals(expected, actual, encoding + " BOM=" + bom + " offset=" + offset);
            assertSearchable(actual, "😀");
          }
        }
      }
    }
  }

  @Test
  void consecutivePairsAndEveryBoundaryInLargeFileSurvive() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int boundary = 4096; boundary <= 102400; boundary += 4096) {
      text.append("x".repeat(boundary - 1 - text.length())).append("😀𠀀𐐀");
    }
    String expected = text + " final marker";
    assertLocalExtraction("large.txt", expected.getBytes(StandardCharsets.UTF_8), expected);
    try (var sandbox = child(TikaExtractionPolicy.defaults())) {
      assertEquals(expected, sandbox.extract(write("large-child.txt",
          expected.getBytes(StandardCharsets.UTF_8))).result().content().strip());
    }
  }

  @Test
  void pairEndingAtEndOfInputSurvives() throws Exception {
    String expected = "x".repeat(4095) + "😀";
    assertLocalExtraction("end.txt", expected.getBytes(StandardCharsets.UTF_8), expected);
  }

  @Test
  void markdownJsonPythonAndJavascriptPairsSurvive() throws Exception {
    for (String extension : List.of("md", "json", "py", "js")) {
      String expected = boundaryText(4095);
      if (extension.equals("json")) {
        expected = "{\"text\":\"" + "x".repeat(4086) + "😀target ending\"}";
      }
      assertLocalExtraction("source." + extension, expected.getBytes(StandardCharsets.UTF_8), expected);
      try (var sandbox = child(TikaExtractionPolicy.defaults())) {
        assertEquals(expected, sandbox.extract(write("child." + extension,
            expected.getBytes(StandardCharsets.UTF_8))).result().content().strip());
      }
    }
  }

  @Test
  void plainPreviewCutsBeforePair() {
    String prefix = "x".repeat(4095);
    assertEquals(prefix, LanguageUtils.contentPreview(prefix + "😀ending", 4096, false));
  }

  @Test
  void markdownPreviewCutsBeforePair() {
    String prefix = "x".repeat(4095);
    assertEquals(prefix, LanguageUtils.contentPreview("# Heading\n\n" + prefix + "😀ending", 4096, true));
  }

  @Test
  void flatContentLimitCutsBeforePair() throws Exception {
    Path file = write("flat-limit.txt", ("x".repeat(47) + "😀ending").getBytes(StandardCharsets.UTF_8));
    var policy = TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(
        48, TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES));
    try (var extractor = new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy)) {
      forceFlatFallback(extractor);
      assertFlatLimit(extractor.extractArtifact(file), policy);
    }
  }

  @Test
  void flatChildContentLimitCutsBeforePair() throws Exception {
    var policy = TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(
        48, TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES));
    Path file = write("flat-child-limit.txt", ("x".repeat(47) + "😀ending").getBytes(StandardCharsets.UTF_8));
    // Force the production structured fallback in a helper using the real sandbox frames.
    try (var sandbox = new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
        PersistentExtractionSandboxTest.javaCommand(FlatExtractionChild.class), policy,
        OcrRoutingConfig.disabled(), Duration.ofSeconds(30), 1, 500, null)) {
      assertFlatLimit(sandbox.extract(file), policy);
    }
  }

  private static void assertFlatLimit(ExtractionArtifact artifact, TikaExtractionPolicy policy)
      throws Exception {
    assertEquals("x".repeat(47), artifact.result().content().strip());
    assertEquals(ExtractionStatus.SUCCESS_PARTIAL, artifact.status());
    assertTrue(artifact.truncated());
    artifact.validate(policy, "split-surrogate-flat-limit");
  }

  @Test
  void flatParseRecordDoesNotRetainPriorWriteLimit() throws Exception {
    var policy = TikaExtractionPolicy.defaults();
    var context = new org.apache.tika.parser.ParseContext();
    var previous = new org.apache.tika.parser.ParseRecord();
    previous.setWriteLimitReached(true);
    context.set(org.apache.tika.parser.ParseRecord.class, previous);
    try (var factory = new PreparedExtractionInput.Factory(); var flat = new ContentExtractor(48)) {
      for (String text : List.of("ordinary text", "short text")) {
        try (var input = factory.prepare(write("fresh-record.txt", text.getBytes(StandardCharsets.UTF_8)), policy)) {
          var budget = new EmbeddedResourceBudget(policy, input.size(), context, input.expansion());
          assertEquals(text, flat.extract(input, context, budget).content().strip());
          var record = context.get(org.apache.tika.parser.ParseRecord.class);
          assertFalse(record.isWriteLimitReached());
          record.setWriteLimitReached(true);
        }
      }
    }
  }

  private static void forceFlatFallback(PolicyDrivenTikaExtractor extractor) throws Exception {
    var structuredField = PolicyDrivenTikaExtractor.class.getDeclaredField("structuredExtractor");
    structuredField.setAccessible(true);
    var parser = org.mockito.Mockito.mock(org.apache.tika.parser.AutoDetectParser.class);
    org.mockito.Mockito.doThrow(new org.xml.sax.SAXException("force flat fallback"))
        .when(parser).parse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    var parserField = StructuredContentExtractor.class.getDeclaredField("parser");
    parserField.setAccessible(true);
    parserField.set(structuredField.get(extractor), parser);
  }

  @Test
  void structuredAndChildContentLimitsCutBeforePair() throws Exception {
    Path file = write("limited.txt", ("x".repeat(47) + "😀ending").getBytes(StandardCharsets.UTF_8));
    var policy = TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(
        48, TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES));
    try (var extractor = new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy);
        var sandbox = child(policy)) {
      for (var artifact : List.of(extractor.extractArtifact(file), sandbox.extract(file))) {
        assertEquals("x".repeat(47), artifact.result().content());
        assertTrue(artifact.truncated());
        assertEquals(ExtractionStatus.SUCCESS_PARTIAL, artifact.status());
        artifact.validate(policy, "split-surrogate-limit");
      }
    }
  }

  @Test
  void artifactContentLimitKeepsWholePairs() throws Exception {
    var policy = TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(
        48, TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES));
    var artifact = ExtractionArtifact.full(new ContentExtractor.ExtractionResult(
        "x".repeat(47) + "😀ending", null, "text/plain"), policy, "test", false);
    assertEquals("x".repeat(47), artifact.result().content());
    assertTrue(artifact.truncated());
    assertEquals(ExtractionStatus.SUCCESS_PARTIAL, artifact.status());
  }

  @Test
  void sharedGraphDoesNotLeakCarryBetweenSequentialOrConcurrentDocuments() throws Exception {
    Path first = write("first.txt", "first 😀 ending".getBytes(StandardCharsets.UTF_8));
    Path second = write("second.txt", "second 𠀀 ending".getBytes(StandardCharsets.UTF_8));
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor();
        var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var extractor : List.<ContentExtractorProvider>of(structured, flat)) {
        assertEquals("first 😀 ending", extractor.extract(first).content().strip());
        assertEquals("second 𠀀 ending", extractor.extract(second).content().strip());
        var tasks = new java.util.ArrayList<java.util.concurrent.Future<String>>();
        for (int i = 0; i < 12; i++) {
          Path file = i % 2 == 0 ? first : second;
          tasks.add(pool.submit(() -> extractor.extract(file).content().strip()));
        }
        for (int i = 0; i < tasks.size(); i++) {
          assertEquals(i % 2 == 0 ? "first 😀 ending" : "second 𠀀 ending", tasks.get(i).get());
        }
      }
    }
  }

  @Test
  void inlineMailWithZeroEmbeddedAllowanceKeepsOriginalParser() throws Exception {
    String body = "Inline mail 😀 body.";
    String mail = "From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Inline test\r\n"
        + "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n"
        + "Content-Transfer-Encoding: 8bit\r\n\r\n" + body;
    var base = TikaExtractionPolicy.defaults();
    var policy = new TikaExtractionPolicy(base.policyId(), base.maxExtractedChars(), base.maxInputBytes(),
        base.maxOfficeInputBytes(), base.maxMetadataEntries(), base.maxMetadataKeyChars(),
        base.maxMetadataValueChars(), 0, 0, base.maxCompressionRatio(),
        base.requireXmlEntitySafeTikaDefaults(), base.allowedMimeTypes(), base.excludedMimeTypes());
    try (var extractor = new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy);
        var sandbox = child(policy)) {
      Path file = write("inline.eml", mail.getBytes(StandardCharsets.UTF_8));
      for (var artifact : List.of(extractor.extractArtifact(file), sandbox.extract(file))) {
        assertTrue(artifact.result().content().contains(body), artifact.result().content());
        assertEquals(0, artifact.embeddedResourceCount());
        assertEquals(0, artifact.maxEmbeddedDepth());
      }
    }
    var graph = OwnedTikaParsers.create(TextNameMagicConflictDetector.wrapDefault());
    assertTrue(hasExactTextParser(graph), "MailContentHandler finds the original parser by exact class");
  }

  private static boolean hasExactTextParser(org.apache.tika.parser.Parser parser) {
    if (parser.getClass() == org.apache.tika.parser.csv.TextAndCSVParser.class) return true;
    if (parser instanceof org.apache.tika.parser.CompositeParser composite) {
      return composite.getAllComponentParsers().stream().anyMatch(SplitSurrogateExtractionTest::hasExactTextParser);
    }
    if (parser instanceof org.apache.tika.parser.ParserDecorator decorator) {
      return hasExactTextParser(decorator.getWrappedParser());
    }
    return false;
  }

  private void assertBoundaries(Charset encoding, boolean bom) throws Exception {
    for (int offset : List.of(4095, 8191, 20479)) {
      String expected = boundaryText(offset);
      assertLocalExtraction("boundary.txt", ((bom ? "\uFEFF" : "") + expected).getBytes(encoding), expected);
    }
  }

  private static String boundaryText(int offset) {
    return "Ordinary café text. ".repeat(1600).substring(0, offset - 1) + " 😀target ending";
  }

  private void assertLocalExtraction(String name, byte[] bytes, String expected) throws Exception {
    Path file = write(name, bytes);
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor()) {
      for (var extractor : List.<ContentExtractorProvider>of(structured, flat)) {
        String actual = extractor.extract(file).content().strip();
        assertEquals(expected, actual, name + " using " + extractor.getClass().getSimpleName());
        if (expected.contains("😀target")) assertSearchable(actual, "😀");
      }
    }
  }

  private static void assertSearchable(String actual, String token) throws Exception {
    try (var analyzer = new StandardAnalyzer()) {
      var index = new MemoryIndex();
      index.addField("content", actual, analyzer);
      assertTrue(index.search(new TermQuery(new Term("content", token))) > 0, "supplementary token must match");
      assertEquals(0, index.search(new TermQuery(new Term("content", token.replace("😀", "�")))));
    }
  }

  private static PersistentExtractionSandbox child(TikaExtractionPolicy policy) {
    return new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
        PersistentExtractionSandboxTest.javaCommand(ExtractionSandboxChild.class), policy,
        OcrRoutingConfig.disabled(), Duration.ofSeconds(30), 1, 500, null);
  }

  private Path write(String name, byte[] bytes) throws Exception {
    return Files.write(tempDir.resolve(name), bytes);
  }

  public static final class FlatExtractionChild {
    public static void main(String[] args) throws Exception {
      var protocolOut = System.out;
      System.setOut(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));
      ExtractionSandboxChild.initializeProcessBoundary(args);
      var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
      byte[] frame;
      while ((frame = SandboxFrames.read(System.in, SandboxFrames.MAX_FRAME_BYTES)) != null) {
        var request = mapper.readValue(new String(frame, StandardCharsets.UTF_8), SandboxExtractionRequest.class);
        var policy = request.policy();
        try (var extractor = new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy)) {
          forceFlatFallback(extractor);
          var artifact = extractor.extractArtifact(Path.of(request.path()));
          SandboxFrames.write(protocolOut, mapper.writeValueAsBytes(
              SandboxExtractionResponse.fromArtifact(request.requestId(), artifact)));
        }
      }
    }
  }
}
