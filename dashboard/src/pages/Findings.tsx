import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bug, FileText, CheckCircle2, AlertTriangle, Check, X } from 'lucide-react';
import { api, Finding } from '../api';
import { Badge, Button, Card, CardBody, EmptyState, ErrorState, Field, Input, PageHeader, Spinner, Table, Td, Th, Row, SortableTh, useSort } from '../components/ui';
import { Modal } from '../components/Modal';
import { useToast } from '../components/Toast';
import { severityBadge, layerLabel, TONE } from '../theme/tokens';
import { cn } from '../components/cn';

const SEV_ORDER = ['BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR', 'INFO'];
const sevRank = (s?: string) => { const i = SEV_ORDER.indexOf((s || 'INFO').toUpperCase()); return i < 0 ? SEV_ORDER.length : i; };

// Recorded disposition → pill styling + human label (the system of record for accept/reject).
const DISP_TONE: Record<string, string> = {
  ACCEPTED: TONE.ok, FIXED: TONE.ok,
  REJECTED: TONE.danger, WONT_FIX: TONE.danger, FALSE_POSITIVE: TONE.danger,
  TRIAGED: TONE.info, JIRA_CREATED: TONE.info,
};
const DISP_LABEL: Record<string, string> = {
  ACCEPTED: 'Accepted', REJECTED: 'Rejected', WONT_FIX: "Won't fix",
  FALSE_POSITIVE: 'False positive', TRIAGED: 'Triaged', JIRA_CREATED: 'Defect raised', FIXED: 'Fixed',
};
const dispositioned = (s?: string) => !!s && s !== 'OPEN';

// Stable accessor map for client-side sorting (severity sorts by blocker→info, not alphabetically).
const SORT_ACCESSORS: Record<string, (f: Finding) => string | number> = {
  severity: (f) => sevRank(f.severity),
  layer: (f) => f.layer ?? '',
  endpoint: (f) => f.endpoint ?? '',
  status: (f) => f.status ?? '',
};

const raiseable = (f: Finding) => f.status !== 'JIRA_CREATED';

export function Findings() {
  const { scanId } = useParams();
  const q = useQuery({ queryKey: ['findings', scanId], queryFn: () => api.findings(scanId!), enabled: !!scanId });
  const [filter, setFilter] = useState<string>('ALL');
  const [defectFor, setDefectFor] = useState<Finding | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkOpen, setBulkOpen] = useState(false);

  const findings = q.data ?? [];
  const counts = useMemo(() => {
    const m: Record<string, number> = {};
    for (const f of findings) { const s = (f.severity || 'INFO').toUpperCase(); m[s] = (m[s] ?? 0) + 1; }
    return m;
  }, [findings]);

  const filtered = useMemo(
    () => findings.filter((f) => filter === 'ALL' || (f.severity || 'INFO').toUpperCase() === filter),
    [findings, filter]);
  const sort = useSort(filtered, { key: 'severity', dir: 'asc' }, SORT_ACCESSORS);
  const shown = sort.sorted;

  const selectableShown = shown.filter(raiseable);
  const allSelected = selectableShown.length > 0 && selectableShown.every((f) => selected.has(f.id));
  const selectedFindings = findings.filter((f) => selected.has(f.id) && raiseable(f));

  const toggle = (id: string) => setSelected((s) => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });
  const toggleAll = () => setSelected((s) => {
    if (selectableShown.every((f) => s.has(f.id))) return new Set();
    return new Set(selectableShown.map((f) => f.id));
  });

  // Disposition (accept/reject) — persisted + audited server-side; the report re-renders from this.
  const toast = useToast();
  const qc = useQueryClient();
  // Track in-flight finding+status keys so each row's spinner is correct under concurrent clicks (a single shared
  // mutation.variables only reflects the most recent call).
  const [inFlight, setInFlight] = useState<Set<string>>(new Set());
  const disposition = useMutation({
    mutationFn: ({ f, status }: { f: Finding; status: string }) => api.patchFinding(f.id, status),
    onMutate: (v) => setInFlight((s) => new Set(s).add(`${v.f.id}:${v.status}`)),
    onSettled: (_d, _e, v) => setInFlight((s) => { const n = new Set(s); n.delete(`${v.f.id}:${v.status}`); return n; }),
    onSuccess: (_r, v) => {
      qc.invalidateQueries({ queryKey: ['findings', scanId] });
      toast.push('success', `Marked ${DISP_LABEL[v.status] ?? v.status}.`);
    },
    onError: (e: Error) => toast.push('error', e.message),
  });
  const busy = (f: Finding, status: string) => inFlight.has(`${f.id}:${status}`);

  return (
    <div>
      <PageHeader title="Findings"
        subtitle="Differences between the documented API and what the code actually does. Review each one, mark it Accepted or Rejected, then raise a Jira defect if it needs fixing."
        actions={scanId && (
          <a href={api.reportUrl(scanId)} target="_blank" rel="noreferrer"
            title="Open the management report — findings, the accuracy score and recommendations, for managers and developers.">
            <Button variant="secondary"><FileText className="h-4 w-4" /> Management report</Button>
          </a>
        )} />

      {q.isLoading ? (
        <Card><CardBody className="flex items-center gap-2 text-sm text-muted"><Spinner /> Loading findings…</CardBody></Card>
      ) : q.isError ? (
        <ErrorState message={(q.error as Error).message} />
      ) : findings.length === 0 ? (
        <EmptyState icon={CheckCircle2} title="No differences found"
          body="The code matches the documented API exactly. Open the management report for the details, or validate again with a different spec." />
      ) : (
        <>
          {/* Severity filter chips */}
          <div className="mb-4 flex flex-wrap items-center gap-2">
            <FilterChip active={filter === 'ALL'} onClick={() => setFilter('ALL')} label={`All ${findings.length}`} />
            {SEV_ORDER.filter((s) => counts[s]).map((s) => (
              <button key={s} onClick={() => setFilter(s)}
                className={cn('rounded-full px-2.5 py-1 text-[11px] font-semibold uppercase tracking-wide transition',
                  filter === s ? severityBadge(s) : 'text-muted ring-1 ring-border hover:bg-ink-50')}>
                {s} {counts[s]}
              </button>
            ))}
            {selected.size > 0 && (
              <div className="ml-auto flex items-center gap-2">
                <span className="text-[13px] text-muted">{selected.size} selected</span>
                <Button size="sm" onClick={() => setBulkOpen(true)}><Bug className="h-4 w-4" /> Raise {selected.size} defects</Button>
              </div>
            )}
          </div>

          <Card>
            <CardBody className="p-0">
              <Table head={<>
                <Th className="w-10">
                  <input type="checkbox" aria-label="Select all" className="h-4 w-4 rounded border-border text-brand focus:ring-brand/40"
                    checked={allSelected} onChange={toggleAll} />
                </Th>
                <SortableTh label="Severity" sortKey="severity" sort={sort} />
                <SortableTh label="Layer" sortKey="layer" sort={sort} />
                <SortableTh label="Endpoint" sortKey="endpoint" sort={sort} />
                <Th>Summary</Th>
                <Th>Evidence</Th>
                <SortableTh label="Review status" sortKey="status" sort={sort} className="text-right" />
              </>}>
                {shown.map((f) => (
                  <Row key={f.id}>
                    <Td>
                      {raiseable(f) && (
                        <input type="checkbox" aria-label={`Select ${f.endpoint}`} className="h-4 w-4 rounded border-border text-brand focus:ring-brand/40"
                          checked={selected.has(f.id)} onChange={() => toggle(f.id)} />
                      )}
                    </Td>
                    <Td><Badge className={severityBadge(f.severity)}>{f.severity}</Badge></Td>
                    <Td className="text-muted" title={f.layer}>{layerLabel(f.layer)}</Td>
                    <Td className="font-mono text-[12.5px] text-ink-900">{f.endpoint}</Td>
                    <Td className="max-w-md">
                      <p className="text-ink-900">{f.summary}</p>
                      {f.explanation && <p className="mt-1 text-[13px] text-muted">{f.explanation}</p>}
                    </Td>
                    <Td className="font-mono text-[12px] text-muted">
                      {f.codeFile ? `${f.codeFile.split(/[\\/]/).pop()}${f.codeStartLine ? ':' + f.codeStartLine : ''}` : '—'}
                    </Td>
                    <Td className="text-right whitespace-nowrap">
                      <div className="inline-flex items-center justify-end gap-1.5">
                        {dispositioned(f.status) && f.status !== 'JIRA_CREATED' && (
                          <span title={f.reviewedBy ? `by ${f.reviewedBy}` : undefined}>
                            <Badge className={DISP_TONE[f.status!] ?? TONE.muted}>{DISP_LABEL[f.status!] ?? f.status}</Badge>
                          </span>
                        )}
                        {f.status === 'JIRA_CREATED' ? (
                          <span className="inline-flex items-center gap-1 text-[13px] text-success"><CheckCircle2 className="h-4 w-4" /> Defect raised</span>
                        ) : (
                          <>
                            <Button size="sm" variant="ghost" title="Accept — this is a real difference that matches the code"
                              loading={busy(f, 'ACCEPTED')} onClick={() => disposition.mutate({ f, status: 'ACCEPTED' })}>
                              <Check className="h-4 w-4 text-success" />
                            </Button>
                            <Button size="sm" variant="ghost" title="Reject — not applicable to this service; it won't appear in reports"
                              loading={busy(f, 'REJECTED')} onClick={() => disposition.mutate({ f, status: 'REJECTED' })}>
                              <X className="h-4 w-4 text-danger" />
                            </Button>
                            <Button size="sm" variant="secondary" onClick={() => setDefectFor(f)}><Bug className="h-4 w-4" /> Raise defect</Button>
                          </>
                        )}
                      </div>
                    </Td>
                  </Row>
                ))}
              </Table>
            </CardBody>
          </Card>
        </>
      )}

      {defectFor && <DefectModal findings={[defectFor]} scanId={scanId} onClose={() => setDefectFor(null)} />}
      {bulkOpen && <DefectModal findings={selectedFindings} scanId={scanId}
        onClose={(done) => { setBulkOpen(false); if (done) setSelected(new Set()); }} />}
    </div>
  );
}

function FilterChip({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button onClick={onClick}
      className={cn('rounded-full px-2.5 py-1 text-[11px] font-semibold uppercase tracking-wide transition',
        active ? 'bg-brand text-white' : 'text-muted ring-1 ring-border hover:bg-ink-50')}>
      {label}
    </button>
  );
}

/* ── Raise-defect form (single or bulk; replaces window.prompt) ──────────────── */
function DefectModal({ findings, scanId, onClose }: { findings: Finding[]; scanId?: string; onClose: (done?: boolean) => void }) {
  const toast = useToast();
  const qc = useQueryClient();
  const [project, setProject] = useState('');
  const bulk = findings.length > 1;

  const create = useMutation({
    mutationFn: async () => {
      const key = project.trim();
      let ok = 0;
      const fail: string[] = [];
      for (const f of findings) {
        try { await api.createDefect(f.id, key); ok++; } catch (e) { fail.push((e as Error).message); }
      }
      return { ok, fail };
    },
    onSuccess: ({ ok, fail }) => {
      qc.invalidateQueries({ queryKey: ['findings', scanId] });
      qc.invalidateQueries({ queryKey: ['defects'] });
      if (ok > 0) toast.push('success', `Created ${ok} defect${ok === 1 ? '' : 's'}.`);
      if (fail.length) toast.push('error', `${fail.length} failed: ${fail[0]}`);
      onClose(true);
    },
    onError: (e: Error) => toast.push('error', `Failed to create defect: ${e.message}`),
  });

  return (
    <Modal open title={bulk ? `Raise ${findings.length} Jira defects` : 'Raise a Jira defect'}
      onClose={create.isPending ? () => {} : () => onClose(false)}
      footer={<>
        <Button variant="secondary" onClick={() => onClose(false)} disabled={create.isPending}>Cancel</Button>
        <Button onClick={() => project.trim() ? create.mutate() : toast.push('error', 'Enter a project key.')}
          loading={create.isPending}><Bug className="h-4 w-4" /> {bulk ? `Create ${findings.length}` : 'Create defect'}</Button>
      </>}>
      <div className="mb-4 flex items-start gap-3 rounded-lg bg-ink-50 p-3">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" />
        <div className="min-w-0 text-[13px]">
          {bulk
            ? <p className="font-medium text-ink-900">{findings.length} findings will each get a Bug issue.</p>
            : <><p className="font-medium text-ink-900">{findings[0].summary}</p>
                <p className="mt-0.5 font-mono text-[12px] text-muted">{findings[0].endpoint}</p></>}
        </div>
      </div>
      <Field label="Jira project key" hint="Where the Bug issue(s) will be created, e.g. CIAM.">
        <Input autoFocus placeholder="CIAM" value={project}
          onChange={(e) => setProject(e.target.value.toUpperCase())}
          onKeyDown={(e) => e.key === 'Enter' && project.trim() && create.mutate()} />
      </Field>
    </Modal>
  );
}
