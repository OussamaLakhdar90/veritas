import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ListChecks, Search, Check, X, Upload } from 'lucide-react';
import { api, TestCase } from '../api';
import { Badge, Button, Card, CardBody, EmptyState, Field, Input, PageHeader, Spinner, Table, Td, Th, Row } from '../components/ui';
import { useToast } from '../components/Toast';
import { TONE } from '../theme/tokens';

const tone = (s?: string) => {
  const v = (s || '').toUpperCase();
  if (v === 'APPROVED' || v.startsWith('CREATED') || v === 'ATTACHED' || v === 'IMPLEMENTED') return TONE.ok;
  if (v === 'REJECTED') return TONE.danger;
  return TONE.info;
};

export function TestCases() {
  const toast = useToast();
  const qc = useQueryClient();
  const [svc, setSvc] = useState('');
  const [query, setQuery] = useState('');
  const [projectKey, setProjectKey] = useState('');

  const q = useQuery({ queryKey: ['test-cases', query], queryFn: () => api.testCases(query), enabled: !!query });

  const act = useMutation({
    mutationFn: ({ tc, fn }: { tc: TestCase; fn: () => Promise<unknown> }) => fn(),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['test-cases'] }); toast.push('success', 'Updated.'); },
    onError: (e: Error) => toast.push('error', e.message),
  });
  const busyId = act.isPending ? act.variables?.tc.id : undefined;
  const rows = q.data ?? [];

  return (
    <div>
      <PageHeader title="Test cases" subtitle="Review, approve and push generated test cases to Xray." />

      <Card className="mb-6">
        <CardBody>
          <div className="flex flex-col gap-4 sm:flex-row sm:items-end">
            <div className="flex-1"><Field label="Service"><Input placeholder="ciam-policies" value={svc}
              onChange={(e) => setSvc(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && svc && setQuery(svc)} /></Field></div>
            <div className="flex-1"><Field label="Project key" hint="needed to push to Xray"><Input placeholder="CIAM" value={projectKey}
              onChange={(e) => setProjectKey(e.target.value.toUpperCase())} /></Field></div>
            <Button onClick={() => setQuery(svc)} disabled={!svc} className="sm:mb-0.5"><Search className="h-4 w-4" /> Load</Button>
          </div>
        </CardBody>
      </Card>

      {!query ? (
        <EmptyState icon={ListChecks} title="Load a service" body="Enter a service name above to review its test cases." />
      ) : q.isLoading ? (
        <Card><CardBody className="flex items-center gap-2 text-sm text-muted"><Spinner /> Loading…</CardBody></Card>
      ) : rows.length === 0 ? (
        <EmptyState icon={ListChecks} title={`No cases for "${query}"`} body="Generate test cases for this service to review them here." />
      ) : (
        <Card>
          <CardBody className="p-0">
            <Table head={<><Th>Title</Th><Th>Technique</Th><Th>Status</Th><Th>Xray</Th><Th className="text-right">Actions</Th></>}>
              {rows.map((tc) => (
                <Row key={tc.id}>
                  <Td className="max-w-md font-medium text-ink-900">{tc.title}</Td>
                  <Td className="text-muted">{tc.technique ?? '—'}</Td>
                  <Td><Badge className={tone(tc.status)}>{tc.status}</Badge></Td>
                  <Td className="font-mono text-[12px] text-muted">{tc.xrayKey ?? '—'}</Td>
                  <Td className="text-right whitespace-nowrap">
                    <span className="inline-flex gap-2">
                      <Button size="sm" variant="secondary" disabled={busyId === tc.id}
                        onClick={() => act.mutate({ tc, fn: () => api.patchTestCase(tc.id, { status: 'APPROVED', actor: 'dashboard' }) })}>
                        <Check className="h-4 w-4" /> Approve</Button>
                      <Button size="sm" variant="ghost" disabled={busyId === tc.id}
                        onClick={() => act.mutate({ tc, fn: () => api.patchTestCase(tc.id, { status: 'REJECTED' }) })}>
                        <X className="h-4 w-4" /> Reject</Button>
                      <Button size="sm" variant="secondary" disabled={busyId === tc.id || !projectKey}
                        title={projectKey ? '' : 'Enter a project key first'}
                        onClick={() => act.mutate({ tc, fn: () => api.pushTestCase(tc.id, projectKey) })}>
                        <Upload className="h-4 w-4" /> Push to Xray</Button>
                    </span>
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
