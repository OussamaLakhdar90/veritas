import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { Layers, GitBranch, FileText, Bug, AlertTriangle, ArrowLeft, Sparkles, ShieldCheck } from 'lucide-react';
import { api, MultiSourceStrategyRequest, StrategyPreview } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, Field, Input, PageHeader } from '../components/ui';
import { useToast } from '../components/Toast';
import { TONE } from '../theme/tokens';
import { cn } from '../components/cn';

// Feature status → pill tone + plain label.
const STATUS_TONE: Record<string, string> = {
  IMPLEMENTED: TONE.ok, PLANNED: TONE.warn, UNDOCUMENTED: TONE.info, PARTIAL: TONE.warn, COVERAGE_GAP: TONE.danger,
};
const STATUS_LABEL: Record<string, string> = {
  IMPLEMENTED: 'Implemented', PLANNED: 'Planned (not built)', UNDOCUMENTED: 'Undocumented',
  PARTIAL: 'Partial', COVERAGE_GAP: 'Coverage gap',
};
// Evidence source → chip tint.
const SOURCE_TONE: Record<string, string> = { JIRA: TONE.info, CONFLUENCE: TONE.warn, CODE: TONE.muted, POLICY: TONE.ok };
const GAP_LABEL: Record<string, string> = {
  PLANNED_NOT_IMPLEMENTED: 'Specified, not built', IMPLEMENTED_UNDOCUMENTED: 'Built, unspecified',
  POSSIBLE_MISCLUSTER: 'Possible mis-cluster',
};

/** A labelled checkbox that reveals its source's inputs when ticked. */
function SourceToggle({ on, setOn, icon: Icon, label, children }:
  { on: boolean; setOn: (v: boolean) => void; icon: React.ElementType; label: string; children?: React.ReactNode }) {
  return (
    <div className={cn('rounded-lg border p-4 transition', on ? 'border-brand/40 bg-brand/5' : 'border-border')}>
      <label className="flex cursor-pointer items-center gap-3">
        <input type="checkbox" checked={on} onChange={(e) => setOn(e.target.checked)}
          className="h-4 w-4 rounded border-border text-brand focus:ring-brand/40" />
        <Icon className="h-[18px] w-[18px] text-muted" />
        <span className="text-sm font-medium text-ink-900">{label}</span>
      </label>
      {on && <div className="mt-3 space-y-3 pl-7">{children}</div>}
    </div>
  );
}

export function MultiSourceStrategy() {
  const nav = useNavigate();
  const toast = useToast();
  const [service, setService] = useState('');
  const [useCode, setUseCode] = useState(false);
  const [appId, setAppId] = useState('');
  const [repoSlug, setRepoSlug] = useState('');
  const [branch, setBranch] = useState('');
  const [useJira, setUseJira] = useState(false);
  const [jql, setJql] = useState('');
  const [useConf, setUseConf] = useState(false);
  const [pageIds, setPageIds] = useState('');
  const [preview, setPreview] = useState<StrategyPreview | null>(null);

  const body = (): MultiSourceStrategyRequest => ({
    code: useCode ? { appId: appId.trim() || undefined, repoSlug: repoSlug.trim() || undefined, branch: branch.trim() || undefined } : undefined,
    jira: useJira ? { jql: jql.trim() || undefined } : undefined,
    confluence: useConf ? { pageIds: pageIds.split(',').map((s) => s.trim()).filter(Boolean) } : undefined,
  });

  const ready = useMemo(() => service.trim() && (
    (useCode && appId.trim() && repoSlug.trim()) || (useJira && jql.trim()) || (useConf && pageIds.trim())
  ), [service, useCode, appId, repoSlug, useJira, jql, useConf, pageIds]);

  const previewM = useMutation({
    mutationFn: () => api.previewMultiSourceStrategy(service.trim(), body()),
    onSuccess: setPreview,
    onError: (e: Error) => toast.push('error', e.message),
  });
  const generateM = useMutation({
    mutationFn: () => api.generateMultiSourceStrategy(service.trim(), body()),
    onSuccess: () => { toast.push('success', 'Multi-source strategy generated.'); nav('/test-strategy'); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  return (
    <div className="max-w-3xl">
      <PageHeader title="Multi-source strategy"
        subtitle="Build a test strategy from Jira + Confluence + code. Preview the clustered features and cost before generating." />

      {!preview ? (
        <Card>
          <CardHeader title="1 · Choose sources" subtitle="Any combination — code-only, Jira-only, pre-dev (Jira + Confluence), or all three." />
          <CardBody className="space-y-4">
            <Field label="Service name"><Input value={service} onChange={(e) => setService(e.target.value)} placeholder="ciam-policies" /></Field>

            <SourceToggle on={useCode} setOn={setUseCode} icon={GitBranch} label="Code (a Bitbucket repo)">
              <div className="grid grid-cols-2 gap-3">
                <Field label="App-id (project key)"><Input value={appId} onChange={(e) => setAppId(e.target.value)} placeholder="APP7571" /></Field>
                <Field label="Repo slug"><Input value={repoSlug} onChange={(e) => setRepoSlug(e.target.value)} placeholder="ciam-policies" /></Field>
              </div>
              <Field label="Branch" hint="defaults to the repo default"><Input value={branch} onChange={(e) => setBranch(e.target.value)} placeholder="develop" /></Field>
            </SourceToggle>

            <SourceToggle on={useJira} setOn={setUseJira} icon={Bug} label="Jira (issues by JQL)">
              <Field label="JQL"><Input value={jql} onChange={(e) => setJql(e.target.value)} placeholder='project = CIAM AND fixVersion = "2025.1"' /></Field>
            </SourceToggle>

            <SourceToggle on={useConf} setOn={setUseConf} icon={FileText} label="Confluence (design pages)">
              <Field label="Page ids" hint="comma-separated"><Input value={pageIds} onChange={(e) => setPageIds(e.target.value)} placeholder="123456, 234567" /></Field>
            </SourceToggle>

            <div className="flex justify-end pt-1">
              <Button onClick={() => previewM.mutate()} loading={previewM.isPending} disabled={!ready}>
                <Layers className="h-4 w-4" /> Preview the feature index
              </Button>
            </div>
          </CardBody>
        </Card>
      ) : (
        <div className="space-y-4">
          {/* Banners */}
          {preview.hardFail && (
            <Card className="border-l-4 border-l-danger"><CardBody className="flex items-start gap-3">
              <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-danger" />
              <div><p className="text-sm font-semibold text-ink-900">A selected source returned no usable evidence</p>
                <p className="mt-0.5 text-[13px] text-muted">{preview.fetchFailures.join('; ') || 'Fix or deselect that source, then preview again.'} Generation is blocked until this is resolved.</p></div>
            </CardBody></Card>
          )}
          {!preview.hardFail && preview.fetchFailures.length > 0 && (
            <Card className="border-l-4 border-l-warning"><CardBody className="text-[13px] text-muted">
              <span className="font-medium text-ink-900">Some items couldn't be fetched:</span> {preview.fetchFailures.join('; ')}
            </CardBody></Card>
          )}

          {/* Summary */}
          <Card>
            <CardHeader title="2 · Review the feature index" subtitle="What the pipeline extracted and clustered — check it before generating." />
            <CardBody>
              <div className="mb-4 flex flex-wrap items-center gap-2 text-[13px]">
                <span className="text-muted">Sources:</span>
                {preview.mix.code && <Badge className={SOURCE_TONE.CODE}>code</Badge>}
                {preview.mix.jira && <Badge className={SOURCE_TONE.JIRA}>jira</Badge>}
                {preview.mix.confluence && <Badge className={SOURCE_TONE.CONFLUENCE}>confluence</Badge>}
                <span className="ml-auto text-muted">{preview.features.length} feature(s) · {preview.gaps.length} gap(s) · {preview.redactionCount} redaction(s)</span>
              </div>

              <div className="space-y-3">
                {preview.features.map((f) => (
                  <div key={f.featureId} className="rounded-lg border border-border p-3">
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-medium text-ink-900">{f.displayName}</span>
                      <Badge className={STATUS_TONE[f.status] ?? TONE.muted}>{STATUS_LABEL[f.status] ?? f.status}</Badge>
                    </div>
                    <div className="mt-2 flex flex-wrap gap-1.5">
                      {f.units.map((u) => (
                        <span key={u.id} title={`${u.id} — ${u.title}`}
                          className={cn('rounded px-1.5 py-0.5 text-[11px] ring-1', SOURCE_TONE[u.source] ?? TONE.muted)}>
                          {u.source.toLowerCase()} · {u.title || u.type.toLowerCase()}
                        </span>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </CardBody>
          </Card>

          {/* Gaps */}
          {preview.gaps.length > 0 && (
            <Card>
              <CardHeader title="Coverage gaps" subtitle="Detected deterministically from the clustering — where intent and implementation diverge." />
              <CardBody className="space-y-2">
                {preview.gaps.map((g, i) => (
                  <div key={i} className="flex items-start gap-2 text-[13px]">
                    <Badge className={g.kind === 'IMPLEMENTED_UNDOCUMENTED' ? TONE.danger : TONE.warn}>{GAP_LABEL[g.kind] ?? g.kind}</Badge>
                    <span className="text-ink-900">{g.message}</span>
                  </div>
                ))}
              </CardBody>
            </Card>
          )}

          {/* Generate */}
          <Card>
            <CardBody className="flex items-center justify-between gap-4">
              <div className="text-[13px] text-muted">
                Estimated synthesis cost <span className="font-semibold text-ink-900">~${preview.estimatedCostUsd.toFixed(2)}</span>
                <span className="ml-1">(rough — billed per feature when you generate).</span>
              </div>
              <div className="flex items-center gap-2">
                <Button variant="secondary" onClick={() => setPreview(null)}><ArrowLeft className="h-4 w-4" /> Back</Button>
                <Button onClick={() => generateM.mutate()} loading={generateM.isPending} disabled={preview.hardFail}>
                  <Sparkles className="h-4 w-4" /> Generate strategy
                </Button>
              </div>
            </CardBody>
          </Card>
        </div>
      )}

      {!preview && (
        <p className="mt-4 flex items-center gap-1.5 text-[12px] text-muted">
          <ShieldCheck className="h-3.5 w-3.5" /> Preview runs the cheap stages only (extract + cluster); the priced synthesis runs only when you click Generate.
        </p>
      )}
    </div>
  );
}
