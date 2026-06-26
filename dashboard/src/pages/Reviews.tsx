import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { ListChecks, Play, ChevronRight, ChevronDown } from 'lucide-react';
import { api, ReviewResult, ReviewDeliverable } from '../api';
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
  const [results, setResults] = useState<ReviewResult[] | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);

  const run = useMutation({
    mutationFn: () => api.runReview({ jql, apply }),
    onSuccess: (r) => { setResults(r); toast.push('success', `Reviewed ${r.length} test${r.length === 1 ? '' : 's'}.`); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  return (
    <div>
      <PageHeader title="Review test cases" subtitle="Score existing Xray tests against the ISTQB Test-Analyst rubric (C1–C6)." />

      <Card className="mb-6">
        <CardHeader title="New review" subtitle="Select the tests to review by JQL; optionally write the verdict back to Jira." />
        <CardBody className="space-y-4">
          <Field label="JQL" hint='Which Xray tests to review, e.g. project = CIAM AND issuetype = Test'>
            <Input placeholder="project = CIAM AND issuetype = Test" value={jql} onChange={(e) => setJql(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && jql.trim() && !blocked && run.mutate()} />
          </Field>
          <div className="flex items-center justify-between">
            <label className="inline-flex items-center gap-2 text-[13px] text-ink-700">
              <input type="checkbox" className="h-4 w-4 rounded border-border text-brand focus:ring-brand/40"
                checked={apply} onChange={(e) => setApply(e.target.checked)} />
              Post the review as a Jira comment (gated write)
            </label>
            <span className="flex items-center gap-3">
              {notice}
              <Button loading={run.isPending} disabled={blocked}
                onClick={() => jql.trim() ? run.mutate() : toast.push('error', 'Enter a JQL query.')}>
                <Play className="h-4 w-4" /> Run review
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
