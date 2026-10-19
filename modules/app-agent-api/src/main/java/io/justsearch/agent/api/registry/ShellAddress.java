/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.Optional;

/**
 * Serialized address pointing at one of two things in the chrome:
 * {@link Navigation a surface} (with restoration state) or {@link Invocation
 * an Operation invocation} (with args and an optional confirmation token).
 *
 * <p>Per slice 489 §4 — the same address can be expressed as a URL string,
 * a clickable button binding, an entry in browser history, a bookmark, an
 * external-app deep-link, an E2E test fixture, or an LLM-emitted Markdown
 * link (slice 491 owns the LLM-emission dispatch path). All transports
 * produce a {@code ShellAddress}; the FE router (slice 489 §7) is the single
 * consumer.
 *
 * <p>Per slice 489 §13 anti-pattern #2: {@link Navigation} and
 * {@link Invocation} stay distinct (not collapsed into a single record with
 * optional fields). They share the address abstraction but have different
 * lifetime semantics:
 *
 * <ul>
 *   <li>{@link Navigation} is idempotent and reloadable. Refresh → same view,
 *       same state, no side effect.
 *   <li>{@link Invocation} is one-shot. Re-dispatch re-runs the Operation
 *       (subject to {@code idempotencyKey} semantics on
 *       {@code OperationInvocationRequest}).
 * </ul>
 *
 * <p>The shell-address grammar (slice 489 §7.1):
 *
 * <pre>
 *   justsearch://surface/&lt;surfaceId&gt;[?k=v&amp;...]    → Navigation
 *   justsearch://op/&lt;opId&gt;[?argName=value&amp;...]   → Invocation
 *   justsearch://query?q=&lt;text&gt;[&amp;k=v&amp;...]       → Query
 *   justsearch://answer?q=&lt;prompt&gt;[&amp;shape=&lt;id&gt;] → Answer
 * </pre>
 *
 * <p>548 §4.5: {@link Answer} is a first-class answer verb the agent emits
 * ({@code justsearch://answer?q=…}). Distinct resolution model: it resolves an AI
 * <em>shape</em> (default {@code core.rag-ask}) rather than a surface/op catalog id; the FE
 * router lowers it to an activation of the shape-hosting chat surface. Like {@link Navigation}
 * it is forwarded to the FE by the backend.
 *
 * <p>548 S4-A (§4.5): {@link Query} is a first-class search verb the agent emits
 * ({@code justsearch://query?q=…}) rather than spelling a search as a Navigation to
 * the search surface. It carries free query text (not a catalog id, so it lives in the
 * {@code q} param, not the path) plus optional refinement {@link StateSnapshot state}.
 * It is idempotent + reloadable like {@link Navigation}: the FE router lowers it to a
 * search-surface activation, and the backend forwards it to the FE the same way a
 * Navigation is forwarded.
 *
 * <p>This Java record is the canonical model for tests + future server-side
 * validation. The FE TS counterpart in {@code modules/ui-web/src/api/url-router/}
 * shares the grammar by hand — the wire-contract pipeline integration
 * ({@code contracts/wire/}) is a follow-up if the grammar grows.
 */
public sealed interface ShellAddress
    permits
        ShellAddress.Navigation,
        ShellAddress.Invocation,
        ShellAddress.Query,
        ShellAddress.Answer {

  /**
   * Address pointing at a chrome surface with optional restoration state.
   *
   * @param target the {@link SurfaceRef} the chrome resolves through its
   *     {@code SurfaceCatalog}
   * @param state the {@link StateSnapshot} the surface restores on activation
   *     (use {@link StateSnapshot#empty()} for "default view")
   */
  record Navigation(SurfaceRef target, StateSnapshot state) implements ShellAddress {

    /** Wire discriminator. Read by the FE IntentRouter to dispatch by address kind. */
    public static final String KIND = "navigate";

    public Navigation {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(state, "state");
    }

    @JsonProperty("kind")
    public String kind() {
      return KIND;
    }
  }

  /**
   * Address pointing at an Operation invocation with args and an optional
   * confirmation token.
   *
   * @param target the {@link OperationRef} the dispatcher resolves through its
   *     {@code OperationCatalog}
   * @param argsJson the args payload as JSON source text — the same shape
   *     {@code OperationInvocationRequest.args} carries. Keeping the carrier
   *     as a {@code String} mirrors {@code Interface.inputs}'s convention
   *     (annotations-only API, no Jackson databind dep)
   * @param confirmationToken optional caller-supplied confirmation token (for
   *     HIGH-risk operations with {@code ConfirmStrategy.Typed}). Empty for
   *     operations that don't require confirmation
   */
  record Invocation(OperationRef target, String argsJson, Optional<String> confirmationToken)
      implements ShellAddress {

    /** Wire discriminator. Read by the FE IntentRouter to dispatch by address kind. */
    public static final String KIND = "invoke";

    public Invocation {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(argsJson, "argsJson");
      Objects.requireNonNull(confirmationToken, "confirmationToken");
    }

    @JsonProperty("kind")
    public String kind() {
      return KIND;
    }

    /** Convenience: invocation with no confirmation token (the common case). */
    public static Invocation of(OperationRef target, String argsJson) {
      return new Invocation(target, argsJson, Optional.empty());
    }
  }

  /**
   * Address expressing a search verb — "run a search for {@code text}". The query text is
   * free-form (not a catalog id), carried in the URL {@code q} param; {@code state} carries
   * optional refinements (filters, date bounds). Idempotent + reloadable like
   * {@link Navigation}.
   *
   * @param query the free-form query string the search surface runs (wire field {@code
   *     "query"} — mirrors the TS {@code ShellAddressQuery.query})
   * @param state optional refinement state (use {@link StateSnapshot#empty()} for none)
   */
  record Query(String query, StateSnapshot state) implements ShellAddress {

    /** Wire discriminator. Read by the FE IntentRouter to dispatch by address kind. */
    public static final String KIND = "query";

    public Query {
      Objects.requireNonNull(query, "query");
      Objects.requireNonNull(state, "state");
    }

    /** Convenience: query with no refinement state (the common case). */
    public Query(String query) {
      this(query, StateSnapshot.empty());
    }

    @JsonProperty("kind")
    public String kind() {
      return KIND;
    }
  }

  /**
   * Answer verb (548 §4.5) — "give a cited one-turn answer to {@code prompt}". Resolves an AI
   * shape (default {@code core.rag-ask}); the FE router lowers it to an activation of the
   * shape-hosting chat surface. Free-form prompt (not a catalog id) — carried in the {@code q}
   * param. Wire fields {@code prompt} + {@code shape} mirror the TS {@code ShellAddressAnswer}.
   *
   * @param prompt the free-form question the AI shape answers
   * @param shape the AI shape id to resolve (default {@code core.rag-ask})
   * @param state optional refinement state (use {@link StateSnapshot#empty()} for none)
   */
  record Answer(String prompt, String shape, StateSnapshot state) implements ShellAddress {

    /** Default AI shape an answer resolves to when none is given. */
    public static final String DEFAULT_SHAPE = "core.rag-ask";

    /** Wire discriminator. Read by the FE IntentRouter to dispatch by address kind. */
    public static final String KIND = "answer";

    public Answer {
      Objects.requireNonNull(prompt, "prompt");
      Objects.requireNonNull(shape, "shape");
      Objects.requireNonNull(state, "state");
    }

    /** Convenience: answer against the default shape with no refinement state. */
    public Answer(String prompt) {
      this(prompt, DEFAULT_SHAPE, StateSnapshot.empty());
    }

    @JsonProperty("kind")
    public String kind() {
      return KIND;
    }
  }
}
