/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;
import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Timeout(120)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030CommittedCertificateTest {
 @ParameterizedTest(name = "{displayName} {0} / stored {1}")
 @CsvSource({"migrating,null", "migrating,absent", "switching,null", "switching,absent"})
 void unknownStoredModelCannotCertifyRetirement(String fixture, String form, @TempDir Path temporary) throws Exception {
  Path root=Release030Fixture.unpack(fixture, temporary);
  var original=state(root);
  Path active=Release030CompatibilityTest.generation(root);
  ConfigStore previous=ConfigStore.globalOrNull();
  var installed=configure(root);
  try (var _=new ReleasedModelIdentities(root)) {
   var metadata=new HashMap<>(Release030CompatibilityTest.readMetadata(active));
   var json=new tools.jackson.databind.ObjectMapper();
   var inputs=(tools.jackson.databind.node.ObjectNode)json.readTree(metadata.get("index_fingerprint_inputs"));
   assertTrue(inputs.path("embedding_model_sha256").asText().matches("[0-9a-f]{64}"));
   if (form.equals("null")) inputs.putNull("embedding_model_sha256"); else inputs.remove("embedding_model_sha256");
   metadata.remove("index_fingerprint");
   metadata.put("index_fingerprint_inputs",json.writeValueAsString(inputs));
   try (var d=org.apache.lucene.store.FSDirectory.open(active); var w=new org.apache.lucene.index.IndexWriter(d,new org.apache.lucene.index.IndexWriterConfig().setOpenMode(org.apache.lucene.index.IndexWriterConfig.OpenMode.APPEND))) {
    w.setLiveCommitData(metadata.entrySet()); w.commit();
   }
   for (int restart=0; restart<2; restart++) {
    try (var executors=new TestEngineExecutors(); var server=boot(root,executors)) {
     assertTrue(search(server,"nebula").contains(key(root.resolve("roots/alpha/file-01.txt"))), "ordinary native opening must retain searchable A");
     var observed=state(root);
     System.out.println("UNKNOWN_STORED_MODEL fixture="+fixture+" form="+form+" boot="+restart+" original="+original+" actual="+observed);
     assertEquals(original.active_generation(), observed.active_generation());
     assertTrue(Files.isDirectory(root.resolve("data/index/default/indices").resolve(original.building_generation())),"B must remain");
     assertEquals(original.migration_state(),observed.migration_state(),"unknown stored model identity cannot positively certify cancellation");
     assertEquals(original.building_generation(),observed.building_generation());
    }
   }
  } finally { ConfigStore.restoreGlobal(installed,previous); }
 }
 @ParameterizedTest(name = "{displayName} {0} / complete {1}")
 @CsvSource({"migrating,digest-only", "switching,digest-only", "migrating,inputs-only", "switching,inputs-only"})
 void completeCommittedEvidenceRetiresAcrossRestart(String fixture,String form,@TempDir Path temporary) throws Exception {
  Path root=Release030Fixture.unpack(fixture,temporary);var original=state(root);
  Path active=Release030CompatibilityTest.generation(root);
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  try(var _=new ReleasedModelIdentities(root)) {
   var expected=Release030CompatibilityTest.expected(Release030CompatibilityTest.readMetadata(active));
   var metadata=new HashMap<String,Object>(expected);
   if(form.equals("digest-only")) metadata.remove("index_fingerprint_inputs");
   else metadata.remove("index_fingerprint");
   stamp(active,metadata);
   for(int restart=0;restart<2;restart++) {
    try(var ex=new TestEngineExecutors();var server=boot(root,ex)) {
     assertEquals("IDLE",state(root).migration_state());activate(server);
     await(()->state(root).previous_generation()==null,"complete identity retires the obsolete build",60);
     assertEquals(original.active_generation(),state(root).active_generation());
     assertTrue(search(server,"nebula").contains(key(root.resolve("roots/alpha/file-01.txt"))));
     assertTrue(search(server,"copper").contains(key(root.resolve("explicit.txt"))));
     Release030RetirementSafetyTest.assertRetired(root,original.building_generation());
    }
   }
  }finally{ConfigStore.restoreGlobal(installed,previous);}
 }
 @ParameterizedTest(name = "{displayName} {0} / genuinely changed model")
 @org.junit.jupiter.params.provider.ValueSource(strings = {"migrating", "switching"})
 void changedCommittedModelKeepsNativeMigration(String fixture,@TempDir Path temporary)throws Exception {
  Path root=Release030Fixture.unpack(fixture,temporary);var original=state(root);
  Path active=Release030CompatibilityTest.generation(root);
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  try(var _=new ReleasedModelIdentities(root)) {
   var metadata=new HashMap<String,Object>(Release030CompatibilityTest.readMetadata(active));
   var json=new tools.jackson.databind.ObjectMapper();
   var inputs=(tools.jackson.databind.node.ObjectNode)json.readTree((String)metadata.get("index_fingerprint_inputs"));
   assertNotEquals("a".repeat(64),inputs.path("embedding_model_sha256").asText());
   inputs.put("embedding_model_sha256","a".repeat(64));String rendered=json.writeValueAsString(inputs);
   metadata.put("index_fingerprint_inputs",rendered);
   metadata.put("index_fingerprint",Release030Fixture.sha256(rendered.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
   stamp(active,metadata);
   for(int restart=0;restart<2;restart++) {
    try(var ex=new TestEngineExecutors();var server=boot(root,ex)) {
     assertEquals(original.migration_state(),state(root).migration_state());
     assertEquals(original.building_generation(),state(root).building_generation());
     assertEquals(original.active_generation(),state(root).active_generation());
     assertTrue(search(server,"nebula").contains(key(root.resolve("roots/alpha/file-01.txt"))));
     assertTrue(Files.isDirectory(root.resolve("data/index/default/indices").resolve(original.building_generation())));
    }
   }
  }finally{ConfigStore.restoreGlobal(installed,previous);}
 }
 private static void stamp(Path active,Map<String,?> metadata)throws Exception {
  try(var directory=org.apache.lucene.store.FSDirectory.open(active);
      var writer=new org.apache.lucene.index.IndexWriter(directory,new org.apache.lucene.index.IndexWriterConfig()
          .setOpenMode(org.apache.lucene.index.IndexWriterConfig.OpenMode.APPEND))) {
   writer.setLiveCommitData(metadata.entrySet().stream().map(e->Map.entry(e.getKey(),e.getValue().toString())).toList());writer.commit();
  }
 }

}
