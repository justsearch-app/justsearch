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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RuntimeActivationAutomaticCpuTest {
  private String nativeFixtureOs;

  @org.junit.jupiter.api.BeforeEach
  void selectWindowsNativeFixture() {
    nativeFixtureOs = System.getProperty("os.name");
    System.setProperty("os.name", "Windows 11");
  }

  @org.junit.jupiter.api.AfterEach
  void restoreNativeFixtureOs() {
    if (nativeFixtureOs == null) System.clearProperty("os.name");
    else System.setProperty("os.name", nativeFixtureOs);
  }

 @TempDir Path tmp;
 @ParameterizedTest @ValueSource(booleans={false,true})
 void activationMustRespectAutomaticCpuProfiles(boolean detected) throws Exception {
   var cfg=ResolvedConfig.builder().put("justsearch.gpu.enabled",150,"auto",null,Boolean.toString(detected)).put("justsearch.gpu.layers",150,"profile",null,"0").build();
   var store=new ConfigStore(cfg);var previous=ConfigStore.globalOrNull();var oldHome=System.getProperty("justsearch.home");
   ConfigStore.setGlobal(store);System.setProperty("justsearch.home",tmp.toString());
   var caps=mock(GpuCapabilitiesService.class);
   var snap=new GpuCapabilities(new GpuCapabilities.Nvml(true,null,null,null,"600",600,0,1,12L<<30,10L<<30,2L<<30,0,0),null,new GpuCapabilities.Effective(detected,"nvml",GpuCapabilities.Confidence.HIGH,"600",600,0,1,12L<<30,10L<<30,2L<<30,GpuCapabilities.Cuda.unknown()));
   when(caps.snapshot()).thenReturn(snap);
   try(var executors=new TestEngineExecutors()) {
    var service=new RuntimeActivationService(executors,OnlineAiService.unavailable(),new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,tmp.resolve("auto-settings.json")),caps,null);
    var captured=new java.util.concurrent.atomic.AtomicReference<SelfTestSpec>();
    var session=new SelfTestSession<SelfTestSpec,SelfTestRun>(){public SelfTestRun run(SelfTestSpec spec){captured.set(spec);return SelfTestRun.passed();}public void close(){}};
    var method=RuntimeActivationService.class.getDeclaredMethod("runSelfTest",SelfTestSession.class,Path.class,Path.class,UiSettings.class);method.setAccessible(true);
    method.invoke(service,session,tmp.resolve("server.exe"),tmp.resolve("model.gguf"),new UiSettings());
    var cmd=captured.get().command();assertEquals("0",cmd.get(cmd.indexOf("-ngl")+1),"activation selected GPU layers in CPU profile; detected="+detected);
   } finally {ConfigStore.restoreGlobal(store,previous);if(oldHome==null)System.clearProperty("justsearch.home");else System.setProperty("justsearch.home",oldHome);}
 }
 @Test void cpuProbeCannotReportUnrelatedGpuMemoryAsItsMeasurement() throws Exception {
  var store=new ConfigStore(ResolvedConfig.builder().putSettings("justsearch.gpu.enabled","false").build());
  var previous=ConfigStore.globalOrNull();var oldHome=System.getProperty("justsearch.home");
  ConfigStore.setGlobal(store);System.setProperty("justsearch.home",tmp.toString());
  var caps=mock(GpuCapabilitiesService.class);
  var snapshot=new GpuCapabilities(new GpuCapabilities.Nvml(true,null,null,null,"600",600,0,1,12L<<30,10L<<30,2L<<30,0,0),null,
     new GpuCapabilities.Effective(true,"nvml",GpuCapabilities.Confidence.HIGH,"600",600,0,1,12L<<30,10L<<30,2L<<30,GpuCapabilities.Cuda.unknown()));
  when(caps.snapshot()).thenReturn(snapshot);
  try(var executors=new TestEngineExecutors()) {
   var service=new RuntimeActivationService(executors,OnlineAiService.unavailable(),new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,tmp.resolve("settings.json")),caps,null);
   var session=new SelfTestSession<SelfTestSpec,SelfTestRun>(){public SelfTestRun run(SelfTestSpec spec){return SelfTestRun.passed();} public void close(){}};
   var method=RuntimeActivationService.class.getDeclaredMethod("runSelfTest",SelfTestSession.class,Path.class,Path.class,UiSettings.class);method.setAccessible(true);
   var result=(RuntimeActivationService.SelfTestResult)method.invoke(service,session,tmp.resolve("server.exe"),tmp.resolve("model.gguf"),new UiSettings());
   assertEquals("passed",result.result());
   assertAll(()->assertNull(result.vramBefore(),"CPU self-test did not measure GPU allocation"),()->assertNull(result.vramAfter()),()->assertNull(result.delta()),()->assertEquals("cpu",result.vramTier()),()->assertEquals("none",result.vramSource()));
  }finally{ConfigStore.restoreGlobal(store,previous);if(oldHome==null)System.clearProperty("justsearch.home");else System.setProperty("justsearch.home",oldHome);}
 }
}
