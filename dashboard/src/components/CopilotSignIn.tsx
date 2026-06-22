import { useEffect, useState } from 'react';
import { api, type CopilotLoginStart } from '../api';
import { Modal } from './Modal';
import { Button, Spinner } from './ui';

/**
 * The GitHub Copilot device-flow sign-in modal: shows the user code + verification link and polls the
 * sign-in session until AUTHORIZED / EXPIRED / ERROR. Shared by Settings and the global Copilot gate so
 * the exact same screen pops wherever sign-in is triggered.
 */
export function DeviceFlowModal({ flow, onDone }: { flow: CopilotLoginStart | null; onDone: (ok: boolean) => void }) {
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
