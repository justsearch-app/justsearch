/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DevLauncherSpladeOptInTest {
  @Test void documentationExplainsInstalledButOptInExecution() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.exists(root.resolve("settings.gradle.kts"))) root = root.getParent();
    String help = Files.readString(root.resolve("SSOT/docs/help/ai-features.md"));
    assertTrue(help.contains("JUSTSEARCH_SPLADE_ENABLED=true"), "S18: document how to enable inference");
    assertTrue(help.contains("restart JustSearch"), "S18: enablement applies at the next start");
    assertTrue(help.contains("Terminal failures keep their existing retry rules"));
    String env = Files.readString(root.resolve("docs/reference/configuration/environment-variables.md"));
    assertTrue(env.contains("justsearch.splade.enabled"));
    assertTrue(env.contains("explicit false vetoes chunk demand"));
    String owners = Files.readString(root.resolve("docs/reference/configuration/runtime-config-ownership-matrix.md"));
    assertTrue(owners.contains("default (off unless chunk SPLADE requests it"));
    String throughput = Files.readString(root.resolve("docs/reference/performance/indexing-throughput.md"));
    assertTrue(throughput.contains("its separate inference lanes are opt-in"));
  }

  @Test void modelDiscoveryDoesNotInjectDemandOrOverrideAmbientValues(@TempDir Path temp) throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.exists(root.resolve("settings.gradle.kts"))) root = root.getParent();
    String script = """
        const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
        const assert = require('node:assert/strict');
        const source = fs.readFileSync(process.argv[1], 'utf8');
        const body = source.match(/function resolveAiDevEnv\\([^]*?\\n\\}/)[0];
        const repoRoot = process.argv[2], mainRepoRoot = repoRoot;
        const model = path.join(repoRoot, 'models/splade/naver-splade-v3');
        fs.mkdirSync(model, {recursive:true});
        for (const file of ['model.onnx','tokenizer.json','vocab.txt']) fs.writeFileSync(path.join(model,file),'fixture');
        for (const explicit of [undefined, 'true', 'false']) {
          const ambient = explicit === undefined ? {} : {JUSTSEARCH_SPLADE_ENABLED:explicit};
          const resolve = vm.runInNewContext('(' + body + ')', {fs,path,repoRoot,mainRepoRoot,
            process:{env:ambient}, ensureSharedCuda12Staged:()=>{},
            require:()=>({resolveDevRuntime:()=>({source:'none',valid:false})})});
          const env = resolve();
          assert.equal(env.JUSTSEARCH_SPLADE_MODEL_PATH, model);
          assert.equal(env.JUSTSEARCH_SPLADE_ENABLED, undefined, 'S19: discovery must not inject demand');
          assert.equal({...ambient,...env}.JUSTSEARCH_SPLADE_ENABLED, explicit, 'S19: ambient value survives launcher overlay');
        }
        """;
    Path output = temp.resolve("node-output.txt");
    var process = new ProcessBuilder("node", "-e", script,
        root.resolve("scripts/dev/dev-runner.cjs").toString(), temp.resolve("repo").toString())
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS), "isolated launcher function must finish");
      assertEquals(0, process.exitValue(), Files.readString(output));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "owned Node process must retire");
      }
    }
  }
}
