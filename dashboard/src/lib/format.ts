import i18n from '../i18n';

/**
 * Locale-true formatting. The app's languages are exactly 'en' | 'fr', but Intl needs the REGIONAL locale:
 * plain 'fr' renders "15:14" where Québec French writes "15 h 14" — this is a Québec bank, so fr maps to
 * fr-CA (and en to en-CA for consistency).
 */
const LOCALES: Record<string, string> = { fr: 'fr-CA', en: 'en-CA' };

export function appLocale(): string {
  return LOCALES[i18n.language] ?? 'en-CA';
}

const dateTimeCache = new Map<string, Intl.DateTimeFormat>();

/** "2 juill. 2026, 15 h 14" (fr-CA) / "Jul 2, 2026, 3:14 p.m." (en-CA); em dash for null/invalid. */
export function formatDateTime(iso?: string | null): string {
  if (!iso) {
    return '—';
  }
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) {
    return '—';
  }
  const locale = appLocale();
  let f = dateTimeCache.get(locale);
  if (!f) {
    f = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' });
    dateTimeCache.set(locale, f);
  }
  return f.format(d);
}

const shortDateCache = new Map<string, Intl.DateTimeFormat>();

/**
 * "20 juin" (fr-CA) / "Jun 20" (en-CA) — a compact day+month label for chart axes. Accepts an ISO date
 * ("2026-06-20") or a full timestamp; em dash for null/invalid.
 */
export function formatShortDate(iso?: string | null): string {
  if (!iso) {
    return '—';
  }
  // A bare YYYY-MM-DD (the /trend series) parses as UTC midnight, which slips to the previous day west of
  // UTC — read it as a LOCAL calendar date so the axis label names the day the API meant.
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  const d = m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : new Date(iso);
  if (Number.isNaN(d.getTime())) {
    return '—';
  }
  const locale = appLocale();
  let f = shortDateCache.get(locale);
  if (!f) {
    f = new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric' });
    shortDateCache.set(locale, f);
  }
  return f.format(d);
}

/**
 * Relative time from an epoch-millisecond instant (react-query's `dataUpdatedAt`), e.g. "il y a 2 min".
 * 0/undefined → em dash. Delegates to {@link formatRelative} once the epoch is turned into an ISO string.
 */
export function formatRelativeEpoch(epochMs?: number | null): string {
  if (!epochMs) {
    return '—';
  }
  return formatRelative(new Date(epochMs).toISOString());
}

const moneyCache = new Map<string, Intl.NumberFormat>();

/**
 * "$0.42" (en-CA) / "0,42 $" (fr-CA) — USD amounts, locale-true digits and symbol placement.
 * `fractionDigits` overrides the currency default of 2 (the 4-decimal AI-cost ledgers); it is part of
 * the cache key so a 2-digit and a 4-digit formatter never collide.
 */
export function formatMoney(usd: number, fractionDigits?: number): string {
  const locale = appLocale();
  const cacheKey = `${locale}:${fractionDigits ?? 'default'}`;
  let f = moneyCache.get(cacheKey);
  if (!f) {
    f = new Intl.NumberFormat(locale, {
      style: 'currency', currency: 'USD', currencyDisplay: 'narrowSymbol',
      ...(fractionDigits != null && { minimumFractionDigits: fractionDigits, maximumFractionDigits: fractionDigits }),
    });
    moneyCache.set(cacheKey, f);
  }
  return f.format(usd);
}

const REL_STEPS: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ['year', 31536000], ['month', 2592000], ['week', 604800], ['day', 86400], ['hour', 3600], ['minute', 60],
];

/** "il y a 2 h" / "2 hours ago" — relative to now, absolute value belongs in a title tooltip. */
export function formatRelative(iso?: string | null): string {
  if (!iso) {
    return '—';
  }
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) {
    return '—';
  }
  const seconds = Math.round((then - Date.now()) / 1000);
  const rtf = new Intl.RelativeTimeFormat(appLocale(), { numeric: 'auto' });
  for (const [unit, size] of REL_STEPS) {
    if (Math.abs(seconds) >= size) {
      return rtf.format(Math.trunc(seconds / size), unit);
    }
  }
  return rtf.format(seconds, 'second');
}

const dayLongCache = new Map<string, Intl.DateTimeFormat>();

/**
 * "lundi 29 juin" (fr-CA) / "Monday, June 29" (en-CA) — a weekday + day + month heading for the Activity
 * page's day separators. Accepts an ISO date ("2026-06-29") or a full timestamp; em dash for null/invalid.
 */
export function formatDayLong(iso?: string | null): string {
  if (!iso) {
    return '—';
  }
  // A bare YYYY-MM-DD is a LOCAL calendar date (mirrors formatShortDate) so the heading names the intended day.
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  const d = m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : new Date(iso);
  if (Number.isNaN(d.getTime())) {
    return '—';
  }
  const locale = appLocale();
  let f = dayLongCache.get(locale);
  if (!f) {
    f = new Intl.DateTimeFormat(locale, { weekday: 'long', day: 'numeric', month: 'long' });
    dayLongCache.set(locale, f);
  }
  return f.format(d);
}

/** "3 min 42 s" duration between two instants; null when either side is missing. */
export function formatDuration(startIso?: string | null, endIso?: string | null): string | null {
  if (!startIso || !endIso) {
    return null;
  }
  const ms = new Date(endIso).getTime() - new Date(startIso).getTime();
  if (Number.isNaN(ms) || ms <= 0) {
    return null;
  }
  const totalSeconds = Math.round(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return minutes > 0 ? `${minutes} min ${seconds} s` : `${seconds} s`;
}
