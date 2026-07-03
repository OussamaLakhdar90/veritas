import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { FileText, PlayCircle, Check, X } from 'lucide-react';
import { api, Deliverable, TestPlan } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, Field, Input, PageHeader, TableSkeleton, Table, Td, Th, Row, ErrorState } from '../components/ui';
import { severityBadge, TONE } from '../theme/tokens';
import { enumLabel } from '../lib/enumLabels';
import { formatMoney } from '../lib/format';

const RISK_TO_SEV: Record<string, string> = {
  'VERY HIGH': 'BLOCKER', VH: 'BLOCKER', HIGH: 'CRITICAL', H: 'CRITICAL',
  MEDIUM: 'MAJOR', M: 'MAJOR', LOW: 'MINOR', L: 'MINOR', 'VERY LOW': 'INFO', VL: 'INFO',
};
const riskBadge = (lvl?: string) => severityBadge(RISK_TO_SEV[(lvl || '').toUpperCase()] ?? 'INFO');
const matchTone = (m?: string) => {
  const v = (m || '').toUpperCase();
  if (v === 'MATCHED') return TONE.ok;
  if (v === 'CREATED') return TONE.info;   // a routine "created" state is informational blue, not brand gold
  if (v === 'GAP') return TONE.warn;
  return TONE.muted; // ORPHAN / DEAD
};

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <Card className="mb-6">
      <CardHeader title={title} />
      <CardBody>{children}</CardBody>
    </Card>
  );
}

const VERDICT_TONE: Record<string, string> = {
  PASS: TONE.ok, PASSED: TONE.ok, GREEN: TONE.ok,
  FAIL: TONE.danger, FAILED: TONE.danger, RED: TONE.danger,
  BLOCKED: TONE.warn, AMBER: TONE.warn, PARTIAL: TONE.warn,
};

/**
 * Read-back of how the plan's tests actually ran, from Xray — the one piece of live data the plan detail can
 * add. Lazy by design (`enabled: false` + a button): the demo portfolio seeds NO execution data, so an
 * eager fetch would render an empty card on every plan; the user pulls it only when they want live status.
 * The JQL is prefilled from the plan's fixVersion when one exists.
 */
function ExecutionStatusCard({ plan }: { plan: TestPlan }) {
  const { t } = useTranslation();
  const [jql, setJql] = useState(plan.fixVersion ? `fixVersion = "${plan.fixVersion}"` : '');
  const q = useQuery({
    queryKey: ['execution-completion', plan.serviceName, jql],
    queryFn: () => api.executionCompletion(jql, plan.serviceName),
    enabled: false,
  });
  const e = q.data;
  const bar: Array<[keyof typeof BAR_TONE, number]> = e
    ? [['passed', e.passed], ['failed', e.failed], ['blocked', e.blocked], ['notRun', e.notRun]] : [];
  const BAR_TONE = { passed: 'bg-success', failed: 'bg-danger', blocked: 'bg-warning', notRun: 'bg-ink-100' } as const;

  return (
    <Section title={t('testPlanDetail.executionStatus')}>
      <div className="flex flex-wrap items-end gap-3">
        <div className="min-w-[16rem] flex-1">
          <Field label={t('testPlanDetail.execJqlLabel')} hint={t('testPlanDetail.execJqlHint')}>
            <Input value={jql} onChange={(ev) => setJql(ev.target.value)} placeholder='fixVersion = "2.4.0"' />
          </Field>
        </div>
        <Button variant="secondary" loading={q.isFetching} disabled={!jql.trim()} onClick={() => q.refetch()}>
          <PlayCircle className="h-4 w-4" /> {t('testPlanDetail.execCheck')}
        </Button>
      </div>

      {q.isError && <div className="mt-4"><ErrorState message={t('testPlanDetail.execFailed')} detail={(q.error as Error)?.message} /></div>}

      {e && (
        e.total === 0 ? (
          <p className="mt-4 text-sm text-muted">{t('testPlanDetail.execEmpty')}</p>
        ) : (
          <div className="mt-4 space-y-3">
            <div className="flex items-center gap-3">
              <span className="text-sm text-muted">{t('testPlanDetail.execTotal', { count: e.total })}</span>
              {e.verdict && <Badge className={VERDICT_TONE[e.verdict.toUpperCase()] ?? TONE.muted}>{enumLabel(t, 'verdict', e.verdict)}</Badge>}
            </div>
            {/* Segmented pass/fail/blocked/not-run bar. */}
            <div className="flex h-3 w-full overflow-hidden rounded-full bg-ink-100">
              {bar.filter(([, n]) => n > 0).map(([k, n]) => (
                <div key={k} className={BAR_TONE[k]} style={{ width: `${(n / e.total) * 100}%` }} title={`${t(`testPlanDetail.exec_${k}`)}: ${n}`} />
              ))}
            </div>
            <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
              {bar.map(([k, n]) => (
                <span key={k} className="inline-flex items-center gap-1.5 text-ink-700">
                  <span className={`h-2.5 w-2.5 rounded-sm ${BAR_TONE[k]}`} /> {t(`testPlanDetail.exec_${k}`)}
                  <span className="font-semibold tabular-nums text-ink-900">{n}</span>
                </span>
              ))}
            </div>
            {e.deviations.length > 0 && (
              <div className="pt-1">
                <p className="mb-1 text-sm font-semibold text-ink-900">{t('testPlanDetail.execDeviations', { count: e.deviations.length })}</p>
                <ul className="space-y-0.5 text-sm text-muted">
                  {e.deviations.map((d) => (
                    <li key={d.testKey}><span className="font-mono text-xs text-ink-700">{d.testKey}</span> — {d.outcome}{d.rawStatus ? ` (${d.rawStatus})` : ''}</li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )
      )}
    </Section>
  );
}

export function TestPlanDetail() {
  const { t } = useTranslation();
  const { id } = useParams();
  const q = useQuery({ queryKey: ['test-plan', id], queryFn: () => api.testPlan(id!), enabled: !!id });

  if (q.isLoading) return <Card><CardBody className="p-0"><TableSkeleton label={t('testPlanDetail.loading')} /></CardBody></Card>;
  if (q.isError || !q.data) return <ErrorState message={t('testPlanDetail.couldNotLoad')} detail={(q.error as Error)?.message} />;

  const { plan, coverage = [] } = q.data;
  let d: Deliverable = {};
  try { d = plan.deliverableJson ? JSON.parse(plan.deliverableJson) : {}; } catch { /* keep empty */ }
  const conf = d.selfReview?.confidence ?? plan.confidence;

  return (
    <div>
      <PageHeader
        title={`${t('testPlanDetail.titlePrefix', { serviceName: plan.serviceName })}${plan.fixVersion ? ` (${plan.fixVersion})` : ''}`}
        subtitle={t('testPlanDetail.subtitle', {
          kind: enumLabel(t, 'planKind', plan.kind),
          status: enumLabel(t, 'planStatus', plan.status),
          cost: formatMoney(plan.estCostUsd ?? 0, 4),
        })}
        actions={
          <div className="flex items-center gap-2">
            <a href={api.testPlanReportUrl(plan.id, 'html')} target="_blank" rel="noreferrer"
              className="inline-flex items-center gap-1 rounded-md px-3 py-2 text-sm font-medium text-ink-700 ring-1 ring-border hover:bg-ink-50"><FileText className="h-4 w-4" /> HTML</a>
            <a href={api.testPlanReportUrl(plan.id, 'pdf')} target="_blank" rel="noreferrer"
              className="inline-flex items-center gap-1 rounded-md px-3 py-2 text-sm font-medium text-ink-700 ring-1 ring-border hover:bg-ink-50"><FileText className="h-4 w-4" /> PDF</a>
          </div>
        } />

      {conf != null && (
        <Card className="mb-5"><CardBody className="flex items-center gap-4">
          <div className={`text-stat font-semibold tabular-nums ${conf >= 70 ? 'text-success' : 'text-warning'}`}>{Math.round(conf)}%</div>
          <div className="text-sm text-muted">{t('testPlanDetail.selfReviewConfidence')}</div>
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
          {d.scope.assumptions?.length ? <p className="mt-3 text-sm text-muted">{t('testPlanDetail.assumptions', { items: d.scope.assumptions.join('; ') })}</p> : null}
        </Section>
      )}

      {d.riskRegister?.length ? (
        <Section title={t('testPlanDetail.riskRegister', { n: d.riskRegister.length })}>
          <Table head={<><Th>{t('testPlanDetail.thId')}</Th><Th>{t('testPlanDetail.thRisk')}</Th><Th>{t('testPlanDetail.thQualityChar')}</Th><Th>{t('testPlanDetail.thL')}</Th><Th>{t('testPlanDetail.thI')}</Th><Th>{t('testPlanDetail.thLevel')}</Th><Th>{t('testPlanDetail.thMitigation')}</Th><Th>{t('testPlanDetail.thCite')}</Th></>}>
            {d.riskRegister.map((r) => (
              <Row key={r.id}>
                <Td className="font-medium text-ink-900">{r.id}</Td><Td className="text-ink-900">{r.description}</Td>
                <Td className="text-muted">{r.qualityCharacteristic ?? '—'}</Td><Td className="text-muted">{r.likelihood ?? '—'}</Td><Td className="text-muted">{r.impact ?? '—'}</Td>
                <Td><Badge className={riskBadge(r.level)}>{enumLabel(t, 'riskLevel', r.level)}</Badge></Td>
                <Td className="text-muted">{r.mitigation ?? '—'}</Td><Td className="text-xs text-muted">{r.citation ?? ''}</Td>
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
                <Row key={i}><Td className="font-medium text-ink-900">{t.name}</Td><Td className="text-ink-700">{t.rationale ?? '—'}</Td><Td className="text-xs text-muted">{t.citation ?? ''}</Td></Row>
              ))}
            </Table>
          ) : null}
          {d.testApproach.entryCriteria?.length ? <p className="mt-3 text-sm text-muted">{t('testPlanDetail.entry', { items: d.testApproach.entryCriteria.join('; ') })}</p> : null}
        </Section>
      )}

      <Section title={t('testPlanDetail.rtm', { n: coverage.length })}>
        <Table head={<><Th>{t('testPlanDetail.thRequirement')}</Th><Th>{t('testPlanDetail.thRequiredCase')}</Th><Th>{t('testPlanDetail.thDimension')}</Th><Th>{t('testPlanDetail.thStatus')}</Th><Th>{t('testPlanDetail.thMatchedTest')}</Th></>}>
          {coverage.map((c, i) => (
            <Row key={i}>
              <Td className="text-ink-900">{c.requirementKey ?? '—'}</Td><Td className="text-ink-700">{c.requiredCaseRef}</Td><Td className="text-muted">{c.dimension}</Td>
              <Td><Badge className={matchTone(c.matchStatus)}>{enumLabel(t, 'matchStatus', c.matchStatus)}</Badge></Td>
              <Td className="font-mono text-xs text-muted">{c.matchedTestKey ?? '—'}</Td>
            </Row>
          ))}
        </Table>
      </Section>

      {/* Live execution read-back (lazy — the demo seeds none). */}
      <ExecutionStatusCard plan={plan} />

      {d.exitCriteria?.length ? (
        <Section title={t('testPlanDetail.exitCriteria')}>
          <Table head={<><Th>{t('testPlanDetail.thCriterion')}</Th><Th>{t('testPlanDetail.thMetric')}</Th><Th>{t('testPlanDetail.thSmart')}</Th><Th>{t('testPlanDetail.thCite')}</Th></>}>
            {d.exitCriteria.map((e, i) => (
              <Row key={i}><Td className="text-ink-900">{e.criterion}</Td><Td className="text-muted">{e.metric ?? '—'}</Td><Td>{e.smart ? <Check className="inline h-4 w-4 text-success" /> : '—'}</Td><Td className="text-xs text-muted">{e.citation ?? ''}</Td></Row>
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
              <li key={i} className="text-ink-700">{c.pass ? <Check className="inline h-4 w-4 text-success" /> : <X className="inline h-4 w-4 text-danger" />} {c.check}{c.note ? <span className="text-muted"> — {c.note}</span> : null}</li>
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
