import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderPage } from './render';
import { LiveScanRow } from '../components/LiveScanRow';
import type { Scan } from '../api';

const scan = (over: Partial<Scan> = {}): Scan => ({
  id: 's1', serviceName: 'ciam-policies', status: 'RUNNING', stage: 'RECONCILING',
  stageDetail: 'Reviewing findings — batch 2 of 3', model: 'claude-sonnet-4.6',
  totalFindings: 0, totalEstCostUsd: 0, startedAt: new Date().toISOString(), specSources: 'openapi',
  ...over,
});

describe('LiveScanRow', () => {
  it('renders nothing when no scan is running', () => {
    const { container } = renderPage(<LiveScanRow scans={[scan({ status: 'COMPLETED' })]} />);
    expect(container.querySelector('.border-l-gold')).toBeNull();
  });

  it('pins a running strip with the service, live step, model chip and detail', () => {
    renderPage(<LiveScanRow scans={[scan()]} />);
    expect(screen.getByText('ciam-policies')).toBeInTheDocument();
    expect(screen.getByText('claude-sonnet-4.6')).toBeInTheDocument();
    expect(screen.getByText(/Reviewing findings/)).toBeInTheDocument();
    // "Step N of M — <stage>" — RECONCILING is step 5.
    expect(screen.getByText(/Step 5 of/)).toBeInTheDocument();
  });
});
