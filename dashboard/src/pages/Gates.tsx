import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { GitPullRequestArrow, Check, X } from 'lucide-react';
import { api } from '../api';
import { Badge, Button, Card, CardBody, EmptyState, ErrorState, PageHeader, Spinner, Table, Td, Th, Row } from '../components/ui';
import { useToast } from '../components/Toast';
import { TONE } from '../theme/tokens';
import { cn } from '../components/cn';

const FILTERS = ['PENDING', 'APPROVED', 'REJECTED'];
const tone = (s?: string) => s === 'APPROVED' ? TONE.ok : s === 'REJECTED' ? TONE.danger : TONE.warn;

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
        <Card><CardBody className="flex items-center gap-2 text-sm text-muted"><Spinner /> {t('gates.loading')}</CardBody></Card>
      ) : q.isError ? (
        <ErrorState message={(q.error as Error).message} />
      ) : rows.length === 0 ? (
        <EmptyState icon={GitPullRequestArrow} title={t('gates.emptyTitle', { status: status.toLowerCase() })}
          body={status === 'PENDING' ? t('gates.emptyBody') : undefined} />
      ) : (
        <Card>
          <CardBody className="p-0">
            <Table head={<><Th>{t('gates.colAction')}</Th><Th>{t('gates.colRun')}</Th><Th>{t('gates.colStatus')}</Th><Th>{t('gates.colApprover')}</Th><Th>{t('gates.colWhen')}</Th><Th /></>}>
              {rows.map((g) => (
                <Row key={g.id}>
                  <Td className="font-medium text-ink-900">{g.action}</Td>
                  <Td className="font-mono text-xs text-muted">{g.runId}</Td>
                  <Td><Badge className={tone(g.status)}>{g.status}</Badge></Td>
                  <Td className="text-muted">{g.approver ?? '—'}</Td>
                  <Td className="text-muted">{g.decidedAt ? new Date(g.decidedAt).toLocaleString() : (g.createdAt ? new Date(g.createdAt).toLocaleString() : '—')}</Td>
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
