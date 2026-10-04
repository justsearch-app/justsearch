/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import io.justsearch.app.api.stream.SseEnvelope;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded per-stream ring buffer of recent {@link SseEnvelope} frames for resume.
 *
 * <p>Per slice 436 §B.4: a reconnecting consumer with a recent {@code resumeToken}
 * receives only frames newer than the token's seq; outside the window the controller
 * emits {@code reset + snapshot} instead. This buffer holds the recent-frames window.
 *
 * <p>Eviction is monotonic: appending past capacity drops the oldest frame.
 * {@link #framesSince(long)} returns the chronological tail of frames whose
 * {@code seq > sinceSeq}; if {@code sinceSeq} is older than the oldest retained frame,
 * the caller (controller) interprets this as "outside resume window" and emits a reset.
 *
 * <p>Default capacity matches slice 436 §B.4's heuristic: {@code MAX_FRAME_RATE *
 * HEARTBEAT_INTERVAL_S * 10 ≈ 9000} frames at 30fps × 30s × 10. This is a per-stream
 * default; streams with different cadence override.
 *
 * <p><strong>Two-tier retention</strong> (tempdoc 834 §2). Beyond the frame axis a
 * {@link FrameRetentionPolicy} may add a byte bound and an evidence slot: frames the
 * policy's classifier keys are held in a latest-wins map under their own byte budget
 * instead of the narrative ring, so one large replace-only frame cannot evict thousands of
 * narrative frames. Replay order is evidence first (in original seq order), then the
 * narrative ring, so a cursor stays coherent. Under {@link FrameRetentionPolicy#DEFAULT}
 * — every catalog stream today — there is no byte bound and no classifier, no frame is
 * ever sized, and behaviour is identical to the pre-834 buffer.
 */
public final class FrameHistoryRingBuffer {

  /**
   * Default capacity (frames) per slice 436 §B.4. Value-coupled to the FE dedup LRU
   * ({@code DEDUP_LRU_SIZE} in {@code modules/ui-web/src/api/intent/bootIntentStreamBridge.ts});
   * drift is caught by the cross-language check in {@code bootIntentStreamBridge.test.ts}
   * ("BE/FE capacity drift") and by {@code FrameHistoryRingBufferTest.defaultCapacity}
   * (tempdoc 682 Item 3).
   */
  public static final int DEFAULT_CAPACITY = 9000;

  private final FrameRetentionPolicy policy;
  private final Deque<SseEnvelope> frames = new ArrayDeque<>();
  private final Map<String, SseEnvelope> evidence = new LinkedHashMap<>();
  private long narrativeBytes;
  private long evidenceBytes;
  private long droppedThroughSeq;

  /** Highest discarded UPDATE sequence; a stronger snapshot cursor must cover this fence. */
  public synchronized long droppedThroughSeq() {
    return droppedThroughSeq;
  }

  public FrameHistoryRingBuffer() {
    this(FrameRetentionPolicy.DEFAULT);
  }

  public FrameHistoryRingBuffer(int capacity) {
    this(FrameRetentionPolicy.ofFrames(capacity));
  }

  public FrameHistoryRingBuffer(FrameRetentionPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  public int capacity() {
    return policy.maxFrames();
  }

  /** The retention bounds this buffer enforces. */
  public FrameRetentionPolicy policy() {
    return policy;
  }

  /** Number of frames in the narrative ring (evidence-slot frames are not counted). */
  public synchronized int size() {
    return frames.size();
  }

  /** Number of distinct keys held in the evidence slot; 0 when the slot is disabled. */
  public synchronized int evidenceSize() {
    return evidence.size();
  }

  /**
   * Estimated retained bytes across both tiers. Always 0 under a policy that does not
   * {@link FrameRetentionPolicy#tracksBytes()} — those frames are never sized.
   */
  public synchronized long retainedBytes() {
    return narrativeBytes + evidenceBytes;
  }

  /**
   * Appends {@code frame}. Evidence-classified frames replace their key in the evidence
   * slot; every other frame enters the narrative ring, evicting oldest-first until both the
   * frame and byte bounds hold.
   */
  public synchronized void append(SseEnvelope frame) {
    Objects.requireNonNull(frame, "frame");
    if (policy.evidenceSlotEnabled()) {
      Optional<String> key = policy.evidenceClassifier().evidenceKey(frame);
      if (key.isPresent()) {
        appendEvidence(key.get(), frame);
        return;
      }
    }
    appendNarrative(frame);
  }

  private void appendEvidence(String key, SseEnvelope frame) {
    SseEnvelope replaced = evidence.put(key, frame);
    if (replaced != null) {
      droppedThroughSeq = Math.max(droppedThroughSeq, replaced.seq());
      evidenceBytes -= FrameRetentionSizer.retainedBytes(replaced);
    }
    evidenceBytes += FrameRetentionSizer.retainedBytes(frame);
    // Latest-wins per key, so the slot only overflows across DISTINCT keys. Evict the
    // lowest-seq keys first — the same oldest-first law the narrative ring uses. The
    // just-added key is never evicted: a slot too small for one frame degrades to
    // holding that frame alone rather than to holding nothing.
    while (evidenceBytes > policy.maxEvidenceBytes() && evidence.size() > 1) {
      Map.Entry<String, SseEnvelope> oldest = null;
      for (Map.Entry<String, SseEnvelope> entry : evidence.entrySet()) {
        if (entry.getKey().equals(key)) {
          continue;
        }
        if (oldest == null || entry.getValue().seq() < oldest.getValue().seq()) {
          oldest = entry;
        }
      }
      if (oldest == null) {
        break;
      }
      evidenceBytes -= FrameRetentionSizer.retainedBytes(oldest.getValue());
      droppedThroughSeq = Math.max(droppedThroughSeq, oldest.getValue().seq());
      evidence.remove(oldest.getKey());
    }
  }

  private void appendNarrative(SseEnvelope frame) {
    boolean tracksBytes = policy.tracksBytes();
    if (tracksBytes) {
      narrativeBytes += FrameRetentionSizer.retainedBytes(frame);
    }
    frames.addLast(frame);
    while (frames.size() > policy.maxFrames()
        || (tracksBytes && narrativeBytes > policy.maxBytes() && frames.size() > 1)) {
      SseEnvelope evicted = frames.pollFirst();
      if (evicted == null) {
        break;
      }
      droppedThroughSeq = Math.max(droppedThroughSeq, evicted.seq());
      if (tracksBytes) {
        narrativeBytes -= FrameRetentionSizer.retainedBytes(evicted);
      }
    }
  }

  /**
   * Returns the chronological tail of frames whose {@code seq > sinceSeq}. Returns an
   * empty list if no frames are newer than {@code sinceSeq}.
   *
   * <p>Evidence-slot frames newer than {@code sinceSeq} come first, in seq order, followed
   * by the narrative tail (tempdoc 834 §2). With the evidence slot disabled — every catalog
   * stream — this is exactly the narrative tail.
   *
   * <p>If the caller's {@code sinceSeq} is older than the oldest retained frame's seq,
   * the buffer doesn't have the gap — the caller should interpret this as "outside resume
   * window" and emit a {@code reset + snapshot} sequence. Use {@link #oldestSeqOrZero()}
   * to detect this case.
   */
  public synchronized List<SseEnvelope> framesSince(long sinceSeq) {
    if (frames.isEmpty() && evidence.isEmpty()) {
      return List.of();
    }
    List<SseEnvelope> out = new ArrayList<>();
    if (!evidence.isEmpty()) {
      List<SseEnvelope> retained = new ArrayList<>(evidence.values());
      retained.removeIf(f -> f.seq() <= sinceSeq);
      retained.sort(Comparator.comparingLong(SseEnvelope::seq));
      out.addAll(retained);
    }
    for (SseEnvelope f : frames) {
      if (f.seq() > sinceSeq) {
        out.add(f);
      }
    }
    return List.copyOf(out);
  }

  /**
   * Returns the seq of the oldest retained frame, or 0 if the buffer is empty. Callers use
   * this to detect "resume token predates the buffer" — when {@code sinceSeq <
   * oldestSeqOrZero()}, the caller emits a reset.
   *
   * <p>The narrative ring is the authority whenever it holds anything: the evidence slot is
   * latest-wins, so an old evidence frame surviving in it does NOT mean the narrative gap
   * back to that seq is replayable. Only when the narrative ring is empty does the evidence
   * slot's oldest seq answer.
   */
  public synchronized long oldestSeqOrZero() {
    if (!frames.isEmpty()) {
      return frames.peekFirst().seq();
    }
    long oldest = 0L;
    for (Iterator<SseEnvelope> it = evidence.values().iterator(); it.hasNext(); ) {
      long seq = it.next().seq();
      if (oldest == 0L || seq < oldest) {
        oldest = seq;
      }
    }
    return oldest;
  }
}
