import { config } from '../config';
import { accessToken } from '../auth/oidc';
import { SseParser, type SseEvent } from './sseParser';

export { SseParser, type SseEvent } from './sseParser';

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
