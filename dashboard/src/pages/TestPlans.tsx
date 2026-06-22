import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ClipboardList, Play, ArrowRight } from 'lucide-react';
import { api } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, Field, Input, PageHeader, Spinner, Table, Td, Th, Row } from '../components/ui';
import { useToast } from '../components/Toast';
import { TONE } from '../theme/tokens';

export function TestPlans() {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['test-plans'], queryFn: api.testPlans });

  const [svc, setSvc] = useState('');
  const [fixVersion, setFixVersion] = useState('');
  const [projectKey, setProjectKey] = useState('');
  const [createGaps, setCreateGaps] = useState(false);

  const trigger = useMutation({
    mutationFn: () => api.triggerReleasePlan(svc, { fixVersion, projectKey: projectKey || undefined, createGaps }),
    onSuccess: (s) => {
      qc.invalidateQueries({ queryKey: ['test-plans'] });
      toast.push('success', `Plan ready — ${s.matched} matched, ${s.gaps} gaps, ${s.created} created.`);
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const plans = q.data ?? [];
  return (
    <div>
      <PageHeader title="Test plans" subtitle="Release test plans with ISTQB risk analysis and a requirements-traceability matrix." />

      <Card className="mb-6">
        <CardHeader title="New release plan" subtitle="Reconcile a release's requirements against existing tests; propose gap tests for approval." />
        <CardBody>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <Field label="Service"><Input placeholder="ciam-policies" value={svc} onChange={(e) => setSvc(e.target.value)} /></Field>
            <Field label="Fix version"><Input placeholder="8.2" value={fixVersion} onChange={(e) => setFixVersion(e.target.value)} /></Field>
            <Field label="Project key" hint="optional"><Input placeholder="CIAM" value={projectKey} onChange={(e) => setProjectKey(e.target.value)} /></Field>
          </div>
          <div className="mt-4 flex items-center justify-between">
            <label className="inline-flex items-center gap-2 text-[13px] text-ink-700">
              <input type="checkbox" className="h-4 w-4 rounded border-border text-brand focus:ring-brand/40"
                checked={createGaps} onChange={(e) => setCreateGaps(e.target.checked)} />
              Create gap tests (still gated for approval)
            </label>
            <Button loading={trigger.isPending}
              onClick={() => (svc && fixVersion) ? trigger.mutate() : toast.push('error', 'Service and fix version are required.')}>
              <Play className="h-4 w-4" /> Generate plan
            </Button>
          </div>
        </CardBody>
      </Card>

      {q.isLoading ? (
        <Card><CardBody className="flex items-center gap-2 text-sm text-muted"><Spinner /> Loading…</CardBody></Card>
      ) : plans.length === 0 ? (
        <EmptyState icon={ClipboardList} title="No test plans yet" body="Generate a release test plan above to see its deliverable and RTM here." />
      ) : (
        <Card>
          <CardBody className="p-0">
            <Table head={<><Th>Service</Th><Th>Kind</Th><Th>Fix version</Th><Th>Status</Th><Th className="text-right">Confidence</Th><Th className="text-right">Risks</Th><Th className="text-right">Est. cost</Th><Th /></>}>
              {plans.map((p) => (
                <Row key={p.id}>
                  <Td className="font-medium text-ink-900">{p.serviceName}</Td>
                  <Td className="text-muted">{p.kind}</Td>
                  <Td className="text-muted">{p.fixVersion ?? '—'}</Td>
                  <Td><Badge className={p.status === 'APPROVED' ? TONE.ok : TONE.info}>{p.status}</Badge></Td>
                  <Td className="text-right tabular-nums text-ink-900">{p.confidence != null ? `${Math.round(p.confidence)}%` : '—'}</Td>
                  <Td className="text-right tabular-nums text-muted">{p.riskCount ?? '—'}</Td>
                  <Td className="text-right tabular-nums text-muted">${(p.estCostUsd ?? 0).toFixed(4)}</Td>
                  <Td className="text-right whitespace-nowrap">
                    <Link to={`/test-plans/${p.id}`} className="inline-flex items-center gap-1 text-[13px] font-medium text-gold hover:underline">
                      Open <ArrowRight className="h-3.5 w-3.5" /></Link>
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
