import type { TFunction } from 'i18next';
import type { ExecutiveServiceSummary, ServiceSummary, Scan } from '../api';

/** Quote a CSV field per RFC 4180: wrap in quotes and double any embedded quotes when it contains , " or a newline. */
function csvCell(value: string | number | null | undefined): string {
  const s = value == null ? '' : String(value);
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/** Serialize rows (first row = headers) to an RFC-4180 CSV body with CRLF line endings. */
export function toCsv(rows: Array<Array<string | number | null | undefined>>): string {
  return rows.map((r) => r.map(csvCell).join(',')).join('\r\n');
}

/** Trigger a browser download of `text` as `filename` (UTF-8 with a BOM so Excel reads accents correctly). */
export function downloadCsv(filename: string, text: string): void {
  // The BOM makes Excel open the file as UTF-8 — without it, "é" / "$" render mojibake in fr-CA.
  const blob = new Blob(['﻿' + text], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/** A service's grade letter from its fidelity score — mirrors FidelityScorecard.letterGrade (kept local to avoid a cycle). */
function grade(score?: number | null): string {
  if (score == null) return '';
  if (score >= 90) return 'A';
  if (score >= 80) return 'B';
  if (score >= 70) return 'C';
  if (score >= 60) return 'D';
  return 'E';
}

/**
 * Build the per-service scorecard CSV from the same data the on-screen scorecard renders: the services list
 * (asset counts), the executive per-service summary (grade/delta/release-safe/blocking), and the latest
 * completed scan per service (visibility = coverage gaps). Headers are localized; the release-safe verdict is
 * humanized. Returns the CSV text (caller downloads it).
 */
export function buildScorecardCsv(
  t: TFunction,
  services: ServiceSummary[],
  summaryByService: Map<string, ExecutiveServiceSummary>,
  latestByService: Map<string, Scan>,
): string {
  const header = [
    t('overview.csvService'), t('overview.csvGrade'), t('overview.csvScore'), t('overview.csvDelta'),
    t('overview.csvReleaseSafe'), t('overview.csvVisibility'), t('overview.csvBlocking'), t('overview.csvScans'),
  ];
  const safeLabel = (v?: string) =>
    v ? t(`overview.csvSafe_${v}`, { defaultValue: v }) : '';
  const rows = services.map((s) => {
    const sum = summaryByService.get(s.name);
    const latest = latestByService.get(s.name);
    const gaps = latest?.coverageGaps;
    return [
      s.name,
      grade(sum?.fidelity),
      sum?.fidelity ?? '',
      sum?.delta != null && sum.delta !== 0 ? `${sum.delta > 0 ? '+' : ''}${sum.delta}` : '',
      safeLabel(sum?.releaseSafe),
      gaps == null ? '' : gaps === 0 ? t('overview.csvVisibilityFull') : t('overview.csvVisibilityGaps', { count: gaps }),
      sum?.blockingCount ?? '',
      s.scans || 0,
    ];
  });
  return toCsv([header, ...rows]);
}

/** Download a plotted trend series (the {date,value}[] behind a TrendChart) as a two-column CSV. */
export function downloadTrendCsv(
  filename: string, dateHeader: string, valueHeader: string,
  points: Array<{ date: string; value: number }>,
): void {
  const rows: Array<Array<string | number>> = [[dateHeader, valueHeader], ...points.map((p) => [p.date, p.value])];
  const stamp = new Date().toISOString().slice(0, 10);
  downloadCsv(`${filename}-${stamp}.csv`, toCsv(rows));
}

/** Compose the scorecard CSV and download it as veritas-scorecard-YYYY-MM-DD.csv. */
export function downloadScorecardCsv(
  t: TFunction,
  services: ServiceSummary[],
  summaryByService: Map<string, ExecutiveServiceSummary>,
  latestByService: Map<string, Scan>,
): void {
  const text = buildScorecardCsv(t, services, summaryByService, latestByService);
  const stamp = new Date().toISOString().slice(0, 10);
  downloadCsv(`veritas-scorecard-${stamp}.csv`, text);
}
