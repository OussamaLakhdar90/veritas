import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ArrowRight, BellRing, CheckCircle2, GitPullRequestArrow, RefreshCw, ShieldAlert, AlertTriangle, OctagonAlert } from 'lucide-react';
import { api } from '../api';
import { Card, CardBody, CardHeader, Skeleton } from './ui';

const HUMAN_WAIT = new Set(['AWAITING_CONFIRM', 'AWAITING_MANUAL_FIX']);

/** The worst service by blocking-finding count — the blocking row links straight to its findings. */
export interface WorstService { service: string; scanId: string }

/**
 * The governance card: everything currently waiting on a NAMED HUMAN — pending approval gates, unseen
 * security alerts, fix trains held for confirmation. The proof that nothing this AI does touches Jira,
 * Xray or Git without a person approving it; a calm green line when nothing waits.
 *
 * It must never fake green: a failed check reads as "couldn't check" (not "nothing awaits") so a silent
 * fetch failure can't hide a pending approval.
 */
export function DecisionQueue({ blockingOpen = 0, worstService }:
  { blockingOpen?: number; worstService?: WorstService }) {
  const { t } = useTranslation();
  const gatesQ = useQuery({ queryKey: ['gates', 'PENDING'], queryFn: () => api.gates('PENDING') });
  const snykQ = useQuery({ queryKey: ['snyk-summary'], queryFn: api.snykSummary });
  const fixesQ = useQuery({ queryKey: ['snyk-fixes'], queryFn: api.snykFixes, staleTime: 30_000 });

  const anyError = gatesQ.isError || snykQ.isError || fixesQ.isError;
  const anyPending = gatesQ.isPending || snykQ.isPending || fixesQ.isPending;

  const pendingGates = (gatesQ.data ?? []).length;
  const unseenAlerts = snykQ.data?.unseenAlerts ?? 0;
  const heldTrains = (fixesQ.data ?? []).filter((x) => HUMAN_WAIT.has(x.status)).length;
  // blockingOpen is included in the total so the empty state can never false-green while a release is blocked.
  const total = pendingGates + unseenAlerts + heldTrains + blockingOpen;

  // The blocking-findings row is danger-toned and leads to the worst service's findings (or the Defects list).
  const blockingTo = worstService ? `/findings/${worstService.scanId}` : '/defects';
  const rows = [
    { count: blockingOpen, icon: OctagonAlert, tone: 'text-danger',
      label: t('queue.blocking', { count: blockingOpen }), to: blockingTo },
    { count: pendingGates, icon: GitPullRequestArrow, tone: 'text-warning', label: t('queue.gates', { count: pendingGates }), to: '/gates' },
    { count: unseenAlerts, icon: ShieldAlert, tone: 'text-warning', label: t('queue.alerts', { count: unseenAlerts }), to: '/snyk' },
    { count: heldTrains, icon: BellRing, tone: 'text-warning', label: t('queue.trains', { count: heldTrains }), to: '/snyk' },
  ].filter((r) => r.count > 0);

  const refetchAll = () => { gatesQ.refetch(); snykQ.refetch(); fixesQ.refetch(); };

  let body: React.ReactNode;
  if (anyError) {
    // A check failed — say so, and offer a retry. Green here would be a lie.
    body = (
      <div className="flex items-center gap-3">
        <AlertTriangle className="h-4 w-4 shrink-0 text-warning" />
        <span className="text-sm text-ink-900">{t('queue.checkFailed')}</span>
        <button type="button" onClick={refetchAll}
          className="ml-auto inline-flex items-center gap-1.5 text-sm font-medium text-gold hover:underline">
          <RefreshCw className="h-3.5 w-3.5" /> {t('common.retry')}
        </button>
      </div>
    );
  } else if (anyPending) {
    body = <div className="space-y-2" role="status" aria-live="polite">
      <span className="sr-only">{t('queue.title')}</span>
      {Array.from({ length: 2 }).map((_, i) => <Skeleton key={i} className="h-8" />)}
    </div>;
  } else if (total > 0) {
    body = (
      <ul className="space-y-2">
        {rows.map((r) => (
          <li key={r.to + r.label}>
            <Link to={r.to} className="group flex items-center gap-3 rounded-lg px-2 py-1.5 hover:bg-ink-50/60">
              <r.icon className={`h-4 w-4 shrink-0 ${r.tone}`} />
              <span className="text-sm text-ink-900">{r.label}</span>
              <ArrowRight className="ml-auto h-3.5 w-3.5 text-muted group-hover:text-ink-900" />
            </Link>
          </li>
        ))}
      </ul>
    );
  } else {
    body = (
      <p className="flex items-center gap-2 text-sm text-success">
        <CheckCircle2 className="h-4 w-4" /> {t('queue.empty')}
      </p>
    );
  }

  return (
    <Card className="mb-6">
      <CardHeader title={t('queue.title')} subtitle={t('queue.subtitle')} />
      <CardBody className="py-4">{body}</CardBody>
    </Card>
  );
}
