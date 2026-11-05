/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.EnrichmentPublicationReceipt;
import io.justsearch.indexing.SchemaFields;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileLockInterruptionException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;

/** Selection tickets are observations only; they never decide whether to encode or write. */
public final class EnrichmentAccounting {
  public enum Stage {
    EMBED("embed", SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_RETRY_COUNT),
    CHUNK_EMBED("chunk_embed", SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT),
    SPLADE("splade", SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_RETRY_COUNT),
    NER("ner", SchemaFields.NER_STATUS, SchemaFields.NER_RETRY_COUNT);

    final String key;
    final String status;
    final String retry;

    Stage(String key, String status, String retry) {
      this.key = key;
      this.status = status;
      this.retry = retry;
    }
  }

  public enum Disposition { PUBLISHED, COMPLETED_EMPTY, FAILED_CHARGED, DEFERRED, REJECTED_STALE, REJECTED_OUTPUT, CANCELLED }
  public record Ticket(String documentId, Stage stage) {}

  /** Native acquisition is a provider outcome, independent of row and storage outcomes. */
  public record Acquisition(Object provider, boolean available) {}

  public record Summary(Map<Ticket, Disposition> tickets, Map<String, Long> counters,
      Set<Stage> storageFailures, List<Acquisition> acquisitions) {
    public Summary {
      tickets = Map.copyOf(tickets);
      counters = Map.copyOf(counters);
      storageFailures = Set.copyOf(storageFailures);
      acquisitions = List.copyOf(acquisitions);
    }

    public Summary(Map<Ticket, Disposition> tickets, Map<String, Long> counters) {
      this(tickets, counters, Set.of(), List.of());
    }

    public static Summary empty() { return new Summary(Map.of(), Map.of()); }

    public long count(Stage stage, Disposition disposition) {
      return tickets.entrySet().stream()
          .filter(entry -> entry.getKey().stage() == stage && entry.getValue() == disposition).count();
    }
  }

  private final Map<Object, Boolean> acquisitions = new java.util.IdentityHashMap<>();

  private final Map<Ticket, Disposition> tickets = new LinkedHashMap<>();
  private final Set<Ticket> computed = new HashSet<>();
  private final Set<Ticket> started = new HashSet<>();
  private final Set<Ticket> retired = new HashSet<>();
  private final Set<Ticket> acceptedStages = new HashSet<>();
  private final Set<Ticket> terminalFailures = new HashSet<>();
  private final Map<String, Long> observations = new HashMap<>();
  private final Set<Stage> storageFailures = java.util.EnumSet.noneOf(Stage.class);

  private final Predicate<Stage> cancellationRequested;

  public EnrichmentAccounting() { this(stage -> false); }

  EnrichmentAccounting(Predicate<Stage> cancellationRequested) {
    this.cancellationRequested = cancellationRequested;
  }

  boolean acquisitionHeld(Object provider) { return Boolean.FALSE.equals(acquisitions.get(provider)); }

  void acquired(Object provider) { acquisitions.put(provider, true); }

  boolean acquisitionUnavailable(Object provider, Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof io.justsearch.ort.SessionTemporarilyUnavailableException) {
        acquisitions.put(provider, false);
        return true;
      }
    }
    return false;
  }

  boolean storageFailed() { return !storageFailures.isEmpty(); }

  void select(List<String> ids, Stage stage) {
    for (String id : ids) select(id, stage);
  }

  void select(String id, Stage stage) {
    tickets.putIfAbsent(new Ticket(id, stage), Disposition.DEFERRED);
  }

  void computed(String id, Map<String, Object> updates) {
    for (Stage stage : Stage.values()) {
      Ticket ticket = new Ticket(id, stage);
      if (updates.containsKey(stage.status) || updates.containsKey(stage.retry)) started.add(ticket);
      if (completed(updates.get(stage.status))) computed.add(ticket);
    }
  }

  void computed(String id, Stage stage) { computed.add(new Ticket(id, stage)); }

  void started(String id, Stage stage) { started.add(new Ticket(id, stage)); }

  /** Retirement defers outcomes without spending a retry, including rejected failure writes. */
  void rejectedRetiredOutcomes() {
    for (Ticket ticket : started) {
      Disposition disposition = tickets.get(ticket);
      if (disposition == Disposition.DEFERRED || disposition == Disposition.CANCELLED) {
        retired.add(ticket);
        tickets.put(ticket, Disposition.DEFERRED);
      }
    }
  }

  void retired(Stage stage) {
    tickets.forEach((ticket, disposition) -> {
      if (ticket.stage() == stage && started.contains(ticket)
          && (disposition == Disposition.DEFERRED || disposition == Disposition.CANCELLED)) {
        retired.add(ticket);
        tickets.put(ticket, Disposition.DEFERRED);
      }
    });
  }

  void attempted(Stage stage) { observations.put(stage.key + ".attempts", 1L); }

  void advanced(Stage stage) { observations.merge(stage.key + ".window_advances", 1L, Long::sum); }

  boolean cancelled(Stage stage, Throwable failure) {
    if (!isCancellation(stage, failure)) return false;
    tickets.replaceAll((ticket, disposition) -> ticket.stage() == stage
        && disposition == Disposition.DEFERRED && !retired.contains(ticket)
            ? Disposition.CANCELLED : disposition);
    return true;
  }

  void cancelRemaining() {
    tickets.replaceAll((ticket, disposition) -> disposition == Disposition.DEFERRED && !retired.contains(ticket)
        ? Disposition.CANCELLED : disposition);
  }

  void published(List<Map.Entry<String, Map<String, Object>>> updates,
      EnrichmentPublicationReceipt receipt) {
    Set<String> accepted = new HashSet<>(receipt.acceptedIds());
    Set<String> rejected = new HashSet<>(receipt.rejectedIds());
    for (var update : updates) {
      for (Stage stage : Stage.values()) {
        Ticket ticket = new Ticket(update.getKey(), stage);
        var fields = update.getValue();
        if (!fields.containsKey(stage.status) && !fields.containsKey(stage.retry)) continue;
        // Only the unaccepted part of a systemic write holds a stage. Row-local storage
        // withholding, stale rejection and cancellation alone never establish a backoff.
        if (receipt.failure() != null && !accepted.contains(update.getKey())
            && !rejected.contains(update.getKey()) && !isCancellation(stage, receipt.failure())) {
          storageFailures.add(stage);
        }
        boolean corrected = receipt.correctedFailedStatuses()
            .getOrDefault(update.getKey(), Set.of()).contains(stage.status);
        if (accepted.contains(update.getKey())) {
          if (corrected) terminalFailures.add(ticket);
          else acceptedStages.add(ticket);
        }
        if (!tickets.containsKey(ticket)) continue;
        if (receipt.storageFailures().containsKey(update.getKey())) {
          tickets.put(ticket, Disposition.DEFERRED);
          observations.merge(stage.key + ".deferred_storage", 1L, Long::sum);
        } else if (rejected.contains(update.getKey())) {
          tickets.put(ticket, Disposition.REJECTED_STALE);
        } else if (accepted.contains(update.getKey())) {
          Object status = fields.get(stage.status);
          Disposition disposition = corrected ? Disposition.REJECTED_OUTPUT
              : "COMPLETED_EMPTY".equals(status) ? Disposition.COMPLETED_EMPTY
              : "COMPLETED".equals(status) ? Disposition.PUBLISHED : Disposition.FAILED_CHARGED;
          tickets.put(ticket, disposition);
          if ("FAILED".equals(status)) terminalFailures.add(ticket);
        }
      }
    }
  }

  Summary snapshot() {
    Map<String, Long> counters = new HashMap<>(observations);
    tickets.forEach((ticket, disposition) -> {
      String stage = ticket.stage().key;
      counters.merge(stage + ".selected", 1L, Long::sum);
      counters.merge(stage + "." + disposition.name().toLowerCase(java.util.Locale.ROOT), 1L, Long::sum);
      if (disposition == Disposition.PUBLISHED) counters.merge(stage + ".completed", 1L, Long::sum);
    });
    for (Ticket ticket : terminalFailures) counters.merge(ticket.stage().key + ".failed", 1L, Long::sum);
    for (Ticket ticket : acceptedStages) counters.merge(ticket.stage().key + ".accepted", 1L, Long::sum);
    for (Ticket ticket : computed) counters.merge(ticket.stage().key + ".computed", 1L, Long::sum);
    return new Summary(tickets, counters, storageFailures, acquisitions.entrySet().stream()
        .map(entry -> new Acquisition(entry.getKey(), entry.getValue())).toList());
  }

  private boolean isCancellation(Stage stage, Throwable failure) {
    if (failure == null) return false;
    if (cancellationRequested.test(stage)) return true;
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof CancellationException || cause instanceof InterruptedException
          || cause instanceof InterruptedIOException || cause instanceof ClosedByInterruptException
          || cause instanceof FileLockInterruptionException) return true;
    }
    return false;
  }

  private static boolean completed(Object status) {
    return "COMPLETED".equals(status) || "COMPLETED_EMPTY".equals(status);
  }
}
