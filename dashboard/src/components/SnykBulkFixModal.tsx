import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQueries, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ShieldCheck } from 'lucide-react';
import { api, type SnykIssueView, type SnykWatchView } from '../api';
import { Modal } from './Modal';
import { Badge, Button, EmptyState, Field, Input, Spinner } from './ui';
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

/**
 * "Fix vulnerabilities" — the top-level, select-what-to-fix flow. Aggregates every fixable vulnerability across the
 * watched apps (reusing the per-watch issues endpoint the page already exposes), lets the user tick the ones to fix
 * (with select-all / by-severity shortcuts), collects one Jira project + optional reviewers, then starts a fix train
 * per selected issue by looping the SAME start endpoint the single-issue wizard uses. Each train then surfaces in the
 * Activity Center + the fix-train view — this modal only kicks them off; it never invents a new fix engine.
 */
export function SnykBulkFixModal({ open, onClose, watches }:
  { open: boolean; onClose: () => void; watches: SnykWatchView[] }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();

  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [jiraProject, setJiraProject] = useState('');
  const [reviewers, setReviewers] = useState('');

  // Only apps that report fixable vulnerabilities are worth loading issues for — skip the clean ones entirely.
  const fixableWatches = useMemo(() => watches.filter((w) => w.fixable > 0), [watches]);

  // Reuse the existing per-watch issues endpoint (one query per app with fixable issues) rather than adding a
  // backend aggregate — the page already owns this data source. Queries run only while the modal is open.
  const issuesQueries = useQueries({
    queries: fixableWatches.map((w) => ({
      queryKey: ['snyk-issues', w.id],
      queryFn: () => api.snykIssues(w.id),
      enabled: open,
    })),
  });

  const loading = open && issuesQueries.some((q) => q.isLoading);
  const failed = issuesQueries.some((q) => q.isError);

  // Flatten to one row per (app, fixable issue), severity-ranked within each app.
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

  // Reset the selection + inputs every time the modal opens, so a prior run never leaves stale ticks/text behind.
  useEffect(() => {
    if (open) {
      setSelected(new Set());
      setJiraProject('');
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

  // A Jira project is required (each fix creates its own ticket there) — mirrors the wizard's create-a-ticket path.
  const canStart = selected.size > 0 && jiraProject.trim() !== '';

  const start = useMutation({
    mutationFn: async () => {
      const chosen = rows.filter((r) => selected.has(r.key));
      const reviewerList = reviewers.split(',').map((r) => r.trim()).filter(Boolean);
      // Loop the SAME per-issue start endpoint the single-issue wizard calls (like watchSelected's allSettled) —
      // no new fix engine. autoConfirm=true runs each train straight through (the reactor build is the real gate);
      // clean fixes land at PR_OPEN and breaking ones hold at AWAITING_MANUAL_FIX, both surfaced for the human.
      return Promise.allSettled(chosen.map((r) => api.startSnykFix({
        watchId: r.watch.id,
        issueId: r.issue.issueId,
        coordinate: r.issue.pkgName,
        oldVersion: r.issue.pkgVersion,
        fixedIn: r.issue.fixedIn ?? '',
        severity: r.issue.severity,
        appIds: [r.watch.orgSlug.toUpperCase()],   // Snyk org slug (app7576) → Bitbucket project (APP7576)
        jiraProject: jiraProject.trim(),
        reviewers: reviewerList,
        autoConfirm: true,
      })));
    },
    onSuccess: (results) => {
      const ok = results.filter((r) => r.status === 'fulfilled').length;
      const failedCount = results.length - ok;
      // A started fix changes the summary/impact totals, the fix-train list AND every watch's issues — settle the
      // whole family so the fix-train view + Activity Center reflect the new trains without a manual refresh.
      qc.invalidateQueries({ queryKey: ['snyk-summary'] });
      qc.invalidateQueries({ queryKey: ['snyk-fixes'] });
      qc.invalidateQueries({ queryKey: ['activity'] });
      for (const w of fixableWatches) qc.invalidateQueries({ queryKey: ['snyk-issues', w.id] });
      if (ok > 0) toast.push('success', t('snyk.bulk.started', { count: ok }));
      if (failedCount > 0) toast.push('error', t('snyk.bulk.someFailed', { count: failedCount }));
      onClose();
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const footer = (
    <>
      <Button variant="secondary" onClick={onClose}>{t('common.close')}</Button>
      <Button disabled={!canStart} loading={start.isPending} onClick={() => start.mutate()}>
        {t('snyk.bulk.confirm', { count: selected.size })}
      </Button>
    </>
  );

  return (
    <Modal open={open} onClose={onClose} size="lg" title={t('snyk.bulk.title')} footer={footer}>
      {loading ? (
        <div className="flex items-center gap-2 py-6 text-sm text-muted">
          <Spinner /> {t('snyk.bulk.loading')}
        </div>
      ) : rows.length === 0 ? (
        <EmptyState icon={ShieldCheck}
          title={failed ? t('snyk.bulk.loadFailed') : t('snyk.bulk.emptyTitle')}
          body={failed ? undefined : t('snyk.bulk.emptyBody')} />
      ) : (
        <div className="space-y-4">
          <p className="text-sm text-muted">{t('snyk.bulk.intro')}</p>

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

          {/* One Jira project for the whole batch (each fix opens its own ticket there) + optional reviewers */}
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label={t('snyk.bulk.jiraProjectLabel')} hint={t('snyk.bulk.jiraProjectHint')}>
              <Input value={jiraProject} onChange={(e) => setJiraProject(e.target.value)} placeholder="CIAM" />
            </Field>
            <Field label={t('snyk.fix.reviewersLabel')} hint={t('snyk.fix.reviewersHint')}>
              <Input value={reviewers} onChange={(e) => setReviewers(e.target.value)} placeholder="alice, bob" />
            </Field>
          </div>
          <p className="rounded-lg bg-ink-50/60 px-3 py-2 text-xs text-muted">{t('snyk.bulk.note')}</p>
        </div>
      )}
    </Modal>
  );
}
