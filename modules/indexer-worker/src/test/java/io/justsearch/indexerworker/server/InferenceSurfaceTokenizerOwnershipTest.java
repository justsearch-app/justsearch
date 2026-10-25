/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.justsearch.indexerworker.bgem3.BgeM3Assembly;
import io.justsearch.indexerworker.embed.onnx.EmbeddingAssembly;
import io.justsearch.indexerworker.ner.NerAssembly;
import io.justsearch.indexerworker.splade.SpladeAssembly;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.PolicySnapshot;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.SessionHandle;
import io.justsearch.reranker.RerankerAssembly;
import io.justsearch.reranker.RerankerTokenizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** B-S1/3/4/6/8 and B-P1/2/5/6/7: count native owners without loading models. */
class InferenceSurfaceTokenizerOwnershipTest {
  private static final class Role {
    final EncoderRole role;
    final SessionHandle sessions = mock(SessionHandle.class);
    final HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);
    final RerankerTokenizer queryTokenizer = mock(RerankerTokenizer.class);
    final AtomicReference<SessionHandle.RetirementStatus> status =
        new AtomicReference<>(SessionHandle.RetirementStatus.ACTIVE);
    final AtomicInteger releases = new AtomicInteger();
    boolean refuse;
    boolean failTokenizer;

    Role(EncoderRole role) {
      this.role = role;
      when(sessions.retirementStatus()).thenAnswer(ignored -> status.get());
      doAnswer(ignored -> {
        status.set(refuse ? SessionHandle.RetirementStatus.REFUSED
            : SessionHandle.RetirementStatus.RETIRED);
        return null;
      }).when(sessions).close();
      doAnswer(ignored -> { release(); return null; }).when(tokenizer).close();
      doAnswer(ignored -> { release(); return null; }).when(queryTokenizer).close();
    }

    void release() {
      assertEquals(SessionHandle.RetirementStatus.RETIRED, status.get(),
          "tokenizer cannot precede its native session");
      if (failTokenizer) throw new IllegalStateException("tokenizer close refused");
      assertEquals(1, releases.incrementAndGet(), "native tokenizer released exactly once");
    }
  }

  private static InferenceSurface surface(Role... roles) {
    Optional<EmbeddingAssembly> embedding = Optional.empty();
    Optional<NerAssembly> ner = Optional.empty();
    Optional<SpladeAssembly> splade = Optional.empty();
    Optional<BgeM3Assembly> bge = Optional.empty();
    Optional<RerankerAssembly> reranker = Optional.empty();
    Optional<RerankerAssembly> citation = Optional.empty();
    var handles = new ArrayList<SessionHandle>();
    var named = new EnumMap<EncoderRole, SessionHandle>(EncoderRole.class);
    for (Role r : roles) {
      handles.add(r.sessions);
      named.put(r.role, r.sessions);
      switch (r.role) {
        case EMBEDDING -> embedding = Optional.of(new EmbeddingAssembly(r.sessions, null, r.tokenizer, null));
        case NER -> ner = Optional.of(new NerAssembly(r.sessions, null, r.tokenizer, null, null));
        case SPLADE -> splade = Optional.of(new SpladeAssembly(r.sessions, null, r.tokenizer, null, null));
        case BGE_M3 -> bge = Optional.of(new BgeM3Assembly(r.sessions, null, r.tokenizer));
        case RERANKER -> reranker = Optional.of(new RerankerAssembly(r.sessions, null, r.queryTokenizer));
        case CITATION -> citation = Optional.of(new RerankerAssembly(r.sessions, null, r.queryTokenizer));
      }
    }
    return new InferenceSurface(embedding, ner, reranker, citation, splade, bge,
        new PolicySnapshot(RuntimePolicy.defaults(), new TreeMap<>()), handles,
        InferenceSurface.ComponentObservation.unknown(), named);
  }

  private static EncoderConfigurationProjection projection() {
    var projection = mock(EncoderConfigurationProjection.class);
    when(projection.digest()).thenReturn("full");
    when(projection.indexDigest()).thenReturn("index");
    when(projection.queryDigest()).thenReturn("query");
    return projection;
  }

  @ParameterizedTest
  @EnumSource(EncoderRole.class)
  void unwrappedAssemblyIsReleasedAndCopiesShareTheCloseState(EncoderRole role) {
    Role r = new Role(role);
    var surface = surface(r);
    var copy = surface.withUnavailableQueryRoles(Set.of(EncoderRole.CITATION));
    copy.close();
    surface.close();
    assertEquals(1, r.releases.get(), "B-S1/3/6: no wrapper is required for native cleanup");
  }

  @Test
  void oneTokenizerFailureDoesNotStrandLaterRolesAndRetryKeepsItsOwner() {
    Role first = new Role(EncoderRole.EMBEDDING);
    Role failed = new Role(EncoderRole.NER);
    Role last = new Role(EncoderRole.SPLADE);
    failed.failTokenizer = true;
    var surface = surface(first, failed, last);
    assertThrows(IllegalStateException.class, surface::close);
    assertEquals(List.of(1, 0, 1), List.of(first.releases.get(), failed.releases.get(), last.releases.get()));
    failed.failTokenizer = false;
    surface.close();
    assertEquals(List.of(1, 1, 1), List.of(first.releases.get(), failed.releases.get(), last.releases.get()));
  }

  @Test
  void allSessionsMustRetireBeforeAnySurfaceTokenizerCanBeReleased() {
    Role index = new Role(EncoderRole.EMBEDDING);
    Role query = new Role(EncoderRole.RERANKER);
    query.refuse = true;
    var surface = surface(index, query);
    assertThrows(IllegalStateException.class, surface::close);
    assertEquals(0, index.releases.get());
    assertEquals(0, query.releases.get());
    query.refuse = false;
    surface.close();
    assertEquals(1, index.releases.get());
    assertEquals(1, query.releases.get());
  }

  @Test
  void partitionsRetireIndependentlyAndTheOriginalCannotDoubleReleaseThem() {
    Role index = new Role(EncoderRole.EMBEDDING);
    Role query = new Role(EncoderRole.RERANKER);
    var composed = surface(index, query);
    var partition = composed.partitionQueryRoles(projection());
    partition.index().close();
    assertEquals(1, index.releases.get());
    assertEquals(0, query.releases.get());
    partition.query().close();
    composed.close();
    assertEquals(1, index.releases.get());
    assertEquals(1, query.releases.get());
  }

  private static EncoderSet.ModelIdentity identity() {
    var model = io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint.present("a");
    return new EncoderSet.ModelIdentity(model, model, model, false, 768);
  }

  @Test
  void interruptedOwnerDrainKeepsTheTokenizerForRetryAndForkedWork() throws Exception {
    Role r = new Role(EncoderRole.EMBEDDING);
    var owner = new EncoderSet(surface(r), identity(), Duration.ofSeconds(2));
    var lease = owner.acquire();
    var child = lease.fork();
    var failure = new AtomicReference<Throwable>();
    Thread closer = Thread.ofVirtual().start(() -> {
      try { owner.close(); } catch (Throwable thrown) { failure.set(thrown); }
    });
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
    while (!owner.isRetiring() && System.nanoTime() < deadline) Thread.sleep(1);
    assertTrue(owner.isRetiring());
    closer.interrupt();
    closer.join(2_000);
    assertFalse(closer.isAlive());
    assertInstanceOf(IllegalStateException.class, failure.get());
    assertFalse(owner.isClosed());
    assertEquals(0, r.releases.get());
    lease.close();
    assertEquals(0, r.releases.get());
    child.close();
    owner.close();
    assertEquals(1, r.releases.get());
    assertTrue(owner.isClosed());
  }

  @Test
  void unpublishedAndRejectedQueryOwnersCloseEveryAssemblyBeforeAnotherCandidate() {
    for (int attempt = 0; attempt < 4; attempt++) {
      Role reranker = new Role(EncoderRole.RERANKER);
      Role citation = new Role(EncoderRole.CITATION);
      var owner = new QueryRoleSet(surface(reranker, citation));
      owner.close();
      assertEquals(1, reranker.releases.get(), "B-S4/6: rejected candidate reranker");
      assertEquals(1, citation.releases.get(), "B-S4/6: rejected candidate citation");
    }
  }
}
