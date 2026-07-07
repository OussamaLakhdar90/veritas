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

  it('surfaces a new watch’s vulnerabilities when its background poll completes (no manual refresh)', async () => {
    // The row is persisted synchronously (0 counts, never checked), then its initial vulnerability poll runs in the
    // BACKGROUND. The page must re-read when that poll finishes so the real severity counts appear on their own —
    // WITHOUT the user clicking "Refresh now" (the reported bug). Completion is signalled by the shared refresh/status.
    let watched = false
    let statusReads = 0
    const polled = () => statusReads >= 2   // 1st status read: still running; 2nd onward: poll done
    server.use(
      http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([{ id: 'o1', slug: 'app7576', name: 'CIAM Profile' }])),
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/watches', () => HttpResponse.json(!watched ? [] : [{
        id: 'w1', orgId: 'o1', orgSlug: 'app7576', orgName: 'CIAM Profile', targetId: 't1',
        repoSlug: 'application-tests', enabled: true,
        critical: polled() ? 3 : 0, high: polled() ? 1 : 0, medium: 0, low: 0,
        fixable: polled() ? 2 : 0, projectCount: polled() ? 1 : 0,
        lastPolled: polled() ? '2026-07-05T12:00:00.000Z' : null,
      }])),
      http.post('*/api/v1/snyk/watches/by-app', () => {
        watched = true   // the row is saved before the response; the poll happens in the background
        return HttpResponse.json({ id: 'w1', orgId: 'o1', orgSlug: 'app7576', orgName: 'CIAM Profile',
          targetId: 't1', repoSlug: 'application-tests', enabled: true, critical: 0, high: 0, medium: 0,
          low: 0, fixable: 0, projectCount: 0 }, { status: 201 })
      }),
      // First read: the background poll is still running; subsequent reads: done → the page settles + re-reads.
      http.get('*/api/v1/snyk/refresh/status', () => {
        statusReads += 1
        return HttpResponse.json({ running: statusReads < 2, lastRefreshedAt: '2026-07-05T12:00:00.000Z' })
      }),
    )
    const user = userEvent.setup()
    renderSnyk()

    await user.click((await screen.findAllByRole('checkbox'))[0])
    await user.click(screen.getByRole('button', { name: /Watch selected/ }))

    // The card first shows 0 counts (row persisted, poll not done)…
    expect(await screen.findByText('application-tests')).toBeInTheDocument()
    // …then, when the background poll completes, the real Critical count surfaces on its own (no manual refresh).
    expect(await screen.findByText('3 Critical', {}, { timeout: 5000 })).toBeInTheDocument()
  })

  it('does not get stuck "refreshing" when the status endpoint keeps failing (bounded failsafe)', async () => {
    // If GET /snyk/refresh/status is down, the settle can never see running:false — without a failsafe the page would
    // hang in a permanent "refreshing" state (spinner + disabled Refresh). The error-aware settle must clear it.
    server.use(
      http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([{ id: 'o1', slug: 'app7576', name: 'CIAM Profile' }])),
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/watches', () => HttpResponse.json([])),
      http.post('*/api/v1/snyk/watches/by-app', () =>
        HttpResponse.json({ id: 'w1', orgId: 'o1', orgSlug: 'app7576', orgName: 'CIAM Profile', targetId: 't1',
          repoSlug: 'application-tests', enabled: true, critical: 0, high: 0, medium: 0, low: 0, fixable: 0,
          projectCount: 0 }, { status: 201 })),
      http.get('*/api/v1/snyk/refresh/status', () => new HttpResponse(null, { status: 500 })),
    )
    const user = userEvent.setup()
    renderSnyk()
    await user.click((await screen.findAllByRole('checkbox'))[0])
    await user.click(screen.getByRole('button', { name: /Watch selected/ }))

    // The watch is added; the "refreshing" indicator must NOT hang once the status query errors out (retries are off).
    expect(await screen.findByText('Now watching 1 application.')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('Refreshing from Snyk…')).not.toBeInTheDocument(),
      { timeout: 5000 })
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

  // ── Bulk "Fix vulnerabilities" wizard (4 steps) ──────────────────────────
  // Preflight reports all three required connections OK, so step 1's gate opens straight through.
  const preflightAllOk = () => server.use(
    http.get('*/api/v1/preflight', () => HttpResponse.json([
      { name: 'Snyk token', status: 'OK', detail: 'Configured.', remediation: '' },
      { name: 'Jira token', status: 'OK', detail: 'Configured.', remediation: '' },
      { name: 'Jira base URL', status: 'OK', detail: 'https://jira', remediation: '' },
      { name: 'Bitbucket base URL', status: 'OK', detail: 'https://bb', remediation: '' },
      { name: 'Git access (clone)', status: 'OK', detail: 'Configured.', remediation: '' },
    ])),
  )
  // The step-4 Jira pickers: one project, one existing epic, one open story under it, and a Bitbucket user search.
  const jiraPickers = () => server.use(
    http.get('*/api/v1/jira/projects', () => HttpResponse.json([{ key: 'CIAM', name: 'CIAM Platform' }])),
    http.get('*/api/v1/jira/projects/CIAM/epics', () =>
      HttpResponse.json([{ key: 'CIAM-100', summary: 'Security backlog' }])),
    http.get('*/api/v1/jira/epics/CIAM-100/stories', () =>
      HttpResponse.json([{ key: 'CIAM-150', summary: 'Q3 dependency fixes' }])),
    http.get('*/api/v1/bitbucket/users', ({ request }) => {
      const q = (new URL(request.url).searchParams.get('query') ?? '').toLowerCase()
      const all = [
        { name: 'alice', displayName: 'Alice Nguyen' },
        { name: 'bob', displayName: 'Bob Tremblay' },
      ]
      return HttpResponse.json(all.filter((u) => u.name.includes(q) || u.displayName.toLowerCase().includes(q)))
    }),
  )

  const twoFixableWatches = () => {
    server.use(
      http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/watches', () => HttpResponse.json([
        { id: 'w1', orgId: 'o1', orgSlug: 'app7576', orgName: 'CIAM Profile', targetId: 't1',
          repoSlug: 'application-tests', enabled: true, critical: 1, high: 0, medium: 0, low: 0,
          fixable: 1, projectCount: 1, lastPolled: '2026-07-01T12:00:00.000Z' },
        { id: 'w2', orgId: 'o2', orgSlug: 'app7571', orgName: 'CIAM Access', targetId: 't2',
          repoSlug: 'application-tests', enabled: true, critical: 0, high: 1, medium: 0, low: 0,
          fixable: 1, projectCount: 1, lastPolled: '2026-07-01T12:00:00.000Z' },
      ])),
      http.get('*/api/v1/snyk/watches/w1/issues', () => HttpResponse.json([
        { projectName: 'profile/pom.xml', issueId: 'i1', severity: 'critical', title: 'Deserialization',
          pkgName: 'com.fasterxml.jackson.core:jackson-databind', pkgVersion: '2.14.0', cve: 'CVE-2020-1',
          cwe: 'CWE-502', cvss: 9.2, riskScore: 298, fixable: true, fixedIn: '2.15.0' },
      ])),
      http.get('*/api/v1/snyk/watches/w2/issues', () => HttpResponse.json([
        { projectName: 'access/pom.xml', issueId: 'i2', severity: 'high', title: 'Recursion',
          pkgName: 'org.apache.commons:commons-lang3', pkgVersion: '3.12.0', cve: 'CVE-2024-2',
          cwe: 'CWE-674', cvss: 7.5, riskScore: 182, fixable: true, fixedIn: '3.18.0' },
        // A tracked-only issue (no safe version) must NOT appear in the bulk list.
        { projectName: 'access/pom.xml', issueId: 'i3', severity: 'low', title: 'No fix',
          pkgName: 'org.example:tracked-only', pkgVersion: '1.0.0', cve: 'CVE-2024-3',
          cwe: 'CWE-000', cvss: 3.1, riskScore: 40, fixable: false, fixedIn: null },
      ])),
    )
    preflightAllOk()
    jiraPickers()
  }

  it('walks the 4-step wizard (connections → choose → review → file) and files one bulk request', async () => {
    let posted: Record<string, unknown> | null = null
    twoFixableWatches()
    server.use(
      http.post('*/api/v1/snyk/fixes/bulk', async ({ request }) => {
        posted = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({
          epicKey: 'CIAM-100',
          storyKey: 'CIAM-150',
          apps: [
            { appId: 'APP7576', jiraKey: 'CIAM-150', trainIds: ['t1'], error: null },
            { appId: 'APP7571', jiraKey: 'CIAM-150', trainIds: ['t2'], error: null },
          ],
        }, { status: 202 })
      }),
    )
    const user = userEvent.setup()
    renderSnyk()

    // Open the wizard → Step 1: connections gate is satisfied (all OK), so Continue is live.
    await user.click(await screen.findByRole('button', { name: /Fix vulnerabilities/ }))
    expect(await screen.findByText(/you’re good to go/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Continue' }))

    // Step 2: both fixable issues surface (grouped by app); the tracked-only one does not.
    expect(await screen.findByText('com.fasterxml.jackson.core:jackson-databind')).toBeInTheDocument()
    expect(screen.getByText('org.apache.commons:commons-lang3')).toBeInTheDocument()
    expect(screen.queryByText('org.example:tracked-only')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Select all' }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))

    // Step 3: the impact review lists the distinct app-ids.
    expect(await screen.findByText(/what Veritas will do/)).toBeInTheDocument()
    expect(screen.getByText('APP7576')).toBeInTheDocument()
    expect(screen.getByText('APP7571')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Continue' }))

    // Step 4: pick the project, then an existing epic, then the one shared open story under it, then Start.
    await user.selectOptions(await screen.findByRole('combobox'), 'CIAM')
    const selects = await screen.findAllByRole('combobox')
    await user.selectOptions(selects[1], 'CIAM-100')   // the epic picker appears once a project is chosen
    // The story picker appears once an existing epic is chosen; select the open story every fix links to.
    await waitFor(() => expect(screen.getAllByRole('combobox')).toHaveLength(3))
    await user.selectOptions(screen.getAllByRole('combobox')[2], 'CIAM-150')
    await user.click(screen.getByRole('button', { name: /Start 2 fixes/ }))

    // ONE bulk request: the selection grouped by app, the chosen project + existing epic + shared story.
    expect(await screen.findByText(/Started 2 fixes across 2 applications, filed under CIAM-150/)).toBeInTheDocument()
    expect(posted).toMatchObject({ project: 'CIAM', epicKey: 'CIAM-100', storyKey: 'CIAM-150' })
    expect((posted as unknown as { apps: unknown[] }).apps).toHaveLength(2)
    expect((posted as unknown as { apps: unknown[] }).apps).toContainEqual(expect.objectContaining({
      appId: 'APP7576', watchId: 'w1',
      issues: [expect.objectContaining({ coordinate: 'com.fasterxml.jackson.core:jackson-databind', fixedIn: '2.15.0' })],
    }))
    expect((posted as unknown as { apps: unknown[] }).apps).toContainEqual(expect.objectContaining({
      appId: 'APP7571', watchId: 'w2',
      issues: [expect.objectContaining({ coordinate: 'org.apache.commons:commons-lang3' })],
    }))
  })

  it('shows a confirmation with the created Jira ticket as a clickable link (does not just close)', async () => {
    twoFixableWatches()
    server.use(
      http.post('*/api/v1/snyk/fixes/bulk', () => HttpResponse.json({
        epicKey: 'CIAM-100', storyKey: 'CIAM-150',
        epicUrl: 'https://jira.bnc.ca/browse/CIAM-100',
        storyUrl: 'https://jira.bnc.ca/browse/CIAM-150',
        apps: [
          { appId: 'APP7576', jiraKey: 'CIAM-150', jiraUrl: 'https://jira.bnc.ca/browse/CIAM-150', trainIds: ['t1'], error: null },
          { appId: 'APP7571', jiraKey: 'CIAM-150', jiraUrl: 'https://jira.bnc.ca/browse/CIAM-150', trainIds: ['t2'], error: null },
        ],
      }, { status: 202 })),
    )
    const user = userEvent.setup()
    renderSnyk()

    await user.click(await screen.findByRole('button', { name: /Fix vulnerabilities/ }))
    expect(await screen.findByText(/you’re good to go/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByText('com.fasterxml.jackson.core:jackson-databind')
    await user.click(screen.getByRole('button', { name: 'Select all' }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByText(/what Veritas will do/)
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await user.selectOptions(await screen.findByRole('combobox'), 'CIAM')
    await user.selectOptions((await screen.findAllByRole('combobox'))[1], 'CIAM-100')
    await waitFor(() => expect(screen.getAllByRole('combobox')).toHaveLength(3))
    await user.selectOptions(screen.getAllByRole('combobox')[2], 'CIAM-150')
    await user.click(screen.getByRole('button', { name: /Start 2 fixes/ }))

    // The wizard stays OPEN on a confirmation screen: the story key is a clickable Jira link (+ epic + a copy
    // control + a "Watch progress" action) — not a silent close that hides the created ticket number.
    const storyLink = await screen.findByRole('link', { name: /CIAM-150/ })
    expect(storyLink).toHaveAttribute('href', 'https://jira.bnc.ca/browse/CIAM-150')
    expect(screen.getByRole('link', { name: /CIAM-100/ })).toHaveAttribute('href', 'https://jira.bnc.ca/browse/CIAM-100')
    expect(screen.getByRole('button', { name: /Watch progress/ })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /Copy/ }).length).toBeGreaterThan(0)
  })

  it('blocks Step 1 when a required connection is missing and links to Settings', async () => {
    twoFixableWatches()
    // Override preflight: Bitbucket base URL is MISSING → the gate must hold.
    server.use(
      http.get('*/api/v1/preflight', () => HttpResponse.json([
        { name: 'Snyk token', status: 'OK', detail: 'Configured.', remediation: '' },
        { name: 'Jira token', status: 'OK', detail: 'Configured.', remediation: '' },
        { name: 'Bitbucket base URL', status: 'MISSING', detail: 'Not set.', remediation: 'Set it.' },
      ])),
    )
    const user = userEvent.setup()
    renderSnyk()

    await user.click(await screen.findByRole('button', { name: /Fix vulnerabilities/ }))
    expect(await screen.findByText(/Some required connections aren’t ready/)).toBeInTheDocument()
    // Continue is disabled with a one-line reason, and a Settings link is offered.
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
    expect(screen.getByRole('link', { name: /Open Settings/ })).toHaveAttribute('href', '/settings')
  })

  it('files under a newly created epic when the user picks "Create a new epic"', async () => {
    let posted: Record<string, unknown> | null = null
    twoFixableWatches()
    server.use(
      http.post('*/api/v1/snyk/fixes/bulk', async ({ request }) => {
        posted = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({
          epicKey: 'CIAM-200',
          storyKey: 'CIAM-250',
          apps: [{ appId: 'APP7576', jiraKey: 'CIAM-250', trainIds: ['t1'], error: null }],
        }, { status: 202 })
      }),
    )
    const user = userEvent.setup()
    renderSnyk()

    await user.click(await screen.findByRole('button', { name: /Fix vulnerabilities/ }))
    await user.click(await screen.findByRole('button', { name: 'Continue' }))   // step 1 → 2

    // Step 2: the "Critical" shortcut ticks only the critical issue (one app).
    await screen.findByText('com.fasterxml.jackson.core:jackson-databind')
    await user.click(screen.getByRole('button', { name: 'Critical' }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))   // step 2 → 3
    await user.click(await screen.findByRole('button', { name: 'Continue' }))   // step 3 → 4

    // Step 4: choose the project, then "Create a new epic" — a brand-new epic has no existing stories, so the
    // Story section defaults to "create" with a default title too (both filled in), then Start.
    await user.selectOptions(await screen.findByRole('combobox'), 'CIAM')
    await user.click(screen.getByLabelText('Create a new epic'))
    await user.click(screen.getByRole('button', { name: /Start 1 fix/ }))

    expect(await screen.findByText(/filed under CIAM-250/)).toBeInTheDocument()
    expect(posted).toMatchObject({ project: 'CIAM', createEpic: true, createStory: true })
    expect((posted as unknown as { epicSummary: string }).epicSummary).toBe('Dependency security remediation')
    expect((posted as unknown as { storySummary: string }).storySummary).toBe('Dependency security fixes')
    expect((posted as unknown as { epicKey?: string }).epicKey).toBeUndefined()
    expect((posted as unknown as { storyKey?: string }).storyKey).toBeUndefined()
  })

  it('adds reviewers only from the Bitbucket-user autocomplete (no free-typed usernames reach the request)', async () => {
    let posted: Record<string, unknown> | null = null
    twoFixableWatches()
    server.use(
      http.post('*/api/v1/snyk/fixes/bulk', async ({ request }) => {
        posted = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({
          epicKey: 'CIAM-100', storyKey: 'CIAM-150',
          apps: [{ appId: 'APP7576', jiraKey: 'CIAM-150', trainIds: ['t1'], error: null }],
        }, { status: 202 })
      }),
    )
    const user = userEvent.setup()
    renderSnyk()

    await user.click(await screen.findByRole('button', { name: /Fix vulnerabilities/ }))
    await user.click(await screen.findByRole('button', { name: 'Continue' }))   // step 1 → 2
    await screen.findByText('com.fasterxml.jackson.core:jackson-databind')
    await user.click(screen.getByRole('button', { name: 'Critical' }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))   // step 2 → 3
    await user.click(await screen.findByRole('button', { name: 'Continue' }))   // step 3 → 4

    // Project → existing epic → existing story.
    await user.selectOptions(await screen.findByRole('combobox'), 'CIAM')
    const selects = await screen.findAllByRole('combobox')
    await user.selectOptions(selects[1], 'CIAM-100')
    await waitFor(() => expect(screen.getAllByRole('combobox')).toHaveLength(3))
    await user.selectOptions(screen.getAllByRole('combobox')[2], 'CIAM-150')

    // Type into the reviewer search → a matching Bitbucket user is suggested → click to add a chip.
    await user.type(screen.getByPlaceholderText('Search Bitbucket users…'), 'ali')
    await user.click(await screen.findByText('Alice Nguyen'))
    expect(await screen.findByText('alice')).toBeInTheDocument()   // the added chip

    await user.click(screen.getByRole('button', { name: /Start 1 fix/ }))
    await screen.findByText(/filed under CIAM-150/)
    // Only the picked username is sent — validated against Bitbucket, never a raw free-text string.
    expect((posted as unknown as { reviewers: string[] }).reviewers).toEqual(['alice'])
  })

  it('surfaces the backend error message when the bulk request fails (e.g. an unknown project)', async () => {
    twoFixableWatches()
    server.use(
      http.post('*/api/v1/snyk/fixes/bulk', () =>
        HttpResponse.json({ detail: 'Jira project CIAM has no create screen for Epic.', status: 400 }, { status: 400 })),
    )
    const user = userEvent.setup()
    renderSnyk()

    await user.click(await screen.findByRole('button', { name: /Fix vulnerabilities/ }))
    await user.click(await screen.findByRole('button', { name: 'Continue' }))   // step 1 → 2
    await screen.findByText('com.fasterxml.jackson.core:jackson-databind')
    await user.click(screen.getByRole('button', { name: 'Select all' }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))   // step 2 → 3
    await user.click(await screen.findByRole('button', { name: 'Continue' }))   // step 3 → 4
    await user.selectOptions(await screen.findByRole('combobox'), 'CIAM')
    const selects = await screen.findAllByRole('combobox')
    await user.selectOptions(selects[1], 'CIAM-100')
    await waitFor(() => expect(screen.getAllByRole('combobox')).toHaveLength(3))
    await user.selectOptions(screen.getAllByRole('combobox')[2], 'CIAM-150')
    await user.click(screen.getByRole('button', { name: /Start 2 fixes/ }))

    // The backend's human-readable 4xx detail is surfaced verbatim in a toast.
    expect(await screen.findByText('Jira project CIAM has no create screen for Epic.')).toBeInTheDocument()
  })

  it('reports a partial failure honestly when one application could not be launched', async () => {
    twoFixableWatches()
    server.use(
      http.post('*/api/v1/snyk/fixes/bulk', () => HttpResponse.json({
        epicKey: 'CIAM-100',
        storyKey: 'CIAM-150',
        apps: [
          { appId: 'APP7576', jiraKey: 'CIAM-150', trainIds: ['t1'], error: null },
          { appId: 'APP7571', jiraKey: null, trainIds: [], error: 'No Bitbucket project APP7571.' },
        ],
      }, { status: 202 })),
    )
    const user = userEvent.setup()
    renderSnyk()

    await user.click(await screen.findByRole('button', { name: /Fix vulnerabilities/ }))
    await user.click(await screen.findByRole('button', { name: 'Continue' }))
    await screen.findByText('com.fasterxml.jackson.core:jackson-databind')
    await user.click(screen.getByRole('button', { name: 'Select all' }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await user.click(await screen.findByRole('button', { name: 'Continue' }))
    await user.selectOptions(await screen.findByRole('combobox'), 'CIAM')
    const selects = await screen.findAllByRole('combobox')
    await user.selectOptions(selects[1], 'CIAM-100')
    await waitFor(() => expect(screen.getAllByRole('combobox')).toHaveLength(3))
    await user.selectOptions(screen.getAllByRole('combobox')[2], 'CIAM-150')
    await user.click(screen.getByRole('button', { name: /Start 2 fixes/ }))

    // One of two apps started; the toast says so rather than pretending all is well.
    expect(await screen.findByText(/1 of 2 applications started; 1 had a problem/)).toBeInTheDocument()
  })

  it('disables the "Fix vulnerabilities" entry when no watched app has a fixable vulnerability', async () => {
    server.use(
      http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/watches', () => HttpResponse.json([{
        id: 'w1', orgId: 'o1', orgSlug: 'app7576', orgName: 'CIAM Profile', targetId: 't1',
        repoSlug: 'application-tests', enabled: true, critical: 0, high: 0, medium: 0, low: 0,
        fixable: 0, projectCount: 1, lastPolled: '2026-07-01T12:00:00.000Z',
      }])),
    )
    renderSnyk()
    const btn = await screen.findByRole('button', { name: /Fix vulnerabilities/ })
    expect(btn).toBeDisabled()
    expect(screen.getByText(/Nothing to fix right now/)).toBeInTheDocument()
  })
})
