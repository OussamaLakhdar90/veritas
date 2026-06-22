import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { ListChecks, Play } from 'lucide-react';
import { api, ReviewResult } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, Field, Input, PageHeader, Table, Td, Th, Row } from '../components/ui';
import { useToast } from '../components/Toast';
import { TONE } from '../theme/tokens';

const verdictTone = (v?: string) => {
  const u = (v || '').toUpperCase();
  if (u.includes('PASS') || u.includes('APPROVE') || u.includes('GOOD')) return TONE.ok;
  if (u.includes('FAIL') || u.includes('REJECT') || u.includes('POOR')) return TONE.danger;
  return TONE.warn;
};

/** ISTQB Test-Analyst review wizard: score Xray tests selected by JQL against the C1–C6 rubric. */
export function Reviews() {
  const toast = useToast();
  const [jql, setJql] = useState('');
  const [apply, setApply] = useState(false);
  const [results, setResults] = useState<ReviewResult[] | null>(null);

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
              onKeyDown={(e) => e.key === 'Enter' && jql.trim() && run.mutate()} />
          </Field>
          <div className="flex items-center justify-between">
            <label className="inline-flex items-center gap-2 text-[13px] text-ink-700">
              <input type="checkbox" className="h-4 w-4 rounded border-border text-brand focus:ring-brand/40"
                checked={apply} onChange={(e) => setApply(e.target.checked)} />
              Post the review as a Jira comment (gated write)
            </label>
            <Button loading={run.isPending} onClick={() => jql.trim() ? run.mutate() : toast.push('error', 'Enter a JQL query.')}>
              <Play className="h-4 w-4" /> Run review
            </Button>
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
            <Table head={<><Th>Target</Th><Th>Verdict</Th><Th className="text-right">Score</Th><Th className="text-right">Confidence</Th></>}>
              {results.map((r) => (
                <Row key={r.id}>
                  <Td className="font-mono text-[12.5px] text-ink-900">{r.targetKey ?? '—'}</Td>
                  <Td><Badge className={verdictTone(r.verdict)}>{r.verdict ?? '—'}</Badge></Td>
                  <Td className="text-right tabular-nums text-ink-900">{r.score != null ? r.score : '—'}</Td>
                  <Td className="text-right tabular-nums text-muted">{r.confidence != null ? `${Math.round(r.confidence)}%` : '—'}</Td>
                </Row>
              ))}
            </Table>
          </CardBody>
        </Card>
      )}
    </div>
  );
}
