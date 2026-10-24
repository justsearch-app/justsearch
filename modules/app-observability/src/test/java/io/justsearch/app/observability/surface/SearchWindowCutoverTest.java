package io.justsearch.app.observability.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Placement;
import io.justsearch.agent.api.registry.SurfaceRef;
import org.junit.jupiter.api.Test;

final class SearchWindowCutoverTest {
  @Test
  void canonicalSearchOwnsTheConversationBinding() {
    var catalog = new CoreSurfaceCatalog();
    var found = catalog.findById(new SurfaceRef("core.search-v3-surface"));
    assertTrue(found.isPresent(), "C1: the Engine must advertise the main Search window");
    var search = found.orElseThrow();
    assertEquals(Audience.USER, search.audience());
    assertEquals(Placement.RAIL, search.placement());
    assertEquals("jf-sv3-window", search.mountTag());
    assertTrue(catalog.findById(new SurfaceRef("core.unified-chat-surface")).isEmpty());
    var schema = search.stateSchema().orElseThrow();
    assertTrue(schema.bindings().stream().anyMatch(binding ->
        binding.schemaPath().equals("/conversationId")
            && binding.storeId().equals("sv3.conversation")
            && binding.storeKey().equals("conversationId")),
        "C3 A7: fetched schema must preserve conversation addresses and Back/Forward");
    assertTrue(schema.bindings().stream().noneMatch(binding -> binding.storeId().equals("unified-chat")),
        "Task hand-offs must not restore the retired window's shared stores");
  }
}
