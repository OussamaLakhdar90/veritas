import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  Layers, GitBranch, FileText, Bug, AlertTriangle, ArrowLeft, Sparkles, ShieldCheck,
  Pin, Pencil, Check, X, GitMerge, RefreshCw,
} from 'lucide-react';
import { api, MultiSourceStrategyRequest, StrategyPreview } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, Field, Input, PageHeader } from '../components/ui';
import { useToast } from '../components/Toast';
import { TONE } from '../theme/tokens';
import { cn } from '../components/cn';
import { formatMoney } from '../lib/format';

// Feature status → pill tone + plain label.
const STATUS_TONE: Record<string, string> = {
  IMPLEMENTED: TONE.ok, PLANNED: TONE.warn, UNDOCUMENTED: TONE.info, PARTIAL: TONE.warn, COVERAGE_GAP: TONE.danger,
};
const STATUS_LABEL_KEY: Record<string, string> = {
  IMPLEMENTED: 'statusImplemented', PLANNED: 'statusPlanned', UNDOCUMENTED: 'statusUndocumented',
  PARTIAL: 'statusPartial', COVERAGE_GAP: 'statusCoverageGap',
};
// Evidence source → chip tint.
const SOURCE_TONE: Record<string, string> = { JIRA: TONE.info, CONFLUENCE: TONE.warn, CODE: TONE.muted, POLICY: TONE.ok };
const GAP_LABEL_KEY: Record<string, string> = {
  PLANNED_NOT_IMPLEMENTED: 'gapPlannedNotImplemented', IMPLEMENTED_UNDOCUMENTED: 'gapImplementedUndocumented',
  COVERAGE_GAP: 'gapCoverageGap', POSSIBLE_MISCLUSTER: 'gapPossibleMiscluster',
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
  const { t } = useTranslation();
  const nav = useNavigate();
  const toast = useToast();
  const [service, setService] = useState('');
  const [useCode, setUseCode] = useState(false);
  const [appId, setAppId] = useState('');
  const [repoSlug, setRepoSlug] = useState('');
  const [branch, setBranch] = useState('');
  const [useJira, setUseJira] = useState(false);
  const [jql, setJql] = useState('');
  const [epicKey, setEpicKey] = useState('');
  const [useConf, setUseConf] = useState(false);
  const [pageIds, setPageIds] = useState('');
  const [rootPageId, setRootPageId] = useState('');
  const [preview, setPreview] = useState<StrategyPreview | null>(null);

  // Edit-step local state.
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [renaming, setRenaming] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [mergeName, setMergeName] = useState('');
  const [generatingId, setGeneratingId] = useState<string | null>(null);   // async generate: the snapshot we're polling

  const snapshotId = preview?.snapshotId ?? '';

  const body = (): MultiSourceStrategyRequest => ({
    code: useCode ? { appId: appId.trim() || undefined, repoSlug: repoSlug.trim() || undefined, branch: branch.trim() || undefined } : undefined,
    jira: useJira ? { jql: jql.trim() || undefined, epicKey: epicKey.trim() || undefined } : undefined,
    confluence: useConf
      ? { pageIds: pageIds.split(',').map((s) => s.trim()).filter(Boolean), rootPageId: rootPageId.trim() || undefined }
      : undefined,
  });

  const ready = useMemo(() => service.trim() && (
    (useCode && appId.trim() && repoSlug.trim())
    || (useJira && (jql.trim() || epicKey.trim()))
    || (useConf && (pageIds.trim() || rootPageId.trim()))
  ), [service, useCode, appId, repoSlug, useJira, jql, epicKey, useConf, pageIds, rootPageId]);

  const onErr = (e: Error) => toast.push('error', e.message);

  const previewM = useMutation({
    mutationFn: () => api.previewMultiSourceStrategy(service.trim(), body()),
    onSuccess: (p) => { setPreview(p); setSelected(new Set()); },
    onError: onErr,
  });
  // Lineage re-run: re-extract the same sources, carrying the reviewer's pins/renames/merges forward onto the
  // fresh index (the prior snapshotId is the carry-forward source). Edits whose features vanished are surfaced.
  const recheckM = useMutation({
    mutationFn: () => api.previewMultiSourceStrategy(service.trim(), body(), snapshotId),
    onSuccess: (p) => {
      setPreview(p); setSelected(new Set());
      toast.push('success', p.carryForwardNotes.length
        ? t('multiSource.reextractedSomeNotCarried', { count: p.carryForwardNotes.length })
        : t('multiSource.reextractedAllCarried'));
    },
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
  // Generation is async (202 + poll): kick it off, then poll the snapshot until it reports done or failed.
  const generateM = useMutation({
    mutationFn: () => api.generateStrategyFromSnapshot(snapshotId),
    onSuccess: (acc) => { setGeneratingId(acc.snapshotId); toast.push('success', t('multiSource.toastGeneratingStrategy')); },
    onError: onErr,
  });
  const genPoll = useQuery({
    queryKey: ['strategy-gen', generatingId],
    queryFn: () => api.getStrategySnapshot(generatingId as string),
    enabled: !!generatingId,
    refetchInterval: (q) => {
      const d = q.state.data;
      return d && (d.generatedStrategyId || d.generationError) ? false : 1200;   // stop polling once done/failed
    },
  });
  useEffect(() => {
    const d = genPoll.data;
    if (!generatingId || !d) return;
    if (d.generatedStrategyId) {
      setGeneratingId(null);
      toast.push('success', t('multiSource.toastStrategyGenerated'));
      nav('/test-strategy');
    } else if (d.generationError) {
      setGeneratingId(null);
      toast.push('error', d.generationError);
    }
  }, [genPoll.data, generatingId, nav, toast, t]);
  const generating = generateM.isPending || !!generatingId;

  const toggleSelect = (id: string) => setSelected((prev) => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });
  const startRename = (featureId: string, current: string) => { setRenaming(featureId); setRenameValue(current); };
  const submitRename = () => {
    if (renaming && renameValue.trim()) renameM.mutate({ featureId: renaming, name: renameValue.trim() });
  };

  const busy = renameM.isPending || mergeM.isPending || pinM.isPending || recheckM.isPending;

  return (
    <div className="max-w-3xl">
      <PageHeader title={t('multiSource.pageTitle')}
        subtitle={t('multiSource.pageSubtitle')} />

      {!preview ? (
        <Card>
          <CardHeader title={t('multiSource.step1Title')} subtitle={t('multiSource.step1Subtitle')} />
          <CardBody className="space-y-4">
            <Field label={t('multiSource.serviceNameLabel')}><Input value={service} onChange={(e) => setService(e.target.value)} placeholder="ciam-policies" /></Field>

            <SourceToggle on={useCode} setOn={setUseCode} icon={GitBranch} label={t('multiSource.codeToggleLabel')}>
              <div className="grid grid-cols-2 gap-3">
                <Field label={t('multiSource.appIdLabel')}><Input value={appId} onChange={(e) => setAppId(e.target.value)} placeholder="APP7571" /></Field>
                <Field label={t('multiSource.repoSlugLabel')} hint={t('multiSource.repoSlugHint')}><Input value={repoSlug} onChange={(e) => setRepoSlug(e.target.value)} placeholder="ciam-policies" /></Field>
              </div>
              <Field label={t('multiSource.branchLabel')} hint={t('multiSource.branchHint')}><Input value={branch} onChange={(e) => setBranch(e.target.value)} placeholder="develop" /></Field>
            </SourceToggle>

            <SourceToggle on={useJira} setOn={setUseJira} icon={Bug} label={t('multiSource.jiraToggleLabel')}>
              <Field label={t('multiSource.jqlLabel')}><Input value={jql} onChange={(e) => setJql(e.target.value)} placeholder='project = CIAM AND fixVersion = "2025.1"' /></Field>
              <Field label={t('multiSource.epicKeyLabel')} hint={t('multiSource.epicKeyHint')}><Input value={epicKey} onChange={(e) => setEpicKey(e.target.value)} placeholder="CIAM-100" /></Field>
            </SourceToggle>

            <SourceToggle on={useConf} setOn={setUseConf} icon={FileText} label={t('multiSource.confToggleLabel')}>
              <Field label={t('multiSource.pageIdsLabel')} hint={t('multiSource.pageIdsHint')}><Input value={pageIds} onChange={(e) => setPageIds(e.target.value)} placeholder="123456, 234567" /></Field>
              <Field label={t('multiSource.rootPageIdLabel')} hint={t('multiSource.rootPageIdHint')}><Input value={rootPageId} onChange={(e) => setRootPageId(e.target.value)} placeholder="987654" /></Field>
            </SourceToggle>

            <div className="flex justify-end pt-1">
              <Button onClick={() => previewM.mutate()} loading={previewM.isPending} disabled={!ready}>
                <Layers className="h-4 w-4" /> {t('multiSource.previewBtn')}
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
              <div><p className="text-sm font-semibold text-ink-900">{t('multiSource.hardFailTitle')}</p>
                <p className="mt-0.5 text-sm text-muted">{preview.fetchFailures.join('; ') || t('multiSource.hardFailFallback')}{t('multiSource.hardFailBlocked')}</p></div>
            </CardBody></Card>
          )}
          {!preview.hardFail && preview.fetchFailures.length > 0 && (
            <Card className="border-l-4 border-l-warning"><CardBody className="text-sm text-muted">
              <span className="font-medium text-ink-900">{t('multiSource.someItemsNotFetched')}</span> {preview.fetchFailures.join('; ')}
            </CardBody></Card>
          )}
          {preview.carryForwardNotes.length > 0 && (
            <Card className="border-l-4 border-l-warning"><CardBody className="text-sm text-muted">
              <p className="font-medium text-ink-900">{t('multiSource.carryForwardTitle')}</p>
              <ul className="mt-1 list-disc space-y-0.5 pl-5">
                {preview.carryForwardNotes.map((n, i) => <li key={i}>{n}</li>)}
              </ul>
            </CardBody></Card>
          )}

          {/* Feature index — editable */}
          <Card>
            <CardHeader title={t('multiSource.step2Title')}
              subtitle={t('multiSource.step2Subtitle')} />
            <CardBody>
              <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
                <span className="text-muted">{t('multiSource.sourcesLabel')}</span>
                {preview.mix.code && <Badge className={SOURCE_TONE.CODE}>code</Badge>}
                {preview.mix.jira && <Badge className={SOURCE_TONE.JIRA}>jira</Badge>}
                {preview.mix.confluence && <Badge className={SOURCE_TONE.CONFLUENCE}>confluence</Badge>}
                <span className="ml-auto text-muted">
                  {[
                    t('multiSource.featureStatsFeatures', { count: preview.features.length }),
                    t('multiSource.featureStatsGaps', { count: preview.gaps.length }),
                    t('multiSource.featureStatsRedactions', { count: preview.redactionCount }),
                  ].join(' · ')}
                </span>
              </div>

              {/* Merge action bar */}
              {selected.size >= 1 && (
                <div className="mb-3 flex flex-wrap items-center gap-2 rounded-lg border border-brand/40 bg-brand/5 p-2.5">
                  <span className="text-sm font-medium text-ink-900">{t('multiSource.selectedCount', { count: selected.size })}</span>
                  {selected.size >= 2 ? (
                    <>
                      <Input className="h-8 max-w-[220px]" value={mergeName} onChange={(e) => setMergeName(e.target.value)}
                        placeholder={t('multiSource.mergedNamePlaceholder')} />
                      <Button size="sm" onClick={() => mergeM.mutate()} loading={mergeM.isPending}>
                        <GitMerge className="h-4 w-4" /> {t('multiSource.mergeBtn', { count: selected.size })}
                      </Button>
                    </>
                  ) : (
                    <span className="text-xs text-muted">{t('multiSource.selectAnotherToMerge')}</span>
                  )}
                  <Button size="sm" variant="ghost" onClick={() => setSelected(new Set())}>{t('multiSource.clearBtn')}</Button>
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
                          aria-label={t('multiSource.selectToMergeAria', { name: f.displayName })}
                          className="h-4 w-4 rounded border-border text-brand focus:ring-brand/40" />

                        {renaming === f.featureId ? (
                          <form className="flex flex-1 items-center gap-1.5"
                            onSubmit={(e) => { e.preventDefault(); submitRename(); }}>
                            <Input className="h-8" autoFocus value={renameValue}
                              onChange={(e) => setRenameValue(e.target.value)}
                              onKeyDown={(e) => { if (e.key === 'Escape') setRenaming(null); }} />
                            <Button size="sm" type="submit" loading={renameM.isPending} aria-label={t('multiSource.saveNameAria')}><Check className="h-4 w-4" /></Button>
                            <Button size="sm" variant="ghost" type="button" onClick={() => setRenaming(null)} aria-label={t('multiSource.cancelAria')}><X className="h-4 w-4" /></Button>
                          </form>
                        ) : (
                          <>
                            <span className="font-medium text-ink-900">{f.displayName}</span>
                            <button type="button" onClick={() => startRename(f.featureId, f.displayName)} disabled={busy}
                              className="text-muted hover:text-ink-700 disabled:opacity-40" title={t('multiSource.renameTitle')} aria-label={t('multiSource.renameFeatureAria')}>
                              <Pencil className="h-3.5 w-3.5" />
                            </button>
                            <Badge className={STATUS_TONE[f.status] ?? TONE.muted}>{STATUS_LABEL_KEY[f.status] ? t(`multiSource.${STATUS_LABEL_KEY[f.status]}`) : f.status}</Badge>
                            <button type="button" onClick={() => pinM.mutate({ featureId: f.featureId, pinned: !f.pinned })}
                              disabled={busy} title={f.pinned ? t('multiSource.unpinTitle') : t('multiSource.pinConfirmTitle')} aria-label={f.pinned ? t('multiSource.unpinFeatureAria') : t('multiSource.pinFeatureAria')}
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
                            className={cn('rounded px-1.5 py-0.5 text-2xs ring-1', SOURCE_TONE[u.source] ?? TONE.muted)}>
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
              <CardHeader title={t('multiSource.coverageGapsTitle')} subtitle={t('multiSource.coverageGapsSubtitle')} />
              <CardBody className="space-y-2">
                {preview.gaps.map((g, i) => (
                  <div key={i} className="flex items-start gap-2 text-sm">
                    <Badge className={g.kind === 'IMPLEMENTED_UNDOCUMENTED' || g.kind === 'COVERAGE_GAP' ? TONE.danger : TONE.warn}>{GAP_LABEL_KEY[g.kind] ? t(`multiSource.${GAP_LABEL_KEY[g.kind]}`) : g.kind}</Badge>
                    <span className="text-ink-900">{g.message}</span>
                  </div>
                ))}
              </CardBody>
            </Card>
          )}

          {/* Generate */}
          <Card>
            <CardBody className="flex items-center justify-between gap-4">
              <div className="text-sm text-muted">
                {t('multiSource.estimatedCostLabel')} <span className="font-semibold text-ink-900">~{formatMoney(preview.estimatedCostUsd)}</span>
                <span className="ml-1">{t('multiSource.estimatedCostNote')}</span>
              </div>
              <div className="flex items-center gap-2">
                <Button variant="secondary" onClick={() => setPreview(null)}><ArrowLeft className="h-4 w-4" /> {t('multiSource.backBtn')}</Button>
                <Button variant="secondary" onClick={() => recheckM.mutate()} loading={recheckM.isPending} disabled={busy}
                  title={t('multiSource.reextractTitle')}>
                  <RefreshCw className="h-4 w-4" /> {t('multiSource.reextractBtn')}
                </Button>
                <Button onClick={() => generateM.mutate()} loading={generating} disabled={preview.hardFail || busy || generating}>
                  <Sparkles className="h-4 w-4" /> {generating ? t('multiSource.generatingBtn') : t('multiSource.generateBtn')}
                </Button>
              </div>
            </CardBody>
          </Card>
        </div>
      )}

      {!preview && (
        <p className="mt-4 flex items-center gap-1.5 text-xs text-muted">
          <ShieldCheck className="h-3.5 w-3.5" /> {t('multiSource.footerNote')}
        </p>
      )}
    </div>
  );
}
