import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { FileText } from 'lucide-react';
import { api, Deliverable } from '../api';
import { Badge, Card, CardBody, PageHeader, Spinner, Table, Td, Th, Row } from '../components/ui';
import { severityBadge, TONE } from '../theme/tokens';

const RISK_TO_SEV: Record<string, string> = {
  'VERY HIGH': 'BLOCKER', VH: 'BLOCKER', HIGH: 'CRITICAL', H: 'CRITICAL',
  MEDIUM: 'MAJOR', M: 'MAJOR', LOW: 'MINOR', L: 'MINOR', 'VERY LOW': 'INFO', VL: 'INFO',
};
const riskBadge = (lvl?: string) => severityBadge(RISK_TO_SEV[(lvl || '').toUpperCase()] ?? 'INFO');
const matchTone = (m?: string) => {
  const v = (m || '').toUpperCase();
  if (v === 'MATCHED') return TONE.ok;
  if (v === 'CREATED') return 'bg-gold/10 text-gold ring-1 ring-gold/30';
  if (v === 'GAP') return TONE.warn;
  return TONE.muted; // ORPHAN / DEAD
};

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <Card className="mb-5">
      <div className="border-b border-border px-5 py-3"><h2 className="text-[15px] font-semibold text-ink-900">{title}</h2></div>
      <CardBody>{children}</CardBody>
    </Card>
  );
}

export function TestPlanDetail() {
  const { t } = useTranslation();
  const { id } = useParams();
  const q = useQuery({ queryKey: ['test-plan', id], queryFn: () => api.testPlan(id!), enabled: !!id });

  if (q.isLoading) return <Card><CardBody className="flex items-center gap-2 text-sm text-muted"><Spinner /> {t('testPlanDetail.loading')}</CardBody></Card>;
  if (q.isError || !q.data) return <Card><CardBody className="text-sm text-danger">{t('testPlanDetail.couldNotLoad')}: {(q.error as Error)?.message}</CardBody></Card>;

  const { plan, coverage = [] } = q.data;
  let d: Deliverable = {};
  try { d = plan.deliverableJson ? JSON.parse(plan.deliverableJson) : {}; } catch { /* keep empty */ }
  const conf = d.selfReview?.confidence ?? plan.confidence;

  return (
    <div>
      <PageHeader
        title={`${t('testPlanDetail.titlePrefix', { serviceName: plan.serviceName })}${plan.fixVersion ? ` (${plan.fixVersion})` : ''}`}
        subtitle={t('testPlanDetail.subtitle', { kind: plan.kind, status: plan.status, cost: (plan.estCostUsd ?? 0).toFixed(4) })}
        actions={
          <div className="flex items-center gap-2">
            <a href={api.testPlanReportUrl(plan.id, 'html')} target="_blank" rel="noreferrer"
              className="inline-flex items-center gap-1 rounded-md px-3 py-2 text-[13px] font-medium text-ink-700 ring-1 ring-border hover:bg-ink-50"><FileText className="h-4 w-4" /> HTML</a>
            <a href={api.testPlanReportUrl(plan.id, 'pdf')} target="_blank" rel="noreferrer"
              className="inline-flex items-center gap-1 rounded-md px-3 py-2 text-[13px] font-medium text-ink-700 ring-1 ring-border hover:bg-ink-50"><FileText className="h-4 w-4" /> PDF</a>
          </div>
        } />

      {conf != null && (
        <Card className="mb-5"><CardBody className="flex items-center gap-4">
          <div className={`text-4xl font-semibold tabular-nums ${conf >= 70 ? 'text-success' : 'text-warning'}`}>{Math.round(conf)}%</div>
          <div className="text-[13px] text-muted">{t('testPlanDetail.selfReviewConfidence')}</div>
        </CardBody></Card>
      )}

      {d.executiveSummary && <Section title={t('testPlanDetail.executiveSummary')}><p className="text-sm leading-relaxed text-ink-900">{d.executiveSummary}</p></Section>}

      {d.scope && (
        <Section title={t('testPlanDetail.scopeObjectives')}>
          <div className="grid grid-cols-1 gap-6 text-sm sm:grid-cols-2">
            <div><p className="mb-1 font-semibold text-ink-900">{t('testPlanDetail.objectives')}</p><ul className="list-disc space-y-0.5 pl-5 text-ink-700">{(d.scope.objectives || []).map((x, i) => <li key={i}>{x}</li>)}</ul></div>
            <div>
              <p className="mb-1 font-semibold text-ink-900">{t('testPlanDetail.inScope')}</p><ul className="list-disc space-y-0.5 pl-5 text-ink-700">{(d.scope.inScope || []).map((x, i) => <li key={i}>{x}</li>)}</ul>
              <p className="mb-1 mt-3 font-semibold text-ink-900">{t('testPlanDetail.outOfScope')}</p><ul className="list-disc space-y-0.5 pl-5 text-ink-700">{(d.scope.outOfScope || []).map((x, i) => <li key={i}>{x}</li>)}</ul>
            </div>
          </div>
          {d.scope.assumptions?.length ? <p className="mt-3 text-[13px] text-muted">{t('testPlanDetail.assumptions', { items: d.scope.assumptions.join('; ') })}</p> : null}
        </Section>
      )}

      {d.riskRegister?.length ? (
        <Section title={t('testPlanDetail.riskRegister', { n: d.riskRegister.length })}>
          <Table head={<><Th>{t('testPlanDetail.thId')}</Th><Th>{t('testPlanDetail.thRisk')}</Th><Th>{t('testPlanDetail.thQualityChar')}</Th><Th>{t('testPlanDetail.thL')}</Th><Th>{t('testPlanDetail.thI')}</Th><Th>{t('testPlanDetail.thLevel')}</Th><Th>{t('testPlanDetail.thMitigation')}</Th><Th>{t('testPlanDetail.thCite')}</Th></>}>
            {d.riskRegister.map((r) => (
              <Row key={r.id}>
                <Td className="font-medium text-ink-900">{r.id}</Td><Td className="text-ink-900">{r.description}</Td>
                <Td className="text-muted">{r.qualityCharacteristic ?? '—'}</Td><Td className="text-muted">{r.likelihood ?? '—'}</Td><Td className="text-muted">{r.impact ?? '—'}</Td>
                <Td><Badge className={riskBadge(r.level)}>{r.level}</Badge></Td>
                <Td className="text-muted">{r.mitigation ?? '—'}</Td><Td className="text-[12px] text-muted">{r.citation ?? ''}</Td>
              </Row>
            ))}
          </Table>
        </Section>
      ) : null}

      {d.testApproach && (
        <Section title={t('testPlanDetail.testApproach')}>
          <p className="mb-3 text-sm text-ink-900"><span className="font-semibold">{t('testPlanDetail.levels')}</span> {(d.testApproach.levels || []).join(', ')} · <span className="font-semibold">{t('testPlanDetail.types')}</span> {(d.testApproach.types || []).join(', ')}</p>
          {d.testApproach.techniques?.length ? (
            <Table head={<><Th>{t('testPlanDetail.thTechnique')}</Th><Th>{t('testPlanDetail.thRationale')}</Th><Th>{t('testPlanDetail.thCite')}</Th></>}>
              {d.testApproach.techniques.map((t, i) => (
                <Row key={i}><Td className="font-medium text-ink-900">{t.name}</Td><Td className="text-ink-700">{t.rationale ?? '—'}</Td><Td className="text-[12px] text-muted">{t.citation ?? ''}</Td></Row>
              ))}
            </Table>
          ) : null}
          {d.testApproach.entryCriteria?.length ? <p className="mt-3 text-[13px] text-muted">{t('testPlanDetail.entry', { items: d.testApproach.entryCriteria.join('; ') })}</p> : null}
        </Section>
      )}

      <Section title={t('testPlanDetail.rtm', { n: coverage.length })}>
        <Table head={<><Th>{t('testPlanDetail.thRequirement')}</Th><Th>{t('testPlanDetail.thRequiredCase')}</Th><Th>{t('testPlanDetail.thDimension')}</Th><Th>{t('testPlanDetail.thStatus')}</Th><Th>{t('testPlanDetail.thMatchedTest')}</Th></>}>
          {coverage.map((c, i) => (
            <Row key={i}>
              <Td className="text-ink-900">{c.requirementKey ?? '—'}</Td><Td className="text-ink-700">{c.requiredCaseRef}</Td><Td className="text-muted">{c.dimension}</Td>
              <Td><Badge className={matchTone(c.matchStatus)}>{c.matchStatus}</Badge></Td>
              <Td className="font-mono text-[12px] text-muted">{c.matchedTestKey ?? '—'}</Td>
            </Row>
          ))}
        </Table>
      </Section>

      {d.exitCriteria?.length ? (
        <Section title={t('testPlanDetail.exitCriteria')}>
          <Table head={<><Th>{t('testPlanDetail.thCriterion')}</Th><Th>{t('testPlanDetail.thMetric')}</Th><Th>{t('testPlanDetail.thSmart')}</Th><Th>{t('testPlanDetail.thCite')}</Th></>}>
            {d.exitCriteria.map((e, i) => (
              <Row key={i}><Td className="text-ink-900">{e.criterion}</Td><Td className="text-muted">{e.metric ?? '—'}</Td><Td>{e.smart ? '✓' : '—'}</Td><Td className="text-[12px] text-muted">{e.citation ?? ''}</Td></Row>
            ))}
          </Table>
        </Section>
      ) : null}

      {d.estimation && (
        <Section title={t('testPlanDetail.estimation')}>
          <p className="text-sm text-ink-900">{d.estimation.technique ?? '—'} · {d.estimation.effortDays != null ? t('testPlanDetail.personDays', { days: d.estimation.effortDays }) : '—'} <span className="text-muted">{d.estimation.basis ?? ''} {d.estimation.citation ?? ''}</span></p>
        </Section>
      )}

      {d.selfReview && (
        <Section title={t('testPlanDetail.selfReview')}>
          {d.selfReview.rubricChecks?.length ? (
            <ul className="space-y-1 text-sm">{d.selfReview.rubricChecks.map((c, i) => (
              <li key={i} className="text-ink-700"><span className={c.pass ? 'text-success' : 'text-danger'}>{c.pass ? '✓' : '✗'}</span> {c.check}{c.note ? <span className="text-muted"> — {c.note}</span> : null}</li>
            ))}</ul>
          ) : null}
          {d.selfReview.blindSpots?.length ? (
            <><p className="mb-1 mt-3 font-semibold text-ink-900">{t('testPlanDetail.blindSpots')}</p><ul className="list-disc space-y-0.5 pl-5 text-sm text-warning">{d.selfReview.blindSpots.map((b, i) => <li key={i}>{b}</li>)}</ul></>
          ) : null}
        </Section>
      )}
    </div>
  );
}
