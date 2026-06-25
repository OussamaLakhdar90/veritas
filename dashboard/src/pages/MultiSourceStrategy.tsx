import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import {
  Layers, GitBranch, FileText, Bug, AlertTriangle, ArrowLeft, Sparkles, ShieldCheck,
  Pin, Pencil, Check, X, GitMerge,
} from 'lucide-react';
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
  COVERAGE_GAP: 'Done in Jira, not built', POSSIBLE_MISCLUSTER: 'Possible mis-cluster',
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

  // Edit-step local state.
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [renaming, setRenaming] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [mergeName, setMergeName] = useState('');

  const snapshotId = preview?.snapshotId ?? '';

  const body = (): MultiSourceStrategyRequest => ({
    code: useCode ? { appId: appId.trim() || undefined, repoSlug: repoSlug.trim() || undefined, branch: branch.trim() || undefined } : undefined,
    jira: useJira ? { jql: jql.trim() || undefined } : undefined,
    confluence: useConf ? { pageIds: pageIds.split(',').map((s) => s.trim()).filter(Boolean) } : undefined,
  });

  const ready = useMemo(() => service.trim() && (
    (useCode && appId.trim() && repoSlug.trim()) || (useJira && jql.trim()) || (useConf && pageIds.trim())
  ), [service, useCode, appId, repoSlug, useJira, jql, useConf, pageIds]);

  const onErr = (e: Error) => toast.push('error', e.message);

  const previewM = useMutation({
    mutationFn: () => api.previewMultiSourceStrategy(service.trim(), body()),
    onSuccess: (p) => { setPreview(p); setSelected(new Set()); },
    onError: onErr,
  });
  const renameM = useMutation({
    mutationFn: (v: { featureId: string; name: string }) => api.renameFeature(snapshotId, v.featureId, v.name),
    onSuccess: (p) => { setPreview(p); setRenaming(null); },
    onError: onErr,
  });
  const mergeM = useMutation({
    mutationFn: () => api.mergeFeatures(snapshotId, [...selected], mergeName.trim() || undefined),
    onSuccess: (p) => { setPreview(p); setSelected(new Set()); setMergeName(''); },
    onError: onErr,
  });
  const pinM = useMutation({
    mutationFn: (v: { featureId: string; pinned: boolean }) => api.pinFeature(snapshotId, v.featureId, v.pinned),
    onSuccess: setPreview,
    onError: onErr,
  });
  const generateM = useMutation({
    mutationFn: () => api.generateStrategyFromSnapshot(snapshotId),
    onSuccess: () => { toast.push('success', 'Multi-source strategy generated.'); nav('/test-strategy'); },
    onError: onErr,
  });

  const toggleSelect = (id: string) => setSelected((prev) => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });
  const startRename = (featureId: string, current: string) => { setRenaming(featureId); setRenameValue(current); };
  const submitRename = () => {
    if (renaming && renameValue.trim()) renameM.mutate({ featureId: renaming, name: renameValue.trim() });
  };

  const busy = renameM.isPending || mergeM.isPending || pinM.isPending;

  return (
    <div className="max-w-3xl">
      <PageHeader title="Multi-source strategy"
        subtitle="Build a test strategy from Jira + Confluence + code. Preview the clustered features, tidy them up, then generate." />

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

          {/* Feature index — editable */}
          <Card>
            <CardHeader title="2 · Review &amp; tidy the feature index"
              subtitle="Merge features that are the same capability, rename them, and pin the ones you've confirmed — then generate." />
            <CardBody>
              <div className="mb-3 flex flex-wrap items-center gap-2 text-[13px]">
                <span className="text-muted">Sources:</span>
                {preview.mix.code && <Badge className={SOURCE_TONE.CODE}>code</Badge>}
                {preview.mix.jira && <Badge className={SOURCE_TONE.JIRA}>jira</Badge>}
                {preview.mix.confluence && <Badge className={SOURCE_TONE.CONFLUENCE}>confluence</Badge>}
                <span className="ml-auto text-muted">{preview.features.length} feature(s) · {preview.gaps.length} gap(s) · {preview.redactionCount} redaction(s)</span>
              </div>

              {/* Merge action bar */}
              {selected.size >= 1 && (
                <div className="mb-3 flex flex-wrap items-center gap-2 rounded-lg border border-brand/40 bg-brand/5 p-2.5">
                  <span className="text-[13px] font-medium text-ink-900">{selected.size} selected</span>
                  {selected.size >= 2 ? (
                    <>
                      <Input className="h-8 max-w-[220px]" value={mergeName} onChange={(e) => setMergeName(e.target.value)}
                        placeholder="Merged name (optional)" />
                      <Button size="sm" onClick={() => mergeM.mutate()} loading={mergeM.isPending}>
                        <GitMerge className="h-4 w-4" /> Merge {selected.size}
                      </Button>
                    </>
                  ) : (
                    <span className="text-[12px] text-muted">Select another feature to merge.</span>
                  )}
                  <Button size="sm" variant="ghost" onClick={() => setSelected(new Set())}>Clear</Button>
                </div>
              )}

              <div className="space-y-3">
                {preview.features.map((f) => {
                  const isSel = selected.has(f.featureId);
                  return (
                    <div key={f.featureId}
                      className={cn('rounded-lg border p-3 transition',
                        isSel ? 'border-brand/50 bg-brand/5' : f.pinned ? 'border-success/40' : 'border-border')}>
                      <div className="flex items-center gap-2">
                        <input type="checkbox" checked={isSel} onChange={() => toggleSelect(f.featureId)}
                          aria-label={`Select ${f.displayName} to merge`}
                          className="h-4 w-4 rounded border-border text-brand focus:ring-brand/40" />

                        {renaming === f.featureId ? (
                          <form className="flex flex-1 items-center gap-1.5"
                            onSubmit={(e) => { e.preventDefault(); submitRename(); }}>
                            <Input className="h-8" autoFocus value={renameValue}
                              onChange={(e) => setRenameValue(e.target.value)}
                              onKeyDown={(e) => { if (e.key === 'Escape') setRenaming(null); }} />
                            <Button size="sm" type="submit" loading={renameM.isPending} aria-label="Save name"><Check className="h-4 w-4" /></Button>
                            <Button size="sm" variant="ghost" type="button" onClick={() => setRenaming(null)} aria-label="Cancel"><X className="h-4 w-4" /></Button>
                          </form>
                        ) : (
                          <>
                            <span className="font-medium text-ink-900">{f.displayName}</span>
                            <button type="button" onClick={() => startRename(f.featureId, f.displayName)} disabled={busy}
                              className="text-muted hover:text-ink-700 disabled:opacity-40" title="Rename" aria-label="Rename feature">
                              <Pencil className="h-3.5 w-3.5" />
                            </button>
                            <Badge className={STATUS_TONE[f.status] ?? TONE.muted}>{STATUS_LABEL[f.status] ?? f.status}</Badge>
                            <button type="button" onClick={() => pinM.mutate({ featureId: f.featureId, pinned: !f.pinned })}
                              disabled={busy} title={f.pinned ? 'Unpin' : 'Pin (confirm)'} aria-label={f.pinned ? 'Unpin feature' : 'Pin feature'}
                              className={cn('ml-auto rounded p-1 disabled:opacity-40',
                                f.pinned ? 'text-success' : 'text-muted hover:text-ink-700')}>
                              <Pin className={cn('h-4 w-4', f.pinned && 'fill-current')} />
                            </button>
                          </>
                        )}
                      </div>
                      <div className="mt-2 flex flex-wrap gap-1.5 pl-6">
                        {f.units.map((u) => (
                          <span key={u.id} title={`${u.id} — ${u.title}`}
                            className={cn('rounded px-1.5 py-0.5 text-[11px] ring-1', SOURCE_TONE[u.source] ?? TONE.muted)}>
                            {u.source.toLowerCase()} · {u.title || u.type.toLowerCase()}
                          </span>
                        ))}
                      </div>
                    </div>
                  );
                })}
              </div>
            </CardBody>
          </Card>

          {/* Gaps */}
          {preview.gaps.length > 0 && (
            <Card>
              <CardHeader title="Coverage gaps" subtitle="Detected deterministically from the clustering — they update as you merge." />
              <CardBody className="space-y-2">
                {preview.gaps.map((g, i) => (
                  <div key={i} className="flex items-start gap-2 text-[13px]">
                    <Badge className={g.kind === 'IMPLEMENTED_UNDOCUMENTED' || g.kind === 'COVERAGE_GAP' ? TONE.danger : TONE.warn}>{GAP_LABEL[g.kind] ?? g.kind}</Badge>
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
                <Button onClick={() => generateM.mutate()} loading={generateM.isPending} disabled={preview.hardFail || busy}>
                  <Sparkles className="h-4 w-4" /> Generate strategy
                </Button>
              </div>
            </CardBody>
          </Card>
        </div>
      )}

      {!preview && (
        <p className="mt-4 flex items-center gap-1.5 text-[12px] text-muted">
          <ShieldCheck className="h-3.5 w-3.5" /> Preview runs the cheap stages only (extract + cluster); editing is free; the priced synthesis runs only when you click Generate.
        </p>
      )}
    </div>
  );
}
