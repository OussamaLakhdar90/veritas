import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { ShieldAlert, AlertTriangle, ArrowRight, X } from 'lucide-react';
import { api } from '../api';

/**
 * Overview security banner — the same unseen-Snyk-alert signal the TopBar bell shows, surfaced prominently on the
 * dashboard so a new Critical isn't hidden behind a bell click. Shares the '['snyk-alerts','unseen']' query key
 * (so dismissing here, on the Snyk page, or acking in the bell all agree), mirrors ActivityBell's `hasCritical`
 * logic, and reuses the Snyk page's alert-row look. Renders NOTHING when there are zero unseen alerts.
 */
export function SnykAlertBanner() {
  const { t } = useTranslation();
  const qc = useQueryClient();

  const q = useQuery({
    queryKey: ['snyk-alerts', 'unseen'],
    queryFn: () => api.snykAlerts(true),
    refetchInterval: 30000,
    refetchOnWindowFocus: true,
  });
  const alerts = q.data ?? [];
  const hasCritical = alerts.some((a) => a.severity?.toLowerCase() === 'critical');

  // Dismiss acks every unseen alert (mark seen) so the banner + the bell badge both clear — the same
  // markSnykAlertSeen the Snyk page uses per-row, applied across the current set.
  const dismiss = useMutation({
    mutationFn: () => Promise.allSettled(alerts.map((a) => api.markSnykAlertSeen(a.id))),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['snyk-alerts', 'unseen'] }),
  });

  if (alerts.length === 0) return null;

  return (
    <div role="alert"
      className={`mb-6 flex items-start justify-between gap-3 rounded-lg border-l-4 px-4 py-3 text-sm ${
        hasCritical
          ? 'border-l-danger bg-danger/10 ring-1 ring-danger/25 shadow-sm motion-safe:animate-pulse'
          : 'border-l-warning bg-warning/5'}`}>
      <div className="flex items-start gap-2.5">
        {hasCritical
          ? <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 motion-safe:animate-pulse text-danger" aria-hidden="true" />
          : <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" aria-hidden="true" />}
        <div className="min-w-0">
          <span className={hasCritical ? 'font-semibold text-danger' : 'font-medium text-ink-900'}>
            {t('overview.snykBanner', { count: alerts.length })}
          </span>
          <Link to="/snyk"
            className="ml-3 inline-flex items-center gap-1 font-medium text-gold hover:underline">
            {t('overview.snykBannerCta')} <ArrowRight className="h-3.5 w-3.5" />
          </Link>
        </div>
      </div>
      <button type="button" onClick={() => dismiss.mutate()} aria-label={t('overview.snykBannerDismiss')}
        className="shrink-0 rounded p-0.5 text-muted hover:text-ink-900">
        <X className="h-4 w-4" />
      </button>
    </div>
  );
}
