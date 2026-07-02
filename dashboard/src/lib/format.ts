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

const moneyCache = new Map<string, Intl.NumberFormat>();

/** "$0.42" (en-CA) / "0,42 $" (fr-CA) — USD amounts, locale-true digits and symbol placement. */
export function formatMoney(usd: number): string {
  const locale = appLocale();
  let f = moneyCache.get(locale);
  if (!f) {
    f = new Intl.NumberFormat(locale, { style: 'currency', currency: 'USD', currencyDisplay: 'narrowSymbol' });
    moneyCache.set(locale, f);
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
