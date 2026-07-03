import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { TrendingDown, TrendingUp } from 'lucide-react';
import type { ExecutiveSummary } from '../api';
import { Card, CardBody } from './ui';
import { ScoreRing, scoreTextTone } from './charts';
import { cn } from './cn';

/** Letter grade — the language bank risk systems already speak (A>=90 tracks the release gate). */
export function letterGrade(score: number): string {
  if (score >= 90) return 'A';
  if (score >= 80) return 'B';
  if (score >= 70) return 'C';
  if (score >= 60) return 'D';
  return 'E';
}

const SAFE_CHIP: Record<string, string> = {
  PASS: 'bg-success/10 text-success ring-1 ring-success/30',
  WARN: 'bg-warning/10 text-warning ring-1 ring-warning/30',
  FAIL: 'bg-danger/10 text-danger ring-1 ring-danger/30',
};

/**
 * The executive hero: the portfolio Contract Fidelity Score (mean of each service's LATEST completed scan —
 * never a mean over history, which would drag a deliberately-improving fleet down), its delta vs the previous
 * audits, the release-safe banner, and per-service chips deep-linking to the findings. Honesty sub-line shows
 * what the scans could NOT see (coverage gaps) so the number reads as earned, not asserted.
 */
export function FidelityScorecard({ summary, coverageGaps }: { summary: ExecutiveSummary; coverageGaps: number }) {
  const { t } = useTranslation();
  const scored = summary.perService.filter((s) => s.fidelity != null);
  if (scored.length === 0) {
    return null;
  }
  const mean = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / xs.length;
  const portfolio = Math.round(mean(scored.map((s) => s.fidelity as number)));
  // Delta = the mean change across ONLY the services that were re-audited (have a prior score). Averaging over
  // all services would dilute a real move with the unchanged ones and misstate the trend.
  const withDelta = scored.filter((s) => s.delta != null);
  const delta = withDelta.length === 0 ? null : Math.round(mean(withDelta.map((s) => s.delta as number)));
  const safe = summary.perService.filter((s) => s.releaseSafe === 'PASS').length;

  return (
    <Card className="h-full">
      <CardBody className="flex h-full flex-col gap-4 sm:flex-row sm:items-center">
        <ScoreRing score={portfolio} ariaLabel={t('overview.heroAria')} centerLabel={t('overview.heroCenter')} />
        <div className="min-w-0 flex-1">
          <p className="text-xs font-medium uppercase tracking-wide text-muted">{t('overview.heroTitle')}</p>
          <div className="mt-1 flex flex-wrap items-center gap-2">
            <span className={cn('text-lg font-semibold', scoreTextTone(portfolio))}>
              {t('overview.heroGrade', { grade: letterGrade(portfolio) })}
            </span>
            {delta != null && delta !== 0 && (
              <span title={t('overview.heroDeltaBasis', { count: withDelta.length })}
                className={cn('inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-2xs font-semibold',
                  delta > 0 ? 'bg-success/10 text-success' : 'bg-danger/10 text-danger')}>
                {delta > 0 ? <TrendingUp className="h-3 w-3" /> : <TrendingDown className="h-3 w-3" />}
                {t('overview.heroDelta', { n: `${delta > 0 ? '+' : ''}${delta}` })}
              </span>
            )}
          </div>
          <p className="mt-2 text-sm font-semibold text-ink-900">
            {t('overview.releaseSafe', { safe, total: summary.perService.length })}
          </p>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {summary.perService.map((s) => (
              <Link key={s.service} to={`/findings/${s.latestScanId}`}
                title={t('overview.serviceChipTooltip', { service: s.service })}
                className={cn('rounded-full px-2 py-0.5 text-2xs font-medium hover:opacity-80', SAFE_CHIP[s.releaseSafe])}>
                {s.service}{s.fidelity != null ? ` · ${s.fidelity}` : ''}
              </Link>
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
