// SPDX-License-Identifier: Apache-2.0
/** Public RouteChip route vocabulary. */
export type TurnRoute = 'search' | 'ask';

/** Human-facing label for a route (used by the route chip and its aria-label). */
export function describeRoute(route: TurnRoute): string {
  return route === 'search' ? 'Search' : 'Ask';
}
