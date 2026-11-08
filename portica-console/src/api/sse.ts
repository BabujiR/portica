import { config } from '../config';
import { accessToken } from '../auth/oidc';

/** One Server-Sent Event. */
export interface SseEvent {
  event: string;
  id?: string;
  data: string;
}

/**
 * Incremental Server-Sent Events parser (the text/event-stream format): feed it chunks as they
 * arrive, it returns the events completed so far and keeps the partial rest for the next chunk.
 */
export class SseParser {
  private buffer = '';

  push(chunk: string): SseEvent[] {
    this.buffer += chunk;
    const events: SseEvent[] = [];
    // a trailing \r may be the first half of \r\n: wait for the next chunk before deciding
    const held = this.buffer.endsWith('\r') ? '\r' : '';
    const text = held ? this.buffer.slice(0, -1) : this.buffer;
    // events end with a blank line; accept \n, \r\n and \r line endings
    const blocks = text.replace(/\r\n?/g, '\n').split('\n\n');
    this.buffer = (blocks.pop() ?? '') + held;
    for (const block of blocks) {
      let event = 'message';
      let id: string | undefined;
      const data: string[] = [];
      for (const line of block.split('\n')) {
        if (!line || line.startsWith(':')) continue; // comment / keep-alive
        const colon = line.indexOf(':');
        const field = colon < 0 ? line : line.slice(0, colon);
        let value = colon < 0 ? '' : line.slice(colon + 1);
        if (value.startsWith(' ')) value = value.slice(1);
        if (field === 'event') event = value;
        else if (field === 'id') id = value;
        else if (field === 'data') data.push(value);
      }
      if (data.length > 0 || event !== 'message') events.push({ event, id, data: data.join('\n') });
    }
    return events;
  }
}

export interface StreamHandle {
  close: () => void;
}

/**
 * Reads an SSE endpoint of the management API with the user's bearer token (EventSource can't send
 * headers, so this uses fetch). Reconnects with backoff when the connection drops, until closed or
 * the server ends the stream with an {@code ended} event.
 */
export function openStream(apiPath: string, handlers: {
  onEvent: (e: SseEvent) => void;
  onError?: (message: string) => void;
  onClose?: () => void;
}): StreamHandle {
  const controller = new AbortController();
  let closed = false;
  let ended = false;

  const run = async () => {
    let backoff = 1000;
    while (!closed && !ended) {
      try {
        const token = await accessToken();
        if (!token) throw new Error('Not signed in');
        const response = await fetch(`${config.apiBaseUrl}/api/v1${apiPath}`, {
          headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
          signal: controller.signal,
        });
        if (!response.ok || !response.body) {
          let detail = `Stream failed with status ${response.status}`;
          try {
            const problem = (await response.json()) as { detail?: string };
            if (problem.detail) detail = problem.detail;
          } catch {
            // not JSON
          }
          handlers.onError?.(detail);
          if (response.status >= 400 && response.status < 500) return; // won't get better by retrying
          throw new Error(detail);
        }
        backoff = 1000;
        const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
        const parser = new SseParser();
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          for (const e of parser.push(value)) {
            if (e.event === 'ended') ended = true;
            handlers.onEvent(e);
          }
        }
      } catch (error) {
        if (closed) return;
        handlers.onError?.(error instanceof Error ? error.message : String(error));
      }
      if (!closed && !ended) {
        await new Promise((resolve) => setTimeout(resolve, backoff));
        backoff = Math.min(backoff * 2, 15_000);
      }
    }
  };
  void run().finally(() => handlers.onClose?.());

  return {
    close: () => {
      closed = true;
      controller.abort();
    },
  };
}
