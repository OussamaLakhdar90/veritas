// Static Tailwind class maps (kept whole here so Tailwind's content scan never purges them).

export type Severity = 'BLOCKER' | 'CRITICAL' | 'MAJOR' | 'MINOR' | 'INFO';

/** Severity pill styling — colored text + ring + subtle tint on the surface. */
export const SEVERITY_BADGE: Record<string, string> = {
  BLOCKER: 'bg-sev-blocker/10 text-sev-blocker ring-1 ring-sev-blocker/30',
  CRITICAL: 'bg-sev-critical/10 text-sev-critical ring-1 ring-sev-critical/30',
  MAJOR: 'bg-sev-major/10 text-sev-major ring-1 ring-sev-major/30',
  MINOR: 'bg-sev-minor/10 text-sev-minor ring-1 ring-sev-minor/30',
  INFO: 'bg-sev-info/10 text-sev-info ring-1 ring-sev-info/30',
};

export function severityBadge(sev?: string): string {
  return SEVERITY_BADGE[(sev || 'INFO').toUpperCase()] ?? SEVERITY_BADGE.INFO;
}

/**
 * Snyk severity pill styling. Snyk uses critical/high/medium/low; we reuse the same colour ramp as the
 * finding severities so Critical is unmistakably red.
 */
export const SNYK_SEVERITY_BADGE: Record<string, string> = {
  critical: SEVERITY_BADGE.CRITICAL,
  high: SEVERITY_BADGE.MAJOR,
  medium: SEVERITY_BADGE.MINOR,
  low: SEVERITY_BADGE.INFO,
};
export function snykSeverityBadge(sev?: string): string {
  return SNYK_SEVERITY_BADGE[(sev || 'low').toLowerCase()] ?? SNYK_SEVERITY_BADGE.low;
}

/** Plain-language labels for the analysis "layer" codes — users should never see L1–L6. */
export const LAYER_LABEL: Record<string, string> = {
  L1: 'Specification structure',
  L2: 'API completeness',
  L3: 'Documentation scope',
  L4: 'Signature accuracy',
  L5: 'Design quality',
  L6: 'Test coverage',
};
export function layerLabel(layer?: string): string {
  return layer ? (LAYER_LABEL[layer.toUpperCase()] ?? layer) : '';
}

/** Plain-language labels for confidence levels (HIGH/MEDIUM/LOW). */
export const CONFIDENCE_LABEL: Record<string, string> = {
  HIGH: 'High confidence', MEDIUM: 'Medium confidence', LOW: 'Low confidence',
};
export function confidenceLabel(c?: string): string {
  return c ? (CONFIDENCE_LABEL[c.toUpperCase()] ?? c) : '';
}

/** Generic status tone → pill classes (for gates, defect status, connection tests, build status…). */
export const TONE: Record<string, string> = {
  ok: 'bg-success/10 text-success ring-1 ring-success/30',
  warn: 'bg-warning/10 text-warning ring-1 ring-warning/30',
  danger: 'bg-danger/10 text-danger ring-1 ring-danger/30',
  info: 'bg-info/10 text-info ring-1 ring-info/30',
  muted: 'bg-ink-600/10 text-muted ring-1 ring-ink-600/20',
};
