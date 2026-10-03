/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.agent.api.AgentService;

/**
 * Always-available core services: settings persistence, policy enforcement, diagnostics export,
 * agent orchestration. None of these depend on the index component being reachable, so they are
 * constructed eagerly during {@code ServicePhase} (tempdoc 519 §4) and remain valid for the
 * lifetime of the Head process.
 *
 * <p>Part of the typed-service-graph that replaces the {@code AppFacade} locator interface
 * (tempdoc 519 §5 / Block C.1). Consumers receive this record (or a specific field from it) at
 * construction time instead of calling {@code appFacade.settings()} etc.
 *
 * <p>Stability: stable (API contract).
 */
public record CoreServices(
    SettingsService settings,
    PolicyService policy,
    DiagnosticsService diagnostics,
    AgentService agent) {}
