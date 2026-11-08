/** Time ranges offered by the analytics page, and the API window they map to. */
export const RANGES = [
  { key: '15m', label: '15 min', minutes: 15 },
  { key: '1h', label: '1 hour', minutes: 60 },
  { key: '6h', label: '6 hours', minutes: 360 },
  { key: '24h', label: '24 hours', minutes: 1440 },
  { key: '7d', label: '7 days', minutes: 10080 },
  { key: '30d', label: '30 days', minutes: 43200 },
] as const;

export type RangeKey = (typeof RANGES)[number]['key'];

export function rangeWindow(key: RangeKey, now: Date = new Date()): { from: string; to: string } {
  const range = RANGES.find((r) => r.key === key) ?? RANGES[1];
  // round to the minute so queries made within the same minute share a cache entry
  const to = new Date(Math.ceil(now.getTime() / 60_000) * 60_000);
  const from = new Date(to.getTime() - range.minutes * 60_000);
  return { from: from.toISOString(), to: to.toISOString() };
}

/** Axis label for a bucket start: time of day for short windows, date (and hour) for long ones. */
export function bucketLabel(iso: string, bucketSeconds: number): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  if (bucketSeconds < 3600) return time;
  const date = `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  return bucketSeconds >= 86_400 ? date : `${date} ${time}`;
}

export type StatusClass = 'good' | 'warning' | 'serious' | 'critical';

/** Status codes as states: 2xx/3xx fine, 4xx client problem, 429 throttled, 5xx failure. */
export function statusClass(status: number): StatusClass {
  if (status >= 500) return 'critical';
  if (status === 429) return 'warning';
  if (status >= 400) return 'serious';
  return 'good';
}

export function formatCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 10_000) return `${(n / 1000).toFixed(1)}k`;
  return n.toLocaleString();
}

export function formatMs(ms: number): string {
  if (ms >= 10_000) return `${(ms / 1000).toFixed(1)} s`;
  return `${Math.round(ms)} ms`;
}

export function formatPercent(rate: number): string {
  return `${(rate * 100).toFixed(rate > 0 && rate < 0.01 ? 2 : 1)}%`;
}
