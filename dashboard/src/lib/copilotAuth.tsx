import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Github, X } from 'lucide-react';
import { api, onCopilotAuthRequired, type CopilotLoginStart } from '../api';
import { DeviceFlowModal } from '../components/CopilotSignIn';

interface CopilotAuthState {
  /** The active engine needs Copilot (http / copilot CLI) — Mock mode never needs it. */
  needsCopilot: boolean;
  /** Copilot is usable right now (token present and a session token can be obtained). */
  connected: boolean;
  loading: boolean;
  /** Start (or re-focus) the device-flow sign-in. */
  signIn: () => void;
}

const Ctx = createContext<CopilotAuthState>({
  needsCopilot: false, connected: false, loading: true, signIn: () => {},
});

/** Read the global Copilot connection state (gate AI-only actions on `needsCopilot && !connected`). */
export function useCopilotAuth() {
  return useContext(Ctx);
}

/**
 * Provides Copilot connection state app-wide and brings sign-in to the user instead of burying it in
 * Settings: a top banner when the active engine needs Copilot but it isn't connected, and an automatic
 * sign-in prompt whenever any API call fails with `copilot-auth-required`. Soft-gate — deterministic
 * contract checks and viewing keep working; only the AI features are gated.
 */
export function CopilotAuthProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();
  const llm = useQuery({ queryKey: ['llm'], queryFn: api.llmSettings });
  const status = useQuery({ queryKey: ['copilot'], queryFn: api.copilotStatus });

  const [flow, setFlow] = useState<CopilotLoginStart | null>(null);
  const [dismissed, setDismissed] = useState(false);
  const [starting, setStarting] = useState(false);
  const busyRef = useRef(false);   // guard against double-starting (banner click + reactive trigger)

  const active = (llm.data?.active ?? 'mock').toLowerCase();
  const needsCopilot = active === 'http' || active === 'copilot';
  const connected = !!(status.data?.connected ?? status.data?.authenticated);
  const loading = llm.isLoading || status.isLoading;

  const signIn = useCallback(async () => {
    if (busyRef.current) return;   // a flow is already starting or on screen
    busyRef.current = true;
    setStarting(true);
    try {
      setFlow(await api.copilotLoginStart());
    } catch {
      busyRef.current = false;   // start failed — allow a retry
    } finally {
      setStarting(false);
    }
  }, []);

  // Pop sign-in automatically when an AI action is blocked server-side (code: copilot-auth-required).
  useEffect(() => {
    onCopilotAuthRequired(() => { setDismissed(false); signIn(); });
    return () => onCopilotAuthRequired(null);
  }, [signIn]);

  const closeFlow = (ok: boolean) => {
    setFlow(null);
    busyRef.current = false;
    if (ok) {
      qc.invalidateQueries({ queryKey: ['copilot'] });
      qc.invalidateQueries({ queryKey: ['preflight'] });
    }
  };

  const showBanner = needsCopilot && !connected && !loading && !dismissed;

  return (
    <Ctx.Provider value={{ needsCopilot, connected, loading, signIn }}>
      {showBanner && (
        <div className="flex items-center justify-between gap-3 border-b border-warning/30 bg-warning/10 px-6 py-2.5 text-sm">
          <span className="flex items-center gap-2 text-ink-900">
            <Github className="h-4 w-4 shrink-0" />
            <span><strong>Connect GitHub Copilot</strong> to enable AI review and test generation.
              Deterministic contract checks still work without it.</span>
          </span>
          <span className="flex shrink-0 items-center gap-2">
            <button onClick={signIn} disabled={starting}
              className="rounded-md bg-brand px-3 py-1 font-medium text-white hover:bg-brand-700 disabled:opacity-60">
              {starting ? 'Starting…' : 'Sign in'}
            </button>
            <button onClick={() => setDismissed(true)} aria-label="Dismiss"
              className="grid h-7 w-7 place-items-center rounded-md text-muted hover:bg-ink-100 hover:text-ink-900">
              <X className="h-4 w-4" />
            </button>
          </span>
        </div>
      )}
      {children}
      <DeviceFlowModal flow={flow} onDone={closeFlow} />
    </Ctx.Provider>
  );
}

/**
 * Inline gate for an AI-only action. When Copilot is required but not connected, returns
 * `{ blocked: true }` plus a small "Connect Copilot" prompt to render near the disabled control.
 */
export function useCopilotGate() {
  const { needsCopilot, connected, loading, signIn } = useCopilotAuth();
  const blocked = needsCopilot && !connected && !loading;
  const notice = blocked ? (
    <button type="button" onClick={signIn}
      className="inline-flex items-center gap-1.5 text-xs font-medium text-gold hover:underline">
      <Github className="h-3.5 w-3.5" /> Connect GitHub Copilot to use this
    </button>
  ) : null;
  return { blocked, notice };
}
