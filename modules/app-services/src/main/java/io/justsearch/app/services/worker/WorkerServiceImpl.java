/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.WorkerService;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link WorkerService} backed by {@link KnowledgeServerBootstrap}.
 *
 * <p>Resolves the bootstrap reference lazily via the supplier — the bootstrap
 * may be late-bound in {@code HeadAssembly} after async Worker startup,
 * so eager capture would freeze a null reference.
 *
 * <p>Lane F stage A item A11: {@link #restart()} used to be
 * spawner.restart() → client.reconnect(expectedPid) → client.resetCircuitBreaker(). All three
 * steps were process- and channel-shaped and all three are gone; the operation now answers
 * {@link RestartRequiredException} (§10 'restart-as-reload'). It stays registered and reachable
 * on purpose — retiring the operation is D1's, and an operation that 404s is a worse answer than
 * one that says what the user must do.
 */
public final class WorkerServiceImpl implements WorkerService {

  private static final Logger log = LoggerFactory.getLogger(WorkerServiceImpl.class);

  private final Supplier<KnowledgeServerBootstrap> bootstrapSupplier;

  public WorkerServiceImpl(Supplier<KnowledgeServerBootstrap> bootstrapSupplier) {
    this.bootstrapSupplier = Objects.requireNonNull(bootstrapSupplier, "bootstrapSupplier");
  }

  @Override
  public boolean available() {
    // The worker is 'available' when the index half is composed and answering, which is what the
    // caller actually wants to know. Before item A11 this asked whether a spawner reference was
    // held — a proxy for the same question that stopped being answerable when the process went.
    KnowledgeServerBootstrap ks = safeGet();
    return ks != null && ks.hasClient();
  }

  @Override
  public long workerPid() {
    // The index half runs in this process since item A6, so its pid is this pid. Reporting 0 (the
    // 'not running' sentinel) would be a lie whenever it IS running, and reporting a child pid is
    // impossible.
    KnowledgeServerBootstrap ks = safeGet();
    return ks != null && ks.hasClient() ? ProcessHandle.current().pid() : 0L;
  }

  @Override
  public int restart() {
    // core.restart-worker stays registered and reachable (stage A §9); what changed is the answer.
    // Item A11 deleted the spawner, so there is no child process to replace — restarting the index
    // half means restarting the Engine, which is the user's action.
    throw new RestartRequiredException("Restarting the index half");
  }

  private KnowledgeServerBootstrap safeGet() {
    try {
      return bootstrapSupplier.get();
    } catch (RuntimeException e) {
      log.warn("WorkerServiceImpl: bootstrap supplier threw", e);
      return null;
    }
  }
}
