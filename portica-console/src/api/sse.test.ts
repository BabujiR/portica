import { describe, expect, it } from 'vitest';
import { SseParser } from './sse';

describe('SseParser', () => {
  it('parses named events with ids and multi-line data', () => {
    const p = new SseParser();
    const events = p.push('event:transaction\nid:r1\ndata:{"a":1}\n\nevent: heartbeat\ndata: {}\n\n');
    expect(events).toEqual([
      { event: 'transaction', id: 'r1', data: '{"a":1}' },
      { event: 'heartbeat', id: undefined, data: '{}' },
    ]);
    expect(new SseParser().push('data:a\ndata:b\n\n')).toEqual([{ event: 'message', id: undefined, data: 'a\nb' }]);
  });

  it('keeps partial events across chunks and ignores comments', () => {
    const p = new SseParser();
    expect(p.push(': keep-alive\n\nevent:ses')).toEqual([]);
    expect(p.push('sion\r\ndata:{"state":"ACTIVE"}\r\n')).toEqual([]);
    expect(p.push('\r\n')).toEqual([{ event: 'session', id: undefined, data: '{"state":"ACTIVE"}' }]);
  });

  it('reports events without data, like ended', () => {
    expect(new SseParser().push('event:ended\n\n')).toEqual([{ event: 'ended', id: undefined, data: '' }]);
  });
});
