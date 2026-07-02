import { useMemo, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FileText, ExternalLink, ScrollText, CheckCircle2, XCircle, RefreshCw, Save, History, ListTree } from 'lucide-react';
import { api, Deliverable, StrategyScorecard } from '../api';
import {
  Badge, Button, Card, CardBody, CardHeader, Field, Input, PageHeader, Spinner, Table, Td, Th, Row, Textarea, ErrorState,
} from '../components/ui';
import { useToast } from '../components/Toast';
import { useCopilotGate } from '../lib/copilotAuth';
import { TONE } from '../theme/tokens';

/** The six editable strategy sections (keys match TestStrategyService.SECTIONS). */
const SECTIONS: Array<{ key: string; labelKey: string }> = [
  { key: 'summary', labelKey: 'sectionSummary' },
  { key: 'scope', labelKey: 'sectionScope' },
  { key: 'riskRegister', labelKey: 'sectionRiskRegister' },
  { key: 'testApproach', labelKey: 'sectionTestApproach' },
  { key: 'exitCriteria', labelKey: 'sectionExitCriteria' },
  { key: 'selfReview', labelKey: 'sectionSelfReview' },
];

const riskTone = (lvl?: string) => {
  const v = (lvl || '').toUpperCase();
  if (v.includes('VERY HIGH') || v === 'VH') return TONE.danger;
  if (v.includes('HIGH') || v === 'H') return TONE.danger;
  if (v.includes('MEDIUM') || v === 'M') return TONE.warn;
  return TONE.muted;
};

function pretty(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  try { return JSON.stringify(value, null, 2); } catch { return String(value); }
}

/** Human-readable view of one section's value (the editor below carries the editable JSON). */
function SectionView({ k, value }: { k: string; value: unknown }) {
  const { t } = useTranslation();
  const d = value as Deliverable['scope'] & Deliverable['testApproach'] & Deliverable['selfReview'] & Record<string, unknown>;
  if (k === 'summary') return <p className="text-sm leading-relaxed text-ink-900">{typeof value === 'string' ? value : pretty(value)}</p>;
  if (k === 'scope' && value && typeof value === 'object') {
    return (
      <div className="grid grid-cols-1 gap-4 text-sm sm:grid-cols-2">
        <div><p className="mb-1 font-semibold text-ink-900">{t('strategyDetail.objectives')}</p><ul className="list-disc space-y-0.5 pl-5 text-ink-700">{(d.objectives || []).map((x, i) => <li key={i}>{x}</li>)}</ul></div>
        <div>
          <p className="mb-1 font-semibold text-ink-900">{t('strategyDetail.inScope')}</p><ul className="list-disc space-y-0.5 pl-5 text-ink-700">{(d.inScope || []).map((x, i) => <li key={i}>{x}</li>)}</ul>
          {d.outOfScope?.length ? <><p className="mb-1 mt-3 font-semibold text-ink-900">{t('strategyDetail.outOfScope')}</p><ul className="list-disc space-y-0.5 pl-5 text-ink-700">{d.outOfScope.map((x, i) => <li key={i}>{x}</li>)}</ul></> : null}
        </div>
      </div>
    );
  }
  if (k === 'riskRegister' && Array.isArray(value)) {
    const risks = value as Deliverable['riskRegister'];
    return (
      <Table head={<><Th>{t('strategyDetail.colId')}</Th><Th>{t('strategyDetail.colRisk')}</Th><Th>{t('strategyDetail.colLevel')}</Th><Th>{t('strategyDetail.colMitigation')}</Th></>}>
        {(risks || []).map((r, i) => (
          <Row key={r.id ?? i}>
            <Td className="font-medium text-ink-900">{r.id}</Td><Td className="text-ink-900">{r.description}</Td>
            <Td><Badge className={riskTone(r.level)}>{r.level}</Badge></Td><Td className="text-muted">{r.mitigation ?? '—'}</Td>
          </Row>
        ))}
      </Table>
    );
  }
  if (k === 'testApproach' && value && typeof value === 'object') {
    return (
      <div className="text-sm">
        <p className="mb-2 text-ink-900"><span className="font-semibold">{t('strategyDetail.levels')}</span> {(d.levels || []).join(', ') || '—'} · <span className="font-semibold">{t('strategyDetail.types')}</span> {(d.types || []).join(', ') || '—'}</p>
        {d.techniques?.length ? <ul className="list-disc space-y-0.5 pl-5 text-ink-700">{d.techniques.map((t, i) => <li key={i}><span className="font-medium text-ink-900">{t.name}</span>{t.rationale ? ` — ${t.rationale}` : ''}</li>)}</ul> : null}
      </div>
    );
  }
  if (k === 'exitCriteria' && Array.isArray(value)) {
    const ex = value as Deliverable['exitCriteria'];
    return <ul className="list-disc space-y-0.5 pl-5 text-sm text-ink-700">{(ex || []).map((e, i) => <li key={i}>{e.criterion}{e.metric ? <span className="text-muted"> — {e.metric}</span> : null} {e.smart ? '✓' : ''}</li>)}</ul>;
  }
  if (k === 'selfReview' && value && typeof value === 'object') {
    const sr = value as Deliverable['selfReview'];
    return (
      <div className="text-sm">
        {sr?.confidence != null && <p className="mb-2 text-ink-900">{t('strategyDetail.confidence')} <span className="font-semibold">{Math.round(sr.confidence)}%</span></p>}
        {sr?.rubricChecks?.length ? <ul className="space-y-1">{sr.rubricChecks.map((c, i) => <li key={i} className="text-ink-700"><span className={c.pass ? 'text-success' : 'text-danger'}>{c.pass ? '✓' : '✗'}</span> {c.check}{c.note ? <span className="text-muted"> — {c.note}</span> : null}</li>)}</ul> : null}
        {sr?.blindSpots?.length ? <><p className="mb-1 mt-2 font-semibold text-ink-900">{t('strategyDetail.blindSpots')}</p><ul className="list-disc space-y-0.5 pl-5 text-warning">{sr.blindSpots.map((b, i) => <li key={i}>{b}</li>)}</ul></> : null}
      </div>
    );
  }
  return <pre className="overflow-x-auto rounded bg-ink-50 p-2 text-xs text-ink-700">{pretty(value)}</pre>;
}

/** One section card: read view + a collapsible JSON editor with Save (revise) and Regenerate (AI). */
function StrategySection({ id, sectionKey, label, value, approved }: {
  id: string; sectionKey: string; label: string; value: unknown; approved: boolean;
}) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const { blocked, notice } = useCopilotGate();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(() => pretty(value));
  const [guidance, setGuidance] = useState('');

  const invalidate = () => { qc.invalidateQueries({ queryKey: ['strategy', id] }); qc.invalidateQueries({ queryKey: ['strategy-versions', id] }); };

  const save = useMutation({
    mutationFn: () => api.reviseStrategySection(id, sectionKey, draft),
    onSuccess: () => { invalidate(); setEditing(false); toast.push('success', t('strategyDetail.savedSection', { label })); },
    onError: (e: Error) => toast.push('error', e.message),
  });
  const regen = useMutation({
    mutationFn: () => api.regenerateStrategySection(id, sectionKey, guidance.trim() || undefined),
    onSuccess: () => { invalidate(); setGuidance(''); toast.push('success', t('strategyDetail.regeneratedSection', { label })); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  return (
    <Card className="mb-4">
      <CardHeader title={label} action={
        approved ? null : (
          <button className="text-sm font-medium text-gold hover:underline"
            onClick={() => { setDraft(pretty(value)); setEditing((v) => !v); }}>
            {editing ? t('strategyDetail.close') : t('strategyDetail.edit')}
          </button>
        )} />
      <CardBody className="space-y-4">
        <SectionView k={sectionKey} value={value} />
        {editing && (
          <div className="space-y-3 border-t border-border pt-4">
            <Field label={t('strategyDetail.sectionContentLabel')} hint={t('strategyDetail.sectionContentHint')}>
              <Textarea rows={8} className="font-mono text-xs" value={draft} onChange={(e) => setDraft(e.target.value)} />
            </Field>
            <div className="flex flex-wrap items-end gap-3">
              <Button size="sm" loading={save.isPending} onClick={() => save.mutate()}>
                <Save className="h-4 w-4" /> {t('strategyDetail.saveVersion')}
              </Button>
              <div className="flex-1 min-w-[200px]">
                <Field label={t('strategyDetail.regenerateGuidanceLabel')}>
                  <Input placeholder={t('strategyDetail.regenerateGuidancePlaceholder')} value={guidance} onChange={(e) => setGuidance(e.target.value)} />
                </Field>
              </div>
              {notice}
              <Button size="sm" variant="secondary" loading={regen.isPending} disabled={blocked} onClick={() => regen.mutate()}>
                <RefreshCw className="h-4 w-4" /> {t('strategyDetail.regenerate')}
              </Button>
            </div>
          </div>
        )}
      </CardBody>
    </Card>
  );
}

/** The scorecard banner (multi-source strategies): verdict + per-rule checks. */
function ScorecardBanner({ sc }: { sc: StrategyScorecard }) {
  const { t } = useTranslation();
  const ok = (sc.verdict || '').toUpperCase() === 'OK';
  return (
    <Card className={`mb-5 border-l-4 ${ok ? 'border-l-success' : 'border-l-warning'}`}>
      <CardBody className="space-y-3">
        <div className="flex flex-wrap items-center gap-3">
          <Badge className={ok ? TONE.ok : TONE.warn}>{sc.verdict}</Badge>
          <span className="text-sm text-ink-900">{t('strategyDetail.qualityScorecard', { confidence: Math.round(sc.confidence) })}</span>
          <span className="text-sm text-muted">{t('strategyDetail.featuresCovered', { count: sc.featuresCovered })}</span>
          {sc.droppedSections > 0 && <span className="text-sm font-medium text-warning">{t('strategyDetail.sectionsDropped', { count: sc.droppedSections })}</span>}
        </div>
        {sc.checks?.length ? (
          <ul className="space-y-1 text-sm">
            {sc.checks.map((c, i) => (
              <li key={i} className="flex items-start gap-2 text-ink-700">
                {c.passed ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-success" /> : <XCircle className="mt-0.5 h-4 w-4 shrink-0 text-danger" />}
                <span><span className="font-medium text-ink-900">{c.name}</span>{c.detail ? ` — ${c.detail}` : ''}</span>
              </li>
            ))}
          </ul>
        ) : null}
      </CardBody>
    </Card>
  );
}

/** Strategy detail: the iterate-and-approve workspace — edit/regenerate each section, see versions, approve. */
export function StrategyDetail() {
  const { t } = useTranslation();
  const { id } = useParams();
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['strategy', id], queryFn: () => api.strategy(id!), enabled: !!id });
  const versionsQ = useQuery({ queryKey: ['strategy-versions', id], queryFn: () => api.strategyVersions(id!), enabled: !!id });

  const approve = useMutation({
    mutationFn: () => api.approveStrategy(id!),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['strategy', id] });
      qc.invalidateQueries({ queryKey: ['strategy-versions', id] });
      toast.push('success', t('strategyDetail.strategyApproved'));
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const deliverable = useMemo<Record<string, unknown>>(() => {
    try { return q.data?.deliverableJson ? JSON.parse(q.data.deliverableJson) : {}; } catch { return {}; }
  }, [q.data?.deliverableJson]);
  const scorecard = useMemo<StrategyScorecard | null>(() => {
    try { return q.data?.scorecardJson ? JSON.parse(q.data.scorecardJson) : null; } catch { return null; }
  }, [q.data?.scorecardJson]);

  if (q.isLoading) return <Card><CardBody className="flex items-center gap-2 text-sm text-muted"><Spinner /> {t('strategyDetail.loading')}</CardBody></Card>;
  if (q.isError || !q.data) return <ErrorState message={t('strategyDetail.couldNotLoad', { error: (q.error as Error)?.message ?? t('strategyDetail.unknownError') })} />;

  const s = q.data;
  const approved = (s.status || '').toUpperCase() === 'APPROVED';
  const versions = versionsQ.data ?? [];

  return (
    <div>
      <PageHeader
        title={t('strategyDetail.pageTitle', { service: s.serviceName ?? t('strategyDetail.strategyFallback') })}
        subtitle={`v${(s as { version?: number }).version ?? 1} · ${s.status ?? 'DRAFT'}${s.confidence != null ? t('strategyDetail.confidenceSuffix', { confidence: Math.round(s.confidence) }) : ''}`}
        actions={
          <div className="flex items-center gap-2">
            <a href={api.strategyRationaleUrl(s.id)} target="_blank" rel="noreferrer"
              className="inline-flex items-center gap-1 rounded-md px-3 py-2 text-sm font-medium text-ink-700 ring-1 ring-border hover:bg-ink-50">
              <FileText className="h-4 w-4" /> {t('strategyDetail.rationale')} <ExternalLink className="h-3 w-3" />
            </a>
            <Link to={`/test-conditions/${s.id}`}
              className="inline-flex items-center gap-1 rounded-md px-3 py-2 text-sm font-medium text-ink-700 ring-1 ring-border hover:bg-ink-50">
              <ListTree className="h-4 w-4" /> {t('strategyDetail.testConditions')}
            </Link>
            {s.source === 'multi-source' && (
              <a href={api.strategyWhyDocUrl(s.id)} target="_blank" rel="noreferrer"
                className="inline-flex items-center gap-1 rounded-md px-3 py-2 text-sm font-medium text-ink-700 ring-1 ring-border hover:bg-ink-50">
                <ScrollText className="h-4 w-4" /> {t('strategyDetail.evidence')} <ExternalLink className="h-3 w-3" />
              </a>
            )}
            {!approved && (
              <Button loading={approve.isPending} onClick={() => approve.mutate()}>
                <CheckCircle2 className="h-4 w-4" /> {t('strategyDetail.approve')}
              </Button>
            )}
            {approved && <Badge className={TONE.ok}>{t('strategyDetail.approved')}</Badge>}
          </div>
        } />

      {scorecard && <ScorecardBanner sc={scorecard} />}

      {approved && (
        <Card className="mb-5 border-l-4 border-l-success"><CardBody className="text-sm text-ink-900">
          <Trans i18nKey="strategyDetail.lockedBanner">
            This version is <span className="font-semibold">approved</span> and locked. Release plans for this service derive from it. To change it, generate a new version from the Test Strategy page.
          </Trans>
        </CardBody></Card>
      )}

      {SECTIONS.map(({ key, labelKey }) => (
        <StrategySection key={key} id={s.id} sectionKey={key} label={t(`strategyDetail.${labelKey}`)} value={deliverable[key]} approved={approved} />
      ))}

      <Card className="mb-5">
        <CardHeader title={<span className="inline-flex items-center gap-2"><History className="h-4 w-4" /> {t('strategyDetail.versionHistory')}</span>}
          subtitle={t('strategyDetail.versionHistorySubtitle')} />
        <CardBody className="p-0">
          {versions.length === 0 ? (
            <p className="px-5 py-4 text-sm text-muted">{t('strategyDetail.onlyThisVersion')}</p>
          ) : (
            <Table head={<><Th>{t('strategyDetail.colVersion')}</Th><Th>{t('strategyDetail.colStatus')}</Th><Th>{t('strategyDetail.colRevisedBy')}</Th><Th>{t('strategyDetail.colCreated')}</Th><Th /></>}>
              {versions.map((v) => {
                const vv = v as typeof v & { version?: number; revisedBy?: string };
                return (
                  <Row key={v.id}>
                    <Td className="font-medium text-ink-900">v{vv.version ?? 1}</Td>
                    <Td><Badge className={(v.status || '').toUpperCase() === 'APPROVED' ? TONE.ok : TONE.info}>{v.status ?? 'DRAFT'}</Badge></Td>
                    <Td className="text-muted">{vv.revisedBy ?? '—'}</Td>
                    <Td className="text-muted">{v.createdAt ? new Date(v.createdAt).toLocaleString() : '—'}</Td>
                    <Td className="text-right">
                      {v.id === s.id ? <span className="text-sm text-muted">{t('strategyDetail.current')}</span> :
                        <Link to={`/test-strategy/${v.id}`} className="text-sm font-medium text-gold hover:underline">{t('strategyDetail.open')}</Link>}
                    </Td>
                  </Row>
                );
              })}
            </Table>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
