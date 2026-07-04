import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { ShieldCheck, CheckCircle2, AlertTriangle, XCircle, PackageCheck, Settings as SettingsIcon } from 'lucide-react';
import {
  api, type JiraProject, type JiraEpicOption, type PreflightCheck, type SnykBulkFixRequest,
  type SnykIssueView, type SnykWatchView,
} from '../api';
import { Modal } from './Modal';
import { Badge, Button, EmptyState, Field, Input, Select, Spinner } from './ui';
import { useToast } from './Toast';
import { snykSeverityBadge, TONE } from '../theme/tokens';

/** Snyk raw lowercase severity → the localized Critical/High/Medium/Low label key (mirrors Snyk.tsx). */
const SEV_KEY: Record<string, string> = {
  critical: 'snyk.sevCritical', high: 'snyk.sevHigh', medium: 'snyk.sevMedium', low: 'snyk.sevLow',
};
/** Severity rank — critical first, so grouped rows read the way a reviewer works top-down. */
const SEV_RANK: Record<string, number> = { critical: 4, high: 3, medium: 2, low: 1 };
/** The severities we offer a "select all <severity>" shortcut for. */
const SEVERITIES = ['critical', 'high', 'medium', 'low'] as const;

/**
 * The three connections this flow depends on, matched loosely against the preflight check names (ConfigDoctor
 * returns human names like "Jira token", "Bitbucket base URL"). Snyk + Jira + Bitbucket are required; anything
 * else the doctor reports (Copilot, Xray, Confluence…) is not a gate for opening dependency-fix PRs.
 */
const CONNECTIONS = [
  { id: 'snyk', labelKey: 'snyk.bulk.wizard.connSnyk', match: /snyk/i },
  { id: 'jira', labelKey: 'snyk.bulk.wizard.connJira', match: /jira/i },
  { id: 'bitbucket', labelKey: 'snyk.bulk.wizard.connBitbucket', match: /bitbucket|git access|git token/i },
] as const;

/** A fixable vulnerability plus the watch (app) it belongs to — the unit the bulk flow starts a fix for. */
interface FixableRow {
  watch: SnykWatchView;
  issue: SnykIssueView;
  /** Stable per-issue key — the same issue can appear under two projects of one app, so include the project. */
  key: string;
}

/** Only issues Snyk can actually fix (a safe version exists) are offered — the SAME predicate as the per-row Fix button. */
function isFixable(i: SnykIssueView): boolean {
  return i.fixable && !!i.fixedIn;
}

/** Roll the preflight checks down to a single status for one of our three connections (worst wins). */
function connStatus(checks: PreflightCheck[], match: RegExp): 'OK' | 'WARN' | 'MISSING' | 'UNKNOWN' {
  const relevant = checks.filter((c) => match.test(c.name));
  if (relevant.length === 0) return 'UNKNOWN';
  if (relevant.some((c) => c.status === 'MISSING')) return 'MISSING';
  if (relevant.some((c) => c.status === 'WARN')) return 'WARN';
  return 'OK';
}

/**
 * "Fix vulnerabilities" — a 4-step, plain-language wizard a non-developer can follow:
 *   1) Check your connections (a gate) · 2) Choose what to fix · 3) Review what will happen · 4) File it in Jira, then start.
 * It reuses the per-watch issues endpoint the page already owns for the selection list, and wires "Start" to the
 * already-built bulk endpoint (POST /api/v1/snyk/fixes/bulk) — one epic, one ticket + fix train per app. It only
 * kicks the work off; each train then surfaces in the Activity Center + the fix-train view.
 */
export function SnykBulkFixWizard({ open, onClose, watches }:
  { open: boolean; onClose: () => void; watches: SnykWatchView[] }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();

  const [step, setStep] = useState(1);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [project, setProject] = useState('');
  const [epicMode, setEpicMode] = useState<'existing' | 'create'>('existing');
  const [epicKey, setEpicKey] = useState('');
  const [epicSummary, setEpicSummary] = useState('');
  const [reviewers, setReviewers] = useState('');

  // Only apps that report fixable vulnerabilities are worth loading issues for — skip the clean ones entirely.
  const fixableWatches = useMemo(() => watches.filter((w) => w.fixable > 0), [watches]);

  // ── Step 1: connections (reuse the Settings preflight; runs only while the wizard is open) ──
  const preflightQ = useQuery({ queryKey: ['preflight'], queryFn: api.preflight, enabled: open });
  const preChecks = preflightQ.data ?? [];
  const connStatuses = useMemo(
    () => CONNECTIONS.map((c) => ({ ...c, status: connStatus(preChecks, c.match) })),
    [preChecks],
  );
  // A required connection blocks Continue when it's MISSING (or couldn't be found at all). WARN is allowed through.
  const connectionsOk = connStatuses.every((c) => c.status === 'OK' || c.status === 'WARN');

  // ── Step 2: the selection (one query per app with fixable issues) ──
  const issuesQueries = useQueries({
    queries: fixableWatches.map((w) => ({
      queryKey: ['snyk-issues', w.id],
      queryFn: () => api.snykIssues(w.id),
      enabled: open,
    })),
  });
  const issuesLoading = open && issuesQueries.some((q) => q.isLoading);
  const issuesFailed = issuesQueries.some((q) => q.isError);

  const rows: FixableRow[] = useMemo(() => {
    const out: FixableRow[] = [];
    fixableWatches.forEach((w, idx) => {
      const data = issuesQueries[idx]?.data ?? [];
      data.filter(isFixable)
        .sort((a, b) => (SEV_RANK[b.severity?.toLowerCase()] ?? 0) - (SEV_RANK[a.severity?.toLowerCase()] ?? 0))
        .forEach((issue) => out.push({ watch: w, issue, key: `${w.id}::${issue.issueId}::${issue.projectName}` }));
    });
    return out;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fixableWatches, issuesQueries.map((q) => q.dataUpdatedAt).join(',')]);

  const rowsByWatch = useMemo(() => {
    const map = new Map<string, { watch: SnykWatchView; rows: FixableRow[] }>();
    for (const r of rows) {
      const bucket = map.get(r.watch.id) ?? { watch: r.watch, rows: [] };
      bucket.rows.push(r);
      map.set(r.watch.id, bucket);
    }
    return [...map.values()];
  }, [rows]);

  // Reset everything each time the wizard opens, so a prior run never leaves stale ticks/text/step behind.
  useEffect(() => {
    if (open) {
      setStep(1);
      setSelected(new Set());
      setProject('');
      setEpicMode('existing');
      setEpicKey('');
      setEpicSummary('');
      setReviewers('');
    }
  }, [open]);

  const toggle = (key: string, on: boolean) => setSelected((prev) => {
    const next = new Set(prev);
    if (on) next.add(key); else next.delete(key);
    return next;
  });
  const allKeys = useMemo(() => rows.map((r) => r.key), [rows]);
  const allSelected = allKeys.length > 0 && allKeys.every((k) => selected.has(k));
  const setAll = (on: boolean) => setSelected(on ? new Set(allKeys) : new Set());
  const selectSeverity = (sev: string) => setSelected((prev) => {
    const next = new Set(prev);
    for (const r of rows) {
      if (r.issue.severity?.toLowerCase() === sev) next.add(r.key);
    }
    return next;
  });

  // The selected rows, and the distinct apps among them — the review step + the request both read this.
  const chosenRows = useMemo(() => rows.filter((r) => selected.has(r.key)), [rows, selected]);
  const chosenApps = useMemo(
    () => [...new Map(chosenRows.map((r) => [r.watch.id, r.watch])).values()],
    [chosenRows],
  );

  // ── Step 4: Jira project + epic (loaded lazily as the user reaches the step / picks a project) ──
  const projectsQ = useQuery({ queryKey: ['jira-projects'], queryFn: api.jiraProjects, enabled: open && step === 4 });
  const epicsQ = useQuery({
    queryKey: ['jira-epics', project], queryFn: () => api.jiraEpics(project),
    enabled: open && step === 4 && project !== '' && epicMode === 'existing',
  });

  // Default the new-epic title once, when the user first switches to "create" with an empty box.
  useEffect(() => {
    if (epicMode === 'create' && epicSummary === '') {
      setEpicSummary(t('snyk.bulk.wizard.epicSummaryDefault'));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [epicMode]);

  const reviewerList = useMemo(
    () => reviewers.split(',').map((r) => r.trim()).filter(Boolean),
    [reviewers],
  );

  // Group the selected issues by app into the request shape the backend expects.
  const buildRequest = (): SnykBulkFixRequest => {
    const byApp = new Map<string, SnykBulkFixRequest['apps'][number]>();
    for (const r of chosenRows) {
      const appId = r.watch.orgSlug.toUpperCase();   // Snyk org slug (app7576) → Bitbucket project (APP7576)
      const bucket = byApp.get(r.watch.id) ?? { appId, watchId: r.watch.id, issues: [] };
      bucket.issues.push({
        issueId: r.issue.issueId,
        coordinate: r.issue.pkgName,
        oldVersion: r.issue.pkgVersion,
        fixedIn: r.issue.fixedIn ?? '',
        severity: r.issue.severity,
      });
      byApp.set(r.watch.id, bucket);
    }
    const creating = epicMode === 'create';
    return {
      project,
      epicKey: creating ? undefined : (epicKey || undefined),
      createEpic: creating || undefined,
      epicSummary: creating ? epicSummary.trim() : undefined,
      reviewers: reviewerList.length ? reviewerList : undefined,
      apps: [...byApp.values()],
    };
  };

  const start = useMutation({
    mutationFn: () => api.bulkSnykFix(buildRequest()),
    onSuccess: (res) => {
      // A started batch changes the summary/impact totals, the fix-train list AND every watch's issues — settle the
      // whole family so the fix-train view + Activity Center reflect the new trains without a manual refresh.
      qc.invalidateQueries({ queryKey: ['snyk-summary'] });
      qc.invalidateQueries({ queryKey: ['snyk-fixes'] });
      qc.invalidateQueries({ queryKey: ['activity'] });
      for (const w of fixableWatches) qc.invalidateQueries({ queryKey: ['snyk-issues', w.id] });

      const ok = res.apps.filter((a) => !a.error).length;
      const failed = res.apps.length - ok;
      const fixes = res.apps.reduce((n, a) => n + a.trainIds.length, 0);
      if (failed === 0) {
        toast.push('success', t('snyk.bulk.wizard.started', { fixes, apps: ok, epic: res.epicKey }));
      } else {
        toast.push('error', t('snyk.bulk.wizard.startedSome',
          { epic: res.epicKey, ok, total: res.apps.length, failed }));
      }
      onClose();
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  // Per-step gating — every disabled Continue/Start shows a one-line reason (no dead buttons).
  const epicReady = epicMode === 'existing' ? epicKey.trim() !== '' : epicSummary.trim() !== '';
  const gate: { ok: boolean; reason?: string } = (() => {
    if (step === 1) {
      return connectionsOk ? { ok: true } : { ok: false, reason: t('snyk.bulk.wizard.connNeedReady') };
    }
    if (step === 2) {
      return selected.size > 0 ? { ok: true } : { ok: false, reason: t('snyk.bulk.wizard.needSelection') };
    }
    if (step === 4) {
      if (project === '') return { ok: false, reason: t('snyk.bulk.wizard.needProject') };
      if (epicMode === 'existing' && epicKey === '') return { ok: false, reason: t('snyk.bulk.wizard.needEpic') };
      if (epicMode === 'create' && epicSummary.trim() === '') return { ok: false, reason: t('snyk.bulk.wizard.needEpicSummary') };
      return { ok: true };
    }
    return { ok: true };
  })();

  const titleKey = ['step1Title', 'step2Title', 'step3Title', 'step4Title'][step - 1];
  const isLast = step === 4;

  const footer = (
    <>
      {!gate.ok && gate.reason && (
        <span className="mr-auto self-center text-xs text-warning" role="status" aria-live="polite">
          {gate.reason}
        </span>
      )}
      {step > 1
        ? <Button variant="secondary" onClick={() => setStep((s) => s - 1)}>{t('snyk.bulk.wizard.back')}</Button>
        : <Button variant="secondary" onClick={onClose}>{t('common.close')}</Button>}
      {isLast ? (
        <Button disabled={!gate.ok || start.isPending} loading={start.isPending} onClick={() => start.mutate()}>
          {start.isPending ? t('snyk.bulk.wizard.starting') : t('snyk.bulk.wizard.start', { count: chosenRows.length })}
        </Button>
      ) : (
        <Button disabled={!gate.ok} onClick={() => setStep((s) => s + 1)}>{t('snyk.bulk.wizard.continue')}</Button>
      )}
    </>
  );

  return (
    <Modal open={open} onClose={onClose} size="lg" title={t(`snyk.bulk.wizard.${titleKey}`)} footer={footer}>
      {/* Step indicator */}
      <div className="mb-4 flex items-center gap-2">
        <span className="text-xs font-medium text-muted" role="status" aria-live="polite">
          {t('snyk.bulk.wizard.stepOf', { current: step, total: 4 })}
        </span>
        <div className="flex flex-1 items-center gap-1.5" aria-hidden="true">
          {[1, 2, 3, 4].map((n) => (
            <span key={n}
              className={`h-1.5 flex-1 rounded-full ${n <= step ? 'bg-brand' : 'bg-ink-100'}`} />
          ))}
        </div>
      </div>

      {step === 1 && <StepConnections loading={preflightQ.isLoading} failed={preflightQ.isError}
        statuses={connStatuses} allGood={connectionsOk} onClose={onClose} />}
      {step === 2 && <StepSelect loading={issuesLoading} failed={issuesFailed} rows={rows} rowsByWatch={rowsByWatch}
        selected={selected} allSelected={allSelected} setAll={setAll} selectSeverity={selectSeverity}
        toggle={toggle} />}
      {step === 3 && <StepReview rowCount={chosenRows.length} apps={chosenApps} />}
      {step === 4 && <StepJira
        projectsLoading={projectsQ.isLoading} projectsFailed={projectsQ.isError} projects={projectsQ.data ?? []}
        project={project} setProject={(p) => { setProject(p); setEpicKey(''); }}
        epicMode={epicMode} setEpicMode={setEpicMode}
        epicsLoading={epicsQ.isLoading} epicsFailed={epicsQ.isError} epics={epicsQ.data ?? []}
        epicKey={epicKey} setEpicKey={setEpicKey}
        epicSummary={epicSummary} setEpicSummary={setEpicSummary} epicReady={epicReady}
        reviewers={reviewers} setReviewers={setReviewers} />}
    </Modal>
  );
}

/* ── Step 1: connections gate ─────────────────────────────────────────────── */
function StepConnections({ loading, failed, statuses, allGood, onClose }:
  { loading: boolean; failed: boolean; allGood: boolean; onClose: () => void;
    statuses: { id: string; labelKey: string; status: 'OK' | 'WARN' | 'MISSING' | 'UNKNOWN' }[] }) {
  const { t } = useTranslation();
  if (loading) {
    return (
      <div className="flex items-center gap-2 py-6 text-sm text-muted">
        <Spinner /> {t('snyk.bulk.wizard.connChecking')}
      </div>
    );
  }
  if (failed) {
    return <EmptyState icon={XCircle} title={t('snyk.bulk.wizard.connLoadFailed')} />;
  }
  return (
    <div className="space-y-4">
      <p className="text-sm text-muted">{t('snyk.bulk.wizard.connIntro')}</p>
      <ul className="space-y-2">
        {statuses.map((c) => {
          // UNKNOWN (the doctor didn't report this one) is treated as not-ready so the gate stays honest.
          const ok = c.status === 'OK';
          const warn = c.status === 'WARN';
          const Icon = ok ? CheckCircle2 : warn ? AlertTriangle : XCircle;
          const iconCls = ok ? 'text-success' : warn ? 'text-warning' : 'text-danger';
          const badgeCls = ok ? TONE.ok : warn ? TONE.warn : TONE.danger;
          const label = ok ? t('snyk.bulk.wizard.connOk')
            : warn ? t('snyk.bulk.wizard.connWarn') : t('snyk.bulk.wizard.connMissing');
          return (
            <li key={c.id} className="flex items-center gap-3 rounded-lg ring-1 ring-border px-3 py-2.5">
              <Icon className={`h-4 w-4 shrink-0 ${iconCls}`} aria-hidden="true" />
              <span className="min-w-0 flex-1 text-sm text-ink-900">{t(c.labelKey)}</span>
              <Badge className={badgeCls}>{label}</Badge>
            </li>
          );
        })}
      </ul>
      {allGood ? (
        <p className="rounded-lg bg-success/10 px-3 py-2 text-xs text-success ring-1 ring-success/20">
          {t('snyk.bulk.wizard.connAllGood')}
        </p>
      ) : (
        <div className="rounded-lg bg-warning/10 px-3 py-3 text-sm text-ink-900 ring-1 ring-warning/25">
          <p>{t('snyk.bulk.wizard.connBlocked')}</p>
          <Link to="/settings" onClick={onClose}
            className="mt-2 inline-flex items-center gap-1.5 font-medium text-gold hover:underline">
            <SettingsIcon className="h-3.5 w-3.5" /> {t('snyk.bulk.wizard.connGoToSettings')}
          </Link>
        </div>
      )}
    </div>
  );
}

/* ── Step 2: choose what to fix (the reused selection UI) ──────────────────── */
function StepSelect({ loading, failed, rows, rowsByWatch, selected, allSelected, setAll, selectSeverity, toggle }:
  { loading: boolean; failed: boolean; rows: FixableRow[];
    rowsByWatch: { watch: SnykWatchView; rows: FixableRow[] }[];
    selected: Set<string>; allSelected: boolean; setAll: (on: boolean) => void;
    selectSeverity: (sev: string) => void; toggle: (key: string, on: boolean) => void }) {
  const { t } = useTranslation();
  if (loading) {
    return (
      <div className="flex items-center gap-2 py-6 text-sm text-muted">
        <Spinner /> {t('snyk.bulk.wizard.pickLoading')}
      </div>
    );
  }
  if (rows.length === 0) {
    return (
      <EmptyState icon={ShieldCheck}
        title={failed ? t('snyk.bulk.wizard.pickLoadFailed') : t('snyk.bulk.wizard.pickEmptyTitle')}
        body={failed ? undefined : t('snyk.bulk.wizard.pickEmptyBody')} />
    );
  }
  return (
    <div className="space-y-4">
      <p className="text-sm text-muted">{t('snyk.bulk.wizard.pickIntro')}</p>

      {/* Select-all / by-severity shortcuts + a running count */}
      <div className="flex flex-wrap items-center gap-2 rounded-lg bg-ink-50/60 px-3 py-2">
        <Button size="sm" variant="ghost" onClick={() => setAll(!allSelected)}>
          {allSelected ? t('snyk.bulk.selectNone') : t('snyk.bulk.selectAll')}
        </Button>
        <span className="text-xs text-muted">·</span>
        {SEVERITIES.map((sev) => rows.some((r) => r.issue.severity?.toLowerCase() === sev) && (
          <Button key={sev} size="sm" variant="ghost" onClick={() => selectSeverity(sev)}>
            {t(SEV_KEY[sev])}
          </Button>
        ))}
        <span className="ml-auto text-xs font-medium text-ink-700" role="status" aria-live="polite">
          {t('snyk.bulk.selectedCount', { count: selected.size })}
        </span>
      </div>

      {/* Fixable issues grouped by app */}
      <div className="space-y-3">
        {rowsByWatch.map(({ watch, rows: appRows }) => (
          <div key={watch.id} className="rounded-lg ring-1 ring-border">
            <div className="flex items-center gap-2 border-b border-border px-3 py-2">
              <span className="truncate text-sm font-semibold text-ink-900">{watch.orgName || watch.orgSlug}</span>
              <span className="text-2xs text-muted">{watch.orgSlug.toUpperCase()}</span>
              <span className="ml-auto text-xs text-muted">{t('snyk.fixable', { count: appRows.length })}</span>
            </div>
            <ul>
              {appRows.map((r) => (
                <li key={r.key}>
                  <label className="flex cursor-pointer items-start gap-2.5 px-3 py-2 text-sm hover:bg-ink-50/60">
                    <input type="checkbox" className="mt-0.5 accent-brand" checked={selected.has(r.key)}
                      onChange={(e) => toggle(r.key, e.target.checked)} />
                    <Badge className={snykSeverityBadge(r.issue.severity)}>
                      {t(SEV_KEY[r.issue.severity] ?? r.issue.severity)}
                    </Badge>
                    <span className="min-w-0 flex-1">
                      <span className="font-medium text-ink-900">{r.issue.pkgName}</span>
                      <span className="text-muted">@{r.issue.pkgVersion}</span>
                      {r.issue.title && <span className="block text-xs text-muted">{r.issue.title}</span>}
                    </span>
                    <Badge className={`shrink-0 ${TONE.ok}`}>
                      {t('snyk.fixAvailable', { version: r.issue.fixedIn })}
                    </Badge>
                  </label>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ── Step 3: review what will happen ──────────────────────────────────────── */
function StepReview({ rowCount, apps }: { rowCount: number; apps: SnykWatchView[] }) {
  const { t } = useTranslation();
  return (
    <div className="space-y-4">
      <p className="text-sm text-muted">{t('snyk.bulk.wizard.reviewIntro')}</p>
      <div className="rounded-lg bg-ink-50/60 px-4 py-3">
        <p className="text-sm font-medium text-ink-900">
          {t('snyk.bulk.wizard.reviewCount', { count: rowCount, apps: apps.length })}
        </p>
      </div>
      <ol className="space-y-3">
        <li className="flex items-start gap-2.5 text-sm text-ink-900">
          <PackageCheck className="mt-0.5 h-4 w-4 shrink-0 text-brand" aria-hidden="true" />
          <span>{t('snyk.bulk.wizard.reviewFramework')}</span>
        </li>
        <li className="text-sm text-ink-900">
          <p className="mb-2">{t('snyk.bulk.wizard.reviewApps')}</p>
          <div className="flex flex-wrap gap-1.5">
            {apps.map((a) => (
              <Badge key={a.id} className={TONE.info}>{a.orgSlug.toUpperCase()}</Badge>
            ))}
          </div>
        </li>
      </ol>
      <p className="rounded-lg bg-ink-50/60 px-3 py-2 text-xs text-muted">{t('snyk.bulk.wizard.reviewSafety')}</p>
    </div>
  );
}

/* ── Step 4: file it in Jira, then start ──────────────────────────────────── */
function StepJira({ projectsLoading, projectsFailed, projects, project, setProject, epicMode, setEpicMode,
  epicsLoading, epicsFailed, epics, epicKey, setEpicKey, epicSummary, setEpicSummary, epicReady,
  reviewers, setReviewers }:
  {
    projectsLoading: boolean; projectsFailed: boolean; projects: JiraProject[];
    project: string; setProject: (p: string) => void;
    epicMode: 'existing' | 'create'; setEpicMode: (m: 'existing' | 'create') => void;
    epicsLoading: boolean; epicsFailed: boolean; epics: JiraEpicOption[];
    epicKey: string; setEpicKey: (k: string) => void;
    epicSummary: string; setEpicSummary: (s: string) => void; epicReady: boolean;
    reviewers: string; setReviewers: (r: string) => void;
  }) {
  const { t } = useTranslation();
  return (
    <div className="space-y-4">
      {/* Project — a real dropdown, never free-typed (an invalid key was the whole problem) */}
      <Field label={t('snyk.bulk.wizard.projectLabel')} hint={t('snyk.bulk.wizard.projectHint')}>
        {projectsLoading ? (
          <span className="inline-flex items-center gap-2 text-sm text-muted">
            <Spinner /> {t('snyk.bulk.wizard.projectLoading')}
          </span>
        ) : projectsFailed ? (
          <span className="text-sm text-danger">{t('snyk.bulk.wizard.projectLoadFailed')}</span>
        ) : projects.length === 0 ? (
          <span className="text-sm text-muted">{t('snyk.bulk.wizard.projectEmpty')}</span>
        ) : (
          <Select value={project} onChange={(e) => setProject(e.target.value)}>
            <option value="">{t('snyk.bulk.wizard.projectPlaceholder')}</option>
            {projects.map((p) => (
              <option key={p.key} value={p.key}>{p.name} ({p.key})</option>
            ))}
          </Select>
        )}
      </Field>

      {/* Epic — pick an existing one, or create a new one. Only meaningful once a project is chosen. */}
      {project !== '' && (
        <Field label={t('snyk.bulk.wizard.epicLabel')} hint={t('snyk.bulk.wizard.epicHint')}>
          <div className="space-y-2">
            <div className="flex flex-wrap gap-4 text-sm">
              <label className="inline-flex cursor-pointer items-center gap-2 text-ink-900">
                <input type="radio" name="epicMode" className="accent-brand" checked={epicMode === 'existing'}
                  onChange={() => setEpicMode('existing')} />
                {t('snyk.bulk.wizard.epicExisting')}
              </label>
              <label className="inline-flex cursor-pointer items-center gap-2 text-ink-900">
                <input type="radio" name="epicMode" className="accent-brand" checked={epicMode === 'create'}
                  onChange={() => setEpicMode('create')} />
                {t('snyk.bulk.wizard.epicCreate')}
              </label>
            </div>

            {epicMode === 'existing' ? (
              epicsLoading ? (
                <span className="inline-flex items-center gap-2 text-sm text-muted">
                  <Spinner /> {t('snyk.bulk.wizard.epicLoading')}
                </span>
              ) : epicsFailed ? (
                <span className="text-sm text-danger">{t('snyk.bulk.wizard.epicLoadFailed')}</span>
              ) : epics.length === 0 ? (
                <span className="text-sm text-muted">{t('snyk.bulk.wizard.epicNone')}</span>
              ) : (
                <Select value={epicKey} onChange={(e) => setEpicKey(e.target.value)} invalid={!epicReady}>
                  <option value="">{t('snyk.bulk.wizard.epicChoose')}</option>
                  {epics.map((ep) => (
                    <option key={ep.key} value={ep.key}>{ep.summary} ({ep.key})</option>
                  ))}
                </Select>
              )
            ) : (
              <Input value={epicSummary} onChange={(e) => setEpicSummary(e.target.value)}
                placeholder={t('snyk.bulk.wizard.epicSummaryDefault')} invalid={!epicReady} />
            )}
            {epicMode === 'create' && (
              <span className="block text-xs text-muted">{t('snyk.bulk.wizard.epicSummaryHint')}</span>
            )}
          </div>
        </Field>
      )}

      <Field label={t('snyk.bulk.wizard.reviewersLabel')} hint={t('snyk.bulk.wizard.reviewersHint')}>
        <Input value={reviewers} onChange={(e) => setReviewers(e.target.value)} placeholder="alice, bob" />
      </Field>
    </div>
  );
}
