/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Transport lifetime for managed connections and blocking SSE request bodies. */
final class SseAttachmentOwner implements AutoCloseable {
  private static final String OWNER = "justsearch.sse.owner";
  private static final String REQUEST = "justsearch.sse.request";
  private final Set<Attachment> attachments = new LinkedHashSet<>();
  private boolean closed;

  static SseAttachmentOwner install(Javalin app) {
    var owner = new SseAttachmentOwner();
    app.before(ctx -> ctx.attribute(OWNER, owner));
    app.after(ctx -> {
      Attachment request = ctx.attribute(REQUEST);
      if (request != null) request.release();
    });
    app.events(events -> events.serverStopping(owner::close));
    return owner;
  }

  static Attachment own(Context ctx, Runnable end) {
    SseAttachmentOwner owner = ctx == null ? null : ctx.attribute(OWNER);
    return owner == null ? null : owner.register(end);
  }

  static void ownRequest(Context ctx) {
    if (ctx.attribute(REQUEST) != null) return;
    Thread request = Thread.currentThread();
    Attachment attachment = own(ctx, request::interrupt);
    if (attachment != null) ctx.attribute(REQUEST, attachment);
  }

  /** Owns only the synchronous attachment work, before the request thread returns to Jetty. */
  static Attachment ownSetup(Context ctx) {
    Thread setup = Thread.currentThread();
    return own(ctx, setup::interrupt);
  }

  private Attachment register(Runnable end) {
    var attachment = new Attachment(end);
    synchronized (this) {
      if (!closed) {
        attachments.add(attachment);
        return attachment;
      }
    }
    attachment.end();
    return attachment;
  }

  @Override
  public void close() {
    List<Attachment> ending;
    synchronized (this) {
      if (closed) return;
      closed = true;
      ending = List.copyOf(attachments);
      attachments.clear();
    }
    Throwable failure = null;
    for (var attachment : ending) {
      try { attachment.end(); }
      catch (RuntimeException | Error problem) {
        if (failure == null) failure = problem;
        else if (failure != problem) failure.addSuppressed(problem);
      }
    }
    if (failure instanceof RuntimeException problem) throw problem;
    if (failure instanceof Error problem) throw problem;
  }

  final class Attachment {
    private final Runnable end;
    private boolean released;

    private Attachment(Runnable end) { this.end = end; }

    synchronized void release() {
      released = true;
      synchronized (SseAttachmentOwner.this) { attachments.remove(this); }
    }

    // Release and interrupt share this monitor: a returned request thread can never be interrupted
    // after Jetty reuses it for another request. Managed termination is independently idempotent.
    synchronized void end() {
      if (released) return;
      released = true;
      end.run();
    }
  }
}
