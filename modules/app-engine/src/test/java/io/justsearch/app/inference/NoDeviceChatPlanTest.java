/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.engine.DevicePlanTestSupport;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.gpu.DeviceFacts;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class NoDeviceChatPlanTest {
 @ParameterizedTest @ValueSource(ints={0,35,100,803})
 void noUsableDeviceFactsMustVetoAnEarlierAutomaticGpuContribution(int code) throws Exception {
  String previousInternalPlan = System.getProperty("justsearch.gpu.memory_plan");
  try {
   DevicePlanTestSupport.uninstall();
   ConfigStoreRebuilder.rememberAutoDetected(Map.of("justsearch.gpu.enabled","true","justsearch.gpu.layers","99"));
   var settings=new UiSettings();settings.setChatEnabled(true);
   var store=new ConfigStore(ConfigStoreRebuilder.prepare(settings));
   var facts=new DeviceFacts(DeviceFacts.ProbeStatus.RAN_NO_DEVICE,code,0,List.of(),Optional.empty());
   DevicePlanTestSupport.install(store,facts,DevicePlanTestSupport.DEFAULT_WEIGHTS,Set.of(ChatModelProfile.STANDARD));
   ConfigStoreRebuilder.rebuild(store,settings);
   var config=store.get();assertEquals("NO_GPU",DevicePlanTestSupport.statusProfileOf(config).orElseThrow());
   var inference=new InferenceConfig(Path.of("llama-server.exe"),Path.of("model.gguf"),Path.of("mmproj.gguf"),8082,8192,config.ai().gpuLayers(),false);
   var cmd=LlamaServerOps.buildLaunchCommand(inference,config,config.ai().gpuLayers(),ContextWindowPolicy.auto(config.ai().gpuLayers()>0,null));
   assertEquals("0",cmd.get(cmd.indexOf("-ngl")+1),"authoritative no-usable-device facts must clamp the serving launch; code="+code);
   assertFalse(config.ai().masterGpuEnabled(),"automatic CUDA availability must follow device facts, not backend presence");
   assertEquals("8192",cmd.get(cmd.indexOf("-c")+1));
   assertEquals("none",cmd.get(cmd.indexOf("--device")+1));
   assertTrue(cmd.contains("--no-mmproj-offload"));
   assertEquals(8192, config.ai().contextSize());
   assertEquals("-1", LlamaDevicePlan.resolve(config, 99, Optional.empty()).environment().get("CUDA_VISIBLE_DEVICES"));
   settings.setContextLength(6144); settings.setGpuLayers(5);
   ConfigStoreRebuilder.rebuild(store,settings);
   config=store.get();
   assertEquals(6144,config.ai().contextSize());
   assertEquals(5,config.ai().gpuLayers(),"explicit preference retained");
   assertEquals(0,LlamaDevicePlan.resolve(config,5,Optional.empty()).gpuLayers());
   assertTrue(ConfigStoreRebuilder.planOf(config).orElseThrow().unmetSettings().contains("layers"));
   System.setProperty("justsearch.gpu.memory_plan", "p16_plus");
   ConfigStoreRebuilder.rebuild(store,settings);
   assertEquals("NO_GPU",DevicePlanTestSupport.statusProfileOf(store.get()).orElseThrow());
   assertEquals(0,LlamaDevicePlan.resolve(store.get(),5,Optional.empty()).gpuLayers(),
       "an ignored internal-plan override must not shadow the authoritative no-device decision");
   settings.setGpuLayers(0); ConfigStoreRebuilder.rebuild(store,settings);
   assertTrue(ConfigStoreRebuilder.planOf(store.get()).orElseThrow().unmetSettings().isEmpty());
  } finally {
   DevicePlanTestSupport.uninstall();ConfigStoreRebuilder.rememberAutoDetected(Map.of());
   if(previousInternalPlan==null)System.clearProperty("justsearch.gpu.memory_plan");
   else System.setProperty("justsearch.gpu.memory_plan",previousInternalPlan);
  }
 }
}
