import { describe, expect, it } from 'vitest';
import { bucketLabel, formatCount, formatMs, formatPercent, rangeWindow, statusClass } from './analyticsModel';

describe('analytics model', () => {
  it('maps a range to a minute-aligned window', () => {
    const w = rangeWindow('1h', new Date('2026-11-07T10:15:30Z'));
    expect(w).toEqual({ from: '2026-11-07T09:16:00.000Z', to: '2026-11-07T10:16:00.000Z' });
  });

  it('classifies status codes', () => {
    expect([200, 304, 404, 429, 503].map(statusClass)).toEqual(['good', 'good', 'serious', 'warning', 'critical']);
  });

  it('formats numbers for tiles', () => {
    expect(formatCount(12_345)).toBe('12.3k');
    expect(formatMs(12.4)).toBe('12 ms');
    expect(formatMs(12_400)).toBe('12.4 s');
    expect(formatPercent(0.1234)).toBe('12.3%');
    expect(formatPercent(0.0012)).toBe('0.12%');
  });

  it('labels buckets by resolution', () => {
    expect(bucketLabel('2026-11-07T10:05:00Z', 60)).toMatch(/^\d\d:\d\d$/);
    expect(bucketLabel('2026-11-07T00:00:00Z', 86_400)).toMatch(/^\d\d-\d\d$/);
  });
});
