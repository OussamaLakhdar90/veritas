import { useEffect, useState } from 'react';
import { GitBranch, FileCode, Code2, GitCompare, Sparkles, FileText } from 'lucide-react';

/**
 * Single source of truth for the live scan stages, shared by the in-modal stepper
 * (RepoPicker) and the floating background-scan dock. Mirrors the backend
 * `ScanStages` constants; the user-facing wording lives in the `scan.*` i18n
 * families (label/short/detail per stage key) so it flips with the language.
 * `pct` drives the progress bars; `long` flags the AI step (by far the slowest)
 * so we can reassure the user it isn't stuck.
 */
export type ScanStep = {
  key: string;
  icon: typeof GitBranch;
  pct: number;
  long?: boolean;
};

export const SCAN_STEPS: ScanStep[] = [
  { key: 'CLONING', icon: GitBranch, pct: 12 },
  { key: 'RESOLVING_SPEC', icon: FileCode, pct: 26 },
  { key: 'EXTRACTING', icon: Code2, pct: 44 },
  { key: 'DIFFING', icon: GitCompare, pct: 60 },
  { key: 'RECONCILING', icon: Sparkles, pct: 82, long: true },
  { key: 'REPORTING', icon: FileText, pct: 95 },
];

/** Stage → ordinal, used to mark each step done / active / pending. DONE & FAILED both sit past the last step. */
export const STAGE_ORDER: Record<string, number> = {
  QUEUED: 0, CLONING: 1, RESOLVING_SPEC: 2, EXTRACTING: 3, DIFFING: 4, RECONCILING: 5, REPORTING: 6, DONE: 7, FAILED: 7,
};

/** A 0–100 progress estimate for the current stage (drives the gradient bars). */
export function stagePct(stage: string): number {
  if (stage === 'DONE') return 100;
  if (stage === 'QUEUED') return 4;
  const step = SCAN_STEPS.find((s) => s.key === stage);
  return step ? step.pct : 4;
}

/** "m:ss" elapsed formatting. */
export function formatElapsed(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/** Ticks every second while `active`, returning ms elapsed since `startMs` (0 if unknown). */
export function useElapsed(startMs: number | null, active: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active || startMs == null) return;
    setNow(Date.now());
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [active, startMs]);
  return startMs == null ? 0 : Math.max(0, now - startMs);
}

/** Like {@link useElapsed} but restarts from zero each time `stage` changes — the current step's own timer. */
export function useStageElapsed(stage: string, active: boolean): number {
  const [start, setStart] = useState(() => Date.now());
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const t = Date.now(); setStart(t); setNow(t); }, [stage]);
  useEffect(() => {
    if (!active) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [active, stage]);
  return Math.max(0, now - start);
}
