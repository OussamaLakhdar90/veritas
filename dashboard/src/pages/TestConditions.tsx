import { useMemo } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FileText, ExternalLink, ListTree } from 'lucide-react';
import { api, TestCondition } from '../api';
import { Badge, Card, CardBody, CardHeader, PageHeader, Spinner, Table, Td, Th, Row, ErrorState } from '../components/ui';
import { useToast } from '../components/Toast';
import { TONE } from '../theme/tokens';

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

  if (q.isLoading) return <Card><CardBody className="flex items-center gap-2 text-sm text-muted"><Spinner /> Loading…</CardBody></Card>;
  if (q.isError) return <ErrorState message={`Could not load test conditions: ${(q.error as Error)?.message ?? 'unknown error'}`} />;

  const service = strategyQ.data?.serviceName ?? 'Service';

  return (
    <div>
      <PageHeader
        title={`${service} — Test Conditions`}
        subtitle={`${conditions.length} condition(s) · identified during test analysis (what to test)`}
        actions={
          <div className="flex items-center gap-2">
            {id && (
              <a href={api.testConditionsReportUrl(id)} target="_blank" rel="noreferrer"
                className="inline-flex items-center gap-1 rounded-md px-3 py-2 text-[13px] font-medium text-ink-700 ring-1 ring-border hover:bg-ink-50">
                <FileText className="h-4 w-4" /> Condition List <ExternalLink className="h-3 w-3" />
              </a>
            )}
            <Link to={`/test-strategy/${id}`}
              className="inline-flex items-center gap-1 rounded-md px-3 py-2 text-[13px] font-medium text-ink-700 ring-1 ring-border hover:bg-ink-50">
              <ListTree className="h-4 w-4" /> Strategy
            </Link>
          </div>
        } />

      <Card className="mb-5">
        <CardBody className="flex flex-wrap items-center gap-3 text-sm">
          <span className="font-semibold text-ink-900">Automation split</span>
          <Badge className={TONE.ok}>{split.AUTOMATED} automated</Badge>
          <Badge className={TONE.danger}>{split.MANUAL} manual</Badge>
          <Badge className={TONE.warn}>{split.CANDIDATE} candidate</Badge>
          <span className="text-muted">— decided per condition by risk × repeatability × stability.</span>
          <span className="w-full text-[12px] text-muted">
            AUTOMATED conditions feed <span className="font-medium text-ink-700">implement-tests</span>;
            MANUAL / CANDIDATE feed <span className="font-medium text-ink-700">create-test-cases</span>.
          </span>
        </CardBody>
      </Card>

      <Card>
        <CardHeader title="Test Condition List"
          subtitle="Each condition traces a basis item and a risk forward to the cases that cover it." />
        <CardBody className="p-0">
          {conditions.length === 0 ? (
            <p className="px-5 py-4 text-sm text-muted">
              No test conditions yet. Run test analysis for this service to generate them from the approved strategy.
            </p>
          ) : (
            <Table head={<><Th>ID</Th><Th>Condition</Th><Th>Source</Th><Th>Priority</Th><Th>Risk</Th><Th>Technique</Th><Th>Automation</Th></>}>
              {conditions.map((c: TestCondition) => (
                <Row key={c.id}>
                  <Td className="font-medium text-ink-900">{c.conditionRef}</Td>
                  <Td className="text-ink-900">{c.description}
                    {c.qualityCharacteristic ? <span className="block text-[12px] text-muted">{c.qualityCharacteristic}</span> : null}
                  </Td>
                  <Td className="text-muted">{c.sourceBasisItem ?? '—'}</Td>
                  <Td><Badge className={priorityTone(c.priority)}>{c.priority ?? '—'}</Badge></Td>
                  <Td className="text-muted">{c.riskRef ?? '—'}</Td>
                  <Td className="text-muted">{c.technique ?? '—'}</Td>
                  <Td>
                    <div className="flex items-center gap-2">
                      <Badge className={automationTone(c.automation)}>{(c.automation || 'CANDIDATE').toUpperCase()}</Badge>
                      <select
                        aria-label={`Automation for ${c.conditionRef}`}
                        className="rounded-md border border-border bg-surface px-2 py-1 text-[12px] text-ink-700"
                        value={(c.automation || 'CANDIDATE').toUpperCase()}
                        disabled={setAutomation.isPending}
                        onChange={(e) => setAutomation.mutate({ condId: c.id, automation: e.target.value })}>
                        {AUTOMATION.map((a) => <option key={a} value={a}>{a}</option>)}
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
