/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.gpu.CudaDevice;
import io.justsearch.app.services.gpu.DeviceFacts;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArenaPlan;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Installs the Engine's device-memory planning (Spec E step E7b) over fixed device facts, for the
 * E7b checks. The planning entry points are reached by name, so the checks also compile on a build
 * from before the plan was applied; there nothing is installed, and the checks' assertions on the
 * applied configuration and launch line are what fail.
 */
public final class DevicePlanTestSupport {

  private static final double MIB = 1024.0 * 1024.0;

  /** The four default models' weights (M2b): 1,930 MiB together. */
  public static final Map<EncoderRole, Double> DEFAULT_WEIGHTS = Map.of(
      EncoderRole.EMBEDDING, 598.3,
      EncoderRole.SPLADE, 474.2,
      EncoderRole.NER, 257.2,
      EncoderRole.RERANKER, 600.3);

  private DevicePlanTestSupport() {}

  /** Device facts for one pinned CUDA device of {@code totalMiB}. */
  public static DeviceFacts device(double totalMiB) {
    CudaDevice device = new CudaDevice(0, Optional.of("luid_0x00000000_0x0000d7a2"),
        Optional.of("GPU-8b20006f-755d-8a37-9762-7b385907e813"), Optional.of("0000:01:00.0"),
        (long) (totalMiB * MIB));
    return new DeviceFacts(DeviceFacts.ProbeStatus.RAN_OK, 0, 12_080, List.of(device),
        Optional.of(device));
  }

  /**
   * Installs the planner for {@code store}'s configurations over these facts, as the Engine does at
   * boot, and returns the plan state it publishes to; null on a build without device planning.
   */
  public static Object install(
      ConfigStore store,
      DeviceFacts facts,
      Map<EncoderRole, Double> weights,
      Set<ChatModelProfile> installed) throws Exception {
    Class<?> resolver = find("io.justsearch.app.services.gpu.GpuProfileResolver");
    Class<?> stateType = find("io.justsearch.app.services.gpu.DevicePlanState");
    Class<?> plannerType =
        find("io.justsearch.app.services.config.ConfigStoreRebuilder$DevicePlanner");
    if (resolver == null || stateType == null || plannerType == null) return null;
    Method resolve =
        resolver.getMethod("resolve", ResolvedConfig.class, DeviceFacts.class, Map.class, Set.class);
    Object planner = Proxy.newProxyInstance(DevicePlanTestSupport.class.getClassLoader(),
        new Class<?>[] {plannerType}, (proxy, method, args) -> switch (method.getName()) {
          case "plan" -> invoke(resolve, null, args[0], facts, weights, installed);
          case "equals" -> proxy == args[0];
          case "hashCode" -> System.identityHashCode(proxy);
          default -> "fixed-facts planner";
        });
    Object state = stateType.getMethod("isolated").invoke(null);
    Method installMethod = EngineRoot.class.getDeclaredMethod(
        "installDevicePlanning", ConfigStore.class, plannerType, stateType);
    installMethod.setAccessible(true);
    invoke(installMethod, null, store, planner, state);
    return state;
  }

  /** Removes an installed planner; nothing on a build without device planning. */
  public static void uninstall() throws Exception {
    Class<?> plannerType =
        find("io.justsearch.app.services.config.ConfigStoreRebuilder$DevicePlanner");
    if (plannerType == null) return;
    ConfigStoreRebuilder.class.getMethod("rememberDevicePlanner", plannerType)
        .invoke(null, new Object[] {null});
    Class<?> plans = find("io.justsearch.ort.DeviceArenaPlans");
    if (plans != null) plans.getMethod("withdraw", int.class).invoke(null, 0);
  }

  /** Makes {@code root}'s bulk-work gate and chat controller follow {@code state}; see install. */
  public static void follow(EngineRoot root, Object state) throws Exception {
    if (state == null) return;
    invoke(EngineRoot.class.getMethod("followDevicePlan", state.getClass()), root, state);
  }

  /** The plan {@code config} carries, when this build applies one. */
  public static Optional<GpuArenaPlan> planOf(ResolvedConfig config) throws Exception {
    Optional<?> resolution = resolutionOf(config);
    if (resolution.isEmpty()) return Optional.empty();
    Object value = resolution.get();
    return (Optional<GpuArenaPlan>) value.getClass().getMethod("plan").invoke(value);
  }

  /** The status profile name of the plan {@code config} carries; empty without one. */
  public static Optional<String> statusProfileOf(ResolvedConfig config) throws Exception {
    Optional<?> resolution = resolutionOf(config);
    if (resolution.isEmpty()) return Optional.empty();
    Object value = resolution.get();
    return Optional.of(value.getClass().getMethod("statusProfile").invoke(value).toString());
  }

  /** The plan {@code state} currently publishes for the encoders' device; empty without one. */
  public static Optional<GpuArenaPlan> publishedPlan(int cudaDeviceId) throws Exception {
    Class<?> plans = find("io.justsearch.ort.DeviceArenaPlans");
    if (plans == null) return Optional.empty();
    return (Optional<GpuArenaPlan>) plans.getMethod("current", int.class).invoke(null, cudaDeviceId);
  }

  /** {@code plan} as a session with {@code capMiB} reads it (NativeSessionHandle#arenaPlan). */
  public static GpuArenaPlan asSessionReadsIt(GpuArenaPlan plan, EncoderRole role, double capMiB)
      throws Exception {
    return (GpuArenaPlan) GpuArenaPlan.class
        .getMethod("withRoleCapAtMost", EncoderRole.class, double.class)
        .invoke(plan, role, capMiB);
  }

  private static Optional<?> resolutionOf(ResolvedConfig config) throws Exception {
    Method planOf;
    try {
      planOf = ConfigStoreRebuilder.class.getMethod("planOf", ResolvedConfig.class);
    } catch (NoSuchMethodException absent) {
      return Optional.empty();
    }
    return (Optional<?>) planOf.invoke(null, config);
  }

  private static Object invoke(Method method, Object target, Object... args) throws Exception {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception cause) throw cause;
      if (failure.getCause() instanceof Error error) throw error;
      throw failure;
    }
  }

  private static Class<?> find(String name) {
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException absent) {
      return null;
    }
  }
}
