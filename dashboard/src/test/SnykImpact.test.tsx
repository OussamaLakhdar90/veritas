import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { SnykImpactCard } from '../components/SnykImpact'
import type { SnykSummary } from '../api'

const summary = (over: Partial<SnykSummary> = {}): SnykSummary => ({
  watchedApps: 2, projects: 5, critical: 4, high: 8, medium: 3, low: 6, fixable: 6, unseenAlerts: 1,
  fixesStarted: 7, fixesInProgress: 2, fixesMerged: 4, fixesBreaking: 1, prsOpened: 15, llmChecks: 7,
  llmCostUsd: 0.42, ...over,
})

describe('Snyk impact card', () => {
  it('shows found (Critical emphasised) vs fixed with PRs and AI cost', async () => {
    server.use(http.get('*/api/v1/snyk/summary', () => HttpResponse.json(summary())))
    renderPage(<SnykImpactCard />)

    expect(await screen.findByText('Dependency security')).toBeInTheDocument()
    expect(screen.getByText('$0.42')).toBeInTheDocument()        // AI spend (unique)
    expect(screen.getByText('15')).toBeInTheDocument()           // PRs opened (unique)
    expect(screen.getByText('fixes merged')).toBeInTheDocument()
    // open = critical+high+medium+low = 4+8+3+6 = 21; 6 have a safe version.
    expect(screen.getByText(/21 open · 6 with a safe version/)).toBeInTheDocument()
  })

  it('renders nothing until at least one app-id is watched', async () => {
    server.use(http.get('*/api/v1/snyk/summary', () => HttpResponse.json(summary({ watchedApps: 0 }))))
    renderPage(<SnykImpactCard />)
    // give the query a tick; the card must never appear.
    await new Promise((r) => setTimeout(r, 30))
    expect(screen.queryByText('Dependency security')).not.toBeInTheDocument()
  })
})
