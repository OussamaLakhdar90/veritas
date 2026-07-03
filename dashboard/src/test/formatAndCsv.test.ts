import { afterEach, describe, expect, it, vi } from 'vitest';
import i18n from '../i18n';
import {
  appLocale, formatDateTime, formatDuration, formatMoney, formatRelative, formatRelativeEpoch, formatShortDate,
} from '../lib/format';
import { buildScorecardCsv, downloadCsv, toCsv } from '../lib/exportCsv';
import type { ExecutiveServiceSummary, Scan, ServiceSummary } from '../api';

const t = ((key: string, opts?: { count?: number; defaultValue?: string }) => {
  if (key === 'overview.csvVisibilityFull') return 'full';
  if (key === 'overview.csvVisibilityGaps') return `${opts?.count} gaps`;
  if (key.startsWith('overview.csvSafe_')) return key.slice('overview.csvSafe_'.length);
  return key.replace('overview.csv', '');   // header cells → the bare column name
}) as unknown as Parameters<typeof buildScorecardCsv>[0];

afterEach(() => { i18n.changeLanguage('en'); vi.restoreAllMocks(); });

describe('format helpers', () => {
  it('maps the two app languages to their regional locale', () => {
    i18n.changeLanguage('fr');
    expect(appLocale()).toBe('fr-CA');
    i18n.changeLanguage('en');
    expect(appLocale()).toBe('en-CA');
  });

  it('returns an em dash for null/invalid across every formatter', () => {
    expect(formatDateTime(null)).toBe('—');
    expect(formatDateTime('not-a-date')).toBe('—');
    expect(formatShortDate(undefined)).toBe('—');
    expect(formatShortDate('nope')).toBe('—');
    expect(formatRelative(null)).toBe('—');
    expect(formatRelativeEpoch(0)).toBe('—');
    expect(formatDuration(null, '2026-01-01T00:00:00Z')).toBeNull();
    expect(formatDuration('2026-01-01T00:01:00Z', '2026-01-01T00:00:00Z')).toBeNull();   // negative
  });

  it('reads a bare YYYY-MM-DD as a LOCAL calendar day (no UTC slip west of UTC)', () => {
    // The whole point of the regex branch: the label must name the 20th, not the 19th.
    expect(formatShortDate('2026-06-20')).toMatch(/20/);
    expect(formatShortDate('2026-06-20T09:00:00Z')).toContain('Jun');
  });

  it('formats money locale-true with an overridable, cache-safe fraction-digit count', () => {
    expect(formatMoney(0.42)).toMatch(/0\.42/);          // default 2dp
    expect(formatMoney(0.4187, 4)).toMatch(/0\.4187/);   // 4dp does not collide with the 2dp formatter
    expect(formatMoney(0.42)).toMatch(/0\.42/);          // …and the 2dp formatter still reads from cache
    i18n.changeLanguage('fr');
    expect(formatMoney(12.5)).toMatch(/12,50/);          // fr-CA comma decimal
  });

  it('formats a relative epoch and a duration', () => {
    expect(formatRelativeEpoch(Date.now() - 2 * 3600_000)).toMatch(/2/);
    expect(formatDuration('2026-01-01T00:00:00Z', '2026-01-01T00:03:42Z')).toBe('3 min 42 s');
    expect(formatDuration('2026-01-01T00:00:00Z', '2026-01-01T00:00:07Z')).toBe('7 s');
  });
});

describe('CSV export', () => {
  it('quotes only fields containing comma/quote/newline (RFC 4180) with CRLF rows', () => {
    const csv = toCsv([['a', 'b,c', 'he said "hi"'], [1, 'plain', null]]);
    expect(csv).toBe('a,"b,c","he said ""hi"""\r\n1,plain,');
  });

  it('builds the scorecard from the on-screen sources with grade + humanized verdict', () => {
    const services = [{ name: 'ciam', scans: 4 } as ServiceSummary];
    const summary = new Map<string, ExecutiveServiceSummary>([
      ['ciam', { service: 'ciam', fidelity: 84, delta: 6, breakingCount: 1, blockingCount: 0, releaseSafe: 'PASS', latestScanId: 's1' }],
    ]);
    const latest = new Map<string, Scan>([['ciam', { coverageGaps: 0 } as Scan]]);
    const csv = buildScorecardCsv(t, services, summary, latest);
    const [header, row] = csv.split('\r\n');
    expect(header).toContain('Service');
    expect(row).toBe('ciam,B,84,+6,PASS,full,0,4');   // grade B, +delta, PASS verdict, full visibility
  });

  it('renders gaps + blanks for a service with no summary/score', () => {
    const services = [{ name: 'x', scans: 0 } as ServiceSummary];
    const latest = new Map<string, Scan>([['x', { coverageGaps: 2 } as Scan]]);
    const csv = buildScorecardCsv(t, services, new Map(), latest);
    expect(csv.split('\r\n')[1]).toBe('x,,,,,2 gaps,,0');
  });

  it('downloadCsv clicks a named anchor with a BOM-prefixed blob', () => {
    const clicked = vi.fn();
    let filename = '';
    const realCreate = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = realCreate(tag) as HTMLAnchorElement;
      if (tag === 'a') {
        el.click = clicked;
        Object.defineProperty(el, 'download', { set: (v: string) => { filename = v; }, get: () => filename });
      }
      return el;
    });
    // jsdom lacks createObjectURL/revokeObjectURL — capture the blob it's handed (its size proves the BOM byte).
    let captured: Blob | null = null;
    (URL as unknown as { createObjectURL: (b: Blob) => string }).createObjectURL = (b) => { captured = b; return 'blob:x'; };
    (URL as unknown as { revokeObjectURL: () => void }).revokeObjectURL = () => {};

    downloadCsv('report.csv', 'a,b');

    expect(clicked).toHaveBeenCalledOnce();
    expect(filename).toBe('report.csv');
    // '﻿' (3 UTF-8 bytes) + 'a,b' (3 bytes) = 6 — proves the BOM was prepended.
    expect((captured as unknown as Blob).size).toBe(6);
  });
});
