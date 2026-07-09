import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import type { ExecutiveSummary } from '../api';
import { Card, CardBody } from './ui';
import { Tooltip } from './Tooltip';
import { cn } from './cn';

/** Per-verdict chip styling — the language the release gate speaks. */
const SAFE_CHIP: Record<string, string> = {
  PASS: 'bg-success/10 text-success ring-1 ring-success/30',
  WARN: 'bg-warning/10 text-warning ring-1 ring-warning/30',
  FAIL: 'bg-danger/10 text-danger ring-1 ring-danger/30',
};
/** The summary tile's ring/tint, keyed off the worst verdict in the portfolio. */
const SAFE_TILE: Record<string, string> = {
  PASS: 'bg-success/5 text-success ring-success/30',
  WARN: 'bg-warning/5 text-warning ring-warning/30',
  FAIL: 'bg-danger/5 text-danger ring-danger/30',
};

/**
 * The executive hero: the portfolio release gate. A categorical PASS/WARN/FAIL rollup across each service's LATEST
 * completed scan — how many are release-safe, how many carry breaking changes — with per-service chips deep-linking
 * to the findings. No composite score: the verdict is the number that matters. Honesty sub-line shows what the scans
 * could NOT see (coverage gaps) so the verdict reads as earned, not asserted.
 */
export function FidelityScorecard({ summary, coverageGaps }: { summary: ExecutiveSummary; coverageGaps: number }) {
  const { t } = useTranslation();
  const svcs = summary.perService;
  if (svcs.length === 0) {
    return null;
  }
  const pass = svcs.filter((s) => s.releaseSafe === 'PASS').length;
  const warn = svcs.filter((s) => s.releaseSafe === 'WARN').length;
  const fail = svcs.filter((s) => s.releaseSafe === 'FAIL').length;
  const totalBreaking = svcs.reduce((a, s) => a + (s.breakingCount ?? 0), 0);
  // The portfolio headline takes the WORST verdict present — one failing service means the fleet needs attention.
  const tone: 'PASS' | 'WARN' | 'FAIL' = fail > 0 ? 'FAIL' : warn > 0 ? 'WARN' : 'PASS';

  return (
    <Card className="h-full">
      <CardBody className="flex h-full flex-col gap-4 sm:flex-row sm:items-center">
        {/* Release-safe tally tile — the categorical replacement for the old /100 ring. */}
        <div className={cn('flex shrink-0 flex-col items-center justify-center rounded-2xl px-6 py-4 ring-1',
          SAFE_TILE[tone])}>
          <span className="text-stat font-bold tabular-nums leading-none">{pass}<span
            className="text-lg font-semibold text-muted">/{svcs.length}</span></span>
          <span className="mt-1 text-2xs font-semibold uppercase tracking-wide">{t('overview.releaseSafeShort')}</span>
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-xs font-medium uppercase tracking-wide text-muted">{t('overview.heroTitle')}</p>
          <p className="mt-1 text-lg font-semibold text-ink-900">{t(`overview.verdictHeadline.${tone}`)}</p>
          {/* PASS / WARN / FAIL breakdown + breaking-change count. */}
          <div className="mt-1 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-2xs font-semibold">
            <span className="text-success">{t('overview.nPass', { n: pass })}</span>
            <span className="text-warning">{t('overview.nWarn', { n: warn })}</span>
            <span className="text-danger">{t('overview.nFail', { n: fail })}</span>
            {totalBreaking > 0 && <span className="text-danger">· {t('overview.nBreaking', { n: totalBreaking })}</span>}
          </div>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {svcs.map((s) => (
              <Tooltip key={s.service} label={t('overview.serviceChipTooltip', { service: s.service })}>
                <Link to={`/findings/${s.latestScanId}`}
                  className={cn('rounded-full px-2 py-0.5 text-2xs font-medium hover:opacity-80', SAFE_CHIP[s.releaseSafe])}>
                  {s.service}
                </Link>
              </Tooltip>
            ))}
          </div>
          <p className="mt-3 text-xs text-muted">
            {t('overview.heroHonesty', { count: coverageGaps })}
          </p>
        </div>
      </CardBody>
    </Card>
  );
}
