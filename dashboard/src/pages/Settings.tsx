import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, XCircle, AlertTriangle, Plug, KeyRound, Github, RefreshCw } from 'lucide-react';
import { api, type ConnectionsCfg, type EndpointCfg, type ConnectionTestResult, type CopilotLoginStart } from '../api';
import { Button, Card, CardBody, CardHeader, Field, Input, Select, PageHeader, Spinner } from '../components/ui';
import { Modal } from '../components/Modal';
import { useToast } from '../components/Toast';

type ServiceKey = 'bitbucket' | 'jira' | 'xray' | 'confluence';
interface TokenField { key: string; label: string; optional?: boolean }
interface ServiceDef {
  key: ServiceKey; label: string; editions: string[]; authTypes: string[]; showWorkspace?: boolean; tokens: TokenField[];
}

const SERVICES: ServiceDef[] = [
  { key: 'bitbucket', label: 'Bitbucket', editions: ['CLOUD', 'SERVER_DC'], authTypes: ['APP_PASSWORD', 'OAUTH'], showWorkspace: true,
    tokens: [{ key: 'GIT_USERNAME', label: 'Username (app-password auth)', optional: true }, { key: 'GIT_TOKEN', label: 'Token / app password' }] },
  { key: 'jira', label: 'Jira', editions: ['SERVER_DC', 'CLOUD'], authTypes: ['BEARER', 'BASIC'],
    tokens: [{ key: 'JIRA_USERNAME', label: 'Username (Basic auth)', optional: true }, { key: 'JIRA_API_TOKEN', label: 'API token / PAT' }] },
  { key: 'xray', label: 'Xray', editions: ['SERVER_DC', 'CLOUD'], authTypes: ['BEARER', 'CLIENT_CREDENTIALS'],
    tokens: [{ key: 'XRAY_API_TOKEN', label: 'Token (Server/DC may reuse the Jira token)', optional: true }] },
  { key: 'confluence', label: 'Confluence', editions: ['CLOUD', 'SERVER_DC'], authTypes: ['BEARER', 'BASIC'],
    tokens: [{ key: 'CONFLUENCE_API_TOKEN', label: 'API token' }] },
];

export function Settings() {
  const qc = useQueryClient();
  const toast = useToast();
  const conns = useQuery({ queryKey: ['connections'], queryFn: api.connections });
  const secrets = useQuery({ queryKey: ['secrets'], queryFn: api.secretsStatus });
  const preflight = useQuery({ queryKey: ['preflight'], queryFn: api.preflight });

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader title="Settings"
        subtitle="Connect your tools and sign in to Copilot — everything Veritas needs, no terminal required." />

      {/* Readiness checklist */}
      <Card className="mb-6">
        <CardHeader title="Setup checklist" subtitle="What's configured and what still needs attention."
          action={<Button variant="ghost" size="sm" onClick={() => preflight.refetch()}><RefreshCw className="h-4 w-4" /> Refresh</Button>} />
        <CardBody className="space-y-2">
          {preflight.isLoading ? <Spinner /> : (preflight.data ?? []).map((c) => (
            <div key={c.name} className="flex items-start gap-3">
              {c.status === 'OK' ? <CheckCircle2 className="mt-0.5 h-4 w-4 text-success" />
                : c.status === 'WARN' ? <AlertTriangle className="mt-0.5 h-4 w-4 text-warning" />
                  : <XCircle className="mt-0.5 h-4 w-4 text-danger" />}
              <div className="min-w-0">
                <p className="text-sm font-medium text-ink-900">{c.name}</p>
                <p className="text-[13px] text-muted">{c.detail}{c.remediation ? ` — ${c.remediation}` : ''}</p>
              </div>
            </div>
          ))}
        </CardBody>
      </Card>

      <CopilotCard />

      {conns.isLoading || secrets.isLoading ? (
        <Card><CardBody><Spinner /></CardBody></Card>
      ) : conns.data && secrets.data ? (
        <div className="space-y-6">
          {SERVICES.map((def) => (
            <ServiceCard key={def.key} def={def}
              initial={conns.data![def.key]} secrets={secrets.data!}
              onSaved={(restart) => {
                qc.invalidateQueries({ queryKey: ['connections'] });
                qc.invalidateQueries({ queryKey: ['secrets'] });
                qc.invalidateQueries({ queryKey: ['preflight'] });
                toast.push('success', restart.length
                  ? `Saved. Restart Veritas to apply: ${restart.join(', ')}.` : 'Saved.');
              }}
              onError={(m) => toast.push('error', m)} />
          ))}
        </div>
      ) : <p className="text-sm text-danger">Could not load settings.</p>}
    </div>
  );
}

/* ── Copilot sign-in ─────────────────────────────────────────────────────── */
function CopilotCard() {
  const qc = useQueryClient();
  const toast = useToast();
  const status = useQuery({ queryKey: ['copilot'], queryFn: api.copilotStatus });
  const [flow, setFlow] = useState<CopilotLoginStart | null>(null);

  const start = useMutation({
    mutationFn: api.copilotLoginStart,
    onSuccess: (s) => setFlow(s),
    onError: (e: Error) => toast.push('error', e.message),
  });
  const signOut = useMutation({
    mutationFn: api.copilotSignout,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['copilot'] }); toast.push('info', 'Signed out of Copilot.'); },
  });

  const authed = status.data?.authenticated;
  return (
    <Card className="mb-6">
      <CardHeader title={<span className="inline-flex items-center gap-2"><Github className="h-4 w-4" /> GitHub Copilot</span>}
        subtitle="The LLM behind Veritas. Sign in once with GitHub — the token is stored and refreshed automatically." />
      <CardBody className="flex items-center justify-between">
        <span className={`inline-flex items-center gap-1.5 text-sm font-medium ${authed ? 'text-success' : 'text-muted'}`}>
          {authed ? <CheckCircle2 className="h-4 w-4" /> : <Plug className="h-4 w-4" />}
          {status.isLoading ? 'Checking…' : authed ? 'Signed in' : 'Not signed in'}
        </span>
        {authed
          ? <Button variant="secondary" size="sm" loading={signOut.isPending} onClick={() => signOut.mutate()}>Sign out</Button>
          : <Button size="sm" loading={start.isPending} onClick={() => start.mutate()}>Sign in with GitHub</Button>}
      </CardBody>
      <DeviceFlowModal flow={flow} onDone={(ok) => {
        setFlow(null);
        if (ok) { qc.invalidateQueries({ queryKey: ['copilot'] }); toast.push('success', 'Signed in to Copilot.'); }
      }} />
    </Card>
  );
}

function DeviceFlowModal({ flow, onDone }: { flow: CopilotLoginStart | null; onDone: (ok: boolean) => void }) {
  const [state, setState] = useState('PENDING');
  useEffect(() => {
    if (!flow) return;
    setState('PENDING');
    const timer = setInterval(async () => {
      try {
        const s = await api.copilotLoginStatus(flow.id);
        setState(s.state);
        if (s.state === 'AUTHORIZED') { clearInterval(timer); onDone(true); }
        else if (s.state === 'EXPIRED' || s.state === 'ERROR') { clearInterval(timer); }
      } catch { /* keep polling */ }
    }, 3000);
    return () => clearInterval(timer);
  }, [flow, onDone]);

  if (!flow) return null;
  return (
    <Modal open title="Sign in to GitHub Copilot" onClose={() => onDone(false)}
      footer={<Button variant="secondary" onClick={() => onDone(false)}>Close</Button>}>
      <ol className="space-y-3 text-sm text-ink-900">
        <li>1. Open <a className="font-medium text-gold underline" href={flow.verificationUri} target="_blank" rel="noreferrer">{flow.verificationUri}</a></li>
        <li>2. Enter this code:
          <div className="mt-2 rounded-lg bg-ink-50 px-4 py-3 text-center font-mono text-2xl font-semibold tracking-[0.3em] text-ink-900">{flow.userCode}</div>
        </li>
        <li className="flex items-center gap-2 text-muted">
          {state === 'PENDING' && <><Spinner /> Waiting for you to authorize…</>}
          {state === 'AUTHORIZED' && <span className="text-success">✓ Connected!</span>}
          {state === 'EXPIRED' && <span className="text-warning">The code expired — close and try again.</span>}
          {state === 'ERROR' && <span className="text-danger">Sign-in failed — close and try again.</span>}
        </li>
      </ol>
    </Modal>
  );
}

/* ── Per-service connection card ─────────────────────────────────────────── */
function ServiceCard({ def, initial, secrets, onSaved, onError }: {
  def: ServiceDef; initial: EndpointCfg; secrets: Record<string, boolean>;
  onSaved: (restart: string[]) => void; onError: (m: string) => void;
}) {
  const [cfg, setCfg] = useState<EndpointCfg>(initial ?? {});
  const [tokens, setTokens] = useState<Record<string, string>>({});
  const [test, setTest] = useState<ConnectionTestResult | null>(null);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);

  const save = async () => {
    setSaving(true);
    try {
      const full = await api.connections();
      const next: ConnectionsCfg = { ...full, [def.key]: { ...full[def.key], ...cfg } };
      const res = await api.saveConnections(next);
      for (const t of def.tokens) {
        const v = tokens[t.key];
        if (v && v.trim()) await api.setSecret(t.key, v.trim());
      }
      setTokens({});
      onSaved(res.restartRequiredFields ?? []);
    } catch (e) { onError((e as Error).message); } finally { setSaving(false); }
  };
  const runTest = async () => {
    setTesting(true); setTest(null);
    try { setTest(await api.testConnection(def.key)); }
    catch (e) { onError((e as Error).message); } finally { setTesting(false); }
  };

  const set = (k: keyof EndpointCfg, val: string) => setCfg((c) => ({ ...c, [k]: val }));
  const testTone = useMemo(() => !test ? '' : test.authenticated ? 'text-success' : test.reachable ? 'text-warning' : 'text-danger', [test]);

  return (
    <Card>
      <CardHeader title={def.label}
        action={<Button variant="ghost" size="sm" loading={testing} onClick={runTest}><Plug className="h-4 w-4" /> Test connection</Button>} />
      <CardBody className="space-y-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Base URL"><Input value={cfg.baseUrl ?? ''} placeholder="https://…" onChange={(e) => set('baseUrl', e.target.value)} /></Field>
          <Field label="Edition">
            <Select value={cfg.edition ?? def.editions[0]} onChange={(e) => set('edition', e.target.value)}>
              {def.editions.map((ed) => <option key={ed} value={ed}>{ed}</option>)}
            </Select>
          </Field>
          {def.showWorkspace && <Field label="Workspace"><Input value={cfg.workspace ?? ''} onChange={(e) => set('workspace', e.target.value)} /></Field>}
          <Field label="Auth type">
            <Select value={cfg.authType ?? def.authTypes[0]} onChange={(e) => set('authType', e.target.value)}>
              {def.authTypes.map((a) => <option key={a} value={a}>{a}</option>)}
            </Select>
          </Field>
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {def.tokens.map((t) => (
            <Field key={t.key} label={t.label}
              hint={secrets[t.key] ? '● configured — leave blank to keep' : (t.optional ? 'optional' : 'not set')}>
              <Input type="password" autoComplete="off" placeholder={secrets[t.key] ? '••••••••' : ''}
                value={tokens[t.key] ?? ''} onChange={(e) => setTokens((s) => ({ ...s, [t.key]: e.target.value }))} />
            </Field>
          ))}
        </div>
        <div className="flex items-center justify-between">
          {test ? <span className={`inline-flex items-center gap-1.5 text-[13px] ${testTone}`}>
            <KeyRound className="h-3.5 w-3.5" /> {test.message}</span> : <span />}
          <Button size="sm" loading={saving} onClick={save}>Save</Button>
        </div>
      </CardBody>
    </Card>
  );
}
