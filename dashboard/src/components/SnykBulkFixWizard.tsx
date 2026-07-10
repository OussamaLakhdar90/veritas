import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { ShieldCheck, CheckCircle2, AlertTriangle, XCircle, PackageCheck, Settings as SettingsIcon,
  ExternalLink, Copy } from 'lucide-react';
import {
  api, type JiraProject, type JiraEpicOption, type JiraStoryOption, type GitUser, type PreflightCheck,
  type SnykBulkFixRequest, type SnykBulkFixResult, type SnykIssueView, type SnykWatchView,
} from '../api';
import { Modal } from './Modal';
import { FixTrainProgress } from './FixTrainProgress';
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
  const navigate = useNavigate();

  const [step, setStep] = useState(1);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [project, setProject] = useState('');
  const [epicMode, setEpicMode] = useState<'existing' | 'create'>('existing');
  const [epicKey, setEpicKey] = useState('');
  const [epicSummary, setEpicSummary] = useState('');
  const [storyMode, setStoryMode] = useState<'existing' | 'create'>('existing');
  const [storyKey, setStoryKey] = useState('');
  const [storySummary, setStorySummary] = useState('');
  const [reviewers, setReviewers] = useState<string[]>([]);   // validated Bitbucket usernames
  const [result, setResult] = useState<SnykBulkFixResult | null>(null);   // set on success → shows the confirmation

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
      setStoryMode('existing');
      setStoryKey('');
      setStorySummary('');
      setReviewers([]);
      setResult(null);
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
  // Open stories under the chosen (existing) epic — loaded only when the user is picking an existing story.
  const storiesQ = useQuery({
    queryKey: ['jira-epic-stories', epicKey], queryFn: () => api.jiraEpicStories(epicKey),
    enabled: open && step === 4 && epicMode === 'existing' && epicKey !== '' && storyMode === 'existing',
  });

  // Default the new-epic title once, when the user first switches to "create" with an empty box.
  useEffect(() => {
    if (epicMode === 'create' && epicSummary === '') {
      setEpicSummary(t('snyk.bulk.wizard.epicSummaryDefault'));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [epicMode]);
  // A new epic doesn't exist yet, so it has no existing stories to pick — force "create a story".
  useEffect(() => {
    if (epicMode === 'create') {
      setStoryMode('create');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [epicMode]);
  // Default the new-story title once, when the user first switches to "create" with an empty box.
  useEffect(() => {
    if (storyMode === 'create' && storySummary === '') {
      setStorySummary(t('snyk.bulk.wizard.storySummaryDefault'));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storyMode]);

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
    const creatingEpic = epicMode === 'create';
    const creatingStory = storyMode === 'create';
    return {
      project,
      epicKey: creatingEpic ? undefined : (epicKey || undefined),
      createEpic: creatingEpic || undefined,
      epicSummary: creatingEpic ? epicSummary.trim() : undefined,
      storyKey: creatingStory ? undefined : (storyKey || undefined),
      createStory: creatingStory || undefined,
      storySummary: creatingStory ? storySummary.trim() : undefined,
      reviewers: reviewers.length ? reviewers : undefined,
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
        toast.push('success', t('snyk.bulk.wizard.started', { fixes, apps: ok, story: res.storyKey }));
      } else {
        toast.push('error', t('snyk.bulk.wizard.startedSome',
          { story: res.storyKey, ok, total: res.apps.length, failed }));
      }
      setResult(res);   // keep the wizard open on a confirmation screen instead of vanishing
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  // Per-step gating — every disabled Continue/Start shows a one-line reason (no dead buttons).
  const epicReady = epicMode === 'existing' ? epicKey.trim() !== '' : epicSummary.trim() !== '';
  const storyReady = storyMode === 'existing' ? storyKey.trim() !== '' : storySummary.trim() !== '';
  const gate: { ok: boolean; reason?: string } = (() => {
    if (step === 1) {
      return connectionsOk ? { ok: true } : { ok: false, reason: t('snyk.bulk.wizard.connNeedReady') };
    }
    if (step === 2) {
      return selected.size > 0 ? { ok: true } : { ok: false, reason: t('snyk.bulk.wizard.needSelection') };
    }
    if (step === 4) {
      if (project === '') return { ok: false, reason: t('snyk.bulk.wizard.needProject') };
      if (!epicReady) {
        return { ok: false, reason: t(epicMode === 'existing' ? 'snyk.bulk.wizard.needEpic' : 'snyk.bulk.wizard.needEpicSummary') };
      }
      if (!storyReady) {
        return { ok: false, reason: t(storyMode === 'existing' ? 'snyk.bulk.wizard.needStory' : 'snyk.bulk.wizard.needStorySummary') };
      }
      return { ok: true };
    }
    return { ok: true };
  })();

  const titleKey = result ? 'successTitle' : ['step1Title', 'step2Title', 'step3Title', 'step4Title'][step - 1];
  const isLast = step === 4;

  const stepFooter = (
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

  // After a successful launch the wizard stays open on a confirmation screen (the created ticket numbers as links).
  const doneFooter = (
    <>
      <Button variant="secondary" onClick={onClose}>{t('common.close')}</Button>
      <Button onClick={() => { onClose(); navigate('/activity'); }}>{t('snyk.bulk.wizard.success.watch')}</Button>
    </>
  );

  return (
    <Modal open={open} onClose={onClose} size="lg" title={t(`snyk.bulk.wizard.${titleKey}`)}
      footer={result ? doneFooter : stepFooter}>
      {result ? (
        <div className="space-y-5">
          <StepDone result={result} onCopy={(text) => {
            navigator.clipboard?.writeText(text);
            toast.push('success', t('snyk.bulk.wizard.success.copied'));
          }} />
          {/* Live progress right here — the fix trains advance in place instead of vanishing to the Activity feed. */}
          {result.apps.some((a) => a.trainIds.length > 0) && (
            <div className="space-y-3">
              <h3 className="text-sm font-semibold text-ink-900">{t('snyk.bulk.wizard.success.liveTitle')}</h3>
              {result.apps.flatMap((a) => a.trainIds.map((id) => (
                <div key={id} className="rounded-xl ring-1 ring-border p-3">
                  <p className="mb-2 font-mono text-2xs uppercase tracking-wide text-muted">{a.appId}</p>
                  <FixTrainProgress trainId={id} />
                </div>
              )))}
            </div>
          )}
        </div>
      ) : (
        <>
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
            project={project} setProject={(p) => { setProject(p); setEpicKey(''); setStoryKey(''); }}
            epicMode={epicMode} setEpicMode={setEpicMode}
            epicsLoading={epicsQ.isLoading} epicsFailed={epicsQ.isError} epics={epicsQ.data ?? []}
            epicKey={epicKey} setEpicKey={(k) => { setEpicKey(k); setStoryKey(''); }}
            epicSummary={epicSummary} setEpicSummary={setEpicSummary} epicReady={epicReady}
            storyMode={storyMode} setStoryMode={setStoryMode}
            storiesLoading={storiesQ.isLoading} storiesFailed={storiesQ.isError} stories={storiesQ.data ?? []}
            storyKey={storyKey} setStoryKey={setStoryKey}
            storySummary={storySummary} setStorySummary={setStorySummary} storyReady={storyReady}
            reviewers={reviewers} setReviewers={setReviewers} />}
        </>
      )}
    </Modal>
  );
}

/* ── Confirmation: the filed Jira tickets (numbers as links) after a successful launch ──────── */
function StepDone({ result, onCopy }: { result: SnykBulkFixResult; onCopy: (text: string) => void }) {
  const { t } = useTranslation();
  const ok = result.apps.filter((a) => !a.error).length;
  const failed = result.apps.filter((a) => a.error);
  const fixes = result.apps.reduce((n, a) => n + a.trainIds.length, 0);
  return (
    <div className="space-y-4">
      <div className="flex items-start gap-3 rounded-xl bg-success/5 p-4 ring-1 ring-success/20">
        <CheckCircle2 className="mt-0.5 h-6 w-6 shrink-0 text-success" />
        <div className="min-w-0">
          <p className="text-sm font-semibold text-ink-900">{t('snyk.bulk.wizard.success.filed')}</p>
          <p className="mt-0.5 text-xs text-muted">{t('snyk.bulk.wizard.success.summary', { fixes, apps: ok })}</p>
        </div>
      </div>
      <TicketRow label={t('snyk.bulk.wizard.success.epic')} keyText={result.epicKey} url={result.epicUrl} onCopy={onCopy} />
      <TicketRow label={t('snyk.bulk.wizard.success.story')} keyText={result.storyKey} url={result.storyUrl} onCopy={onCopy} />
      {failed.length > 0 && (
        <div className="rounded-lg bg-danger/5 p-3 text-xs ring-1 ring-danger/20">
          <p className="font-medium text-danger">{t('snyk.bulk.wizard.success.someFailed', { count: failed.length })}</p>
          <ul className="mt-1 space-y-0.5 text-muted">
            {failed.map((a) => (
              <li key={a.appId}><span className="font-medium text-ink-700">{a.appId}</span> — {a.error}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

/** One filed ticket: its key as a clickable Jira link (or plain text when unconfigured) + a copy button. */
function TicketRow({ label, keyText, url, onCopy }:
  { label: string; keyText: string; url?: string | null; onCopy: (text: string) => void }) {
  const { t } = useTranslation();
  return (
    <div className="flex items-center gap-3 rounded-lg bg-surface p-3 ring-1 ring-border">
      <span className="text-2xs font-semibold uppercase tracking-wide text-muted">{label}</span>
      {url ? (
        <a href={url} target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center gap-1 text-sm font-semibold text-brand hover:underline">
          {keyText}<ExternalLink className="h-3.5 w-3.5" />
        </a>
      ) : (
        <span className="text-sm font-semibold text-ink-900">{keyText}</span>
      )}
      <button type="button" onClick={() => onCopy(keyText)}
        className="ml-auto inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted ring-1 ring-border hover:bg-ink-50"
        aria-label={`${t('snyk.bulk.wizard.success.copy')} ${keyText}`}>
        <Copy className="h-3.5 w-3.5" />{t('snyk.bulk.wizard.success.copy')}
      </button>
    </div>
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
  storyMode, setStoryMode, storiesLoading, storiesFailed, stories, storyKey, setStoryKey,
  storySummary, setStorySummary, storyReady, reviewers, setReviewers }:
  {
    projectsLoading: boolean; projectsFailed: boolean; projects: JiraProject[];
    project: string; setProject: (p: string) => void;
    epicMode: 'existing' | 'create'; setEpicMode: (m: 'existing' | 'create') => void;
    epicsLoading: boolean; epicsFailed: boolean; epics: JiraEpicOption[];
    epicKey: string; setEpicKey: (k: string) => void;
    epicSummary: string; setEpicSummary: (s: string) => void; epicReady: boolean;
    storyMode: 'existing' | 'create'; setStoryMode: (m: 'existing' | 'create') => void;
    storiesLoading: boolean; storiesFailed: boolean; stories: JiraStoryOption[];
    storyKey: string; setStoryKey: (k: string) => void;
    storySummary: string; setStorySummary: (s: string) => void; storyReady: boolean;
    reviewers: string[]; setReviewers: (r: string[]) => void;
  }) {
  const { t } = useTranslation();
  // The story only makes sense once we know the epic: an existing epic that's been chosen, or a brand-new epic.
  const epicChosen = epicMode === 'existing' ? epicKey !== '' : epicSummary.trim() !== '';
  const newEpic = epicMode === 'create';
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

      {/* Story — one shared story under the epic that every fix links to. Needs an epic first. A brand-new epic
          has no existing stories, so we only offer "create" there (the mode is forced upstream). */}
      {project !== '' && epicChosen && (
        <Field label={t('snyk.bulk.wizard.storyLabel')} hint={t('snyk.bulk.wizard.storyHint')}>
          <div className="space-y-2">
            {!newEpic && (
              <div className="flex flex-wrap gap-4 text-sm">
                <label className="inline-flex cursor-pointer items-center gap-2 text-ink-900">
                  <input type="radio" name="storyMode" className="accent-brand" checked={storyMode === 'existing'}
                    onChange={() => setStoryMode('existing')} />
                  {t('snyk.bulk.wizard.storyExisting')}
                </label>
                <label className="inline-flex cursor-pointer items-center gap-2 text-ink-900">
                  <input type="radio" name="storyMode" className="accent-brand" checked={storyMode === 'create'}
                    onChange={() => setStoryMode('create')} />
                  {t('snyk.bulk.wizard.storyCreate')}
                </label>
              </div>
            )}

            {storyMode === 'existing' && !newEpic ? (
              storiesLoading ? (
                <span className="inline-flex items-center gap-2 text-sm text-muted">
                  <Spinner /> {t('snyk.bulk.wizard.storyLoading')}
                </span>
              ) : storiesFailed ? (
                <span className="text-sm text-danger">{t('snyk.bulk.wizard.storyLoadFailed')}</span>
              ) : stories.length === 0 ? (
                <span className="text-sm text-muted">{t('snyk.bulk.wizard.storyNone')}</span>
              ) : (
                <Select value={storyKey} onChange={(e) => setStoryKey(e.target.value)} invalid={!storyReady}>
                  <option value="">{t('snyk.bulk.wizard.storyChoose')}</option>
                  {stories.map((s) => (
                    <option key={s.key} value={s.key}>{s.summary} ({s.key})</option>
                  ))}
                </Select>
              )
            ) : (
              <>
                <Input value={storySummary} onChange={(e) => setStorySummary(e.target.value)}
                  placeholder={t('snyk.bulk.wizard.storySummaryDefault')} invalid={!storyReady} />
                <span className="block text-xs text-muted">{t('snyk.bulk.wizard.storySummaryHint')}</span>
              </>
            )}
          </div>
        </Field>
      )}

      {/* Reviewers — real Bitbucket users only, picked from an autocomplete (free text let anything through). */}
      <Field label={t('snyk.bulk.wizard.reviewersLabel')} hint={t('snyk.bulk.wizard.reviewersHint')}>
        <ReviewerPicker reviewers={reviewers} setReviewers={setReviewers} />
      </Field>
    </div>
  );
}

/* ── Reviewer autocomplete: validated Bitbucket usernames as removable chips ─── */
function ReviewerPicker({ reviewers, setReviewers }:
  { reviewers: string[]; setReviewers: (r: string[]) => void }) {
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const [debounced, setDebounced] = useState('');

  // Debounce keystrokes so we don't fire a lookup per character; the query itself is cached by React Query.
  useEffect(() => {
    const id = setTimeout(() => setDebounced(q.trim()), 250);
    return () => clearTimeout(id);
  }, [q]);

  const usersQ = useQuery({
    queryKey: ['bitbucket-users', debounced], queryFn: () => api.bitbucketUsers(debounced),
    enabled: debounced.length >= 2,
  });

  const add = (name: string) => {
    if (name && !reviewers.includes(name)) setReviewers([...reviewers, name]);
    setQ('');
    setDebounced('');
  };
  const remove = (name: string) => setReviewers(reviewers.filter((r) => r !== name));

  // Only suggest users not already picked; the endpoint caps the list, so no client-side slice needed.
  const suggestions = (usersQ.data ?? []).filter((u) => !reviewers.includes(u.name));

  return (
    <div className="space-y-2">
      {reviewers.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {reviewers.map((r) => (
            <span key={r}
              className="inline-flex items-center gap-1 rounded-full bg-brand/10 px-2.5 py-1 text-xs font-medium text-brand ring-1 ring-brand/20">
              {r}
              <button type="button" onClick={() => remove(r)} aria-label={t('snyk.bulk.wizard.reviewerRemove', { name: r })}
                className="rounded-full text-brand/70 hover:text-brand">
                <XCircle className="h-3.5 w-3.5" />
              </button>
            </span>
          ))}
        </div>
      )}
      <div>
        <Input value={q} onChange={(e) => setQ(e.target.value)}
          placeholder={t('snyk.bulk.wizard.reviewerSearch')} aria-label={t('snyk.bulk.wizard.reviewerSearch')} />
        {/* Suggestions render in normal flow (not an absolute popover) so the modal body scrolls to reach them
            even when the reviewer field is the last one — an absolute layer would clip under overflow-y-auto. */}
        {debounced.length >= 2 && (
          <div className="mt-1 overflow-hidden rounded-lg ring-1 ring-border">
            {usersQ.isLoading ? (
              <div className="flex items-center gap-2 px-3 py-2 text-sm text-muted">
                <Spinner /> {t('snyk.bulk.wizard.reviewerSearching')}
              </div>
            ) : usersQ.isError ? (
              <div className="px-3 py-2 text-sm text-danger">{t('snyk.bulk.wizard.reviewerSearchFailed')}</div>
            ) : suggestions.length === 0 ? (
              <div className="px-3 py-2 text-sm text-muted">{t('snyk.bulk.wizard.reviewerNoMatch')}</div>
            ) : (
              <ul className="max-h-48 overflow-y-auto py-1">
                {suggestions.map((u) => (
                  <li key={u.name}>
                    <button type="button" onClick={() => add(u.name)}
                      className="flex w-full flex-col items-start px-3 py-1.5 text-left hover:bg-ink-50">
                      <span className="text-sm text-ink-900">{u.displayName}</span>
                      <span className="text-xs text-muted">{u.name}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
