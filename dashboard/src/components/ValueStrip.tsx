import { useTranslation } from 'react-i18next';
import { Clock, HandCoins, PiggyBank, BadgeCheck } from 'lucide-react';
import type { Scan } from '../api';
import { Card, CardBody } from './ui';
import { Tooltip } from './Tooltip';
import { formatMoney } from '../lib/format';

/** Documented assumption behind "hours avoided": a manual contract review of one service ≈ half a senior day. */
const MANUAL_REVIEW_HOURS_PER_AUDIT = 4;

function median(xs: number[]): number | null {
  if (xs.length === 0) {
    return null;
  }
  const s = [...xs].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 === 1 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

/**
 * The ROI strip — the sentence the VP repeats in steering committee: a full audit takes minutes and cents,
 * versus half a day of a senior reviewer. "Hours avoided" is an ESTIMATE and says so on screen, with the
 * assumption in the tooltip (an honest number beats an impressive one at a bank).
 */
export function ValueStrip({ scans }: { scans: Scan[] }) {
  const { t } = useTranslation();
  // FAILED scans also set finishedAt — only completed audits speak for duration and value.
  const completed = scans.filter((s) => (s.status || '').toUpperCase() === 'COMPLETED');
  if (completed.length === 0) {
    return null;
  }
  const durationsMin = completed
    .filter((s) => s.finishedAt)
    .map((s) => (new Date(s.finishedAt as string).getTime() - new Date(s.startedAt).getTime()) / 60000)
    .filter((m) => m > 0);
  const med = median(durationsMin);
  const costPerAudit = completed.reduce((n, s) => n + (s.totalEstCostUsd ?? 0), 0) / completed.length;
  const hoursAvoided = completed.length * MANUAL_REVIEW_HOURS_PER_AUDIT;

  const items = [
    { icon: Clock, label: t('overview.valueDuration'), value: med == null ? '—'
        : t('overview.valueMinutes', { m: Math.floor(med), s: Math.round((med % 1) * 60) }) },
    { icon: HandCoins, label: t('overview.valueCost'), value: formatMoney(costPerAudit) },
    { icon: BadgeCheck, label: t('overview.valueAudits'), value: String(completed.length) },
    { icon: PiggyBank, label: t('overview.valueHours'), value: t('overview.valueHoursValue', { h: hoursAvoided }),
      tooltip: t('overview.valueHoursTooltip', { h: MANUAL_REVIEW_HOURS_PER_AUDIT }) },
  ];

  return (
    <Card className="mb-6">
      <CardBody className="grid grid-cols-2 gap-4 py-4 lg:grid-cols-4">
        {items.map(({ icon: Icon, label, value, tooltip }) => {
          const cell = (
            <div className="flex items-center gap-3">
              <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-gold/10 text-gold">
                <Icon className="h-4 w-4" />
              </span>
              <div className="min-w-0">
                <p className="truncate text-2xs font-medium uppercase tracking-wide text-muted">{label}</p>
                <p className="text-md font-semibold tabular-nums text-ink-900">{value}</p>
              </div>
            </div>
          );
          return tooltip
            ? <Tooltip key={label} label={tooltip}>{cell}</Tooltip>
            : <div key={label}>{cell}</div>;
        })}
      </CardBody>
    </Card>
  );
}
