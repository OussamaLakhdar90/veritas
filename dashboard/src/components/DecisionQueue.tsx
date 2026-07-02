import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ArrowRight, BellRing, CheckCircle2, GitPullRequestArrow, ShieldAlert } from 'lucide-react';
import { api } from '../api';
import { Card, CardBody, CardHeader } from './ui';

const HUMAN_WAIT = new Set(['AWAITING_CONFIRM', 'AWAITING_MANUAL_FIX']);

/**
 * The governance card: everything currently waiting on a NAMED HUMAN — pending approval gates, unseen
 * security alerts, fix trains held for confirmation. The proof that nothing this AI does touches Jira,
 * Xray or Git without a person approving it; a calm green line when nothing waits.
 */
export function DecisionQueue() {
  const { t } = useTranslation();
  const gatesQ = useQuery({ queryKey: ['gates', 'PENDING'], queryFn: () => api.gates('PENDING') });
  const snykQ = useQuery({ queryKey: ['snyk-summary'], queryFn: api.snykSummary });
  const fixesQ = useQuery({ queryKey: ['snyk-fixes'], queryFn: api.snykFixes, staleTime: 30_000 });

  const pendingGates = (gatesQ.data ?? []).length;
  const unseenAlerts = snykQ.data?.unseenAlerts ?? 0;
  const heldTrains = (fixesQ.data ?? []).filter((x) => HUMAN_WAIT.has(x.status)).length;
  const total = pendingGates + unseenAlerts + heldTrains;

  const rows = [
    { count: pendingGates, icon: GitPullRequestArrow, label: t('queue.gates', { count: pendingGates }), to: '/gates' },
    { count: unseenAlerts, icon: ShieldAlert, label: t('queue.alerts', { count: unseenAlerts }), to: '/snyk' },
    { count: heldTrains, icon: BellRing, label: t('queue.trains', { count: heldTrains }), to: '/snyk' },
  ].filter((r) => r.count > 0);

  return (
    <Card className="mb-6">
      <CardHeader title={t('queue.title')} subtitle={t('queue.subtitle')} />
      <CardBody className="py-4">
        {total === 0 ? (
          <p className="flex items-center gap-2 text-sm text-success">
            <CheckCircle2 className="h-4 w-4" /> {t('queue.empty')}
          </p>
        ) : (
          <ul className="space-y-2">
            {rows.map((r) => (
              <li key={r.to + r.label}>
                <Link to={r.to} className="group flex items-center gap-3 rounded-lg px-2 py-1.5 hover:bg-ink-50/60">
                  <r.icon className="h-4 w-4 shrink-0 text-warning" />
                  <span className="text-sm text-ink-900">{r.label}</span>
                  <ArrowRight className="ml-auto h-3.5 w-3.5 text-muted group-hover:text-ink-900" />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </CardBody>
    </Card>
  );
}
