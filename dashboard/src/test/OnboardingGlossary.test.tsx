import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { Onboarding } from '../pages/Onboarding'
import { Glossary } from '../pages/Glossary'

const check = (over: Record<string, unknown> = {}) => ({
  name: 'Bitbucket', status: 'OK', detail: 'reachable', remediation: '', ...over,
})

function renderOnboarding() {
  return renderPage(<Onboarding />, {
    extraRoutes: [
      { path: '/settings', element: <div>settings-page</div> },
      { path: '/repos', element: <div>repos-page</div> },
    ],
  })
}

describe('Onboarding', () => {
  it('renders the welcome header and the three setup steps', async () => {
    // copilot/status comes from the base handler (authenticated: true). Stub preflight empty.
    server.use(http.get('*/api/v1/preflight', () => HttpResponse.json([])))
    renderOnboarding()

    expect(await screen.findByText('Welcome to Veritas')).toBeInTheDocument()
    expect(screen.getByText('Sign in to GitHub Copilot')).toBeInTheDocument()
    expect(screen.getByText('Connect Bitbucket')).toBeInTheDocument()
    expect(screen.getByText('Connect Jira / Xray / Confluence')).toBeInTheDocument()
  })

  it('shows "Almost there" when preflight has unresolved checks', async () => {
    server.use(
      http.get('*/api/v1/preflight', () =>
        HttpResponse.json([check({ name: 'Bitbucket', status: 'FAIL' })])),
    )
    renderOnboarding()

    expect(await screen.findByText('Almost there')).toBeInTheDocument()
    expect(
      screen.getByText('Finish the steps above, then run your first validation.'),
    ).toBeInTheDocument()
  })

  it('shows "You\'re ready" when every check is OK and Copilot is authenticated', async () => {
    server.use(
      http.get('*/api/v1/preflight', () =>
        HttpResponse.json([check({ name: 'Bitbucket', status: 'OK' })])),
      http.get('*/api/v1/settings/copilot/status', () =>
        HttpResponse.json({ authenticated: true, connected: true })),
    )
    renderOnboarding()

    expect(await screen.findByText("You're ready")).toBeInTheDocument()
    expect(
      screen.getByText('Run your first contract validation to see findings and an executive report.'),
    ).toBeInTheDocument()
  })

  it('keeps "Almost there" when preflight passes but Copilot is not authenticated', async () => {
    server.use(
      http.get('*/api/v1/preflight', () =>
        HttpResponse.json([check({ name: 'Bitbucket', status: 'OK' })])),
      http.get('*/api/v1/settings/copilot/status', () =>
        HttpResponse.json({ authenticated: false, connected: false })),
    )
    renderOnboarding()

    // header renders first; the ready banner resolves to the "not ready" copy
    expect(await screen.findByText('Almost there')).toBeInTheDocument()
  })

  it('still renders the page when preflight errors (no crash, steps shown)', async () => {
    server.use(
      http.get('*/api/v1/preflight', () =>
        HttpResponse.json({ detail: 'boom', status: 500 }, { status: 500 })),
    )
    renderOnboarding()

    expect(await screen.findByText('Welcome to Veritas')).toBeInTheDocument()
    // preflight failed → no checks → "Almost there"
    expect(await screen.findByText('Almost there')).toBeInTheDocument()
  })

  it('navigates to Settings from a step action', async () => {
    server.use(http.get('*/api/v1/preflight', () => HttpResponse.json([])))
    const user = userEvent.setup()
    renderOnboarding()

    expect(await screen.findByText('Welcome to Veritas')).toBeInTheDocument()
    await user.click(screen.getAllByRole('link', { name: /Open Settings/ })[0])
    expect(await screen.findByText('settings-page')).toBeInTheDocument()
  })

  it('navigates to repos via "Validate a contract"', async () => {
    server.use(http.get('*/api/v1/preflight', () => HttpResponse.json([])))
    const user = userEvent.setup()
    renderOnboarding()

    expect(await screen.findByText('Welcome to Veritas')).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: /Validate a contract/ }))
    expect(await screen.findByText('repos-page')).toBeInTheDocument()
  })
})

describe('Glossary', () => {
  it('renders the header and the intro explainer', () => {
    renderPage(<Glossary />)

    expect(screen.getByText('Glossary')).toBeInTheDocument()
    expect(screen.getByText(/code actually does/)).toBeInTheDocument()
  })

  it('renders every section heading', () => {
    renderPage(<Glossary />)

    for (const title of [
      'The bottom line',
      'Severity',
      'Confidence',
      'Analysis area',
      'Review status',
      'Report metrics',
    ]) {
      expect(screen.getByText(title)).toBeInTheDocument()
    }
  })

  it('renders the bottom-line verdict chips and severity terms', () => {
    renderPage(<Glossary />)

    expect(screen.getByText('Proceed')).toBeInTheDocument()
    expect(screen.getByText('Hold for fixes')).toBeInTheDocument()
    expect(screen.getByText('Do not release')).toBeInTheDocument()
    expect(screen.getByText('Blocker')).toBeInTheDocument()
    expect(screen.getByText('Critical')).toBeInTheDocument()
    expect(screen.getByText('Info')).toBeInTheDocument()
  })

  it('renders the plain-language analysis-area labels (never the L1–L6 codes)', () => {
    renderPage(<Glossary />)

    expect(screen.getByText('API completeness')).toBeInTheDocument()
    expect(screen.getByText('Signature accuracy')).toBeInTheDocument()
    expect(screen.getByText('Test coverage')).toBeInTheDocument()
    // the internal codes are deliberately never shown to the user
    expect(screen.queryByText('L1')).not.toBeInTheDocument()
    expect(screen.queryByText('L6')).not.toBeInTheDocument()
  })

  it('renders the report-metric terms', () => {
    renderPage(<Glossary />)

    expect(screen.getByText('Release quality gate')).toBeInTheDocument()
    expect(screen.getByText('Analysis coverage')).toBeInTheDocument()
    expect(screen.getByText('Est. analysis cost')).toBeInTheDocument()
  })
})