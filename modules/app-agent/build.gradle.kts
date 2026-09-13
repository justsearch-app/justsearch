plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

dependencies {
  testImplementation(testFixtures(project(":modules:core")))
  api(project(":modules:app-agent-api"))

  api(project(":modules:app-api"))
  // Tempdoc 883 decision 3 — ContextBudget + TokenEstimation live in modules/core (a leaf, no
  // project deps, so this cannot cycle). `api` because the budget appears in the constructor
  // signatures of the tools this module exports.
  api(project(":modules:core"))
  implementation(project(":modules:configuration"))
  api(project(":modules:telemetry"))

  api(libs.jackson.databind)
  implementation(libs.jackson.core)
  api(libs.opentelemetry.api)
  implementation(libs.slf4j.api)

  // Tempdoc 834 §1.3 — TEST scope only. The run observation substrate lives in app-observability;
  // main code here publishes through the RunObservation SPI and gains NO dependency on it (§1.4's
  // "no new edge"). The attach/park tests drive the REAL substrate rather than a double, because a
  // double for evict-on-throw + bounded replay would reimplement the mechanism they exist to pin.
  //
  // Declared HERE rather than inside the jvm-test-suite block below on purpose: in that DSL the
  // configuration is spelled `implementation`, and scripts/architecture/module-deps.mjs keys off the
  // configuration NAME (`PRODUCTION_CONFIGS` vs `TEST_CONFIGS`) — so the suite-block form is
  // published as a PRODUCTION edge in the canonical architecture doc's dependency graph, which
  // would state the opposite of what this dependency is. `testImplementation` puts it in the doc's
  // "test-only coupling" section, where it belongs.
  testImplementation(project(":modules:app-observability"))
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(testFixtures(project(":modules:configuration")))
        implementation(testFixtures(project(":modules:telemetry")))
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        // Tempdoc 883 decision 3 — AgentContextBudgetsTest asserts that an operator completion cap
        // the window cannot afford is REPORTED, not silently reduced, so it captures the log.
        implementation(libs.logback.classic)
        implementation(libs.logback.core)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
        // Slice 487 Phase 1.7 audit-test (post-impl fix A2): ArchUnit-based
        // assertion that no class in modules/app-agent calls
        // OperationDispatcher.dispatch directly (except the documented legacy
        // fallback in AgentLoopService.dispatchToolCall). Bytecode-level check
        // — name-independent, so a future field rename does not silently break
        // the gate.
        implementation(libs.archunit.junit5)
      }
      targets {
        all {
          testTask.configure {
            jvmArgs("-Dnet.bytebuddy.experimental=true")
            // Tempdoc 577 §2.12 Move 2 — the budget gate falls through immediately under test (0s
            // timeout), so an interactive budget-exhaustion test exercises exactly the legacy
            // finalize-else-error fallback (plus the two observable park events) without blocking.
            // The gate's CONTINUE/STOP resolution is unit-tested directly via AgentSession.
            systemProperty("justsearch.agent.budgetGateTimeoutSec", "0")
            // Tempdoc 577 §2.14 Root II — likewise the context gate falls through immediately under
            // test (0s ⇒ CONTINUE), so a high-context-pressure run never blocks the suite.
            systemProperty("justsearch.agent.contextGateTimeoutSec", "0")
            // Tempdoc 577 §2.14 Root I — the zero-observer park also falls through immediately (0s),
            // so a Watch test with no subscribed observer never blocks the suite.
            systemProperty("justsearch.agent.zeroObserverParkTimeoutSec", "0")
          }
        }
      }
    }
  }
}
