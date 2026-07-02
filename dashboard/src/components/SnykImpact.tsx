import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ArrowRight, Clock, GitPullRequest, ShieldCheck, Sparkles } from 'lucide-react';
import { api } from '../api';
import { Card, CardBody } from './ui';
import { SnykLogo } from './SnykLogo';
import { formatMoney } from '../lib/format';

/** Severity → swatch + text colour + its localized-label key (Critical is the alarming one). */
const SEV = [
  { key: 'critical', dot: 'bg-sev-critical', text: 'text-sev-critical', label: 'snyk.sevCritical' },
  { key: 'high', dot: 'bg-sev-high', text: 'text-sev-high', label: 'snyk.sevHigh' },
  { key: 'medium', dot: 'bg-sev-medium', text: 'text-ink-700', label: 'snyk.sevMedium' },
  { key: 'low', dot: 'bg-sev-low', text: 'text-muted', label: 'snyk.sevLow' },
] as const;

/**
 * Managerial "what we found vs what we fixed" card for the executive dashboard: open vulnerabilities by severity
 * (Critical emphasised in red) on the left, Veritas's remediation activity (fixes merged, PRs opened, in flight,
 * AI spend) on the right. Renders nothing until at least one app-id is watched, so it never shows empty zeros.
 */
export function SnykImpactCard({ showLink = true }: { showLink?: boolean }) {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ['snyk-summary'], queryFn: api.snykSummary, refetchInterval: 60000 });
  const fixesQ = useQuery({ queryKey: ['snyk-fixes'], queryFn: api.snykFixes, staleTime: 30_000 });
  // Median detection-to-merged over DONE trains; createdAt is the honest clock (startedAt is reconciler-reset).
  const mttrDays = (() => {
    const done = (fixesQ.data ?? []).filter((x) => x.status === 'DONE' && x.createdAt && x.finishedAt)
      .map((x) => (new Date(x.finishedAt as string).getTime() - new Date(x.createdAt as string).getTime()) / 86_400_000)
      .filter((d) => d > 0)
      .sort((a, b) => a - b);
    if (done.length === 0) return null;
    const mid = Math.floor(done.length / 2);
    const med = done.length % 2 === 1 ? done[mid] : (done[mid - 1] + done[mid]) / 2;
    return Math.round(med * 10) / 10;
  })();
  const s = q.data;
  if (!s || s.watchedApps === 0) return null;

  const open = s.critical + s.high + s.medium + s.low;
  const counts: Record<string, number> = { critical: s.critical, high: s.high, medium: s.medium, low: s.low };

  return (
    <Card className={`mb-6 overflow-hidden ${s.critical > 0 ? 'ring-1 ring-sev-critical/30' : ''}`}>
      <CardBody className="p-0">
        <div className="flex items-center justify-between gap-3 border-b border-border px-5 py-3">
          <div className="flex items-center gap-2">
            <SnykLogo className="h-5 w-5" />
            <span className="text-md font-semibold text-ink-900">{t('snyk.impact.title')}</span>
            <span className="text-xs text-muted">
              {t('snyk.impact.watchingApps', { count: s.watchedApps })} · {t('snyk.impact.watchingProjects', { count: s.projects })}
            </span>
          </div>
          {showLink && (
            <Link to="/snyk" className="inline-flex items-center gap-1 text-sm font-medium text-gold hover:underline">
              {t('snyk.impact.open')} <ArrowRight className="h-3.5 w-3.5" />
            </Link>
          )}
        </div>

        <div className="grid gap-px bg-border sm:grid-cols-2">
          {/* Found — open vulnerabilities, Critical emphasised */}
          <div className="bg-surface px-5 py-4">
            <p className="mb-2 text-2xs font-semibold uppercase tracking-wide text-muted">{t('snyk.impact.found')}</p>
            <div className="flex items-baseline gap-2">
              <span className={`text-display font-bold tabular-nums leading-none ${s.critical > 0 ? 'text-sev-critical' : 'text-ink-900'}`}>
                {s.critical}
              </span>
              <span className="text-sm text-ink-700">{t('snyk.sevCritical')}</span>
              {s.critical > 0 && <span className="ml-1 h-2 w-2 animate-pulse rounded-full bg-sev-critical" aria-hidden="true" />}
            </div>
            <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-sm">
              {SEV.slice(1).map((sev) => (
                <span key={sev.key} className="inline-flex items-center gap-1.5">
                  <span className={`h-2 w-2 rounded-sm ${sev.dot}`} />
                  <span className={sev.text}>{t(sev.label)}</span>
                  <span className="font-semibold tabular-nums text-ink-900">{counts[sev.key]}</span>
                </span>
              ))}
            </div>
            <p className="mt-2 text-xs text-muted">
              {t('snyk.impact.foundSub', { open, fixable: s.fixable })}
            </p>
          </div>

          {/* Fixed — Veritas remediation activity */}
          <div className="bg-surface px-5 py-4">
            <p className="mb-2 text-2xs font-semibold uppercase tracking-wide text-muted">{t('snyk.impact.fixed')}</p>
            <div className="grid grid-cols-2 gap-x-4 gap-y-2">
              <Stat icon={<ShieldCheck className="h-3.5 w-3.5 text-success" />} value={s.fixesMerged} label={t('snyk.impact.merged')} />
              <Stat icon={<GitPullRequest className="h-3.5 w-3.5 text-gold" />} value={s.prsOpened} label={t('snyk.impact.prs')} />
              <Stat icon={<Clock className="h-3.5 w-3.5 text-muted" />} value={s.fixesInProgress} label={t('snyk.impact.inProgress')} />
              <Stat icon={<Sparkles className="h-3.5 w-3.5 text-brand" />} value={formatMoney(s.llmCostUsd)} label={t('snyk.impact.aiCost')} />
            </div>
          </div>
        </div>
        {mttrDays != null && (
          <p className="mt-3 text-sm text-muted">{t('snyk.impact.mttr', { days: mttrDays })}</p>
        )}
      </CardBody>
    </Card>
  );
}

function Stat({ icon, value, label }: { icon?: React.ReactNode; value: number | string; label: string }) {
  return (
    <div className="min-w-0">
      <div className="flex items-center gap-1.5">
        {icon}
        <span className="text-xl font-bold tabular-nums leading-none text-ink-900">{value}</span>
      </div>
      <p className="mt-0.5 truncate text-xs text-muted">{label}</p>
    </div>
  );
}
