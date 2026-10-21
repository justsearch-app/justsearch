import { setImmediate } from 'node:timers';

/** Drain fetch, ReadableStream and Lit work without advancing a test's fake timers. */
export function flushStreamWork(): Promise<void> {
  return new Promise((resolve) => setImmediate(resolve));
}

/** A controlled HTTP response, consumed by the product's real fetch-based SSE parser. */
export class FetchSseConnection {
  readonly response: Promise<Response>;
  closed = false;
  private resolve!: (response: Response) => void;
  private reject!: (reason: unknown) => void;
  private controller!: ReadableStreamDefaultController<Uint8Array>;
  private opened = false;

  constructor(readonly url: string, readonly init?: RequestInit) {
    this.response = new Promise((resolve, reject) => {
      this.resolve = resolve;
      this.reject = reject;
    });
    const abort = () => {
      this.closed = true;
      this.reject(new DOMException('Aborted', 'AbortError'));
    };
    if (init?.signal?.aborted) abort();
    else init?.signal?.addEventListener('abort', abort, { once: true });
  }

  async open(): Promise<void> {
    if (!this.opened) {
      this.opened = true;
      const body = new ReadableStream<Uint8Array>({
        start: (controller) => { this.controller = controller; },
        cancel: () => { this.closed = true; },
      });
      this.resolve(new Response(body, { headers: { 'Content-Type': 'text/event-stream' } }));
    }
    await flushStreamWork();
  }

  async emit(event: string, data: unknown): Promise<void> {
    await this.open();
    this.controller.enqueue(new TextEncoder().encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
    await flushStreamWork();
  }

  async emitFrame(envelope: object): Promise<void> {
    await this.emit('frame', envelope);
  }

  async error(): Promise<void> {
    await this.open();
    this.controller.error(new Error('SSE connection dropped'));
    await flushStreamWork();
  }
}

export function fetchSseHarness(
  fallback: typeof fetch = async (input) => { throw new Error(`Unexpected fetch: ${String(input)}`); },
  onConnect?: (connection: FetchSseConnection) => void,
): { connections: FetchSseConnection[]; fetch: typeof fetch } {
  const connections: FetchSseConnection[] = [];
  return {
    connections,
    fetch: (input, init) => {
      if (new Headers(init?.headers).get('Accept') === 'text/event-stream') {
        const connection = new FetchSseConnection(String(input), init);
        connections.push(connection);
        onConnect?.(connection);
        return connection.response;
      }
      return fallback(input, init);
    },
  };
}
