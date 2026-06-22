import { useMemo, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bug, FileText, CheckCircle2, AlertTriangle } from 'lucide-react';
import { api, Finding } from '../api';
import { Badge, Button, Card, CardBody, EmptyState, Field, Input, PageHeader, Spinner } from '../components/ui';
import { Modal } from '../components/Modal';
import { useToast } from '../components/Toast';
import { severityBadge } from '../theme/tokens';
import { cn } from '../components/cn';

const SEV_ORDER = ['BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR', 'INFO'];
const sevRank = (s?: string) => { const i = SEV_ORDER.indexOf((s || 'INFO').toUpperCase()); return i < 0 ? SEV_ORDER.length : i; };

export function Findings() {
  const { scanId } = useParams();
  const q = useQuery({ queryKey: ['findings', scanId], queryFn: () => api.findings(scanId!), enabled: !!scanId });
  const [filter, setFilter] = useState<string>('ALL');
  const [defectFor, setDefectFor] = useState<Finding | null>(null);

  const findings = q.data ?? [];
  const counts = useMemo(() => {
    const m: Record<string, number> = {};
    for (const f of findings) { const s = (f.severity || 'INFO').toUpperCase(); m[s] = (m[s] ?? 0) + 1; }
    return m;
  }, [findings]);

  const shown = useMemo(() =>
    [...findings]
      .filter((f) => filter === 'ALL' || (f.severity || 'INFO').toUpperCase() === filter)
      .sort((a, b) => sevRank(a.severity) - sevRank(b.severity)),
    [findings, filter]);

  return (
    <div>
      <PageHeader title="Findings"
        subtitle="Where the spec and the code disagree, with evidence and a proposed fix."
        actions={scanId && (
          <a href={api.reportUrl(scanId)} target="_blank" rel="noreferrer">
            <Button variant="secondary"><FileText className="h-4 w-4" /> Executive report</Button>
          </a>
        )} />

      {q.isLoading ? (
        <Card><CardBody className="flex items-center gap-2 text-sm text-muted"><Spinner /> Loading findings…</CardBody></Card>
      ) : q.isError ? (
        <Card><CardBody className="text-sm text-danger">Could not load findings: {(q.error as Error).message}</CardBody></Card>
      ) : findings.length === 0 ? (
        <EmptyState icon={CheckCircle2} title="No findings"
          body="This scan found no discrepancies between the spec and the code — the contract tells the truth." />
      ) : (
        <>
          {/* Severity filter chips */}
          <div className="mb-4 flex flex-wrap gap-2">
            <FilterChip active={filter === 'ALL'} onClick={() => setFilter('ALL')} label={`All ${findings.length}`} />
            {SEV_ORDER.filter((s) => counts[s]).map((s) => (
              <button key={s} onClick={() => setFilter(s)}
                className={cn('rounded-full px-2.5 py-1 text-[11px] font-semibold uppercase tracking-wide transition',
                  filter === s ? severityBadge(s) : 'text-muted ring-1 ring-border hover:bg-ink-50')}>
                {s} {counts[s]}
              </button>
            ))}
          </div>

          <Card>
            <CardBody className="p-0">
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border text-left text-[12px] uppercase tracking-wide text-muted">
                      <th className="px-5 py-3 font-medium">Severity</th>
                      <th className="px-5 py-3 font-medium">Layer</th>
                      <th className="px-5 py-3 font-medium">Endpoint</th>
                      <th className="px-5 py-3 font-medium">Summary</th>
                      <th className="px-5 py-3 font-medium">Evidence</th>
                      <th className="px-5 py-3" />
                    </tr>
                  </thead>
                  <tbody>
                    {shown.map((f) => (
                      <tr key={f.id} className="border-b border-border/60 align-top last:border-0 hover:bg-ink-50/60">
                        <td className="px-5 py-3"><Badge className={severityBadge(f.severity)}>{f.severity}</Badge></td>
                        <td className="px-5 py-3 text-muted">{f.layer}</td>
                        <td className="px-5 py-3 font-mono text-[12.5px] text-ink-900">{f.endpoint}</td>
                        <td className="max-w-md px-5 py-3">
                          <p className="text-ink-900">{f.summary}</p>
                          {f.explanation && <p className="mt-1 text-[13px] text-muted">{f.explanation}</p>}
                        </td>
                        <td className="px-5 py-3 font-mono text-[12px] text-muted">
                          {f.codeFile ? `${f.codeFile.split(/[\\/]/).pop()}${f.codeStartLine ? ':' + f.codeStartLine : ''}` : '—'}
                        </td>
                        <td className="px-5 py-3 text-right whitespace-nowrap">
                          {f.status === 'JIRA_CREATED'
                            ? <span className="inline-flex items-center gap-1 text-[13px] text-success"><CheckCircle2 className="h-4 w-4" /> Defect raised</span>
                            : <Button size="sm" variant="secondary" onClick={() => setDefectFor(f)}><Bug className="h-4 w-4" /> Raise defect</Button>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </CardBody>
          </Card>
        </>
      )}

      {defectFor && <DefectModal finding={defectFor} scanId={scanId} onClose={() => setDefectFor(null)} />}
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

/* ── Raise-defect form (replaces window.prompt) ──────────────────────────────── */
function DefectModal({ finding, scanId, onClose }: { finding: Finding; scanId?: string; onClose: () => void }) {
  const toast = useToast();
  const qc = useQueryClient();
  const [project, setProject] = useState('');

  const create = useMutation({
    mutationFn: () => api.createDefect(finding.id, project.trim()),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ['findings', scanId] });
      qc.invalidateQueries({ queryKey: ['defects'] });
      toast.push('success', `Created defect ${r.jiraKey}.`);
      onClose();
    },
    onError: (e: Error) => toast.push('error', `Failed to create defect: ${e.message}`),
  });

  return (
    <Modal open title="Raise a Jira defect" onClose={create.isPending ? () => {} : onClose}
      footer={<>
        <Button variant="secondary" onClick={onClose} disabled={create.isPending}>Cancel</Button>
        <Button onClick={() => project.trim() ? create.mutate() : toast.push('error', 'Enter a project key.')}
          loading={create.isPending}><Bug className="h-4 w-4" /> Create defect</Button>
      </>}>
      <div className="mb-4 flex items-start gap-3 rounded-lg bg-ink-50 p-3">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" />
        <div className="min-w-0 text-[13px]">
          <p className="font-medium text-ink-900">{finding.summary}</p>
          <p className="mt-0.5 font-mono text-[12px] text-muted">{finding.endpoint}</p>
        </div>
      </div>
      <Field label="Jira project key" hint="Where the Bug issue will be created, e.g. CIAM.">
        <Input autoFocus placeholder="CIAM" value={project}
          onChange={(e) => setProject(e.target.value.toUpperCase())}
          onKeyDown={(e) => e.key === 'Enter' && project.trim() && create.mutate()} />
      </Field>
    </Modal>
  );
}
