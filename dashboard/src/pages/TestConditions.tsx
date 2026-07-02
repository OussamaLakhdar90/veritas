import { useMemo } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Trans, useTranslation } from 'react-i18next';
import { FileText, ExternalLink, ListTree } from 'lucide-react';
import { api, TestCondition } from '../api';
import { Badge, Card, CardBody, CardHeader, PageHeader, TableSkeleton, Table, Td, Th, Row, ErrorState } from '../components/ui';
import { useToast } from '../components/Toast';
import { TONE } from '../theme/tokens';
import { enumLabel } from '../lib/enumLabels';

const AUTOMATION = ['AUTOMATED', 'MANUAL', 'CANDIDATE'];

const automationTone = (a?: string) => {
  const v = (a || '').toUpperCase();
  if (v === 'AUTOMATED') return TONE.ok;
  if (v === 'MANUAL') return TONE.danger;
  return TONE.warn;
};

const priorityTone = (p?: string) => {
  const v = (p || '').toUpperCase();
  if (v === 'P1' || v.includes('VERY HIGH') || v.includes('HIGH')) return TONE.danger;
  if (v === 'P2' || v.includes('MEDIUM')) return TONE.warn;
  return TONE.muted;
};

/**
 * Test Condition List — the ISTQB test-analysis work product for a strategy: what to test, traced to a basis item
 * and a risk, with the automation candidacy decided per condition. Reached from a strategy's detail page.
 */
export function TestConditions() {
  const { t } = useTranslation();
  const { id } = useParams();   // = the strategy id the conditions derive from
  const toast = useToast();
  const qc = useQueryClient();

  const q = useQuery({ queryKey: ['test-conditions', id], queryFn: () => api.testConditions(id!), enabled: !!id });
  const strategyQ = useQuery({ queryKey: ['strategy', id], queryFn: () => api.strategy(id!), enabled: !!id });

  const setAutomation = useMutation({
    mutationFn: ({ condId, automation }: { condId: string; automation: string }) =>
      api.patchCondition(condId, { automation }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['test-conditions', id] }); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const conditions = q.data ?? [];
  const split = useMemo(() => {
    const s = { AUTOMATED: 0, MANUAL: 0, CANDIDATE: 0 } as Record<string, number>;
    for (const c of conditions) s[(c.automation || 'CANDIDATE').toUpperCase()] = (s[(c.automation || 'CANDIDATE').toUpperCase()] ?? 0) + 1;
    return s;
  }, [conditions]);

  if (q.isLoading) return <Card><CardBody className="p-0"><TableSkeleton label={t('testConditions.loading')} /></CardBody></Card>;
  if (q.isError) return <ErrorState message={t('testConditions.loadError')} detail={(q.error as Error)?.message ?? t('testConditions.unknownError')} />;

  const service = strategyQ.data?.serviceName ?? t('testConditions.serviceFallback');

  return (
    <div>
      <PageHeader
        title={t('testConditions.pageTitle', { service })}
        subtitle={t('testConditions.pageSubtitle', { count: conditions.length })}
        actions={
          <div className="flex items-center gap-2">
            {id && (
              <a href={api.testConditionsReportUrl(id)} target="_blank" rel="noreferrer"
                className="inline-flex items-center gap-1 rounded-md px-3 py-2 text-sm font-medium text-ink-700 ring-1 ring-border hover:bg-ink-50">
                <FileText className="h-4 w-4" /> {t('testConditions.conditionListLink')} <ExternalLink className="h-3 w-3" />
              </a>
            )}
            <Link to={`/test-strategy/${id}`}
              className="inline-flex items-center gap-1 rounded-md px-3 py-2 text-sm font-medium text-ink-700 ring-1 ring-border hover:bg-ink-50">
              <ListTree className="h-4 w-4" /> {t('testConditions.strategyLink')}
            </Link>
          </div>
        } />

      <Card className="mb-5">
        <CardBody className="flex flex-wrap items-center gap-3 text-sm">
          <span className="font-semibold text-ink-900">{t('testConditions.automationSplit')}</span>
          <Badge className={TONE.ok}>{t('testConditions.automatedBadge', { count: split.AUTOMATED })}</Badge>
          <Badge className={TONE.danger}>{t('testConditions.manualBadge', { count: split.MANUAL })}</Badge>
          <Badge className={TONE.warn}>{t('testConditions.candidateBadge', { count: split.CANDIDATE })}</Badge>
          <span className="text-muted">{t('testConditions.splitNote')}</span>
          <span className="w-full text-xs text-muted">
            <Trans i18nKey="testConditions.feedNote">
              AUTOMATED conditions feed <span className="font-medium text-ink-700">implement-tests</span>;
              MANUAL / CANDIDATE feed <span className="font-medium text-ink-700">create-test-cases</span>.
            </Trans>
          </span>
        </CardBody>
      </Card>

      <Card>
        <CardHeader title={t('testConditions.cardTitle')}
          subtitle={t('testConditions.cardSubtitle')} />
        <CardBody className="p-0">
          {conditions.length === 0 ? (
            <p className="px-5 py-4 text-sm text-muted">
              {t('testConditions.emptyState')}
            </p>
          ) : (
            <Table head={<><Th>{t('testConditions.colId')}</Th><Th>{t('testConditions.colCondition')}</Th><Th>{t('testConditions.colSource')}</Th><Th>{t('testConditions.colPriority')}</Th><Th>{t('testConditions.colRisk')}</Th><Th>{t('testConditions.colTechnique')}</Th><Th>{t('testConditions.colAutomation')}</Th></>}>
              {conditions.map((c: TestCondition) => (
                <Row key={c.id}>
                  <Td className="font-medium text-ink-900">{c.conditionRef}</Td>
                  <Td className="text-ink-900">{c.description}
                    {c.qualityCharacteristic ? <span className="block text-xs text-muted">{c.qualityCharacteristic}</span> : null}
                  </Td>
                  <Td className="text-muted">{c.sourceBasisItem ?? '—'}</Td>
                  <Td><Badge className={priorityTone(c.priority)}>{c.priority ?? '—'}</Badge></Td>
                  <Td className="text-muted">{c.riskRef ?? '—'}</Td>
                  <Td className="text-muted">{c.technique ?? '—'}</Td>
                  <Td>
                    <div className="flex items-center gap-2">
                      <Badge className={automationTone(c.automation)}>{enumLabel(t, 'automation', c.automation ?? 'CANDIDATE')}</Badge>
                      <select
                        aria-label={t('testConditions.automationFor', { ref: c.conditionRef })}
                        className="rounded-md border border-border bg-surface px-2 py-1 text-xs text-ink-700"
                        value={(c.automation || 'CANDIDATE').toUpperCase()}
                        disabled={setAutomation.isPending}
                        onChange={(e) => setAutomation.mutate({ condId: c.id, automation: e.target.value })}>
                        {AUTOMATION.map((a) => <option key={a} value={a}>{enumLabel(t, 'automation', a)}</option>)}
                      </select>
                    </div>
                  </Td>
                </Row>
              ))}
            </Table>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
