// Single source of truth for the Snyk fix-train status strings — mirrors the backend `SnykFixStatus`
// so the wizard and any future train view never drift on a raw literal.

export const FIX_STATUS = {
  PLANNING: 'PLANNING',
  AWAITING_CONFIRM: 'AWAITING_CONFIRM',
  CHECKING: 'CHECKING',
  VERIFYING: 'VERIFYING',
  OPENING_PRS: 'OPENING_PRS',
  PR_OPEN: 'PR_OPEN',
  AWAITING_MANUAL_FIX: 'AWAITING_MANUAL_FIX',
  DONE: 'DONE',
  FAILED: 'FAILED',
} as const;

/** The machine-driven stages the wizard polls on (mirrors the backend MACHINE_DRIVEN set). */
export const IN_FLIGHT: string[] = [
  FIX_STATUS.PLANNING, FIX_STATUS.CHECKING, FIX_STATUS.VERIFYING, FIX_STATUS.OPENING_PRS,
];
