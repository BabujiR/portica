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
