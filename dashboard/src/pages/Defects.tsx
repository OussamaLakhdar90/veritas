import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bug, RefreshCw, ExternalLink } from 'lucide-react';
import { api } from '../api';
import { Badge, Button, Card, CardBody, EmptyState, PageHeader, Spinner, Table, Td, Row, SortableTh, useSort } from '../components/ui';
import { useToast } from '../components/Toast';
import { TONE } from '../theme/tokens';
import { DefectLink } from '../api';

const DEFECT_ACCESSORS: Record<string, (d: DefectLink) => string | number> = {
  jiraKey: (d) => d.jiraKey ?? '',
  status: (d) => d.jiraStatus ?? '',
  createdBy: (d) => d.createdBy ?? '',
  lastSyncedAt: (d) => d.lastSyncedAt ?? '',
};

function statusTone(category?: string): string {
  const c = (category || '').toLowerCase();
  if (c === 'done') return TONE.ok;
  if (c === 'indeterminate') return TONE.warn;
  if (c === 'new' || c === 'to do' || c === 'todo') return TONE.info;
  return TONE.muted;
}

export function Defects() {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['defects'], queryFn: api.defects });
  const sync = useMutation({
    mutationFn: api.syncDefects,
    onSuccess: (r) => { qc.invalidateQueries({ queryKey: ['defects'] }); toast.push('success', `Synced — ${r.updated} updated.`); },
    onError: (e: Error) => toast.push('error', `Sync failed: ${e.message}`),
  });

  const sort = useSort(q.data ?? [], { key: 'jiraKey' }, DEFECT_ACCESSORS);
  const rows = sort.sorted;
  return (
    <div>
      <PageHeader title="Defects" subtitle="Jira defects raised from contract findings, with live status."
        actions={<Button variant="secondary" loading={sync.isPending} onClick={() => sync.mutate()}>
          <RefreshCw className="h-4 w-4" /> Refresh statuses</Button>} />

      {q.isLoading ? (
        <Card><CardBody className="flex items-center gap-2 text-sm text-muted"><Spinner /> Loading…</CardBody></Card>
      ) : q.isError ? (
        <Card><CardBody className="text-sm text-danger">Could not load defects: {(q.error as Error).message}</CardBody></Card>
      ) : rows.length === 0 ? (
        <EmptyState icon={Bug} title="No defects yet"
          body="Defects you raise from findings will appear here, and Veritas will keep their Jira status in sync." />
      ) : (
        <Card>
          <CardBody className="p-0">
            <Table head={<>
              <SortableTh label="Jira" sortKey="jiraKey" sort={sort} />
              <SortableTh label="Status" sortKey="status" sort={sort} />
              <SortableTh label="Created by" sortKey="createdBy" sort={sort} />
              <SortableTh label="Last synced" sortKey="lastSyncedAt" sort={sort} />
            </>}>
              {rows.map((d) => (
                <Row key={d.id}>
                  <Td className="font-medium text-ink-900">
                    {d.jiraUrl
                      ? <a href={d.jiraUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-gold hover:underline">{d.jiraKey} <ExternalLink className="h-3.5 w-3.5" /></a>
                      : (d.jiraKey ?? '—')}
                  </Td>
                  <Td><Badge className={statusTone(d.jiraStatusCategory)}>{d.jiraStatus ?? (d.createdInJira ? 'Open' : 'Not created')}</Badge></Td>
                  <Td className="text-muted">{d.createdBy ?? '—'}</Td>
                  <Td className="text-muted">{d.lastSyncedAt ? new Date(d.lastSyncedAt).toLocaleString() : '—'}</Td>
                </Row>
              ))}
            </Table>
          </CardBody>
        </Card>
      )}
    </div>
  );
}
