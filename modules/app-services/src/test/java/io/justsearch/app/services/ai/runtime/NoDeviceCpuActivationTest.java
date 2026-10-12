/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;
import io.justsearch.app.api.*;
import io.justsearch.app.api.chat.SelfTestSession;
import io.justsearch.app.inference.chat.*;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.*;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.gpu.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class NoDeviceCpuActivationTest {
 @TempDir Path tmp;
 void probe(ResolvedConfig cfg, Integer saved, int expected, boolean cpu) throws Exception {
  var store=new ConfigStore(cfg); var previous=ConfigStore.globalOrNull(); var home=System.getProperty("justsearch.home");
  ConfigStore.setGlobal(store); System.setProperty("justsearch.home",tmp.toString());
  var caps=mock(GpuCapabilitiesService.class);
  var snap=new GpuCapabilities(new GpuCapabilities.Nvml(true,null,null,null,"600",600,0,1,12L<<30,10L<<30,2L<<30,0,0),null,new GpuCapabilities.Effective(true,"nvml",GpuCapabilities.Confidence.HIGH,"600",600,0,1,12L<<30,10L<<30,2L<<30,GpuCapabilities.Cuda.unknown()));
  when(caps.snapshot()).thenReturn(snap);
  try(var ex=new TestEngineExecutors()) {
   var service=new RuntimeActivationService(ex,OnlineAiService.unavailable(),new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,tmp.resolve("settings.json")),caps,null);
   var captured=new java.util.concurrent.atomic.AtomicReference<SelfTestSpec>();
   var session=new SelfTestSession<SelfTestSpec,SelfTestRun>() { public SelfTestRun run(SelfTestSpec spec) { captured.set(spec); return SelfTestRun.passed(); } public void close() {} };
   var settings=new UiSettings(); if(saved!=null) settings.setGpuLayers(saved); settings.setContextLength(6144);
   var method=RuntimeActivationService.class.getDeclaredMethod("runSelfTest",SelfTestSession.class,Path.class,Path.class,UiSettings.class); method.setAccessible(true);
   clearInvocations(caps);
   var result=(RuntimeActivationService.SelfTestResult)method.invoke(service,session,tmp.resolve("server.exe"),tmp.resolve("model.gguf"),settings);
   var cmd=captured.get().command();
   assertEquals(Integer.toString(expected),cmd.get(cmd.indexOf("-ngl")+1));
   assertEquals("6144",cmd.get(cmd.indexOf("-c")+1));
   if(cpu) {assertEquals("none",cmd.get(cmd.indexOf("--device")+1)); assertTrue(cmd.contains("--no-mmproj-offload")); assertEquals("passed",result.result()); assertNull(result.vramAfter()); assertNull(result.delta()); assertEquals(Map.of("CUDA_VISIBLE_DEVICES","-1"),captured.get().environment()); assertNull(result.vramBefore()); assertEquals("cpu",result.vramTier()); assertEquals("none",result.vramSource()); verifyNoInteractions(caps);}
  } finally { ConfigStore.restoreGlobal(store,previous); if(home==null)System.clearProperty("justsearch.home"); else System.setProperty("justsearch.home",home); }
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(ints={0,35,100,803})
 void noDeviceActivationUsesTheSameProfileDecision(int code) throws Exception {
  var facts=new io.justsearch.app.services.gpu.DeviceFacts(io.justsearch.app.services.gpu.DeviceFacts.ProbeStatus.RAN_NO_DEVICE,code,0,List.of(),Optional.empty());
  try {
   io.justsearch.app.services.config.ConfigStoreRebuilder.rememberAutoDetected(Map.of("justsearch.gpu.layers","99","justsearch.context.size","32768"));
   io.justsearch.app.services.config.ConfigStoreRebuilder.rememberDevicePlanner(cfg -> io.justsearch.app.services.gpu.GpuProfileResolver.resolve(cfg,facts,Map.of(),Set.of(io.justsearch.configuration.model.ChatModelProfile.STANDARD)));
   var cfg=io.justsearch.app.services.config.ConfigStoreRebuilder.prepare(new UiSettings());
   assertEquals("NO_GPU",io.justsearch.app.services.config.ConfigStoreRebuilder.planOf(cfg).orElseThrow().statusProfile().name());
   probe(cfg,null,0,true);
   probe(cfg,5,0,true);
  } finally {
   io.justsearch.app.services.config.ConfigStoreRebuilder.rememberDevicePlanner(null);
   io.justsearch.app.services.config.ConfigStoreRebuilder.rememberAutoDetected(Map.of());
  }
 }
}
