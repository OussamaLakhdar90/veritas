import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { Snyk } from '../pages/Snyk'

function renderSnyk() {
  return renderPage(<Snyk />, { path: '/snyk', route: '/snyk' })
}

const emptyBase = () => {
  server.use(
    http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([])),
    http.get('*/api/v1/snyk/watches', () => HttpResponse.json([])),
    http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
  )
}

describe('Snyk page', () => {
  it('shows the Snyk-branded header and the watch-applications card', async () => {
    emptyBase()
    renderSnyk()
    expect(await screen.findByText('Watch applications')).toBeInTheDocument()
    // Snyk brand mark is present (aria-label="Snyk").
    expect(screen.getAllByLabelText('Snyk').length).toBeGreaterThan(0)
  })

  it('lets the user paste a Snyk token inline and connects, then loads the apps', async () => {
    let orgsConnected = false
    let savedToken: string | null = null
    server.use(
      // Orgs error until a token is saved + the connection tests OK, then they load.
      http.get('*/api/v1/snyk/orgs', () => orgsConnected
        ? HttpResponse.json([{ id: 'o1', slug: 'app7576', name: 'CIAM Profile' }])
        : new HttpResponse(null, { status: 500 })),
      http.get('*/api/v1/snyk/watches', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
      http.post('*/api/v1/settings/secrets', async ({ request }) => {
        savedToken = ((await request.json()) as { value: string }).value
        return new HttpResponse(null, { status: 204 })
      }),
      http.post('*/api/v1/settings/connections/snyk/test', () => {
        orgsConnected = true
        return HttpResponse.json({ service: 'snyk', reachable: true, authenticated: true, status: 200, message: 'ok' })
      }),
    )
    const user = userEvent.setup()
    renderSnyk()

    // Guided panel with an inline token field — no trip to Settings needed.
    expect(await screen.findByText('Connect Snyk to get started')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Paste your Snyk API token'), 'my-personal-token')
    await user.click(screen.getByRole('button', { name: /Connect/ }))

    // The token was saved and, once connected, the apps appear (panel replaced by the app list).
    expect(await screen.findByText('CIAM Profile')).toBeInTheDocument()
    expect(savedToken).toBe('my-personal-token')
    // A link to the full Settings page remains for region/advanced options.
    expect(screen.queryByRole('link', { name: /Open full settings/ })).not.toBeInTheDocument()   // panel is gone now
  })

  it('watches selected app-ids via the by-app endpoint (auto application-tests)', async () => {
    let posted: unknown = null
    server.use(
      http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([
        { id: 'o1', slug: 'app7576', name: 'CIAM Profile' },
        { id: 'o2', slug: 'app7571', name: 'CIAM Access' },
      ])),
      http.get('*/api/v1/snyk/watches', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
      http.post('*/api/v1/snyk/watches/by-app', async ({ request }) => {
        posted = await request.json()
        return HttpResponse.json({ id: 'w1', orgId: 'o1', orgSlug: 'app7576', orgName: 'CIAM Profile',
          targetId: 't1', repoSlug: 'application-tests', enabled: true, critical: 0, high: 0, medium: 0,
          low: 0, fixable: 0, projectCount: 0 }, { status: 201 })
      }),
    )
    const user = userEvent.setup()
    renderSnyk()

    // Tick the first application, then "Watch selected".
    const checkbox = (await screen.findAllByRole('checkbox'))[0]
    await user.click(checkbox)
    await user.click(screen.getByRole('button', { name: /Watch selected/ }))

    expect(await screen.findByText('Now watching 1 application.')).toBeInTheDocument()
    expect(posted).toMatchObject({ orgId: 'o1', orgSlug: 'app7576' })
  })

  it('refetches the summary/impact card after "Watch selected" (broad invalidation, not just the watch list)', async () => {
    let summaryCalls = 0
    server.use(
      http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([
        { id: 'o1', slug: 'app7576', name: 'CIAM Profile' },
      ])),
      http.get('*/api/v1/snyk/watches', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
      // The impact card reads the summary; watching one app must invalidate it so it re-fetches (a refetch = a
      // second GET). Start with one watched app so the card renders and its query is live from the first load.
      http.get('*/api/v1/snyk/summary', () => {
        summaryCalls += 1
        return HttpResponse.json({
          watchedApps: 1, projects: 1, critical: 0, high: 0, medium: 0, low: 0, fixable: 0, unseenAlerts: 0,
          fixesStarted: 0, fixesInProgress: 0, fixesMerged: 0, fixesBreaking: 0, prsOpened: 0, llmChecks: 0, llmCostUsd: 0,
        })
      }),
      http.post('*/api/v1/snyk/watches/by-app', () =>
        HttpResponse.json({ id: 'w1', orgId: 'o1', orgSlug: 'app7576', orgName: 'CIAM Profile', targetId: 't1',
          repoSlug: 'application-tests', enabled: true, critical: 0, high: 0, medium: 0, low: 0, fixable: 0,
          projectCount: 0 }, { status: 201 })),
    )
    const user = userEvent.setup()
    renderSnyk()

    // The impact card's first summary read settles.
    expect(await screen.findByText('Dependency security')).toBeInTheDocument()
    const before = summaryCalls

    await user.click((await screen.findAllByRole('checkbox'))[0])
    await user.click(screen.getByRole('button', { name: /Watch selected/ }))

    // Success toast confirms the mutation resolved; the summary must have re-fetched (invalidate() covers it).
    expect(await screen.findByText('Now watching 1 application.')).toBeInTheDocument()
    await waitFor(() => expect(summaryCalls).toBeGreaterThan(before))
  })

  it('shows a running indicator on "Refresh now" and settles from the background status poll (button never dead)', async () => {
    let refreshPosted = false
    let statusReads = 0
    server.use(
      http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/watches', () => HttpResponse.json([{
        id: 'w1', orgId: 'o1', orgSlug: 'app7576', orgName: 'CIAM Profile', targetId: 't1',
        repoSlug: 'application-tests', enabled: true, critical: 0, high: 0, medium: 0, low: 0,
        fixable: 0, projectCount: 1, lastPolled: '2026-07-01T12:00:00.000Z',
      }])),
      // The refresh returns 202 FAST (the poll runs in the background) — the click must not hang on it.
      http.post('*/api/v1/snyk/refresh', () => { refreshPosted = true; return HttpResponse.json({ polled: 1 }, { status: 202 }) }),
      // First status read: still running; subsequent reads: done — so the page settles off the background poll.
      http.get('*/api/v1/snyk/refresh/status', () => {
        statusReads += 1
        return HttpResponse.json({ running: statusReads < 2, lastRefreshedAt: '2026-07-04T12:00:00.000Z' })
      }),
    )
    const user = userEvent.setup()
    renderSnyk()

    await user.click(await screen.findByRole('button', { name: /Refresh now/ }))

    // Immediate running state — the indicator appears without waiting on the long poll.
    expect(refreshPosted).toBe(true)
    expect(await screen.findByText('Refreshing from Snyk…')).toBeInTheDocument()

    // The background status poll flips to done → the indicator clears and a success toast confirms it.
    // Allow a few status-poll cycles (the interval is ~1.5s) before it reports done.
    expect(await screen.findByText('Refreshed the watched repositories.', {}, { timeout: 5000 })).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('Refreshing from Snyk…')).not.toBeInTheDocument())
  })

  it('shows a just-watched app immediately (the row is persisted synchronously; its poll is backgrounded)', async () => {
    let watched = false
    server.use(
      http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([
        { id: 'o1', slug: 'app7576', name: 'CIAM Profile' },
      ])),
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
      // Watches are empty until the by-app POST persists the row, then the invalidated read returns it.
      http.get('*/api/v1/snyk/watches', () => HttpResponse.json(watched ? [{
        id: 'w1', orgId: 'o1', orgSlug: 'app7576', orgName: 'CIAM Profile', targetId: 't1',
        repoSlug: 'application-tests', enabled: true, critical: 0, high: 0, medium: 0, low: 0,
        fixable: 0, projectCount: 0,
      }] : [])),
      http.post('*/api/v1/snyk/watches/by-app', () => {
        watched = true   // the row is saved before the response — the poll happens in the background
        return HttpResponse.json({ id: 'w1', orgId: 'o1', orgSlug: 'app7576', orgName: 'CIAM Profile',
          targetId: 't1', repoSlug: 'application-tests', enabled: true, critical: 0, high: 0, medium: 0,
          low: 0, fixable: 0, projectCount: 0 }, { status: 201 })
      }),
    )
    const user = userEvent.setup()
    renderSnyk()

    await user.click((await screen.findAllByRole('checkbox'))[0])
    await user.click(screen.getByRole('button', { name: /Watch selected/ }))

    // The new watch surfaces right away (from the invalidated watch-list read) — no wait on any poll.
    expect(await screen.findByText('application-tests')).toBeInTheDocument()
  })

  it('shows the empty state when no repos are watched', async () => {
    emptyBase()
    renderSnyk()
    expect(await screen.findByText('No repositories watched yet')).toBeInTheDocument()
  })

  it('renders a watched repo with its severity counts', async () => {
    server.use(
      http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/watches', () => HttpResponse.json([{
        id: 'w1', orgId: 'o1', orgSlug: 'app7576', orgName: 'CIAM Profile', targetId: 't1',
        repoSlug: 'application-tests', enabled: true, critical: 4, high: 1, medium: 12, low: 0,
        fixable: 3, projectCount: 14, lastPolled: '2026-07-01T12:00:00.000Z',
      }])),
    )
    renderSnyk()
    expect(await screen.findByText('application-tests')).toBeInTheDocument()
    expect(screen.getByText('app7576')).toBeInTheDocument()
  })

  it('surfaces a new critical alert banner', async () => {
    server.use(
      http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/watches', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([{
        id: 'a1', watchId: 'w1', orgSlug: 'app7576', repoSlug: 'application-tests',
        severity: 'critical', message: 'New critical vulnerability in application-tests (app7576).', seen: false,
      }])),
    )
    renderSnyk()
    expect(await screen.findByText(/New critical vulnerability/)).toBeInTheDocument()
    // The severity badge shows the localized label ("Critical"), not the raw lowercase API value.
    expect(screen.getByText('Critical')).toBeInTheDocument()
  })

  it('loads issues for a watch and shows fix info (upgrade vs no-supported-fix)', async () => {
    server.use(
      http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/watches', () => HttpResponse.json([{
        id: 'w1', orgId: 'o1', orgSlug: 'app7576', orgName: 'CIAM Profile', targetId: 't1',
        repoSlug: 'application-tests', enabled: true, critical: 1, high: 1, medium: 0, low: 0,
        fixable: 1, projectCount: 1, lastPolled: '2026-07-01T12:00:00.000Z',
      }])),
      http.get('*/api/v1/snyk/watches/w1/issues', () => HttpResponse.json([
        { projectName: 'profile-management/pom.xml', issueId: 'i1', severity: 'critical', title: 'Deserialization',
          pkgName: 'com.fasterxml.jackson.core:jackson-databind', pkgVersion: '3.1.1', cve: 'CVE-2020-1',
          cwe: 'CWE-502', cvss: 9.2, riskScore: 298, fixable: false, fixedIn: null },
        { projectName: 'profile-management/pom.xml', issueId: 'i2', severity: 'high', title: 'Recursion',
          pkgName: 'org.apache.commons:commons-lang3', pkgVersion: '3.12.0', cve: 'CVE-2024-2',
          cwe: 'CWE-674', cvss: 7.5, riskScore: 182, fixable: true, fixedIn: '3.18.0' },
      ])),
    )
    const user = userEvent.setup()
    renderSnyk()

    await user.click(await screen.findByTitle('View vulnerabilities'))

    expect(await screen.findByText('Upgrade to 3.18.0')).toBeInTheDocument()
    expect(screen.getByText('No safe version — tracked only')).toBeInTheDocument()
    expect(screen.getByText('com.fasterxml.jackson.core:jackson-databind')).toBeInTheDocument()

    // Sort headers exercise every issue accessor (severity rank, package, project, cve, cvss, fixability).
    // Exact names — the fixable row also has a "Fix…" action button, so a /Fix/ regex would be ambiguous.
    for (const col of ['Severity', 'Package', 'Project', 'CVE', 'CVSS', 'Fix']) {
      await user.click(screen.getByRole('button', { name: col }))
    }
    // Clicking Package again (it wasn't the last-clicked column) makes it the active key, ascending.
    await user.click(screen.getByRole('button', { name: 'Package' }))
    expect(screen.getByText('org.apache.commons:commons-lang3')).toBeInTheDocument()
    expect(screen.getByText('com.fasterxml.jackson.core:jackson-databind')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Package' }).closest('th'))
      .toHaveAttribute('aria-sort', 'ascending')
  })
})
