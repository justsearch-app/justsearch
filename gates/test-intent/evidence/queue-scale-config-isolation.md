# Queue-scale fixture config isolation

Task t-20261020-540095; builder session 01a1562b-b2e9-7510-832f-04ea6ad6da28.

The no-Engine probe below calls the real fixture publisher and teardown. Before the repair
(HEAD b1cacc99c), the null-root startup-failure path retained the installed global. After the
repair, both null and initialized predecessors are restored; a mocked shutdown failure still
propagates and also restores the predecessor. No Engine or database is created by this probe.
The burst test's inputs and assertions are unchanged.

Before (exit 1, intended assertion failure):

```text
variant=0 previous=null restored=false
Exception in thread "main" java.lang.AssertionError: fixture teardown leaked ConfigStore.GLOBAL
BUILD FAILED in 15s
```

After (exit 0, probe and selected enqueue-burst test in one invocation):

```text
variant=0 previous=null restored=true
variant=1 previous=initialized restored=true
variant=2 previous=initialized restored=true
BUILD SUCCESSFUL in 19s
```

The touched JUnit class executed 1 test, 0 failures, 0 errors, 0 skips (5.379 s).
The first probe setup attempt lacked a null-project guard for included builds; that setup
failure is excluded from leak evidence. The corrected init script is below.

Audit: searched every newly added test and all added test lines against merge-base f6a14b5a6
for direct/global publication and EngineTestHarness/WorkerBootFixture/TestResolvedConfigHelper
calls. IndexingJobsEnqueueBurstTest is the only new config publisher. The only other new
JVM-global mutation found is IndexingJobsChangeStreamLocaleTest's FORMAT locale change,
already restored in finally under a locale resource lock. No other config leak was found.
Restoration follows EngineRootConfigAuthorityTest's existing restoreGlobal pattern.

## Reproduce the scaffolding probe

Save these snippets under ignored tmp/builder/config-isolation, substituting that directory's
absolute path in the init script when running elsewhere. Invoke with JAVA_OPTS containing
-Djava.io.tmpdir=<that directory>, TMPDIR=<that directory>, and task-local JUSTSEARCH_HOME
and JUSTSEARCH_DATA_DIR. No JAVA_TOOL_OPTIONS is used.

```sh
js-gradle-slot ./gradlew :modules:app-engine:queueScaleIsolationProbe \
  -I "$PWD/tmp/builder/config-isolation/probe.init.gradle"
# After repair, also select only the touched class:
js-gradle-slot ./gradlew :modules:app-engine:queueScaleIsolationProbe \
  :modules:app-engine:test --tests io.justsearch.app.engine.IndexingJobsEnqueueBurstTest \
  -I "$PWD/tmp/builder/config-isolation/probe.init.gradle" -PskipWebBuild=true --console=plain
```

QueueScaleConfigIsolationProbe.java (scaffolding, not a product expectation):

```java
package io.justsearch.app.engine;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.nio.file.Path;
import java.util.Map;
public class QueueScaleConfigIsolationProbe {
  public static void main(String[] args) throws Exception {
    ConfigStore entering = ConfigStore.globalOrNull();
    try {
      for (int variant = 0; variant < 3; variant++) {
        boolean initialized = variant != 0;
        ConfigStore previous = initialized ? new ConfigStore(new ResolvedConfigBuilder().build()) : null;
        ConfigStore current = ConfigStore.globalOrNull();
        if (current != null) ConfigStore.restoreGlobal(current, previous);
        else if (previous != null) ConfigStore.setGlobal(previous);
        var fixture = new IndexingJobsEnqueueBurstTest();
        Path data = Path.of(args[0]).resolve(Boolean.toString(initialized));
        EngineTestHarness.publishConfig(data, data.resolve("index"), Map.of());
        if (variant == 2) {
          var root = org.mockito.Mockito.mock(EngineRoot.class);
          org.mockito.Mockito.when(root.admission()).thenThrow(new IllegalStateException("shutdown failure probe"));
          var field = IndexingJobsEnqueueBurstTest.class.getDeclaredField("root");
          field.setAccessible(true);
          field.set(fixture, root);
          try {
            fixture.tearDown();
            throw new AssertionError("shutdown failure did not propagate");
          } catch (IllegalStateException expected) {
            if (!expected.getMessage().equals("shutdown failure probe")) throw expected;
          }
        } else fixture.tearDown();
        System.out.println("variant=" + variant + " previous=" + (initialized ? "initialized" : "null") + " restored=" + (ConfigStore.globalOrNull() == previous));
        if (ConfigStore.globalOrNull() != previous) throw new AssertionError("fixture teardown leaked ConfigStore.GLOBAL");
      }
    } finally {
      ConfigStore current = ConfigStore.globalOrNull();
      if (current != null) ConfigStore.restoreGlobal(current, entering);
      else if (entering != null) ConfigStore.setGlobal(entering);
    }
  }
}
```

probe.init.gradle:

```groovy
gradle.projectsEvaluated {
  def p = gradle.rootProject.findProject(':modules:app-engine')
  if (p == null) return
  def ss = p.extensions.getByType(org.gradle.api.tasks.SourceSetContainer).getByName('test')
  def probeRoot = new File('/home/elias/dev/justsearch/.claude/worktrees/queue-scale/tmp/builder/config-isolation')
  p.tasks.register('queueScaleIsolationCompile', org.gradle.api.tasks.compile.JavaCompile) {
    dependsOn p.tasks.named('testClasses')
    source new File(probeRoot, 'QueueScaleConfigIsolationProbe.java')
    classpath = ss.runtimeClasspath
    destinationDirectory.set(new File(probeRoot, 'classes'))
  }
  p.tasks.register('queueScaleIsolationProbe', org.gradle.api.tasks.JavaExec) {
    dependsOn p.tasks.named('queueScaleIsolationCompile')
    classpath = ss.runtimeClasspath + p.files(new File(probeRoot, 'classes'))
    mainClass.set('io.justsearch.app.engine.QueueScaleConfigIsolationProbe')
    systemProperty 'java.io.tmpdir', probeRoot.absolutePath
    args new File(probeRoot, 'fixture').absolutePath
  }
}
```

Entry 30's refreshed Q27 receipt is queue-scale-config-isolation.json, produced by the unchanged
run-evidence.mjs against e4d7bbfb5. It reruns only IndexingJobsEnqueueBurstTest; the Q27 oracle
remains the held real subscription / 2,005 invisible enqueue rows check.

Final gate: exit 1, only test-intent/acceptance-stale remains. All existing acceptance records
are byte-for-byte unchanged. The evidence checkout is removed; ~/.config/justsearch is absent.
