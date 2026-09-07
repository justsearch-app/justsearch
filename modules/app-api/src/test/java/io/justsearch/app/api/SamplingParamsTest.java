package io.justsearch.app.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SamplingParamsTest {

  @Test
  void twoArgConstructorSetsToolChoiceNull() {
    var params = new SamplingParams(0.5, 0.9);
    assertNull(params.toolChoice());
  }

  @Test
  void threeArgConstructorAcceptsValidToolChoices() {
    assertEquals("required", new SamplingParams(0.5, 0.9, "required").toolChoice());
    assertEquals("auto", new SamplingParams(0.5, 0.9, "auto").toolChoice());
    assertEquals("none", new SamplingParams(0.5, 0.9, "none").toolChoice());
    assertNull(new SamplingParams(0.5, 0.9, null).toolChoice());
  }

  @Test
  void invalidToolChoiceThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new SamplingParams(0.5, 0.9, "always"));
  }

  @Test
  void withToolChoiceReturnsCopyWithOverride() {
    var base = SamplingParams.AGENT;
    var forced = base.withToolChoice("required");
    assertEquals("required", forced.toolChoice());
    assertEquals(base.temperature(), forced.temperature());
    assertEquals(base.topP(), forced.topP());
    assertNull(base.toolChoice(), "Original must be unchanged");
    assertNull(forced.grammar(), "Grammar must be null when not set");
  }

  @Test
  void presetsHaveNullToolChoice() {
    assertNull(SamplingParams.AGENT.toolChoice());
    assertNull(SamplingParams.THINKING.toolChoice());
    assertNull(SamplingParams.DETERMINISTIC.toolChoice());
    assertNull(SamplingParams.VDU.toolChoice());
  }

  @Test
  void withGrammarReturnsCopyWithOverride() {
    var base = SamplingParams.AGENT;
    var constrained = base.withGrammar("root ::= \"ok\"");
    assertEquals("root ::= \"ok\"", constrained.grammar());
    assertEquals(base.temperature(), constrained.temperature());
    assertEquals(base.topP(), constrained.topP());
    assertNull(base.grammar(), "Original must be unchanged");
  }

  @Test
  void withToolChoicePreservesGrammar() {
    var constrained = SamplingParams.AGENT
        .withGrammar("root ::= \"ok\"")
        .withToolChoice("required");
    assertEquals("required", constrained.toolChoice());
    assertEquals("root ::= \"ok\"", constrained.grammar(), "Grammar must survive withToolChoice");
  }

  @Test
  void withGrammarPreservesToolChoice() {
    var forced = SamplingParams.AGENT.withToolChoice("required");
    var constrained = forced.withGrammar("root ::= \"ok\"");
    assertEquals("required", constrained.toolChoice(), "toolChoice must survive withGrammar");
    assertEquals("root ::= \"ok\"", constrained.grammar());
  }

  @Test
  void presetsHaveNullGrammar() {
    assertNull(SamplingParams.AGENT.grammar());
    assertNull(SamplingParams.THINKING.grammar());
    assertNull(SamplingParams.DETERMINISTIC.grammar());
    assertNull(SamplingParams.VDU.grammar());
  }

  @Test
  void withEnableThinkingReturnsCopyWithOverride() {
    var base = SamplingParams.AGENT;
    var suppressed = base.withEnableThinking(false);
    assertEquals(false, suppressed.enableThinking());
    assertEquals(base.temperature(), suppressed.temperature());
    assertEquals(base.topP(), suppressed.topP());
    assertNull(base.enableThinking(), "Original must be unchanged");
  }

  @Test
  void presetsHaveExpectedEnableThinking() {
    // Null = "let the server decide": the calls that are allowed to think.
    assertNull(SamplingParams.AGENT.enableThinking());
    assertNull(SamplingParams.THINKING.enableThinking());
    // VDU explicitly disables thinking — output goes to reasoning_content (lost) otherwise
    assertEquals(false, SamplingParams.VDU.enableThinking());
    assertEquals(false, SamplingParams.VDU_PROBE.enableThinking());
    // DETERMINISTIC is the pipeline's mechanical preset (query rewrite, QU, filter normalization,
    // context sufficiency, query expansion, section summarize). Since the server-wide reasoning
    // budget is on by default, a null here would make every one of those calls think — measured at
    // 95 reasoning activations across 101 completion requests in one rag-ask round (tempdoc 835
    // §10f). Thinking for answers, not for plumbing.
    assertEquals(false, SamplingParams.DETERMINISTIC.enableThinking());
  }

  @Test
  void withEnableThinkingPreservesOtherFields() {
    var base = SamplingParams.AGENT
        .withToolChoice("required")
        .withGrammar("root ::= \"ok\"");
    var suppressed = base.withEnableThinking(false);
    assertEquals("required", suppressed.toolChoice(), "toolChoice must survive withEnableThinking");
    assertEquals("root ::= \"ok\"", suppressed.grammar(), "grammar must survive withEnableThinking");
    assertEquals(base.temperature(), suppressed.temperature());
    assertEquals(base.topP(), suppressed.topP());
  }

  // ==================== seed (lane F PR 0b) ====================

  @Test
  void seedIsNullOnEveryPreset() {
    // Null = omit the field on the wire, which is what every caller got before the component
    // existed. A preset that quietly carried a seed would make every shipped call reproducible-
    // looking without anyone asking for it.
    assertNull(SamplingParams.AGENT.seed());
    assertNull(SamplingParams.THINKING.seed());
    assertNull(SamplingParams.DETERMINISTIC.seed());
    assertNull(SamplingParams.VDU.seed());
    assertNull(SamplingParams.VDU_PROBE.seed());
  }

  @Test
  void withSeedRoundTripsAndPreservesEveryOtherField() {
    var base = SamplingParams.AGENT
        .withToolChoice("required")
        .withGrammar("root ::= \"ok\"")
        .withEnableThinking(false)
        .withResponseFormat(java.util.Map.of("type", "json_object"));
    var seeded = base.withSeed(20260907L);

    assertEquals(20260907L, seeded.seed());
    assertEquals(base.temperature(), seeded.temperature());
    assertEquals(base.topP(), seeded.topP());
    assertEquals("required", seeded.toolChoice());
    assertEquals("root ::= \"ok\"", seeded.grammar());
    assertEquals(false, seeded.enableThinking());
    assertEquals(java.util.Map.of("type", "json_object"), seeded.responseFormat());
    assertNull(base.seed(), "Original must be unchanged");
  }

  @Test
  void seedSurvivesTheOtherWithers() {
    // The regression this pins: every with* rebuilds the record positionally, so one that forgot
    // the new component would silently drop a pinned seed on the next forced-tool turn.
    var seeded = SamplingParams.AGENT.withSeed(42L);
    assertEquals(42L, seeded.withToolChoice("required").seed());
    assertEquals(42L, seeded.withGrammar("root ::= \"x\"").seed());
    assertEquals(42L, seeded.withEnableThinking(false).seed());
    assertEquals(42L, seeded.withResponseFormat(java.util.Map.of("type", "json_object")).seed());
  }

  @Test
  void backCompatConstructorLeavesSeedNull() {
    var params = new SamplingParams(0.5, 0.9, null, null, null, null);
    assertNull(params.seed());
  }
}
