/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed marker for the registry primitives — Operation, Resource, Prompt, DiagnosticChannel.
 *
 * <p>Per tempdoc 429 §6 + 421-data-plane.md: the FE registry system surfaces typed registry
 * primitives, each backed by a typed catalog of entries. The MCP-precedented split
 * (Operation = Tool, Resource = readable/subscribable data, Prompt = template) was
 * extended in slice 448 to add DiagnosticChannel as a fourth primitive (operator-trace
 * surfaces — engine-log, brain-log, OTel spans, audit log) per CONFLICT-LEDGER
 * C-012 path-b chosen 2026-05-07. See slice 446 §A for the truth-class conflation reasoning
 * that motivated separating operator-trace from Resource.
 *
 * <p>Jackson discriminator {@code "type"} enables victools schema generation to honor
 * sealed permits, producing discriminated {@code anyOf} schemas with {@code const}
 * type fields per §A.3 + §E.10 verified pattern.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Operation.class, name = "operation"),
    @JsonSubTypes.Type(value = Resource.class, name = "resource"),
    @JsonSubTypes.Type(value = Prompt.class, name = "prompt"),
    @JsonSubTypes.Type(value = DiagnosticChannel.class, name = "diagnostic-channel")
})
public sealed interface RegistryEntry extends Provenanced
    permits Operation, Resource, Prompt, DiagnosticChannel {

  /**
   * The stable identifier for this entry.
   *
   * <p>Per slice 481 §7 step 1: lifted to the sealed parent so the generic
   * {@link PrimitiveCatalog} can extract the id without per-entry instanceof dispatch.
   * Each primitive record's auto-generated {@code id()} accessor returns its specific
   * {@link RegistryRef} subtype (e.g., {@link OperationRef} for {@link Operation}); the
   * Java covariant-return rule allows the override.
   */
  RegistryRef<? extends RegistryEntry> id();
}
