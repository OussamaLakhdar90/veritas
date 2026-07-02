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
  gateAction: 'enums.gateAction',
  strategyStatus: 'enums.strategyStatus',
  planKind: 'enums.planKind',
  planStatus: 'enums.planStatus',
  caseStatus: 'enums.caseStatus',
  automation: 'enums.automation',
  matchStatus: 'enums.matchStatus',
  buildStatus: 'enums.buildStatus',
  verdict: 'enums.verdict',
  layer: 'enums.layer',
  confidence: 'enums.confidence',
  skill: 'enums.skill',
} as const;

export type EnumFamily = keyof typeof PREFIX;

/**
 * Families whose values are always machine identifiers (SCREAMING_SNAKE gate actions, kebab-case skill
 * ids) — an unknown code is prettified rather than echoed raw, so a new backend value can never put
 * XRAY_UPDATE_STEPS on screen. Free-form families (verdicts…) still echo untouched.
 */
const PRETTIFY: ReadonlySet<EnumFamily> = new Set(['gateAction', 'planKind', 'planStatus', 'caseStatus', 'skill']);

/** Looks like a machine id (no spaces, only word chars and _/- separators)? */
const MACHINE_ID = /^[A-Za-z0-9_-]+$/;

/** "XRAY_UPDATE_STEPS" → "Xray update steps"; "report-translation" → "Report translation". */
function prettifyCode(value: string): string {
  const words = value.split(/[_-]+/).filter(Boolean).join(' ').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function enumLabel(t: TFunction, family: EnumFamily, value?: string | null): string {
  if (!value) {
    return '—';
  }
  const label = t(`${PREFIX[family]}.${value.toUpperCase()}`, { defaultValue: '' });
  if (label) {
    return label;
  }
  return PRETTIFY.has(family) && MACHINE_ID.test(value) ? prettifyCode(value) : value;
}
