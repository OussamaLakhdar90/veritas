import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ClipboardList, Play, ArrowRight } from 'lucide-react';
import { api } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, ErrorState, Field, Input, PageHeader, TableSkeleton, Table, Td, Th, Row, SortableTh, useSort } from '../components/ui';
import { useToast } from '../components/Toast';
import { TONE } from '../theme/tokens';
import { enumLabel } from '../lib/enumLabels';
import { formatMoney } from '../lib/format';

/** Sort accessors — module-level for a stable reference (useSort memoizes on it). */
type PlanRow = { serviceName?: string; kind?: string; fixVersion?: string | null; status?: string;
  confidence?: number | null; riskCount?: number | null; estCostUsd?: number | null };
const PLAN_ACCESSORS: Record<string, (p: PlanRow) => string | number> = {
  service: (p) => p.serviceName ?? '',
  kind: (p) => p.kind ?? '',
  fixVersion: (p) => p.fixVersion ?? '',
  status: (p) => p.status ?? '',
  confidence: (p) => p.confidence ?? -1,
  risks: (p) => p.riskCount ?? -1,
  cost: (p) => p.estCostUsd ?? -1,
};

export function TestPlans() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['test-plans'], queryFn: api.testPlans });

  const [svc, setSvc] = useState('');
  const [fixVersion, setFixVersion] = useState('');
  const [projectKey, setProjectKey] = useState('');
  const [createGaps, setCreateGaps] = useState(false);
  const [submitted, setSubmitted] = useState(false);   // once submitted, empty required fields show inline errors

  const trigger = useMutation({
    mutationFn: () => api.triggerReleasePlan(svc, { fixVersion, projectKey: projectKey || undefined, createGaps }),
    onSuccess: (s) => {
      qc.invalidateQueries({ queryKey: ['test-plans'] });
      toast.push('success', t('testPlans.planReadyToast', { matched: s.matched, gaps: s.gaps, created: s.created }));
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const submit = () => {
    setSubmitted(true);
    if (svc.trim() && fixVersion.trim()) trigger.mutate();
    else toast.push('error', t('testPlans.requiredFieldsToast'));
  };

  const plans = q.data ?? [];
  const { sorted, ...sort } = useSort(plans, { key: 'service', dir: 'asc' }, PLAN_ACCESSORS);
  return (
    <div>
      <PageHeader title={t('testPlans.pageTitle')} subtitle={t('testPlans.pageSubtitle')} />

      <Card className="mb-6">
        <CardHeader title={t('testPlans.newPlanTitle')} subtitle={t('testPlans.newPlanSubtitle')} />
        <CardBody>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <Field label={t('testPlans.serviceLabel')} error={submitted && !svc.trim() ? t('testPlans.fieldRequired') : undefined}>
              <Input placeholder="ciam-policies" value={svc} onChange={(e) => setSvc(e.target.value)} />
            </Field>
            <Field label={t('testPlans.fixVersionLabel')} error={submitted && !fixVersion.trim() ? t('testPlans.fieldRequired') : undefined}>
              <Input placeholder="8.2" value={fixVersion} onChange={(e) => setFixVersion(e.target.value)} />
            </Field>
            <Field label={t('testPlans.projectKeyLabel')} hint={t('testPlans.projectKeyHint')}><Input placeholder="CIAM" value={projectKey} onChange={(e) => setProjectKey(e.target.value)} /></Field>
          </div>
          <div className="mt-4 flex items-center justify-between">
            <label className="inline-flex items-center gap-2 text-sm text-ink-700">
              <input type="checkbox" className="h-4 w-4 accent-brand"
                checked={createGaps} onChange={(e) => setCreateGaps(e.target.checked)} />
              {t('testPlans.createGapTestsLabel')}
            </label>
            <Button loading={trigger.isPending} onClick={submit}>
              <Play className="h-4 w-4" /> {t('testPlans.generatePlanButton')}
            </Button>
          </div>
        </CardBody>
      </Card>

      {q.isLoading ? (
        <Card><CardBody className="p-0"><TableSkeleton label={t('testPlans.loading')} /></CardBody></Card>
      ) : q.isError ? (
        <ErrorState message={t('testPlans.loadError')} detail={(q.error as Error).message} />
      ) : plans.length === 0 ? (
        <EmptyState icon={ClipboardList} title={t('testPlans.emptyTitle')} body={t('testPlans.emptyBody')} />
      ) : (
        <Card>
          <CardBody className="p-0">
            <Table head={<>
              <SortableTh label={t('testPlans.colService')} sortKey="service" sort={sort} />
              <SortableTh label={t('testPlans.colKind')} sortKey="kind" sort={sort} />
              <SortableTh label={t('testPlans.colFixVersion')} sortKey="fixVersion" sort={sort} />
              <SortableTh label={t('testPlans.colStatus')} sortKey="status" sort={sort} />
              <SortableTh label={t('testPlans.colConfidence')} sortKey="confidence" sort={sort} className="text-right" />
              <SortableTh label={t('testPlans.colRisks')} sortKey="risks" sort={sort} className="text-right" />
              <SortableTh label={t('testPlans.colEstCost')} sortKey="cost" sort={sort} className="text-right" />
              <Th />
            </>}>
              {sorted.map((p, i) => (
                <Row key={p.id} index={i}>
                  <Td className="font-medium text-ink-900">{p.serviceName}</Td>
                  <Td className="text-muted">{enumLabel(t, 'planKind', p.kind)}</Td>
                  <Td className="text-muted">{p.fixVersion ?? '—'}</Td>
                  <Td><Badge className={p.status === 'APPROVED' ? TONE.ok : TONE.info}>{enumLabel(t, 'planStatus', p.status)}</Badge></Td>
                  <Td className="text-right tabular-nums text-ink-900">{p.confidence != null ? `${Math.round(p.confidence)}%` : '—'}</Td>
                  <Td className="text-right tabular-nums text-muted">{p.riskCount ?? '—'}</Td>
                  <Td className="text-right tabular-nums text-muted">{formatMoney(p.estCostUsd ?? 0, 4)}</Td>
                  <Td className="text-right whitespace-nowrap">
                    <Link to={`/test-plans/${p.id}`} className="inline-flex items-center gap-1 text-sm font-medium text-gold hover:underline">
                      {t('testPlans.open')} <ArrowRight className="h-3.5 w-3.5" /></Link>
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
