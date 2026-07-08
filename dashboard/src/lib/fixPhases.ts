import { GitBranch, ShieldCheck, Hammer, GitPullRequest } from 'lucide-react';

/**
 * The four machine-driven lifecycle phases of a Snyk fix train, in cascade order — the contract-validation-style
 * stepper the fix wizard renders so the user always sees *which operation* is running (and which one failed).
 * Mirrors the backend `SnykFixStatus` machine-driven states; the wording lives in the `snyk.fix.phase.*` i18n
 * family (label + detail per key) so it flips with the language. `pct` drives the progress bar; `long` flags the
 * two slow phases (the AI check + the local reactor build) so we can reassure the user they aren't stuck.
 */
export type FixPhase = {
  key: string;
  icon: typeof GitBranch;
  pct: number;
  long?: boolean;
};

export const FIX_PHASES: FixPhase[] = [
  { key: 'PLANNING', icon: GitBranch, pct: 18 },
  { key: 'CHECKING', icon: ShieldCheck, pct: 45, long: true },
  { key: 'VERIFYING', icon: Hammer, pct: 75, long: true },
  { key: 'OPENING_PRS', icon: GitPullRequest, pct: 92 },
];

/**
 * Train status → ordinal, used to mark each phase done / active / pending. The human-wait + terminal states
 * (PR_OPEN / AWAITING_MANUAL_FIX / DONE / FAILED) all sit *past* the last machine-driven phase. AWAITING_CONFIRM
 * sits before phase 1 (the wizard shows its own review UI there, not this stepper).
 */
export const FIX_PHASE_ORDER: Record<string, number> = {
  AWAITING_CONFIRM: 0,
  PLANNING: 1,
  CHECKING: 2,
  VERIFYING: 3,
  OPENING_PRS: 4,
  PR_OPEN: 5,
  AWAITING_MANUAL_FIX: 5,
  DONE: 5,
  MERGED: 5,
  FAILED: 5,
};

/** A 0–100 progress estimate for the current status (drives the progress bar). */
export function fixPhasePct(status: string, failedStage?: string | null): number {
  if (status === 'DONE' || status === 'PR_OPEN' || status === 'MERGED') return 100;
  if (status === 'AWAITING_MANUAL_FIX') return 92;
  if (status === 'FAILED') {
    const failed = failedStage ? FIX_PHASES.find((p) => p.key === failedStage) : undefined;
    return failed ? failed.pct : 8;
  }
  const p = FIX_PHASES.find((x) => x.key === status);
  return p ? p.pct : 6;
}

export type PhaseVisual = 'done' | 'active' | 'pending' | 'error' | 'manual';

/**
 * The visual state of one lifecycle phase given the train's current status, the stage it failed at, and whether the
 * local reactor build passed. On a FAILED train we trust the backend's preserved `failedStage` (the live status is
 * overwritten with FAILED) — without it we mark nothing done, never fabricating green ticks. On the
 * AWAITING_MANUAL_FIX (breaking) path the branches are pushed but the PRs are *held* for the user, so "Opening the
 * PRs" reads as an action-needed (manual) phase; and when the hold was caused by a failed build, "Building & testing"
 * is flagged as the error étape rather than a green check that would contradict the action-needed banner.
 */
export function phaseVisual(phaseKey: string, status: string, failedStage?: string | null,
    reactorPassed?: boolean | null): PhaseVisual {
  const order = FIX_PHASE_ORDER[phaseKey] ?? 0;
  const current = FIX_PHASE_ORDER[status] ?? 0;
  if (status === 'FAILED') {
    const failedOrder = failedStage ? (FIX_PHASE_ORDER[failedStage] ?? -1) : -1;
    if (failedOrder < 0) return 'pending';        // unknown failure point → don't invent progress
    if (order === failedOrder) return 'error';
    return order > failedOrder ? 'pending' : 'done';
  }
  if (status === 'AWAITING_MANUAL_FIX') {
    if (phaseKey === 'VERIFYING' && reactorPassed === false) return 'error';   // the build broke → flag it, not ✓
    if (phaseKey === 'OPENING_PRS') return 'manual';                           // branches pushed, PRs held for the user
  }
  if (current > order) return 'done';
  if (current === order) return 'active';
  return 'pending';
}
