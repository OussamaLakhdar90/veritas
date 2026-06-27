import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ClipboardList, Play, FileText, ExternalLink, ScrollText, ArrowRight } from 'lucide-react';
import { api } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, Field, PageHeader, Select, Spinner, Table, Td, Th, Row, Textarea } from '../components/ui';
import { useToast } from '../components/Toast';
import { ServiceField } from '../components/ServiceField';
import { useCopilotGate } from '../lib/copilotAuth';
import { TONE } from '../theme/tokens';

/** ISTQB Test-Manager strategy wizard: synthesize a structured strategy from a basis, then review versions. */
export function TestStrategy() {
  const toast = useToast();
  const qc = useQueryClient();
  const [service, setService] = useState('');
  const [loaded, setLoaded] = useState('');
  const [basis, setBasis] = useState('');
  const [source, setSource] = useState('CODE');

  const { blocked, notice } = useCopilotGate();
  const list = useQuery({ queryKey: ['strategies', loaded], queryFn: () => api.strategies(loaded), enabled: !!loaded });

  const generate = useMutation({
    mutationFn: () => api.generateStrategy(service, { basis, source }),
    onSuccess: () => {
      setLoaded(service);
      qc.invalidateQueries({ queryKey: ['strategies', service] });
      toast.push('success', 'Strategy generated.');
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const rows = list.data ?? [];
  return (
    <div>
      <PageHeader title="Test strategy" subtitle="Generate a consultant-grade ISTQB Test-Manager strategy from the codebase or stories." />

      <Card className="mb-6">
        <CardHeader title="New strategy" subtitle="Risk register, approach, exit criteria + a self-review — synthesized in one cost-routed call." />
        <CardBody className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <Field label="Service" hint="Pick an existing service or type a new one."><ServiceField value={service} onChange={(e) => setService(e.target.value)} /></Field>
            <Field label="Basis source">
              <Select aria-label="Basis source" value={source} onChange={(e) => setSource(e.target.value)}>
                <option value="CODE">Codebase</option>
                <option value="JIRA">Jira stories</option>
                <option value="CONFLUENCE">Confluence</option>
              </Select>
            </Field>
          </div>
          <Field label="Basis" hint="Paste the test basis (endpoints, user stories, requirements) the strategy should cover.">
            <Textarea placeholder="e.g. POST /policies — create a policy; GET /policies/{id} — fetch…" value={basis} onChange={(e) => setBasis(e.target.value)} />
          </Field>
          <div className="flex items-center justify-end gap-3">
            {notice}
            <Button loading={generate.isPending} disabled={blocked}
              onClick={() => (service && basis.trim()) ? generate.mutate() : toast.push('error', 'Service and basis are required.')}>
              <Play className="h-4 w-4" /> Generate strategy
            </Button>
          </div>
        </CardBody>
      </Card>

      {!loaded ? (
        <EmptyState icon={ClipboardList} title="No strategy yet" body="Generate one above; its versions and rationale appear here." />
      ) : list.isLoading ? (
        <Card><CardBody className="flex items-center gap-2 text-sm text-muted"><Spinner /> Loading…</CardBody></Card>
      ) : rows.length === 0 ? (
        <EmptyState icon={ClipboardList} title={`No strategies for "${loaded}"`} />
      ) : (
        <Card>
          <CardHeader title={`Strategies — ${loaded}`} />
          <CardBody className="p-0">
            <Table head={<><Th>Status</Th><Th className="text-right">Confidence</Th><Th>Created</Th><Th /></>}>
              {rows.map((s) => (
                <Row key={s.id}>
                  <Td><Badge className={s.status === 'APPROVED' ? TONE.ok : TONE.info}>{s.status ?? 'DRAFT'}</Badge></Td>
                  <Td className="text-right tabular-nums text-ink-900">{s.confidence != null ? `${Math.round(s.confidence)}%` : '—'}</Td>
                  <Td className="text-muted">{s.createdAt ? new Date(s.createdAt).toLocaleString() : '—'}</Td>
                  <Td className="text-right">
                    <div className="inline-flex items-center gap-4">
                      {s.source === 'multi-source' && (
                        <a href={api.strategyWhyDocUrl(s.id)} target="_blank" rel="noreferrer"
                          title="Evidence why-doc: each section traced to its Jira/Confluence/code units"
                          className="inline-flex items-center gap-1 text-[13px] font-medium text-gold hover:underline">
                          <ScrollText className="h-3.5 w-3.5" /> Evidence <ExternalLink className="h-3 w-3" />
                        </a>
                      )}
                      <a href={api.strategyRationaleUrl(s.id)} target="_blank" rel="noreferrer"
                        className="inline-flex items-center gap-1 text-[13px] font-medium text-gold hover:underline">
                        <FileText className="h-3.5 w-3.5" /> Rationale <ExternalLink className="h-3 w-3" />
                      </a>
                      <Link to={`/test-strategy/${s.id}`}
                        className="inline-flex items-center gap-1 text-[13px] font-medium text-gold hover:underline">
                        Open <ArrowRight className="h-3.5 w-3.5" />
                      </Link>
                    </div>
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
