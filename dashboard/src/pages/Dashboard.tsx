import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ShieldCheck, AlertTriangle, Activity, FileText, ArrowRight, Settings as SettingsIcon } from 'lucide-react';
import { api, Scan } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, KpiTile, PageHeader, Skeleton } from '../components/ui';
import { TONE } from '../theme/tokens';

/** Map a scan/run status to a status-pill tone. */
function statusTone(status?: string): string {
  const s = (status || '').toUpperCase();
  if (['COMPLETED', 'DONE', 'SUCCESS', 'PASSED'].includes(s)) return TONE.ok;
  if (['FAILED', 'ERROR'].includes(s)) return TONE.danger;
  if (['RUNNING', 'PENDING', 'IN_PROGRESS', 'QUEUED'].includes(s)) return TONE.info;
  return TONE.muted;
}

export function Dashboard() {
  const scansQ = useQuery({ queryKey: ['scans'], queryFn: api.scans });
  const preflightQ = useQuery({ queryKey: ['preflight'], queryFn: api.preflight });
  const costQ = useQuery({ queryKey: ['costs'], queryFn: api.costSummary });
  const defectsQ = useQuery({ queryKey: ['defects'], queryFn: api.defects });

  const scans = scansQ.data ?? [];
  const missing = (preflightQ.data ?? []).filter((c) => c.status === 'MISSING');

  const totals = useMemo(() => {
    const services = new Set(scans.map((s) => s.serviceName)).size;
    const findings = scans.reduce((n, s) => n + (s.totalFindings ?? 0), 0);
    const openDefects = (defectsQ.data ?? []).filter(
      (d) => (d.jiraStatusCategory ?? '').toLowerCase() !== 'done').length;
    const spend = costQ.data?.totalEstCostUsd ?? scans.reduce((n, s) => n + (s.totalEstCostUsd ?? 0), 0);
    return { services, findings, openDefects, spend };
  }, [scans, defectsQ.data, costQ.data]);

  const recent: Scan[] = [...scans]
    .sort((a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? ''))
    .slice(0, 8);

  return (
    <div>
      <PageHeader
        title="Overview"
        subtitle="Contract quality, test coverage and spend across your services."
        actions={
          <Link to="/repos">
            <Button><ShieldCheck className="h-4 w-4" /> Run validation</Button>
          </Link>
        }
      />

      {/* Setup nudge — only when something is unconfigured */}
      {missing.length > 0 && (
        <Card className="mb-6 border-l-4 border-l-warning">
          <CardBody className="flex items-start justify-between gap-4">
            <div className="flex items-start gap-3">
              <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-warning" />
              <div>
                <p className="text-sm font-semibold text-ink-900">
                  Finish setup ({missing.length} {missing.length === 1 ? 'item' : 'items'})
                </p>
                <p className="mt-0.5 text-[13px] text-muted">
                  {missing.slice(0, 3).map((c) => c.name).join(', ')}
                  {missing.length > 3 ? ` and ${missing.length - 3} more` : ''} need attention before Veritas can run.
                </p>
              </div>
            </div>
            <Link to="/settings">
              <Button variant="secondary" size="sm"><SettingsIcon className="h-4 w-4" /> Open Settings</Button>
            </Link>
          </CardBody>
        </Card>
      )}

      {/* KPI row */}
      <div className="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
        {scansQ.isLoading ? (
          Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-28" />)
        ) : (
          <>
            <KpiTile label="Services scanned" value={totals.services} tone="brand" sub={`${scans.length} scan${scans.length === 1 ? '' : 's'} total`} />
            <KpiTile label="Findings" value={totals.findings} tone={totals.findings > 0 ? 'warning' : 'success'} sub="across all scans" />
            <KpiTile label="Open defects" value={totals.openDefects} tone={totals.openDefects > 0 ? 'danger' : 'success'} sub="not yet resolved in Jira" />
            <KpiTile label="Est. LLM spend" value={`$${totals.spend.toFixed(2)}`} sub={costQ.data ? `${costQ.data.actions} actions` : 'this environment'} />
          </>
        )}
      </div>

      {/* Recent activity */}
      <Card>
        <CardHeader title="Recent scans" subtitle="Latest contract-validation runs."
          action={<Link to="/repos" className="text-[13px] font-medium text-gold hover:underline">New scan</Link>} />
        <CardBody className="p-0">
          {scansQ.isLoading ? (
            <div className="space-y-2 p-5">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-10" />)}</div>
          ) : recent.length === 0 ? (
            <div className="p-5">
              <EmptyState icon={Activity} title="No scans yet"
                body="Validate an API contract against its codebase to see findings and an executive report here."
                action={<Link to="/repos"><Button><ShieldCheck className="h-4 w-4" /> Run your first validation</Button></Link>} />
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-[12px] uppercase tracking-wide text-muted">
                    <th className="px-5 py-3 font-medium">Service</th>
                    <th className="px-5 py-3 font-medium">Status</th>
                    <th className="px-5 py-3 font-medium text-right">Findings</th>
                    <th className="px-5 py-3 font-medium text-right">Est. cost</th>
                    <th className="px-5 py-3 font-medium">Started</th>
                    <th className="px-5 py-3" />
                  </tr>
                </thead>
                <tbody>
                  {recent.map((s) => (
                    <tr key={s.id} className="border-b border-border/60 last:border-0 hover:bg-ink-50/60">
                      <td className="px-5 py-3 font-medium text-ink-900">{s.serviceName}</td>
                      <td className="px-5 py-3"><Badge className={statusTone(s.status)}>{s.status}</Badge></td>
                      <td className="px-5 py-3 text-right tabular-nums text-ink-900">{s.totalFindings}</td>
                      <td className="px-5 py-3 text-right tabular-nums text-muted">${(s.totalEstCostUsd ?? 0).toFixed(4)}</td>
                      <td className="px-5 py-3 text-muted">{s.startedAt}</td>
                      <td className="px-5 py-3 text-right whitespace-nowrap">
                        <Link to={`/findings/${s.id}`} className="inline-flex items-center gap-1 text-[13px] font-medium text-gold hover:underline">
                          View <ArrowRight className="h-3.5 w-3.5" />
                        </Link>
                        <a href={api.reportUrl(s.id)} target="_blank" rel="noreferrer"
                          className="ml-3 inline-flex items-center gap-1 text-[13px] font-medium text-muted hover:text-ink-900">
                          <FileText className="h-3.5 w-3.5" /> Report
                        </a>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
