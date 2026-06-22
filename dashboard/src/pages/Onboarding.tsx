import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, Circle, AlertTriangle, Settings as SettingsIcon, ShieldCheck, Github } from 'lucide-react';
import { api } from '../api';
import { Button, Card, CardBody, CardHeader, PageHeader, Spinner } from '../components/ui';

/** First-run guided setup — a friendlier, step-by-step companion to the Settings page. */
export function Onboarding() {
  const preflight = useQuery({ queryKey: ['preflight'], queryFn: api.preflight });
  const copilot = useQuery({ queryKey: ['copilot'], queryFn: api.copilotStatus });

  const checks = preflight.data ?? [];
  const ready = checks.length > 0 && checks.every((c) => c.status === 'OK') && copilot.data?.authenticated;

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader title="Welcome to Veritas"
        subtitle="Three quick steps and you can validate a contract, manage tests and generate automation — all from here." />

      <Card className="mb-6">
        <CardHeader title="Get set up" subtitle="Everything is configured in the app — no terminal, no env vars." />
        <CardBody className="space-y-4">
          <Step n={1} done={!!copilot.data?.authenticated} loading={copilot.isLoading}
            icon={Github} title="Sign in to GitHub Copilot"
            body="Veritas uses Copilot as its reasoning engine. Sign in once on the Settings page."
            action={<Link to="/settings"><Button size="sm" variant="secondary">Open Settings</Button></Link>} />

          <Step n={2} done={checks.some((c) => c.name.toLowerCase().includes('bitbucket') && c.status === 'OK')}
            loading={preflight.isLoading} icon={SettingsIcon} title="Connect Bitbucket"
            body="Add your Bitbucket base URL + token so Veritas can discover and clone your repos."
            action={<Link to="/settings"><Button size="sm" variant="secondary">Configure</Button></Link>} />

          <Step n={3} done={checks.filter((c) => c.status !== 'OK').length === 0 && checks.length > 0}
            loading={preflight.isLoading} icon={SettingsIcon} title="Connect Jira / Xray / Confluence"
            body="Optional, but needed for defects, test management and coverage. Test each connection live in Settings."
            action={<Link to="/settings"><Button size="sm" variant="secondary">Configure</Button></Link>} />
        </CardBody>
      </Card>

      <Card>
        <CardBody className="flex items-center justify-between gap-4">
          <div className="flex items-start gap-3">
            {ready ? <CheckCircle2 className="mt-0.5 h-5 w-5 text-success" /> : <AlertTriangle className="mt-0.5 h-5 w-5 text-warning" />}
            <div>
              <p className="text-sm font-semibold text-ink-900">{ready ? "You're ready" : 'Almost there'}</p>
              <p className="mt-0.5 text-[13px] text-muted">
                {ready ? 'Run your first contract validation to see findings and an executive report.'
                  : 'Finish the steps above, then run your first validation.'}
              </p>
            </div>
          </div>
          <Link to="/repos"><Button><ShieldCheck className="h-4 w-4" /> Validate a contract</Button></Link>
        </CardBody>
      </Card>

      {(preflight.isLoading || copilot.isLoading) && <div className="mt-4 flex justify-center"><Spinner /></div>}
    </div>
  );
}

function Step({ n, done, loading, icon: Icon, title, body, action }: {
  n: number; done: boolean; loading: boolean; icon: React.ComponentType<{ className?: string }>;
  title: string; body: string; action: React.ReactNode;
}) {
  return (
    <div className="flex items-start gap-4 rounded-lg border border-border p-4">
      <div className="mt-0.5">
        {loading ? <Circle className="h-5 w-5 text-muted/40" />
          : done ? <CheckCircle2 className="h-5 w-5 text-success" /> : <Circle className="h-5 w-5 text-muted/50" />}
      </div>
      <div className="min-w-0 flex-1">
        <p className="flex items-center gap-2 text-sm font-semibold text-ink-900">
          <span className="text-muted">{n}.</span><Icon className="h-4 w-4 text-muted" /> {title}
        </p>
        <p className="mt-1 text-[13px] text-muted">{body}</p>
      </div>
      <div className="shrink-0">{action}</div>
    </div>
  );
}
