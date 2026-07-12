import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ChevronDown } from 'lucide-react';
import { api, type SnykFixTrainView } from '../api';
import { PageHeader, ErrorState, Spinner, Badge } from '../components/ui';
import { FixTrainProgress } from '../components/FixTrainProgress';
import { FIX_STATUS, IN_FLIGHT } from '../lib/snykStatus';

/** The plain-language, colour-coded bucket a train falls into for the batch roll-up. Colour convention:
 *  GOLD = in-progress, green = delivered, amber = waiting on you, RED = failed, muted = cancelled / already-safe. */
type Bucket = 'fixed' | 'building' | 'waiting' | 'failed' | 'alreadySafe' | 'cancelled';
function bucketOf(status: string): Bucket {
  if (status === FIX_STATUS.DONE || status === FIX_STATUS.PR_OPEN) return 'fixed';
  if (IN_FLIGHT.includes(status)) return 'building';
  if (status === FIX_STATUS.AWAITING_MANUAL_FIX || status === FIX_STATUS.AWAITING_CONFIRM) return 'waiting';
  if (status === FIX_STATUS.FAILED) return 'failed';
  if (status === FIX_STATUS.ALREADY_FIXED) return 'alreadySafe';
  return 'cancelled';
}
const BUCKET_TONE: Record<Bucket, string> = {
  fixed: 'bg-success/5 text-success ring-success/30',
  building: 'bg-gold/5 text-gold ring-gold/30',
  waiting: 'bg-warning/5 text-warning ring-warning/30',
  failed: 'bg-danger/5 text-danger ring-danger/30',
  alreadySafe: 'bg-ink-50 text-muted ring-border',
  cancelled: 'bg-ink-50 text-muted ring-border',
};

/**
 * The batch (aggregate) view for one bulk fix story: a roll-up of every per-app train under it + a drill-in card per
 * train. Fixes the "the N trains scatter after the launch modal closes" dead-end — a manager can re-enter by story key
 * and see, per app, exactly what the AI did. Polls every 2s while anything is still in flight.
 */
export function SnykBatchDetail() {
  const { t } = useTranslation();
  const { storyKey } = useParams();
  const q = useQuery({
    queryKey: ['snyk-batch', storyKey], queryFn: () => api.snykBatch(storyKey as string), enabled: !!storyKey,
    refetchInterval: (query) => (query.state.data?.some((tr) => IN_FLIGHT.includes(tr.status)) ? 2000 : false),
  });
  const trains = q.data ?? [];

  const counts = trains.reduce<Record<Bucket, number>>((acc, tr) => {
    acc[bucketOf(tr.status)] = (acc[bucketOf(tr.status)] ?? 0) + 1; return acc;
  }, { fixed: 0, building: 0, waiting: 0, failed: 0, alreadySafe: 0, cancelled: 0 });
  // Group by app (each bulk train targets one app; an app can have several coordinate trains).
  const byApp = trains.reduce<Record<string, SnykFixTrainView[]>>((acc, tr) => {
    const app = tr.appIds || '—'; (acc[app] ??= []).push(tr); return acc;
  }, {});

  return (
    <div>
      <PageHeader title={t('snyk.batch.title', { story: storyKey })} subtitle={t('snyk.batch.subtitle')} />

      {q.isError ? (
        <ErrorState message={t('snyk.fix.loadFailed')} detail={(q.error as Error).message} />
      ) : !q.data ? (
        <div className="flex items-center gap-2 text-sm text-muted"><Spinner /> {t('snyk.fix.starting')}</div>
      ) : trains.length === 0 ? (
        <ErrorState message={t('snyk.batch.empty')} />
      ) : (
        <>
          {/* Roll-up: at-a-glance counts. Only non-zero buckets, GOLD/green/amber/red/muted per the colour convention. */}
          <div className="mb-4 flex flex-wrap gap-2">
            {(['fixed', 'building', 'waiting', 'failed', 'alreadySafe', 'cancelled'] as Bucket[])
              .filter((b) => counts[b] > 0)
              .map((b) => (
                <span key={b} className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-sm font-medium ring-1 ${BUCKET_TONE[b]}`}>
                  <span className="tabular-nums">{counts[b]}</span> {t(`snyk.batch.bucket.${b}`)}
                </span>
              ))}
          </div>

          {/* Per-app groups; each train is a compact row that expands into its full live stepper + AI activity. */}
          <div className="space-y-4">
            {Object.entries(byApp).map(([app, appTrains]) => (
              <div key={app}>
                <p className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted">{app}</p>
                <div className="space-y-2">
                  {appTrains.map((tr) => (
                    <details key={tr.id} className="group rounded-xl bg-surface ring-1 ring-border">
                      <summary className="flex cursor-pointer list-none items-center gap-3 px-4 py-3">
                        <ChevronDown className="h-4 w-4 shrink-0 text-muted transition-transform group-open:rotate-180" />
                        <span className="min-w-0 flex-1 truncate text-sm font-medium text-ink-900">{tr.coordinate}</span>
                        {tr.jiraKey && <span className="hidden shrink-0 text-2xs text-muted sm:inline">{tr.jiraKey}</span>}
                        <Badge className={`shrink-0 ${BUCKET_TONE[bucketOf(tr.status)]}`}>
                          {t(`snyk.batch.bucket.${bucketOf(tr.status)}`)}
                        </Badge>
                      </summary>
                      <div className="border-t border-border px-4 py-3">
                        <FixTrainProgress trainId={tr.id} />
                      </div>
                    </details>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
