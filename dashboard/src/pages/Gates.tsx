import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { GitPullRequestArrow, Check, X } from 'lucide-react';
import { api } from '../api';
import { Badge, Button, Card, CardBody, EmptyState, ErrorState, PageHeader, TableSkeleton, Table, Td, Th, Row, SortableTh, useSort } from '../components/ui';
import { useToast } from '../components/Toast';
import { TONE } from '../theme/tokens';
import { cn } from '../components/cn';
import { enumLabel } from '../lib/enumLabels';
import { formatDateTime } from '../lib/format';

const FILTERS = ['PENDING', 'APPROVED', 'REJECTED'];
const tone = (s?: string) => s === 'APPROVED' ? TONE.ok : s === 'REJECTED' ? TONE.danger : TONE.warn;

/** Sort accessors — module-level so the reference is stable across renders (useSort memoizes on it). */
type GateRow = { action?: string; runId?: string; status?: string; approver?: string | null; decidedAt?: string | null; createdAt?: string };
const GATE_ACCESSORS: Record<string, (g: GateRow) => string | number> = {
  action: (g) => g.action ?? '',
  run: (g) => g.runId ?? '',
  status: (g) => g.status ?? '',
  approver: (g) => g.approver ?? '',
  when: (g) => new Date(g.decidedAt ?? g.createdAt ?? 0).getTime(),
};

export function Gates() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const [status, setStatus] = useState('PENDING');
  const q = useQuery({ queryKey: ['gates', status], queryFn: () => api.gates(status) });

  const decide = useMutation({
    mutationFn: ({ id, approve }: { id: string; approve: boolean }) =>
      approve ? api.approveGate(id) : api.rejectGate(id, 'dashboard', 'Rejected from dashboard'),
    onSuccess: (_r, v) => { qc.invalidateQueries({ queryKey: ['gates'] }); toast.push('success', v.approve ? t('gates.approvedToast') : t('gates.rejectedToast')); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const rows = q.data ?? [];
  const { sorted, ...sort } = useSort(rows, { key: 'when', dir: 'desc' }, GATE_ACCESSORS);
  return (
    <div>
      <PageHeader title={t('gates.title')} subtitle={t('gates.subtitle')}
        actions={
          <div className="inline-flex rounded-lg bg-ink-50 p-0.5">
            {FILTERS.map((f) => (
              <button key={f} onClick={() => setStatus(f)}
                className={cn('rounded-md px-3 py-1.5 text-sm font-medium transition',
                  f === status ? 'bg-surface text-ink-900 shadow-sm ring-1 ring-border' : 'text-muted hover:text-ink-900')}>
                {t(`gates.filter_${f}`)}
              </button>
            ))}
          </div>
        } />

      {q.isLoading ? (
        <Card><CardBody className="p-0"><TableSkeleton label={t('gates.loading')} /></CardBody></Card>
      ) : q.isError ? (
        <ErrorState message={t('gates.loadError')} detail={(q.error as Error).message} />
      ) : rows.length === 0 ? (
        <EmptyState icon={GitPullRequestArrow} title={t('gates.emptyTitle', { statusLabel: enumLabel(t, 'gateStatus', status).toLowerCase() })}
          body={status === 'PENDING' ? t('gates.emptyBody') : undefined} />
      ) : (
        <Card>
          <CardBody className="p-0">
            <Table head={<>
              <SortableTh label={t('gates.colAction')} sortKey="action" sort={sort} />
              <SortableTh label={t('gates.colRun')} sortKey="run" sort={sort} />
              <SortableTh label={t('gates.colStatus')} sortKey="status" sort={sort} />
              <SortableTh label={t('gates.colApprover')} sortKey="approver" sort={sort} />
              <SortableTh label={t('gates.colWhen')} sortKey="when" sort={sort} />
              <Th />
            </>}>
              {sorted.map((g, i) => (
                <Row key={g.id} index={i}>
                  <Td className="font-medium text-ink-900">{enumLabel(t, 'gateAction', g.action)}</Td>
                  <Td className="font-mono text-xs text-muted">{g.runId}</Td>
                  <Td><Badge className={tone(g.status)}>{enumLabel(t, 'gateStatus', g.status)}</Badge></Td>
                  <Td className="text-muted">{g.approver ?? '—'}</Td>
                  <Td className="text-muted">{formatDateTime(g.decidedAt ?? g.createdAt)}</Td>
                  <Td className="text-right whitespace-nowrap">
                    {g.status === 'PENDING' && (
                      <span className="inline-flex gap-2">
                        <Button size="sm" loading={decide.isPending && decide.variables?.id === g.id} onClick={() => decide.mutate({ id: g.id, approve: true })}>
                          <Check className="h-4 w-4" /> {t('gates.approve')}</Button>
                        <Button size="sm" variant="secondary" disabled={decide.isPending} onClick={() => decide.mutate({ id: g.id, approve: false })}>
                          <X className="h-4 w-4" /> {t('gates.reject')}</Button>
                      </span>
                    )}
                  </Td>
                </Row>
              ))}
            </Table>
          </CardBody>
        </Card>
      )}
    </div>
  );
}
