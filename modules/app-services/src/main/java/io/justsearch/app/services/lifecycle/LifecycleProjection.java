/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.lifecycle;

import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.status.EngineComponentView;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The sole aggregate and schema-2 projection of an immutable Engine component snapshot. */
public final class LifecycleProjection {

  private LifecycleProjection() {}

  /**
   * The one component aggregate policy, shared by health/status and the runtime manifest.
   * Essential failure/reload wins, then startup, then unavailable essential services, then
   * requested optional services. Intentional optional absence preserves text-search readiness.
   */
  public static LifecycleState derive(EngineComponentSnapshot snapshot) {
    requireComponents(snapshot);
    return aggregate(snapshot).state();
  }

  /** Projects both schema-2 surfaces from exactly the same immutable observation. */
  public static Projection project(EngineComponentSnapshot snapshot, Instant observedAt) {
    var components = requireComponents(snapshot);
    var views = components.entrySet().stream().collect(Collectors.toUnmodifiableMap(
        Map.Entry::getKey, entry -> EngineComponentView.from(entry.getValue())));
    var lifecycle = new LifecycleSnapshotV2(LifecycleSnapshotV2.SCHEMA_VERSION,
        observedAt.toString(), aggregate(snapshot), new LifecycleSnapshotV2.Components(
            slot(views.get("api")), slot(views.get("index")),
            slot(views.get("encoders")), slot(views.get("generative"))));
    return new Projection(lifecycle, views);
  }

  public record Projection(LifecycleSnapshotV2 lifecycle,
      Map<String, EngineComponentView> engineComponents) {
    public Projection {
      java.util.Objects.requireNonNull(lifecycle, "lifecycle");
      engineComponents = Map.copyOf(engineComponents);
    }
  }

  private static LifecycleSnapshotV2.Component slot(EngineComponentView component) {
    return new LifecycleSnapshotV2.Component(component.state(), component.reasonCode(),
        component.stateSince());
  }

  private static Map<String, EngineComponentSnapshot.Component> requireComponents(
      EngineComponentSnapshot snapshot) {
    var components = snapshot.components().stream().collect(Collectors.toUnmodifiableMap(
        component -> component.spec().name(), Function.identity()));
    if (!components.keySet().containsAll(Set.of("api", "index", "encoders", "generative"))) {
      throw new IllegalStateException("Lifecycle projection requires all four Engine components");
    }
    return components;
  }

  private static LifecycleSnapshotV2.Lifecycle aggregate(EngineComponentSnapshot snapshot) {
    var cause = snapshot.components().stream()
        .filter(component -> priority(component) < 4)
        .min(Comparator.comparingInt(LifecycleProjection::priority)
            .thenComparing(component -> component.spec().name()));
    if (cause.isEmpty()) {
      return new LifecycleSnapshotV2.Lifecycle(LifecycleState.LIFECYCLE_STATE_READY, null, null);
    }
    var component = cause.orElseThrow();
    var state = switch (priority(component)) {
      case 0 -> LifecycleState.LIFECYCLE_STATE_ERROR;
      case 1 -> LifecycleState.LIFECYCLE_STATE_STARTING;
      default -> LifecycleState.LIFECYCLE_STATE_DEGRADED;
    };
    return new LifecycleSnapshotV2.Lifecycle(state, component.reasonCode(), component.evidence());
  }

  private static int priority(EngineComponentSnapshot.Component component) {
    if (component.spec().essential()) {
      return switch (component.state()) {
        case FAILED, RELOADING -> 0;
        case STARTING -> 1;
        case ABSENT, UNAVAILABLE -> 2;
        case READY -> 4;
      };
    }
    return component.state() == ComponentState.READY || component.state() == ComponentState.ABSENT
        ? 4 : 3;
  }

}
