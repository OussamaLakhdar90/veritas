import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bug, FileText, CheckCircle2, AlertTriangle, Check, X, ExternalLink, Download } from 'lucide-react';
import { api, Finding } from '../api';
import { Badge, Button, Card, CardBody, EmptyState, ErrorState, Field, Input, PageHeader, TableSkeleton, Table, Td, Th, Row, SortableTh, useSort } from '../components/ui';
import { Modal } from '../components/Modal';
import { useToast } from '../components/Toast';
import { severityBadge, TONE } from '../theme/tokens';
import { cn } from '../components/cn';
import { enumLabel } from '../lib/enumLabels';

const SEV_ORDER = ['BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR', 'INFO'];
const sevRank = (s?: string) => { const i = SEV_ORDER.indexOf((s || 'INFO').toUpperCase()); return i < 0 ? SEV_ORDER.length : i; };
const CONF_ORDER = ['HIGH', 'MEDIUM', 'LOW'];
const confRank = (c?: string) => { const i = CONF_ORDER.indexOf((c || '').toUpperCase()); return i < 0 ? CONF_ORDER.length : i; };
const HIGH_SEV = ['BLOCKER', 'CRITICAL'];
/** A high-severity finding the engine is only LOW-confident about — verify before acting. */
const riskyConfidence = (f: Finding) => (f.confidence || '').toUpperCase() === 'LOW' && HIGH_SEV.includes((f.severity || '').toUpperCase());

// Recorded disposition → pill styling + human label (the system of record for accept/reject).
const DISP_TONE: Record<string, string> = {
  ACCEPTED: TONE.ok, FIXED: TONE.ok,
  REJECTED: TONE.danger, WONT_FIX: TONE.danger, FALSE_POSITIVE: TONE.danger,
  TRIAGED: TONE.info, JIRA_CREATED: TONE.info,
};
const DISP_LABEL_KEY: Record<string, string> = {
  ACCEPTED: 'dispAccepted', REJECTED: 'dispRejected', WONT_FIX: 'dispWontFix',
  FALSE_POSITIVE: 'dispFalsePositive', TRIAGED: 'dispTriaged', JIRA_CREATED: 'dispDefectRaised', FIXED: 'dispFixed',
};
// Recorded disposition → translated human label (the system of record for accept/reject).
const dispLabel = (t: (k: string) => string, status?: string) =>
  status && DISP_LABEL_KEY[status] ? t(`findings.${DISP_LABEL_KEY[status]}`) : (status ?? '');
const dispositioned = (s?: string) => !!s && s !== 'OPEN';

// Stable accessor map for client-side sorting (severity sorts by blocker→info, not alphabetically).
const SORT_ACCESSORS: Record<string, (f: Finding) => string | number> = {
  severity: (f) => sevRank(f.severity),
  confidence: (f) => confRank(f.confidence),
  layer: (f) => f.layer ?? '',
  endpoint: (f) => f.endpoint ?? '',
  status: (f) => f.status ?? '',
};

const raiseable = (f: Finding) => f.status !== 'JIRA_CREATED';

export function Findings() {
  const { t } = useTranslation();
  const { scanId } = useParams();
  const q = useQuery({ queryKey: ['findings', scanId], queryFn: () => api.findings(scanId!), enabled: !!scanId });
  const [filter, setFilter] = useState<string>('ALL');
  const [defectFor, setDefectFor] = useState<Finding | null>(null);
  const [rejectFor, setRejectFor] = useState<Finding | null>(null);
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
      toast.push('success', t('findings.markedDisposition', { label: dispLabel(t, v.status) }));
    },
    onError: (e: Error) => toast.push('error', e.message),
  });
  const busy = (f: Finding, status: string) => inFlight.has(`${f.id}:${status}`);

  return (
    <div>
      <PageHeader title={t('findings.title')}
        subtitle={t('findings.subtitle')}
        actions={scanId && (
          <div className="flex items-center gap-2">
            <a href={api.reportUrl(scanId)} target="_blank" rel="noreferrer"
              title={t('findings.reportTooltip')}>
              <Button variant="secondary"><FileText className="h-4 w-4" /> {t('findings.managementReport')}</Button>
            </a>
            <a href={api.reportDownloadUrl(scanId)} title={t('findings.downloadTooltip')}>
              <Button variant="ghost"><Download className="h-4 w-4" /> {t('findings.download')}</Button>
            </a>
          </div>
        )} />

      {q.isLoading ? (
        <Card><CardBody className="p-0"><TableSkeleton label={t('findings.loading')} /></CardBody></Card>
      ) : q.isError ? (
        <ErrorState detail={(q.error as Error).message} />
      ) : findings.length === 0 ? (
        <EmptyState icon={CheckCircle2} title={t('findings.emptyTitle')}
          body={t('findings.emptyBody')} />
      ) : (
        <>
          {/* Severity filter chips */}
          <div className="mb-4 flex flex-wrap items-center gap-2">
            <FilterChip active={filter === 'ALL'} onClick={() => setFilter('ALL')} label={t('findings.filterAll', { count: findings.length })} />
            {SEV_ORDER.filter((s) => counts[s]).map((s) => (
              <button key={s} onClick={() => setFilter(s)}
                className={cn('rounded-full px-2.5 py-1 text-2xs font-semibold uppercase tracking-wide transition',
                  filter === s ? severityBadge(s) : 'text-muted ring-1 ring-border hover:bg-ink-50')}>
                {enumLabel(t, 'severity', s)} {counts[s]}
              </button>
            ))}
            {selected.size > 0 && (
              <div className="ml-auto flex items-center gap-2">
                <span className="text-sm text-muted">{t('findings.selectedCount', { count: selected.size })}</span>
                <Button size="sm" onClick={() => setBulkOpen(true)}><Bug className="h-4 w-4" /> {t('findings.raiseDefects', { count: selected.size })}</Button>
              </div>
            )}
          </div>

          <Card>
            <CardBody className="p-0">
              <Table head={<>
                <Th className="w-10">
                  <input type="checkbox" aria-label={t('findings.selectAll')} className="h-4 w-4 rounded border-border text-brand focus:ring-brand/40"
                    checked={allSelected} onChange={toggleAll} />
                </Th>
                <SortableTh label={t('findings.colSeverity')} sortKey="severity" sort={sort} />
                <SortableTh label={t('findings.colConfidence')} sortKey="confidence" sort={sort} />
                <SortableTh label={t('findings.colLayer')} sortKey="layer" sort={sort} />
                <SortableTh label={t('findings.colEndpoint')} sortKey="endpoint" sort={sort} />
                <Th>{t('findings.colSummary')}</Th>
                <Th>{t('findings.colEvidence')}</Th>
                <SortableTh label={t('findings.colReviewStatus')} sortKey="status" sort={sort} className="text-right" />
              </>}>
                {shown.map((f) => (
                  <Row key={f.id}>
                    <Td>
                      {raiseable(f) && (
                        <input type="checkbox" aria-label={t('findings.selectRow', { endpoint: f.endpoint })} className="h-4 w-4 rounded border-border text-brand focus:ring-brand/40"
                          checked={selected.has(f.id)} onChange={() => toggle(f.id)} />
                      )}
                    </Td>
                    <Td><Badge className={severityBadge(f.severity)}>{enumLabel(t, 'severity', f.severity)}</Badge></Td>
                    <Td>
                      {f.confidence ? (
                        <span className={cn('inline-flex items-center gap-1 text-sm',
                          riskyConfidence(f) ? 'font-medium text-warning' : 'text-muted')}
                          title={riskyConfidence(f) ? t('findings.riskyConfidenceTooltip') : undefined}>
                          {enumLabel(t, 'confidence', f.confidence)}
                          {riskyConfidence(f) && <AlertTriangle className="h-3.5 w-3.5" />}
                        </span>
                      ) : <span className="text-muted">—</span>}
                    </Td>
                    <Td className="text-muted">{enumLabel(t, 'layer', f.layer)}</Td>
                    <Td className="font-mono text-xs text-ink-900">{f.endpoint}</Td>
                    <Td className="max-w-md">
                      <p className="text-ink-900">{f.summary}</p>
                      {f.explanation && <p className="mt-1 text-sm text-muted">{f.explanation}</p>}
                    </Td>
                    <Td className="font-mono text-xs text-muted">
                      {f.codeFile ? (
                        f.codeUrl ? (
                          <a href={f.codeUrl} target="_blank" rel="noreferrer"
                            className="inline-flex items-center gap-1 text-brand hover:underline"
                            title={t('findings.openInBitbucket', { location: `${f.codeFile}${f.codeStartLine ? ':' + f.codeStartLine : ''}` })}>
                            {`${f.codeFile.split(/[\\/]/).pop()}${f.codeStartLine ? ':' + f.codeStartLine : ''}`}
                            <ExternalLink className="h-3 w-3 shrink-0" />
                          </a>
                        ) : (
                          <span title={f.codeFile}>{`${f.codeFile.split(/[\\/]/).pop()}${f.codeStartLine ? ':' + f.codeStartLine : ''}`}</span>
                        )
                      ) : '—'}
                    </Td>
                    <Td className="text-right whitespace-nowrap">
                      <div className="inline-flex items-center justify-end gap-1.5">
                        {dispositioned(f.status) && f.status !== 'JIRA_CREATED' && (
                          <span title={f.reviewedBy ? t('findings.reviewedBy', { name: f.reviewedBy }) : undefined}>
                            <Badge className={DISP_TONE[f.status!] ?? TONE.muted}>{dispLabel(t, f.status)}</Badge>
                          </span>
                        )}
                        {f.status === 'JIRA_CREATED' ? (
                          <span className="inline-flex items-center gap-1 text-sm text-success"><CheckCircle2 className="h-4 w-4" /> {t('findings.defectRaised')}</span>
                        ) : (
                          <>
                            <Button size="sm" variant="ghost" title={t('findings.acceptTooltip')}
                              loading={busy(f, 'ACCEPTED')} onClick={() => disposition.mutate({ f, status: 'ACCEPTED' })}>
                              <Check className="h-4 w-4 text-success" />
                            </Button>
                            <Button size="sm" variant="ghost" title={t('findings.rejectTooltip')}
                              loading={busy(f, 'REJECTED')} onClick={() => setRejectFor(f)}>
                              <X className="h-4 w-4 text-danger" />
                            </Button>
                            <Button size="sm" variant="secondary" onClick={() => setDefectFor(f)}><Bug className="h-4 w-4" /> {t('findings.raiseDefect')}</Button>
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

      {rejectFor && (
        <Modal open title={t('findings.rejectModalTitle')}
          onClose={busy(rejectFor, 'REJECTED') ? () => {} : () => setRejectFor(null)}
          footer={<>
            <Button variant="secondary" onClick={() => setRejectFor(null)} disabled={busy(rejectFor, 'REJECTED')}>{t('findings.cancel')}</Button>
            <Button variant="danger" loading={busy(rejectFor, 'REJECTED')}
              onClick={() => { disposition.mutate({ f: rejectFor, status: 'REJECTED' }); setRejectFor(null); }}>
              <X className="h-4 w-4" /> {t('findings.rejectFinding')}
            </Button>
          </>}>
          <p className="text-sm text-ink-900">{rejectFor.summary}</p>
          <p className="mt-3 text-sm text-muted">
            {t('findings.rejectModalBody')}
          </p>
        </Modal>
      )}
    </div>
  );
}

function FilterChip({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button onClick={onClick}
      className={cn('rounded-full px-2.5 py-1 text-2xs font-semibold uppercase tracking-wide transition',
        active ? 'bg-brand text-white' : 'text-muted ring-1 ring-border hover:bg-ink-50')}>
      {label}
    </button>
  );
}

/* ── Raise-defect form (single or bulk; replaces window.prompt) ──────────────── */
function DefectModal({ findings, scanId, onClose }: { findings: Finding[]; scanId?: string; onClose: (done?: boolean) => void }) {
  const { t } = useTranslation();
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
      if (ok > 0) toast.push('success', t('findings.createdDefects', { count: ok }));
      if (fail.length) toast.push('error', t('findings.defectsFailed', { count: fail.length, message: fail[0] }));
      onClose(true);
    },
    onError: (e: Error) => toast.push('error', t('findings.createDefectError', { message: e.message })),
  });

  return (
    <Modal open title={bulk ? t('findings.raiseJiraDefectsBulk', { count: findings.length }) : t('findings.raiseJiraDefect')}
      onClose={create.isPending ? () => {} : () => onClose(false)}
      footer={<>
        <Button variant="secondary" onClick={() => onClose(false)} disabled={create.isPending}>{t('findings.cancel')}</Button>
        <Button onClick={() => project.trim() ? create.mutate() : toast.push('error', t('findings.enterProjectKey'))}
          loading={create.isPending}><Bug className="h-4 w-4" /> {bulk ? t('findings.createCount', { count: findings.length }) : t('findings.createDefect')}</Button>
      </>}>
      <div className="mb-4 flex items-start gap-3 rounded-lg bg-ink-50 p-3">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" />
        <div className="min-w-0 text-sm">
          {bulk
            ? <p className="font-medium text-ink-900">{t('findings.findingsGetBug', { count: findings.length })}</p>
            : <><p className="font-medium text-ink-900">{findings[0].summary}</p>
                <p className="mt-0.5 font-mono text-xs text-muted">{findings[0].endpoint}</p></>}
        </div>
      </div>
      <Field label={t('findings.projectKeyLabel')} hint={t('findings.projectKeyHint')}>
        <Input autoFocus placeholder="CIAM" value={project}
          onChange={(e) => setProject(e.target.value.toUpperCase())}
          onKeyDown={(e) => e.key === 'Enter' && project.trim() && create.mutate()} />
      </Field>
    </Modal>
  );
}
