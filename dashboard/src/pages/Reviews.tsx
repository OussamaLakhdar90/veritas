import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ListChecks, Play, ChevronRight, ChevronDown, Search } from 'lucide-react';
import { api, ReviewResult, ReviewDeliverable, ReviewCandidate } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, ErrorState, Field, Input, PageContainer, PageHeader, Table, Td, Th, Row } from '../components/ui';
import { useToast } from '../components/Toast';
import { useCopilotGate } from '../lib/copilotAuth';
import { TONE } from '../theme/tokens';
import { enumLabel } from '../lib/enumLabels';

const verdictTone = (v?: string) => {
  const u = (v || '').toUpperCase();
  if (u.includes('PASS') || u.includes('APPROVE') || u.includes('GOOD')) return TONE.ok;
  if (u.includes('FAIL') || u.includes('REJECT') || u.includes('POOR')) return TONE.danger;
  return TONE.warn;
};

/** ISTQB Test-Analyst review wizard: score Xray tests selected by JQL against the C1–C6 rubric. */
function parseDeliverable(json?: string): ReviewDeliverable | null {
  if (!json) return null;
  try { return JSON.parse(json) as ReviewDeliverable; } catch { return null; }
}

/** The expanded detail for one review: C1–C6 rubric, gaps, corrected steps, self-review. */
function ReviewDetail({ r }: { r: ReviewResult }) {
  const { t } = useTranslation();
  const d = parseDeliverable(r.deliverableJson);
  if (!d) return <p className="text-sm text-muted">{t('reviews.noDetail')}</p>;
  return (
    <div className="space-y-5">
      {d.rubric?.length ? (
        <div>
          <p className="mb-1.5 text-sm font-semibold text-ink-900">{t('reviews.rubricHeading')}</p>
          <Table head={<><Th>{t('reviews.thCriterion')}</Th><Th className="text-right">{t('reviews.thScore')}</Th><Th>{t('reviews.thNote')}</Th></>}>
            {d.rubric.map((c, i) => (
              <Row key={i}><Td className="font-medium text-ink-900">{c.criterion}</Td>
                <Td className="text-right tabular-nums text-ink-900">{c.score ?? '—'}</Td>
                <Td className="text-muted">{c.note ?? '—'}</Td></Row>
            ))}
          </Table>
        </div>
      ) : null}
      {d.gaps?.length ? (
        <div>
          <p className="mb-1.5 text-sm font-semibold text-ink-900">{t('reviews.gapsHeading', { count: d.gaps.length })}</p>
          <ul className="list-disc space-y-0.5 pl-5 text-sm text-ink-700">
            {d.gaps.map((g, i) => <li key={i}>{g.criterion ? <span className="font-medium">{g.criterion}: </span> : null}{g.issue}{g.citation ? <span className="text-muted"> — {g.citation}</span> : null}</li>)}
          </ul>
        </div>
      ) : null}
      {d.correctedSteps?.length ? (
        <div>
          <p className="mb-1.5 text-sm font-semibold text-ink-900">{t('reviews.correctedStepsHeading')}</p>
          <Table head={<><Th>{t('reviews.thAction')}</Th><Th>{t('reviews.thData')}</Th><Th>{t('reviews.thExpected')}</Th></>}>
            {d.correctedSteps.map((s, i) => (
              <Row key={i}><Td className="text-ink-900">{s.action ?? '—'}</Td><Td className="text-muted">{s.data ?? '—'}</Td><Td className="text-muted">{s.expected ?? '—'}</Td></Row>
            ))}
          </Table>
        </div>
      ) : null}
      {d.selfReview?.blindSpots?.length ? (
        <div>
          <p className="mb-1 text-sm font-semibold text-ink-900">{t('reviews.blindSpots')}{d.selfReview.confidence != null ? t('reviews.blindSpotsConfidence', { confidence: Math.round(d.selfReview.confidence) }) : ''}</p>
          <ul className="list-disc space-y-0.5 pl-5 text-sm text-warning">{d.selfReview.blindSpots.map((b, i) => <li key={i}>{b}</li>)}</ul>
        </div>
      ) : null}
    </div>
  );
}

export function Reviews() {
  const { t } = useTranslation();
  const toast = useToast();
  const { blocked, notice } = useCopilotGate();
  const [jql, setJql] = useState('');
  const [apply, setApply] = useState(false);
  const [candidates, setCandidates] = useState<ReviewCandidate[] | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [results, setResults] = useState<ReviewResult[] | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const qc = useQueryClient();
  const recentQ = useQuery({ queryKey: ['reviews-recent'], queryFn: api.recentReviews });
  const recent = recentQ.data ?? [];

  // Optional pre-step: list the tests the JQL selects so the user can pick a subset (defaults to all selected).
  const load = useMutation({
    mutationFn: () => api.reviewCandidates(jql),
    onSuccess: (c) => {
      setCandidates(c);
      setSelected(new Set(c.map((t) => t.key)));
      setResults(null);
      if (c.length === 0) toast.push('error', t('reviews.toastNoCandidates'));
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const run = useMutation({
    // With candidates loaded, review only the ticked subset; otherwise review every JQL match (the original flow).
    mutationFn: () => api.runReview({ jql, apply, testKeys: candidates ? [...selected] : undefined }),
    onSuccess: (r) => { setResults(r); qc.invalidateQueries({ queryKey: ['reviews-recent'] }); toast.push('success', t('reviews.toastReviewed', { count: r.length })); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const toggle = (key: string) => setSelected((s) => {
    const n = new Set(s); if (n.has(key)) { n.delete(key); } else { n.add(key); } return n;
  });
  const allSelected = candidates != null && candidates.length > 0 && selected.size === candidates.length;
  const toggleAll = () => setSelected(allSelected ? new Set() : new Set((candidates ?? []).map((t) => t.key)));

  return (
    <PageContainer variant="wide">
      <PageHeader title={t('reviews.pageTitle')} subtitle={t('reviews.pageSubtitle')} />

      <Card className="mb-6">
        <CardHeader title={t('reviews.newReviewTitle')} subtitle={t('reviews.newReviewSubtitle')} />
        <CardBody className="space-y-4">
          <Field label={t('reviews.jqlLabel')} hint={t('reviews.jqlHint')}>
            <div className="flex gap-2">
              <Input placeholder={t('reviews.jqlPlaceholder')} value={jql}
                onChange={(e) => { setJql(e.target.value); setCandidates(null); }}
                onKeyDown={(e) => e.key === 'Enter' && jql.trim() && !blocked && run.mutate()} />
              <Button variant="secondary" loading={load.isPending}
                onClick={() => jql.trim() ? load.mutate() : toast.push('error', t('reviews.toastEnterJql'))}>
                <Search className="h-4 w-4" /> {t('reviews.loadTests')}
              </Button>
            </div>
          </Field>

          {candidates != null && candidates.length > 0 && (
            <div className="rounded-lg border border-border">
              <label className="flex items-center gap-2 px-3 py-2 text-sm text-ink-700">
                <input type="checkbox" className="h-4 w-4 accent-brand"
                  checked={allSelected} onChange={toggleAll} aria-label={t('reviews.selectAll')} />
                {t('reviews.selectedCount', { selected: selected.size, total: candidates.length })}
              </label>
              <Table head={<><Th /><Th>{t('reviews.thKey')}</Th><Th>{t('reviews.thSummary')}</Th><Th>{t('reviews.thType')}</Th><Th className="text-right">{t('reviews.thSteps')}</Th></>}>
                {candidates.map((c) => (
                  <Row key={c.key}>
                    <Td><input type="checkbox" aria-label={t('reviews.selectKey', { key: c.key })}
                      className="h-4 w-4 accent-brand"
                      checked={selected.has(c.key)} onChange={() => toggle(c.key)} /></Td>
                    <Td className="font-mono text-xs text-ink-900">{c.key}</Td>
                    <Td className="text-ink-900">{c.summary ?? '—'}</Td>
                    <Td className="text-muted">{c.testType ?? '—'}</Td>
                    <Td className="text-right tabular-nums text-muted">{c.steps}</Td>
                  </Row>
                ))}
              </Table>
            </div>
          )}

          <div className="flex items-center justify-between">
            <label className="inline-flex items-center gap-2 text-sm text-ink-700">
              <input type="checkbox" className="h-4 w-4 accent-brand"
                checked={apply} onChange={(e) => setApply(e.target.checked)} />
              {t('reviews.applyLabel')}
            </label>
            <span className="flex items-center gap-3">
              {notice}
              <Button loading={run.isPending} disabled={blocked || (candidates != null && selected.size === 0)}
                onClick={() => jql.trim() ? run.mutate() : toast.push('error', t('reviews.toastEnterJql'))}>
                <Play className="h-4 w-4" /> {candidates != null ? t('reviews.reviewSelected', { count: selected.size }) : t('reviews.runReview')}
              </Button>
            </span>
          </div>
        </CardBody>
      </Card>

      {results == null ? (
        recentQ.isError ? (
          // A fetch failure must not masquerade as "no reviews yet".
          <ErrorState message={t('reviews.loadError')} detail={(recentQ.error as Error).message} />
        ) : recent.length > 0 ? (
          <Card>
            <CardHeader title={t('reviews.recentTitle')} subtitle={t('reviews.recentSubtitle')} />
            <CardBody className="p-0"><ResultsTable results={recent} openId={openId} setOpenId={setOpenId} /></CardBody>
          </Card>
        ) : (
          <EmptyState icon={ListChecks} title={t('reviews.emptyNoReviewTitle')} body={t('reviews.emptyNoReviewBody')} />
        )
      ) : results.length === 0 ? (
        <EmptyState icon={ListChecks} title={t('reviews.emptyNoMatchTitle')} body={t('reviews.emptyNoMatchBody')} />
      ) : (
        <Card>
          <CardHeader title={t('reviews.resultsTitle', { count: results.length })} />
          <CardBody className="p-0"><ResultsTable results={results} openId={openId} setOpenId={setOpenId} /></CardBody>
        </Card>
      )}
    </PageContainer>
  );
}

/** The expandable per-test verdict table — shared by a fresh run's results and the recent-reviews history. */
function ResultsTable({ results, openId, setOpenId }:
  { results: ReviewResult[]; openId: string | null; setOpenId: (id: string | null) => void }) {
  const { t } = useTranslation();
  return (
    <Table head={<><Th /><Th>{t('reviews.thTarget')}</Th><Th>{t('reviews.thVerdict')}</Th><Th className="text-right">{t('reviews.thScore')}</Th><Th className="text-right">{t('reviews.thConfidence')}</Th></>}>
      {results.flatMap((r) => {
        const open = openId === r.id;
        const main = (
          <Row key={r.id} className="cursor-pointer" onClick={() => setOpenId(open ? null : r.id)}>
            <Td className="text-muted">{open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}</Td>
            <Td className="font-mono text-xs text-ink-900">{r.targetKey ?? '—'}</Td>
            <Td><Badge className={verdictTone(r.verdict)}>{enumLabel(t, 'verdict', r.verdict)}</Badge></Td>
            <Td className="text-right tabular-nums text-ink-900">{r.score != null ? r.score : '—'}</Td>
            <Td className="text-right tabular-nums text-muted">{r.confidence != null ? `${Math.round(r.confidence)}%` : '—'}</Td>
          </Row>
        );
        return open
          ? [main, <Row key={`${r.id}-detail`}><Td colSpan={5} className="bg-ink-50/40"><ReviewDetail r={r} /></Td></Row>]
          : [main];
      })}
    </Table>
  );
}
