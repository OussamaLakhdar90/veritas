import { useMemo, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, XCircle, AlertTriangle, Plug, KeyRound, Github, RefreshCw } from 'lucide-react';
import { api, type ConnectionsCfg, type EndpointCfg, type ConnectionTestResult, type CopilotLoginStart } from '../api';
import { Button, Card, CardBody, CardHeader, ErrorState, Field, Input, Select, PageContainer, PageHeader, Skeleton } from '../components/ui';
import { DeviceFlowModal } from '../components/CopilotSignIn';
import { useToast } from '../components/Toast';

// Plain-language labels for the raw config enums shown in the dropdowns (value stays the enum; only the display changes).
// Maps hold i18n leaf keys; the visible label is resolved with `t` inside the component.
const EDITION_LABEL: Record<string, string> = {
  CLOUD: 'editionCloud',
  SERVER_DC: 'editionSelfHosted',
};
const AUTH_LABEL: Record<string, string> = {
  APP_PASSWORD: 'authAppPassword',
  BEARER: 'authBearer',
  BASIC: 'authBasic',
  OAUTH: 'authOauth',
  CLIENT_CREDENTIALS: 'authClientCredentials',
};

type ServiceKey = 'bitbucket' | 'jira' | 'xray' | 'confluence' | 'snyk';
interface TokenField { key: string; label: string; optional?: boolean; role?: 'username' | 'secret' }
interface ServiceDef {
  key: ServiceKey; label: string; editions: string[];
  /** Auth types offered per edition (Cloud and Server/DC use different mechanisms). */
  authByEdition: Record<string, string[]>;
  /** Editions for which the Workspace field applies (Cloud only — Server/DC keys repos by project). */
  workspaceEditions?: string[];
  tokens: TokenField[];
  note?: Record<string, string>;   // optional per-edition hint shown on the card
  /** SaaS-token services (Snyk): a base URL + one personal API token, no edition/workspace/auth choices. */
  simple?: boolean;
  /** i18n leaf key for an always-shown help line under the card title. */
  hint?: string;
}

// Auth types that require a username (Basic = user+password; App-password = user+token). Bearer/OAuth/Client-creds don't.
const NEEDS_USERNAME = new Set(['BASIC', 'APP_PASSWORD']);

// `label` (token field) and `note` hold i18n leaf keys; resolved with `t` inside ServiceCard.
// Service `label` stays a literal brand name (not translated).
const SERVICES: ServiceDef[] = [
  { key: 'bitbucket', label: 'Bitbucket', editions: ['CLOUD', 'SERVER_DC'],
    authByEdition: { CLOUD: ['APP_PASSWORD', 'OAUTH'], SERVER_DC: ['BEARER', 'BASIC'] },
    workspaceEditions: ['CLOUD'],
    tokens: [{ key: 'GIT_USERNAME', label: 'tokenUsername', role: 'username', optional: true },
             { key: 'GIT_TOKEN', label: 'tokenHttpAccessToken', role: 'secret' }],
    note: { SERVER_DC: 'noteBitbucketServerDc' } },
  { key: 'jira', label: 'Jira', editions: ['SERVER_DC', 'CLOUD'],
    authByEdition: { SERVER_DC: ['BEARER', 'BASIC'], CLOUD: ['BEARER', 'BASIC'] },
    tokens: [{ key: 'JIRA_USERNAME', label: 'tokenUsername', role: 'username', optional: true },
             { key: 'JIRA_API_TOKEN', label: 'tokenHttpAccessToken', role: 'secret' }] },
  { key: 'xray', label: 'Xray', editions: ['SERVER_DC', 'CLOUD'],
    authByEdition: { SERVER_DC: ['BEARER', 'BASIC'], CLOUD: ['CLIENT_CREDENTIALS', 'BEARER'] },
    tokens: [{ key: 'JIRA_USERNAME', label: 'tokenUsername', role: 'username', optional: true },
             { key: 'XRAY_API_TOKEN', label: 'tokenHttpAccessTokenXray', role: 'secret', optional: true }] },
  { key: 'confluence', label: 'Confluence', editions: ['CLOUD', 'SERVER_DC'],
    authByEdition: { CLOUD: ['BEARER', 'BASIC'], SERVER_DC: ['BEARER', 'BASIC'] },
    tokens: [{ key: 'JIRA_USERNAME', label: 'tokenUsername', role: 'username', optional: true },
             { key: 'CONFLUENCE_API_TOKEN', label: 'tokenHttpAccessToken', role: 'secret' }] },
  // Snyk: a SaaS dependency scanner reached with a personal API token — no edition/workspace/auth choice.
  { key: 'snyk', label: 'Snyk', editions: ['CLOUD'], authByEdition: { CLOUD: ['BEARER'] }, simple: true,
    hint: 'snykHint',
    tokens: [{ key: 'SNYK_API_TOKEN', label: 'tokenSnykApiToken', role: 'secret' }] },
];

export function Settings() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const toast = useToast();
  const conns = useQuery({ queryKey: ['connections'], queryFn: api.connections });
  const secrets = useQuery({ queryKey: ['secrets'], queryFn: api.secretsStatus });
  const preflight = useQuery({ queryKey: ['preflight'], queryFn: api.preflight });

  return (
    <PageContainer variant="wide">
      <PageHeader title={t('settings.pageTitle')}
        subtitle={t('settings.pageSubtitle')} />

      {/* Readiness checklist */}
      <Card className="mb-6">
        <CardHeader title={t('settings.checklistTitle')} subtitle={t('settings.checklistSubtitle')}
          action={<Button variant="ghost" size="sm" onClick={() => preflight.refetch()}><RefreshCw className="h-4 w-4" /> {t('settings.refresh')}</Button>} />
        <CardBody className="space-y-2">
          {preflight.isLoading ? (
            <div role="status" aria-live="polite" className="space-y-2">
              <span className="sr-only">{t('settings.checklistLoading')}</span>
              {Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-8" />)}
            </div>
          ) : preflight.isError ? (
            // A failed readiness check must be visible — an empty list would read as "all clear".
            <ErrorState message={t('settings.checklistError')} detail={(preflight.error as Error).message} />
          ) : (preflight.data ?? []).map((c) => (
            <div key={c.name} className="flex items-start gap-3">
              {c.status === 'OK' ? <CheckCircle2 className="mt-0.5 h-4 w-4 text-success" />
                : c.status === 'WARN' ? <AlertTriangle className="mt-0.5 h-4 w-4 text-warning" />
                  : <XCircle className="mt-0.5 h-4 w-4 text-danger" />}
              <div className="min-w-0">
                <p className="text-sm font-medium text-ink-900">{c.name}</p>
                <p className="text-sm text-muted">{c.detail}{c.remediation ? ` — ${c.remediation}` : ''}</p>
              </div>
            </div>
          ))}
        </CardBody>
      </Card>

      <EngineCard />

      <CopilotCard />

      {conns.isLoading || secrets.isLoading ? (
        <Card><CardBody>
          <div role="status" aria-live="polite" className="space-y-3">
            <span className="sr-only">{t('settings.connectionsLoading')}</span>
            <Skeleton className="h-6 w-1/4" />
            {Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-9" />)}
          </div>
        </CardBody></Card>
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
                  ? t('settings.toastSavedRestart', { fields: restart.join(', ') }) : t('settings.toastSaved'));
              }}
              onError={(m) => toast.push('error', m)} />
          ))}
        </div>
      ) : <ErrorState message={t('settings.couldNotLoad')}
            detail={(conns.error as Error)?.message ?? (secrets.error as Error)?.message} />}
    </PageContainer>
  );
}

/* ── LLM engine (mock vs live) ───────────────────────────────────────────── */
function EngineCard() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const toast = useToast();
  const llm = useQuery({ queryKey: ['llm'], queryFn: api.llmSettings });
  const [mode, setMode] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: (m: string) => api.saveLlmSettings(m),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ['llm'] });
      toast.push(r.restartRequiredFields.length ? 'info' : 'success',
        r.restartRequiredFields.length ? t('settings.toastEngineRestart') : t('settings.toastSaved'));
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const active = llm.data?.active ?? 'mock';
  const desired = mode ?? llm.data?.desired ?? active;
  const simulated = llm.data?.simulated ?? true;
  const pendingRestart = desired.toLowerCase() !== active.toLowerCase();

  return (
    <Card className="mb-6">
      <CardHeader title={t('settings.engineTitle')}
        subtitle={t('settings.engineSubtitle')}
        action={<span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-2xs font-semibold uppercase tracking-wide ${simulated ? 'bg-warning/10 text-warning ring-1 ring-warning/30' : 'bg-success/10 text-success ring-1 ring-success/30'}`}>
          {simulated ? t('settings.engineBadgeMock') : t('settings.engineBadgeLive')}</span>} />
      <CardBody className="space-y-4">
        {simulated && (
          <div className="flex items-start gap-2 rounded-lg bg-warning/5 p-3 text-sm text-ink-700">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" />
            <span><Trans i18nKey="settings.engineMockWarning" components={{ b: <strong /> }}
              defaults="You're in <b>mock</b> mode — skill results are simulated and the Copilot sign-in below is not used. Switch to Copilot and restart to run real analysis." /></span>
          </div>
        )}
        <div className="flex items-end justify-between gap-4">
          <Field label={t('settings.engineFieldLabel')} hint={t('settings.engineFieldHint')}>
            <Select value={desired} onChange={(e) => setMode(e.target.value)}>
              <option value="mock">{t('settings.engineOptionMock')}</option>
              <option value="http">{t('settings.engineOptionHttp')}</option>
              <option value="copilot">{t('settings.engineOptionCopilot')}</option>
            </Select>
          </Field>
          <Button size="sm" loading={save.isPending} disabled={desired.toLowerCase() === (llm.data?.desired ?? active).toLowerCase()}
            onClick={() => save.mutate(desired)}>{t('settings.save')}</Button>
        </div>
        {pendingRestart && (
          <p className="text-xs text-warning">
            <Trans i18nKey="settings.enginePendingRestart" values={{ desired, active }}
              components={{ d: <strong />, a: <strong /> }}
              defaults="Pending: <d>{{desired}}</d> will be active after the next restart (currently running <a>{{active}}</a>." />
          </p>
        )}
      </CardBody>
    </Card>
  );
}

/* ── Copilot sign-in ─────────────────────────────────────────────────────── */
function CopilotCard() {
  const { t } = useTranslation();
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
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['copilot'] }); toast.push('info', t('settings.toastSignedOut')); },
  });

  const authed = status.data?.authenticated;
  return (
    <Card className="mb-6">
      <CardHeader title={<span className="inline-flex items-center gap-2"><Github className="h-4 w-4" /> {t('settings.copilotTitle')}</span>}
        subtitle={t('settings.copilotSubtitle')} />
      <CardBody className="flex items-center justify-between">
        <span className={`inline-flex items-center gap-1.5 text-sm font-medium ${authed ? 'text-success' : 'text-muted'}`}>
          {authed ? <CheckCircle2 className="h-4 w-4" /> : <Plug className="h-4 w-4" />}
          {status.isLoading ? t('settings.copilotChecking') : authed ? t('settings.copilotSignedIn') : t('settings.copilotNotSignedIn')}
        </span>
        {authed
          ? <Button variant="secondary" size="sm" loading={signOut.isPending} onClick={() => signOut.mutate()}>{t('settings.signOut')}</Button>
          : <Button size="sm" loading={start.isPending} onClick={() => start.mutate()}>{t('settings.signInGithub')}</Button>}
      </CardBody>
      <DeviceFlowModal flow={flow} onDone={(ok) => {
        setFlow(null);
        if (ok) { qc.invalidateQueries({ queryKey: ['copilot'] }); toast.push('success', t('settings.toastSignedIn')); }
      }} />
    </Card>
  );
}

/* ── Per-service connection card ─────────────────────────────────────────── */
function ServiceCard({ def, initial, secrets, onSaved, onError }: {
  def: ServiceDef; initial: EndpointCfg; secrets: Record<string, boolean>;
  onSaved: (restart: string[]) => void; onError: (m: string) => void;
}) {
  const { t } = useTranslation();
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
      <CardHeader title={def.label} subtitle={def.hint ? t('settings.' + def.hint) : undefined}
        action={<Button variant="ghost" size="sm" loading={testing} onClick={runTest}><Plug className="h-4 w-4" /> {t('settings.testConnection')}</Button>} />
      <CardBody className="space-y-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={t('settings.baseUrl')}><Input value={cfg.baseUrl ?? ''} placeholder={t('settings.baseUrlPlaceholder')} onChange={(e) => set('baseUrl', e.target.value)} /></Field>
          {!def.simple && <Field label={t('settings.edition')} hint={t('settings.editionHint')}>
            <Select value={edition} onChange={(e) => set('edition', e.target.value)}>
              {def.editions.map((ed) => <option key={ed} value={ed}>{EDITION_LABEL[ed] ? t('settings.' + EDITION_LABEL[ed]) : ed}</option>)}
            </Select>
          </Field>}
          {showWorkspace && !def.simple && <Field label={t('settings.workspace')} hint={t('settings.workspaceHint')}><Input value={cfg.workspace ?? ''} onChange={(e) => set('workspace', e.target.value)} /></Field>}
          {!def.simple && <Field label={t('settings.signInMethod')}>
            <Select value={authType} onChange={(e) => set('authType', e.target.value)}>
              {authTypes.map((a) => <option key={a} value={a}>{AUTH_LABEL[a] ? t('settings.' + AUTH_LABEL[a]) : a}</option>)}
            </Select>
          </Field>}
        </div>
        {note && !def.simple && <p className="text-xs text-muted">{t('settings.' + note)}</p>}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {def.tokens
            // Username only matters for username+secret schemes (BASIC / APP_PASSWORD); hide it for token-only auth.
            .filter((t) => t.role !== 'username' || NEEDS_USERNAME.has(authType ?? ''))
            .map((tok) => {
              const label = tok.role === 'secret' && !def.simple
                ? (authType === 'BASIC' ? t('settings.tokenPassword') : authType === 'BEARER' ? t('settings.tokenHttpAccessTokenPat') : t('settings.' + tok.label))
                : t('settings.' + tok.label);
              const isUsername = tok.role === 'username';
              return (
                <Field key={tok.key} label={label}
                  hint={isUsername
                    ? (secrets[tok.key] ? t('settings.hintAlreadySetUsername') : t('settings.hintYourUsername'))
                    : (secrets[tok.key] ? t('settings.hintAlreadyConfigured') : (tok.optional ? t('settings.hintOptional') : t('settings.hintRequired')))}>
                  <Input type={isUsername ? 'text' : 'password'} autoComplete="off"
                    placeholder={secrets[tok.key] ? (isUsername ? t('settings.placeholderSet') : '••••••••') : ''}
                    value={tokens[tok.key] ?? ''} onChange={(e) => setTokens((s) => ({ ...s, [tok.key]: e.target.value }))} />
                </Field>
              );
            })}
        </div>
        <div className="flex items-center justify-between">
          {test ? <span className={`inline-flex items-center gap-1.5 text-sm ${testTone}`}>
            <KeyRound className="h-3.5 w-3.5" /> {test.message}</span> : <span />}
          <Button size="sm" loading={saving} onClick={save}>{t('settings.save')}</Button>
        </div>
      </CardBody>
    </Card>
  );
}
