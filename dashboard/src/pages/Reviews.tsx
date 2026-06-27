import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { ListChecks, Play, ChevronRight, ChevronDown, Search } from 'lucide-react';
import { api, ReviewResult, ReviewDeliverable, ReviewCandidate } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, Field, Input, PageHeader, Table, Td, Th, Row } from '../components/ui';
import { useToast } from '../components/Toast';
import { useCopilotGate } from '../lib/copilotAuth';
import { TONE } from '../theme/tokens';

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
  const d = parseDeliverable(r.deliverableJson);
  if (!d) return <p className="text-[13px] text-muted">No detail captured for this review.</p>;
  return (
    <div className="space-y-5">
      {d.rubric?.length ? (
        <div>
          <p className="mb-1.5 text-[13px] font-semibold text-ink-900">Rubric (C1–C6)</p>
          <Table head={<><Th>Criterion</Th><Th className="text-right">Score</Th><Th>Note</Th></>}>
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
          <p className="mb-1.5 text-[13px] font-semibold text-ink-900">Gaps ({d.gaps.length})</p>
          <ul className="list-disc space-y-0.5 pl-5 text-[13px] text-ink-700">
            {d.gaps.map((g, i) => <li key={i}>{g.criterion ? <span className="font-medium">{g.criterion}: </span> : null}{g.issue}{g.citation ? <span className="text-muted"> — {g.citation}</span> : null}</li>)}
          </ul>
        </div>
      ) : null}
      {d.correctedSteps?.length ? (
        <div>
          <p className="mb-1.5 text-[13px] font-semibold text-ink-900">Corrected steps</p>
          <Table head={<><Th>Action</Th><Th>Data</Th><Th>Expected</Th></>}>
            {d.correctedSteps.map((s, i) => (
              <Row key={i}><Td className="text-ink-900">{s.action ?? '—'}</Td><Td className="text-muted">{s.data ?? '—'}</Td><Td className="text-muted">{s.expected ?? '—'}</Td></Row>
            ))}
          </Table>
        </div>
      ) : null}
      {d.selfReview?.blindSpots?.length ? (
        <div>
          <p className="mb-1 text-[13px] font-semibold text-ink-900">Blind spots{d.selfReview.confidence != null ? ` · ${Math.round(d.selfReview.confidence)}% confidence` : ''}</p>
          <ul className="list-disc space-y-0.5 pl-5 text-[13px] text-warning">{d.selfReview.blindSpots.map((b, i) => <li key={i}>{b}</li>)}</ul>
        </div>
      ) : null}
    </div>
  );
}

export function Reviews() {
  const toast = useToast();
  const { blocked, notice } = useCopilotGate();
  const [jql, setJql] = useState('');
  const [apply, setApply] = useState(false);
  const [candidates, setCandidates] = useState<ReviewCandidate[] | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [results, setResults] = useState<ReviewResult[] | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);

  // Optional pre-step: list the tests the JQL selects so the user can pick a subset (defaults to all selected).
  const load = useMutation({
    mutationFn: () => api.reviewCandidates(jql),
    onSuccess: (c) => {
      setCandidates(c);
      setSelected(new Set(c.map((t) => t.key)));
      setResults(null);
      if (c.length === 0) toast.push('error', 'The JQL returned no Xray tests.');
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const run = useMutation({
    // With candidates loaded, review only the ticked subset; otherwise review every JQL match (the original flow).
    mutationFn: () => api.runReview({ jql, apply, testKeys: candidates ? [...selected] : undefined }),
    onSuccess: (r) => { setResults(r); toast.push('success', `Reviewed ${r.length} test${r.length === 1 ? '' : 's'}.`); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const toggle = (key: string) => setSelected((s) => {
    const n = new Set(s); if (n.has(key)) { n.delete(key); } else { n.add(key); } return n;
  });
  const allSelected = candidates != null && candidates.length > 0 && selected.size === candidates.length;
  const toggleAll = () => setSelected(allSelected ? new Set() : new Set((candidates ?? []).map((t) => t.key)));

  return (
    <div>
      <PageHeader title="Review test cases" subtitle="Score existing Xray tests against the ISTQB Test-Analyst rubric (C1–C6)." />

      <Card className="mb-6">
        <CardHeader title="New review" subtitle="Find tests by JQL, pick which to review, optionally write the verdict back to Jira." />
        <CardBody className="space-y-4">
          <Field label="JQL" hint='Which Xray tests, e.g. project = CIAM AND issuetype = Test — load to pick a subset, or review all'>
            <div className="flex gap-2">
              <Input placeholder="project = CIAM AND issuetype = Test" value={jql}
                onChange={(e) => { setJql(e.target.value); setCandidates(null); }}
                onKeyDown={(e) => e.key === 'Enter' && jql.trim() && !blocked && run.mutate()} />
              <Button variant="secondary" loading={load.isPending}
                onClick={() => jql.trim() ? load.mutate() : toast.push('error', 'Enter a JQL query.')}>
                <Search className="h-4 w-4" /> Load tests
              </Button>
            </div>
          </Field>

          {candidates != null && candidates.length > 0 && (
            <div className="rounded-lg border border-border">
              <label className="flex items-center gap-2 px-3 py-2 text-[13px] text-ink-700">
                <input type="checkbox" className="h-4 w-4 rounded border-border text-brand focus:ring-brand/40"
                  checked={allSelected} onChange={toggleAll} aria-label="Select all" />
                {selected.size} of {candidates.length} selected
              </label>
              <Table head={<><Th /><Th>Key</Th><Th>Summary</Th><Th>Type</Th><Th className="text-right">Steps</Th></>}>
                {candidates.map((t) => (
                  <Row key={t.key}>
                    <Td><input type="checkbox" aria-label={`Select ${t.key}`}
                      className="h-4 w-4 rounded border-border text-brand focus:ring-brand/40"
                      checked={selected.has(t.key)} onChange={() => toggle(t.key)} /></Td>
                    <Td className="font-mono text-[12.5px] text-ink-900">{t.key}</Td>
                    <Td className="text-ink-900">{t.summary ?? '—'}</Td>
                    <Td className="text-muted">{t.testType ?? '—'}</Td>
                    <Td className="text-right tabular-nums text-muted">{t.steps}</Td>
                  </Row>
                ))}
              </Table>
            </div>
          )}

          <div className="flex items-center justify-between">
            <label className="inline-flex items-center gap-2 text-[13px] text-ink-700">
              <input type="checkbox" className="h-4 w-4 rounded border-border text-brand focus:ring-brand/40"
                checked={apply} onChange={(e) => setApply(e.target.checked)} />
              Apply the corrected steps back to Xray (gated write)
            </label>
            <span className="flex items-center gap-3">
              {notice}
              <Button loading={run.isPending} disabled={blocked || (candidates != null && selected.size === 0)}
                onClick={() => jql.trim() ? run.mutate() : toast.push('error', 'Enter a JQL query.')}>
                <Play className="h-4 w-4" /> {candidates != null ? `Review selected (${selected.size})` : 'Run review'}
              </Button>
            </span>
          </div>
        </CardBody>
      </Card>

      {results == null ? (
        <EmptyState icon={ListChecks} title="No review yet" body="Run a review above to see per-test verdicts and scores." />
      ) : results.length === 0 ? (
        <EmptyState icon={ListChecks} title="No tests matched" body="The JQL returned no Xray tests to review." />
      ) : (
        <Card>
          <CardHeader title={`Results (${results.length})`} />
          <CardBody className="p-0">
            <Table head={<><Th /><Th>Target</Th><Th>Verdict</Th><Th className="text-right">Score</Th><Th className="text-right">Confidence</Th></>}>
              {results.flatMap((r) => {
                const open = openId === r.id;
                const main = (
                  <Row key={r.id} className="cursor-pointer" onClick={() => setOpenId(open ? null : r.id)}>
                    <Td className="text-muted">{open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}</Td>
                    <Td className="font-mono text-[12.5px] text-ink-900">{r.targetKey ?? '—'}</Td>
                    <Td><Badge className={verdictTone(r.verdict)}>{r.verdict ?? '—'}</Badge></Td>
                    <Td className="text-right tabular-nums text-ink-900">{r.score != null ? r.score : '—'}</Td>
                    <Td className="text-right tabular-nums text-muted">{r.confidence != null ? `${Math.round(r.confidence)}%` : '—'}</Td>
                  </Row>
                );
                return open
                  ? [main, <Row key={`${r.id}-detail`}><Td colSpan={5} className="bg-ink-50/40"><ReviewDetail r={r} /></Td></Row>]
                  : [main];
              })}
            </Table>
          </CardBody>
        </Card>
      )}
    </div>
  );
}
