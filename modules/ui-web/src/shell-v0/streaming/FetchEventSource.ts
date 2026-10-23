// SPDX-License-Identifier: Apache-2.0
import { authorizedFetch } from '../api/authorizedFetch.js';

/** The EventSource surface used by EnvelopeStream and its test factories. */
export interface StreamSource extends EventTarget {
  readonly readyState: number;
  close(): void;
}

/** One authenticated SSE connection. EnvelopeStream owns reconnects and resume checkpoints. */
export class FetchEventSource extends EventTarget implements StreamSource {
  readyState = 0;
  private readonly abort = new AbortController();
  private reader: ReadableStreamDefaultReader<Uint8Array> | null = null;

  constructor(url: string) {
    super();
    void this.connect(url);
  }

  close(): void {
    this.readyState = 2;
    this.abort.abort();
    void this.reader?.cancel().catch(() => {});
  }

  private async connect(url: string): Promise<void> {
    try {
      const response = await authorizedFetch(url, {
        headers: { Accept: 'text/event-stream' }, signal: this.abort.signal, redirect: 'error',
      });
      if (this.abort.signal.aborted) {
        await response.body?.cancel();
        return;
      }
      if (!response.ok || !response.body ||
          response.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase() !== 'text/event-stream') {
        await response.body?.cancel();
        throw new Error('SSE connection refused');
      }
      this.reader = response.body.getReader();
      this.readyState = 1;
      this.dispatchEvent(new Event('open'));
      const decoder = new TextDecoder();
      let buffer = '';
      let event = '';
      let id = '';
      let data: string[] = [];
      const line = (value: string): void => {
        if (value === '') {
          if (data.length > 0) this.dispatchEvent(new MessageEvent(event || 'message', {
            data: data.join('\n'), lastEventId: id,
          }));
          event = '';
          data = [];
          return;
        }
        const colon = value.indexOf(':');
        const field = colon < 0 ? value : value.slice(0, colon);
        let content = colon < 0 ? '' : value.slice(colon + 1);
        if (content.startsWith(' ')) content = content.slice(1);
        if (field === 'data') data.push(content);
        else if (field === 'event') event = content;
        else if (field === 'id' && !content.includes('\0')) id = content;
      };
      while (!this.abort.signal.aborted) {
        const chunk = await this.reader.read();
        if (chunk.done) break;
        buffer += decoder.decode(chunk.value, { stream: true });
        for (;;) {
          const end = buffer.search(/[\r\n]/);
          if (end < 0 || (buffer[end] === '\r' && end === buffer.length - 1)) break;
          const width = buffer[end] === '\r' && buffer[end + 1] === '\n' ? 2 : 1;
          const value = buffer.slice(0, end);
          buffer = buffer.slice(end + width);
          line(value);
          if (this.abort.signal.aborted) return;
        }
      }
      if (!this.abort.signal.aborted) throw new Error('SSE connection ended');
    } catch {
      if (!this.abort.signal.aborted) {
        this.readyState = 2;
        this.dispatchEvent(new Event('error'));
      }
    } finally {
      this.reader?.releaseLock();
      this.reader = null;
    }
  }
}
