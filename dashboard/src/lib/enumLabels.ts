import type { TFunction } from 'i18next';

/**
 * Plain-language labels for backend enum codes — a non-technical user must never read a raw CRITICAL /
 * PENDING / ORPHAN on screen (and a French screen must never show an untranslated English code). Values
 * arriving from the API stay RAW in <select> values and request payloads; ONLY the visible text goes
 * through this. Unknown codes fall back to the raw value — review verdicts, for instance, are free-form
 * LLM output, never a strict enum.
 */
const PREFIX = {
  severity: 'severity',              // reuses the existing severity.* block
  gateStatus: 'enums.gateStatus',
  strategyStatus: 'enums.strategyStatus',
  automation: 'enums.automation',
  matchStatus: 'enums.matchStatus',
  buildStatus: 'enums.buildStatus',
  verdict: 'enums.verdict',
} as const;

export type EnumFamily = keyof typeof PREFIX;

export function enumLabel(t: TFunction, family: EnumFamily, value?: string | null): string {
  if (!value) {
    return '—';
  }
  const label = t(`${PREFIX[family]}.${value.toUpperCase()}`, { defaultValue: '' });
  return label || value;
}
