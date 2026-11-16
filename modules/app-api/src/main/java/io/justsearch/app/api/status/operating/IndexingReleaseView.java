/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

import io.justsearch.agent.api.registry.PreciseWire;

/**
 * "Read waiting files now": whether a release of the reading rules (hours, battery) is letting file
 * reading through right now. It lives in Engine memory only; enrichment, picture reading and GPL
 * stay held while it is active.
 */
public record IndexingReleaseView(boolean active) implements PreciseWire {}
