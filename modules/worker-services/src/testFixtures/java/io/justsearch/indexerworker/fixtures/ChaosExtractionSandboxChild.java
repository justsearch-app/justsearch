/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.fixtures;

import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.ExtractionSandboxChild;
import io.justsearch.indexerworker.extract.OcrRoutingConfig;
import io.justsearch.indexerworker.extract.PolicyDrivenTikaExtractor;
import io.justsearch.indexerworker.extract.SandboxExtractionRequest;
import io.justsearch.indexerworker.extract.SandboxExtractionResponse;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chaos-tier stub parser for the extraction sandbox pool (tempdoc 885 item 14).
 *
 * <p>Launched by a real Worker through the production
 * {@code JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND} operator override, so the <b>parent</b> under test
 * is production {@code PersistentExtractionSandbox} code and only the parser's behaviour is
 * substituted. It exists because no real input wedges a parser: the tempdoc-410 adversarial corpus
 * fails fast, so "a wedged parser" has to be synthesised.
 *
 * <p>Behaviour is selected by the requested file's name, so the file that follows a failure lands
 * on a genuinely fresh child of the same stub:
 *
 * <ul>
 *   <li>{@code chaos-hang} — never returns, and never reads stdin again (the shape a wedged
 *       native parser has: it ignores interruption and the pipe stays open)
 *   <li>{@code chaos-crash} — {@code System.exit(3)} mid-request
 *   <li>{@code chaos-oom} — allocates until the child heap is exhausted
 *   <li>anything else — the <b>real</b> {@link PolicyDrivenTikaExtractor}, so "the next file
 *       extracts normally" is a real extraction, not a canned answer
 * </ul>
 *
 * <p>Two things are deliberately production code rather than copies: the orphan gate
 * ({@link ExtractionSandboxChild#initializeProcessBoundary}) and the response records. The frame codec is
 * re-implemented here on purpose — an independent implementation of the wire format is stronger
 * evidence that the format is real than reusing the same codec on both ends would be.
 *
 * <p><b>Why it lives in worker-services' test fixtures</b> (lane F stage A item A12 closure): it
 * substitutes a parser inside {@code PersistentExtractionSandbox}, which is worker-services' own
 * code, and it now has two consumers in two different modules — the in-process containment test
 * ({@code io.justsearch.app.engine.EngineExtractionSandboxChaosTest}, {@code @Tag("stress")}) and
 * the orphan-reaping test ({@code io.justsearch.systemtests.chaos.ExtractionSandboxOrphanE2ETest},
 * which needs a killable parent and so keeps a spawned Head). A published test-fixtures artifact is
 * the only home both can reach; its previous home was {@code system-tests}' {@code systemTest}
 * source set, which neither could.
 *
 * <p>Both consumers point {@code JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND} at
 * {@code java @argfile <thisClass>} using their own JVM's {@code java.class.path}, so this class has
 * to be on the <em>runtime</em> classpath of whichever JVM spawns the sandbox.
 */
public final class ChaosExtractionSandboxChild {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private ChaosExtractionSandboxChild() {}

  public static void main(String[] args) throws Exception {
    PrintStream protocolOut = System.out;
    System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
    ExtractionSandboxChild.initializeProcessBoundary(args);

    DataInputStream in = new DataInputStream(System.in);
    List<byte[]> ballast = new ArrayList<>();
    byte[] frame;
    while ((frame = readFrame(in)) != null) {
      SandboxExtractionRequest request =
          MAPPER.readValue(new String(frame, StandardCharsets.UTF_8), SandboxExtractionRequest.class);
      Path file = Path.of(request.path());
      String name = file.getFileName().toString();
      System.out.println("chaos child handling " + name);

      if (name.contains("native-descendant")) {
        Process nativeChild = new ProcessBuilder("ping.exe", "-t", "127.0.0.1")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!nativeChild.isAlive()) throw new IllegalStateException("native fixture did not start");
        Path pidTemp = Path.of(request.path() + ".pid.tmp");
        java.nio.file.Files.writeString(pidTemp, Long.toString(nativeChild.pid()));
        java.nio.file.Files.move(pidTemp, Path.of(request.path() + ".pid"));
      }
      if (name.contains("chaos-hang")) {
        String entered = System.getenv("JUSTSEARCH_PROCESSING_TEST_ENTERED");
        if (entered != null && !entered.isBlank()) {
          java.nio.file.Files.writeString(Path.of(entered), request.path());
        }
        while (true) {
          LockSupport.parkNanos(1_000_000_000L);
        }
      }
      if (name.contains("chaos-crash")) {
        System.exit(3);
      }
      if (name.contains("chaos-oom")) {
        while (true) {
          ballast.add(new byte[8 * 1024 * 1024]);
        }
      }

      TikaExtractionPolicy policy =
          request.policy() == null ? TikaExtractionPolicy.defaults() : request.policy();
      OcrRoutingConfig ocrConfig =
          request.ocrConfig() == null ? OcrRoutingConfig.disabled() : request.ocrConfig();
      SandboxExtractionResponse response;
      try {
        ExtractionArtifact artifact =
            new PolicyDrivenTikaExtractor(ExtractionSandboxChild::openOcrPool, policy, ocrConfig).extractArtifact(file);
        response = SandboxExtractionResponse.fromArtifact(artifact);
      } catch (Exception e) {
        response =
            SandboxExtractionResponse.failed(
                io.justsearch.indexerworker.extract.ExtractionStatus.FAILED,
                policy,
                "chaos-child",
                "Chaos stub parser failed",
                "PARSER_FAILED");
      }
      writeFrame(protocolOut, MAPPER.writeValueAsBytes(response));
    }
  }

  private static byte[] readFrame(DataInputStream in) throws IOException {
    int first = in.read();
    if (first < 0) {
      return null;
    }
    int length = (first << 24) | (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8)
        | in.readUnsignedByte();
    byte[] payload = new byte[length];
    in.readFully(payload);
    return payload;
  }

  private static void writeFrame(OutputStream out, byte[] payload) throws IOException {
    int length = payload.length;
    out.write((length >>> 24) & 0xFF);
    out.write((length >>> 16) & 0xFF);
    out.write((length >>> 8) & 0xFF);
    out.write(length & 0xFF);
    out.write(payload);
    out.flush();
  }
}
