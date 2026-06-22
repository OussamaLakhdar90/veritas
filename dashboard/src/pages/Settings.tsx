import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, XCircle, AlertTriangle, Plug, KeyRound, Github, RefreshCw } from 'lucide-react';
import { api, type ConnectionsCfg, type EndpointCfg, type ConnectionTestResult, type CopilotLoginStart } from '../api';
import { Button, Card, CardBody, CardHeader, Field, Input, Select, PageHeader, Spinner } from '../components/ui';
import { DeviceFlowModal } from '../components/CopilotSignIn';
import { useToast } from '../components/Toast';

type ServiceKey = 'bitbucket' | 'jira' | 'xray' | 'confluence';
interface TokenField { key: string; label: string; optional?: boolean; role?: 'username' | 'secret' }
interface ServiceDef {
  key: ServiceKey; label: string; editions: string[];
  /** Auth types offered per edition (Cloud and Server/DC use different mechanisms). */
  authByEdition: Record<string, string[]>;
  /** Editions for which the Workspace field applies (Cloud only — Server/DC keys repos by project). */
  workspaceEditions?: string[];
  tokens: TokenField[];
  note?: Record<string, string>;   // optional per-edition hint shown on the card
}

// Auth types that require a username (Basic = user+password; App-password = user+token). Bearer/OAuth/Client-creds don't.
const NEEDS_USERNAME = new Set(['BASIC', 'APP_PASSWORD']);

const SERVICES: ServiceDef[] = [
  { key: 'bitbucket', label: 'Bitbucket', editions: ['CLOUD', 'SERVER_DC'],
    authByEdition: { CLOUD: ['APP_PASSWORD', 'OAUTH'], SERVER_DC: ['BEARER', 'BASIC'] },
    workspaceEditions: ['CLOUD'],
    tokens: [{ key: 'GIT_USERNAME', label: 'Username', role: 'username', optional: true },
             { key: 'GIT_TOKEN', label: 'HTTP access token', role: 'secret' }],
    note: { SERVER_DC: 'Server/DC: BEARER = an HTTP access token (PAT); BASIC = your BNC username + password. No workspace — the project key (app-id) is entered on the Validate screen.' } },
  { key: 'jira', label: 'Jira', editions: ['SERVER_DC', 'CLOUD'],
    authByEdition: { SERVER_DC: ['BEARER', 'BASIC'], CLOUD: ['BEARER', 'BASIC'] },
    tokens: [{ key: 'JIRA_USERNAME', label: 'Username', role: 'username', optional: true },
             { key: 'JIRA_API_TOKEN', label: 'HTTP access token', role: 'secret' }] },
  { key: 'xray', label: 'Xray', editions: ['SERVER_DC', 'CLOUD'],
    authByEdition: { SERVER_DC: ['BEARER', 'BASIC'], CLOUD: ['CLIENT_CREDENTIALS', 'BEARER'] },
    tokens: [{ key: 'JIRA_USERNAME', label: 'Username', role: 'username', optional: true },
             { key: 'XRAY_API_TOKEN', label: 'HTTP access token (may reuse the Jira token)', role: 'secret', optional: true }] },
  { key: 'confluence', label: 'Confluence', editions: ['CLOUD', 'SERVER_DC'],
    authByEdition: { CLOUD: ['BEARER', 'BASIC'], SERVER_DC: ['BEARER', 'BASIC'] },
    tokens: [{ key: 'JIRA_USERNAME', label: 'Username', role: 'username', optional: true },
             { key: 'CONFLUENCE_API_TOKEN', label: 'HTTP access token', role: 'secret' }] },
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

      <EngineCard />

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

/* ── LLM engine (mock vs live) ───────────────────────────────────────────── */
function EngineCard() {
  const qc = useQueryClient();
  const toast = useToast();
  const llm = useQuery({ queryKey: ['llm'], queryFn: api.llmSettings });
  const [mode, setMode] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: (m: string) => api.saveLlmSettings(m),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ['llm'] });
      toast.push(r.restartRequiredFields.length ? 'info' : 'success',
        r.restartRequiredFields.length ? 'Saved — restart Veritas to switch the engine.' : 'Saved.');
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const active = llm.data?.active ?? 'mock';
  const desired = mode ?? llm.data?.desired ?? active;
  const simulated = llm.data?.simulated ?? true;
  const pendingRestart = desired.toLowerCase() !== active.toLowerCase();

  return (
    <Card className="mb-6">
      <CardHeader title="LLM engine"
        subtitle="Which engine runs the skills. Mock returns simulated results; switch to Copilot for real analysis."
        action={<span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-semibold uppercase tracking-wide ${simulated ? 'bg-warning/10 text-warning ring-1 ring-warning/30' : 'bg-success/10 text-success ring-1 ring-success/30'}`}>
          {simulated ? 'Mock · simulated' : 'Copilot · live'}</span>} />
      <CardBody className="space-y-4">
        {simulated && (
          <div className="flex items-start gap-2 rounded-lg bg-warning/5 p-3 text-[13px] text-ink-700">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" />
            <span>You're in <strong>mock</strong> mode — skill results are simulated and the Copilot sign-in below is not used.
              Switch to Copilot and restart to run real analysis.</span>
          </div>
        )}
        <div className="flex items-end justify-between gap-4">
          <Field label="Engine" hint="Takes effect after a restart (the gateway is wired at startup).">
            <Select value={desired} onChange={(e) => setMode(e.target.value)}>
              <option value="mock">Mock — simulated (no tokens needed)</option>
              <option value="http">Copilot — HTTP device-flow (recommended)</option>
              <option value="copilot">Copilot — CLI binary</option>
            </Select>
          </Field>
          <Button size="sm" loading={save.isPending} disabled={desired.toLowerCase() === (llm.data?.desired ?? active).toLowerCase()}
            onClick={() => save.mutate(desired)}>Save</Button>
        </div>
        {pendingRestart && (
          <p className="text-[12px] text-warning">Pending: <strong>{desired}</strong> will be active after the next restart (currently running <strong>{active}</strong>).</p>
        )}
      </CardBody>
    </Card>
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

  const edition = cfg.edition ?? def.editions[0];
  const authTypes = def.authByEdition[edition] ?? Object.values(def.authByEdition)[0];
  const authType = authTypes.includes(cfg.authType ?? '') ? cfg.authType : authTypes[0];
  const showWorkspace = (def.workspaceEditions ?? []).includes(edition);
  const note = def.note?.[edition];

  return (
    <Card>
      <CardHeader title={def.label}
        action={<Button variant="ghost" size="sm" loading={testing} onClick={runTest}><Plug className="h-4 w-4" /> Test connection</Button>} />
      <CardBody className="space-y-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Base URL"><Input value={cfg.baseUrl ?? ''} placeholder="https://…" onChange={(e) => set('baseUrl', e.target.value)} /></Field>
          <Field label="Edition">
            <Select value={edition} onChange={(e) => set('edition', e.target.value)}>
              {def.editions.map((ed) => <option key={ed} value={ed}>{ed}</option>)}
            </Select>
          </Field>
          {showWorkspace && <Field label="Workspace"><Input value={cfg.workspace ?? ''} onChange={(e) => set('workspace', e.target.value)} /></Field>}
          <Field label="Auth type">
            <Select value={authType} onChange={(e) => set('authType', e.target.value)}>
              {authTypes.map((a) => <option key={a} value={a}>{a}</option>)}
            </Select>
          </Field>
        </div>
        {note && <p className="text-[12px] text-muted">{note}</p>}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {def.tokens
            // Username only matters for username+secret schemes (BASIC / APP_PASSWORD); hide it for token-only auth.
            .filter((t) => t.role !== 'username' || NEEDS_USERNAME.has(authType ?? ''))
            .map((t) => {
              const label = t.role === 'secret'
                ? (authType === 'BASIC' ? 'Password' : authType === 'BEARER' ? 'HTTP access token (PAT)' : t.label)
                : t.label;
              const isUsername = t.role === 'username';
              return (
                <Field key={t.key} label={label}
                  hint={isUsername
                    ? (secrets[t.key] ? '● set — leave blank to keep' : 'your BNC username')
                    : (secrets[t.key] ? '● configured — leave blank to keep' : (t.optional ? 'optional' : 'required'))}>
                  <Input type={isUsername ? 'text' : 'password'} autoComplete="off"
                    placeholder={secrets[t.key] ? (isUsername ? '(set)' : '••••••••') : ''}
                    value={tokens[t.key] ?? ''} onChange={(e) => setTokens((s) => ({ ...s, [t.key]: e.target.value }))} />
                </Field>
              );
            })}
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
