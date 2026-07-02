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
