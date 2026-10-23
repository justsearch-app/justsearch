#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# On-demand #739 fixture. All generated data stays in build/; no release rebuild is needed.
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${TMPDIR:?Set TMPDIR to a directory outside /tmp}"
mkdir -p "$TMPDIR"
fixture_init=$(mktemp "$TMPDIR/upgrade-030-scale.XXXXXX.gradle")
trap 'rm -f "$fixture_init"' EXIT
cat > "$fixture_init" <<'GRADLE'
allprojects {
  if (path == ':modules:indexer-worker') {
    afterEvaluate {
      tasks.register('generateRelease030Scale', JavaExec) {
        dependsOn tasks.named('testClasses')
        classpath = sourceSets.test.runtimeClasspath
        mainClass = 'io.justsearch.indexerworker.server.Release030ScaleGenerator'
        args rootProject.layout.buildDirectory.dir('upgrade-030-scale').get().asFile.absolutePath
        jvmArgs '-Djava.io.tmpdir=' + System.getenv('TMPDIR')
        maxHeapSize = '1g'
      }
    }
  }
}
GRADLE
unset JAVA_TOOL_OPTIONS
export JDK_JAVA_OPTIONS="${JDK_JAVA_OPTIONS:+$JDK_JAVA_OPTIONS }-Djava.io.tmpdir=$TMPDIR"
js-gradle-slot ./gradlew :modules:indexer-worker:generateRelease030Scale --init-script "$fixture_init" --continue -PskipWebBuild=true
