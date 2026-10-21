# Llama key assertion audit

Historical source comparison: auth baseline `f003a5c6476776ed2e56d9e0882f8bbaf55fc731` with initial ignored-key repair `dc1bcaa07a18351991fdd24c991ffb4963a3ca56`. The wrapper/#858 repair used review parent `0109762bc16244364dacdff4752a95521d8d9275`; the current review parent is merge `2b775d981` over main `4f4e20514ff82dd9d698661ef09dc646ee643311`. Subsequent wrapper and #858 cleanup oracle changes are documented in the changeset.

This is a source comparison, not D4 execution evidence. Java assertion invocations were tokenized with strings retained and comments/whitespace ignored. Nested assertions are counted individually; mocks and fixture handlers are reviewed in the changeset. No test or production file was edited for this audit.

| File | Base calls | Candidate calls | Unchanged calls |
| --- | ---: | ---: | ---: |
| modules/app-inference/src/test/java/io/justsearch/app/inference/CpuCrashClassificationTest.java | 3 | 3 | 3 |
| modules/app-inference/src/test/java/io/justsearch/app/inference/HealthyFakeLlamaServer.java | 0 | 0 | 0 |
| modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerShutdownTest.java | 20 | 20 | 19 |
| modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerCandidateContextTest.java | 46 | 46 | 46 |
| modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerTestAccess.java | 0 | 0 | 0 |
| modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLlamaAdoptionTest.java | 58 | 63 | 58 |
| modules/app-inference/src/test/java/io/justsearch/app/inference/OnlineModeOpsTest.java | 147 | 147 | 147 |
| modules/app-inference/src/test/java/io/justsearch/app/inference/OwnedStreamCancellationTest.java | 58 | 58 | 58 |
| modules/app-inference/src/test/java/io/justsearch/app/inference/TokenEndpointOpsTest.java | 40 | 40 | 40 |
| modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/NoDeviceCpuActivationTest.java | 12 | 13 | 11 |
| modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationCpuDevicePlanTest.java | 12 | 13 | 11 |
| modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationSelfTestStopFailureTest.java | 6 | 6 | 6 |
| modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerChildApiTest.java | 14 | 14 | 14 |
| modules/app-services/src/test/java/io/justsearch/app/services/chat/SlowStartFakeLlamaServer.java | 0 | 0 | 0 |
| modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java | 82 | 82 | 82 |
| modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatControllerTest.java | 42 | 42 | 41 |
| modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatOfflineWordingTest.java | 5 | 5 | 5 |
| modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatTokenProgressTest.java | 4 | 4 | 4 |
| modules/ui/src/test/java/io/justsearch/ui/api/ProxyStreamAcrossReconfigureTest.java | 10 | 10 | 10 |
| modules/ui/src/test/java/io/justsearch/ui/api/StreamingFakeLlamaServer.java | 0 | 0 | 0 |
| modules/ui/src/test/java/io/justsearch/ui/api/VduRequestActivityTest.java | 32 | 32 | 32 |

## Changed assertion invocations

### InferenceLifecycleManagerShutdownTest.java

Before:

```java
assertThrows ( java . lang . reflect . InvocationTargetException . class , ( ) - > start . invoke ( server , spec ( ) , Path . of ( "llama.log" ) , result , 9 9 9 1 ) )
```

After:

```java
assertThrows ( java . lang . reflect . InvocationTargetException . class , ( ) - > start . invoke ( server , spec ( ) , Path . of ( "llama.log" ) , result , new LlamaServerEndpoint ( 9 9 9 1 , null ) ) )
```

### ManagedLlamaAdoptionTest.java

After:

```java
assertTrue ( key ! = null & & key . matches ( "[A-Za-z0-9_-]{43}" ) , "every lost-bind attempt must carry a private environment credential" )
assertTrue ( spec . argv ( ) . stream ( ) . noneMatch ( arg - > arg . contains ( key ) ) )
assertThrows ( ModeTransitionException . class , ( ) - > { if ( recovery ) ops . waitForServerHealthOnce ( start ) ; else ops . waitForServerHealth ( start ) ; } )
assertEquals ( 2 , keys . size ( ) , "one launch and one retry" )
assertTrue ( ! keys . getFirst ( ) . equals ( keys . getLast ( ) ) , "retry must rotate the key" )
```

### NoDeviceCpuActivationTest.java

Before:

```java
assertEquals ( Map . of ( "CUDA_VISIBLE_DEVICES" , "-1" ) , captured . get ( ) . environment ( ) )
```

After:

```java
assertTrue ( privateKey ! = null & & privateKey . matches ( "[A-Za-z0-9_-]{43}" ) )
assertEquals ( Map . of ( "CUDA_VISIBLE_DEVICES" , "-1" ) , environment )
```

### RuntimeActivationCpuDevicePlanTest.java

Before:

```java
assertEquals ( Map . of ( "CUDA_VISIBLE_DEVICES" , "-1" ) , spec . environment ( ) )
```

After:

```java
assertTrue ( privateKey ! = null & & privateKey . matches ( "[A-Za-z0-9_-]{43}" ) )
assertEquals ( Map . of ( "CUDA_VISIBLE_DEVICES" , "-1" ) , environment )
```

### OpenAiCompatControllerTest.java

Before:

```java
assertThrows ( IllegalStateException . class , ( ) - > new OpenAiCompatController ( http , ( ) - > 8 0 8 1 , null ) . handleChatCompletions ( ctx ) )
```

After:

```java
assertThrows ( IllegalStateException . class , ( ) - > new OpenAiCompatController ( http , ( ) - > new io . justsearch . app . inference . LlamaServerEndpoint ( 8 0 8 1 , null ) , null ) . handleChatCompletions ( ctx ) )
```

## Execution evidence status

The orchestrator subsequently authorized running the unchanged official `run-evidence.mjs` under one outer Gradle slot and allowed its own detached before-state worktrees. All three producer invocations exited 0 and removed their before-state worktrees. The sealed artifacts are `llama-key-initial-auth.json` (15 candidate cases passed; 15 assertion failures on f003a5c64), `llama-key-cpu-environment.json` (5 passed; 5 assertion failures on f003a5c64), and `llama-key-ignored-key.json` (6 passed; 4 assertion failures and 2 preserved passing cases on 673b54a1b), in this directory. This source audit does not substitute for those execution artifacts. No evidence JSON was hand-authored or edited.

The two CPU environment checks retain exact equality to `{CUDA_VISIBLE_DEVICES=-1}` after removing and validating the newly required private key; these are classified New, not a pure adaptation. The shutdown assertion changes only its reflected endpoint argument, retaining the same expected InvocationTargetException, IOException cause and no-spawn oracle. OpenAiCompatControllerTest likewise changes only the endpoint supplied inside its existing assertThrows lambda. The only other removed-or-reencoded assertions are the two CPU whole-environment checks described above.

## Linux zombie wait fixture adaptation (#858)

Compared `ChatJobAccountingTest#selfTestChecksMembershipAfterObservedDescendantsDrain` at review parent `0109762bc16244364dacdff4752a95521d8d9275` with the current fixture. All four assertion statements are byte-identical after removing leading indentation:

```java
assertTrue(drained.get(), "the initially observed descendant exited during cleanup");
assertTrue(late.isAlive(), "the later scope member is still running");
assertFalse(result.exercised(), "a late scope member must prevent a successful self-test");
assertTrue(result.failure().contains("still running"), result.failure());
```

The member still exits after the post-stop snapshot and exposes a still-running late member. Only its exit trigger moves from an `onExit` subscription to the first liveness observation, so kernel polling and future waiting see the same exit facts. The original assertions still reject a self-test that skips the final membership read. The unchanged official producer records a passing preservation check on both parent production (with this fixture overlaid) and candidate production in `llama-key-drain-adaptation-before.json` and `llama-key-drain-adaptation.json`; no fail-before is claimed for this adaptation.

## Main integration audit (2026-10-21)

The merge of origin/main resolved both touched Java files automatically. Against
merged main, `VduRequestActivityTest.java` has exactly one changed line: the
proxy supplier wraps the existing upstream port in `LlamaServerEndpoint(port,
null)`. No assertion in that file differs from main. The table above remains a
historical comparison with the original auth baseline, rather than a claim
that later main assertions were authored on this branch. The warning repair
changes only the production concurrent-map iteration; it changes no test file.
