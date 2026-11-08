import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';

dayjs.extend(relativeTime);

export function fromNow(iso?: string | null): string {
  return iso ? dayjs(iso).fromNow() : '–';
}

export function dateTime(iso?: string | null): string {
  return iso ? dayjs(iso).format('YYYY-MM-DD HH:mm') : '–';
}

/** Resource names: lower-case letters, digits and hyphens, like the API requires. */
export const RESOURCE_NAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
export const RESOURCE_NAME_RULE = {
  pattern: RESOURCE_NAME,
  message: 'Lower-case letters, digits and hyphens; must start and end with a letter or digit',
};

export function toResourceName(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 63);
}
