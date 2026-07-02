import { describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { Settings } from '../pages/Settings'

/* ── Fixtures ──────────────────────────────────────────────────────────────
 * The page fans out five GETs on mount (preflight, connections, secrets, llm,
 * copilot status). The CopilotAuthProvider in render.tsx ALSO reads llm +
 * copilot status (same query keys). The base handlers already stub llm = mock
 * and copilot = connected; per-test we add the rest. Every endpoint a flow
 * touches must be stubbed — setup.ts fails the test on any unhandled request.
 */
const preflight = [
  { name: 'Bitbucket connection', status: 'OK', detail: 'Reachable and authenticated.', remediation: '' },
  { name: 'Jira connection', status: 'WARN', detail: 'No token configured.', remediation: 'Add a token in Settings' },
  { name: 'Copilot sign-in', status: 'FAIL', detail: 'Not signed in.', remediation: 'Sign in below' },
]

const connections = {
  bitbucket: { baseUrl: 'https://bitbucket.org', edition: 'CLOUD', workspace: 'bnc', authType: 'APP_PASSWORD' },
  jira: { baseUrl: 'https://jira.bnc.ca', edition: 'SERVER_DC', authType: 'BEARER' },
  xray: { baseUrl: '', edition: 'SERVER_DC', authType: 'BEARER' },
  confluence: { baseUrl: '', edition: 'CLOUD', authType: 'BEARER' },
  snyk: { baseUrl: 'https://api.snyk.io', authType: 'BEARER' },
}

const secrets = { GIT_TOKEN: true, GIT_USERNAME: false, JIRA_API_TOKEN: false, JIRA_USERNAME: false }

/** Stub the five mount GETs. Pass overrides to vary a single response. */
function mountGets(over: {
  preflight?: unknown
  connections?: unknown
  secrets?: unknown
  llm?: Record<string, unknown>
  copilot?: Record<string, unknown>
} = {}) {
  server.use(
    http.get('*/api/v1/preflight', () => HttpResponse.json(over.preflight ?? preflight)),
    http.get('*/api/v1/settings/connections', () => HttpResponse.json(over.connections ?? connections)),
    http.get('*/api/v1/settings/secrets', () => HttpResponse.json(over.secrets ?? secrets)),
  )
  if (over.llm) server.use(http.get('*/api/v1/settings/llm', () => HttpResponse.json(over.llm)))
  if (over.copilot) server.use(http.get('*/api/v1/settings/copilot/status', () => HttpResponse.json(over.copilot)))
}

/** The card whose <h3> heading is exactly `title` (synchronous — heading must already be mounted). */
function card(title: string): HTMLElement {
  const heading = screen.getByRole('heading', { name: title })
  // CardHeader → Card: the heading lives inside the Card's rounded-xl root.
  return heading.closest('div.rounded-xl') as HTMLElement
}

/** Await a card whose heading appears asynchronously (service cards mount after connections + secrets load). */
async function findCard(title: string): Promise<HTMLElement> {
  const heading = await screen.findByRole('heading', { name: title })
  return heading.closest('div.rounded-xl') as HTMLElement
}

describe('Settings page', () => {
  it('renders the setup checklist with OK / WARN / FAIL rows from /preflight', async () => {
    mountGets()
    renderPage(<Settings />)

    expect(await screen.findByText('Bitbucket connection')).toBeInTheDocument()
    // The WARN row appends the remediation after an em dash.
    expect(screen.getByText('No token configured. — Add a token in Settings')).toBeInTheDocument()
    expect(screen.getByText('Not signed in. — Sign in below')).toBeInTheDocument()
  })

  it('shows a spinner while the checklist is loading, then the rows', async () => {
    // preflight stays pending so the Spinner shows; the rest resolve so the page mounts.
    let releasePreflight: (() => void) | undefined
    const gate = new Promise<void>((r) => { releasePreflight = r })
    server.use(
      http.get('*/api/v1/preflight', async () => { await gate; return HttpResponse.json(preflight) }),
      http.get('*/api/v1/settings/connections', () => HttpResponse.json(connections)),
      http.get('*/api/v1/settings/secrets', () => HttpResponse.json(secrets)),
    )
    renderPage(<Settings />)

    // The checklist card is present immediately but its body is just the spinner.
    expect(await screen.findByText('Setup checklist')).toBeInTheDocument()
    expect(screen.queryByText('Bitbucket connection')).not.toBeInTheDocument()
    releasePreflight!()
    expect(await screen.findByText('Bitbucket connection')).toBeInTheDocument()
  })

  it('renders the per-service connection cards (incl. Snyk) once connections + secrets load', async () => {
    mountGets()
    renderPage(<Settings />)

    expect(await screen.findByRole('heading', { name: 'Bitbucket' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Jira' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Xray' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Confluence' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Snyk' })).toBeInTheDocument()
    // The persisted base URL is reflected in the input value.
    expect(within(card('Bitbucket')).getByDisplayValue('https://bitbucket.org')).toBeInTheDocument()
    // The Snyk card is the SaaS-token variant: base URL + a single API-token field, no edition/workspace/auth.
    const snykCard = card('Snyk')
    expect(within(snykCard).getByDisplayValue('https://api.snyk.io')).toBeInTheDocument()
    expect(within(snykCard).queryByRole('combobox')).not.toBeInTheDocument()
  })

  it('falls back to an error message when connections fail to load', async () => {
    server.use(
      http.get('*/api/v1/preflight', () => HttpResponse.json(preflight)),
      http.get('*/api/v1/settings/connections', () =>
        HttpResponse.json({ detail: 'boom', status: 500 }, { status: 500 })),
      http.get('*/api/v1/settings/secrets', () => HttpResponse.json(secrets)),
    )
    renderPage(<Settings />)

    expect(await screen.findByText('Could not load settings.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Bitbucket' })).not.toBeInTheDocument()
  })

  it('LLM engine card surfaces mock mode and warns to switch to Copilot', async () => {
    mountGets()
    renderPage(<Settings />)

    expect(await screen.findByText('LLM engine')).toBeInTheDocument()
    // The simulated badge + the mock-mode warning banner.
    expect(screen.getByText('Mock · simulated')).toBeInTheDocument()
    expect(screen.getByText(/skill results are simulated/)).toBeInTheDocument()
  })

  it('switching the engine to Copilot saves and shows the restart-required toast', async () => {
    mountGets()
    server.use(
      http.put('*/api/v1/settings/llm', () =>
        HttpResponse.json({ applied: false, restartRequiredFields: ['engine'] })),
    )
    const user = userEvent.setup()
    renderPage(<Settings />)

    const engineCard = card('LLM engine')
    const select = within(engineCard).getByRole('combobox')
    // Default desired = mock → Save is disabled until we pick a different engine.
    const save = within(engineCard).getByRole('button', { name: 'Save' })
    expect(save).toBeDisabled()

    await user.selectOptions(select, 'http')
    expect(save).toBeEnabled()
    // A pending-restart hint appears once desired ≠ active.
    expect(screen.getByText(/will be active after the next restart/)).toBeInTheDocument()

    await user.click(save)
    expect(await screen.findByText('Saved — restart Veritas to switch the engine.')).toBeInTheDocument()
  })

  it('Copilot card shows signed-in state and signs out', async () => {
    mountGets()
    server.use(http.post('*/api/v1/settings/copilot/signout', () => new HttpResponse(null, { status: 204 })))
    const user = userEvent.setup()
    renderPage(<Settings />)

    const copilotCard = card('GitHub Copilot')
    expect(await within(copilotCard).findByText('Signed in')).toBeInTheDocument()

    await user.click(within(copilotCard).getByRole('button', { name: 'Sign out' }))
    expect(await screen.findByText('Signed out of Copilot.')).toBeInTheDocument()
  })

  it('Copilot card device-flow sign-in opens the modal with the user code', async () => {
    // Not signed in → the "Sign in with GitHub" button is shown.
    mountGets({ copilot: { authenticated: false, connected: false } })
    server.use(
      http.post('*/api/v1/settings/copilot/login/start', () =>
        HttpResponse.json({ id: 'dev-1', userCode: 'ABCD-1234', verificationUri: 'https://github.com/login/device', expiresIn: 900 })),
      // The modal polls status; PENDING keeps it on screen without needing fake timers.
      http.get('*/api/v1/settings/copilot/login/status', () =>
        HttpResponse.json({ state: 'PENDING', message: 'waiting' })),
    )
    const user = userEvent.setup()
    renderPage(<Settings />)

    const copilotCard = card('GitHub Copilot')
    await user.click(await within(copilotCard).findByRole('button', { name: 'Sign in with GitHub' }))

    // The shared device-flow modal renders the user code + verification link.
    expect(await screen.findByRole('dialog', { name: 'Sign in to GitHub Copilot' })).toBeInTheDocument()
    expect(screen.getByText('ABCD-1234')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'https://github.com/login/device' })).toBeInTheDocument()
  })

  it('saving a service card PUTs connections, writes the typed token, and toasts', async () => {
    mountGets()
    let putBody: unknown
    let secretBody: unknown
    server.use(
      // ServiceCard.save() re-reads the full config before merging — already covered by mountGets, but make
      // it explicit it stays stable.
      http.put('*/api/v1/settings/connections', async ({ request }) => {
        putBody = await request.json()
        return HttpResponse.json({ applied: true, restartRequiredFields: [] })
      }),
      http.post('*/api/v1/settings/secrets', async ({ request }) => {
        secretBody = await request.json()
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const user = userEvent.setup()
    renderPage(<Settings />)

    const jiraCard = await findCard('Jira')
    await within(jiraCard).findByDisplayValue('https://jira.bnc.ca')

    // Edit the base URL and type a new token, then Save.
    const url = within(jiraCard).getByDisplayValue('https://jira.bnc.ca')
    await user.clear(url)
    await user.type(url, 'https://jira.example.com')
    // The secret field for Jira (BEARER → labelled "HTTP access token (PAT)"); it's the only password input here.
    const tokenField = within(jiraCard).getByDisplayValue('') as HTMLInputElement
    // Guard: the empty input we grabbed is the password token, not the URL.
    expect(tokenField.type).toBe('password')
    await user.type(tokenField, 'pat-secret-xyz')

    await user.click(within(jiraCard).getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Saved.')).toBeInTheDocument()
    expect((putBody as { jira: { baseUrl: string } }).jira.baseUrl).toBe('https://jira.example.com')
    expect(secretBody).toEqual({ key: 'JIRA_API_TOKEN', value: 'pat-secret-xyz' })
  })

  it('saving with a restart-required field surfaces the restart toast', async () => {
    mountGets()
    server.use(
      http.put('*/api/v1/settings/connections', () =>
        HttpResponse.json({ applied: false, restartRequiredFields: ['BITBUCKET_BASE_URL'] })),
    )
    const user = userEvent.setup()
    renderPage(<Settings />)

    const bbCard = await findCard('Bitbucket')
    await within(bbCard).findByDisplayValue('https://bitbucket.org')
    await user.click(within(bbCard).getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(/Restart Veritas to apply: BITBUCKET_BASE_URL\./)).toBeInTheDocument()
  })

  it('Test connection shows the authenticated pill from the result', async () => {
    mountGets()
    server.use(
      http.post('*/api/v1/settings/connections/:service/test', () =>
        HttpResponse.json({ service: 'bitbucket', reachable: true, authenticated: true, status: 200, message: 'Authenticated as bnc-bot' })),
    )
    const user = userEvent.setup()
    renderPage(<Settings />)

    const bbCard = await findCard('Bitbucket')
    await within(bbCard).findByDisplayValue('https://bitbucket.org')
    await user.click(within(bbCard).getByRole('button', { name: /Test connection/ }))

    expect(await within(bbCard).findByText('Authenticated as bnc-bot')).toBeInTheDocument()
  })

  it('Test connection surfaces an error toast when the test request fails', async () => {
    mountGets()
    server.use(
      http.post('*/api/v1/settings/connections/:service/test', () =>
        HttpResponse.json({ detail: 'Connection refused', status: 502 }, { status: 502 })),
    )
    const user = userEvent.setup()
    renderPage(<Settings />)

    const jiraCard = await findCard('Jira')
    await within(jiraCard).findByDisplayValue('https://jira.bnc.ca')
    await user.click(within(jiraCard).getByRole('button', { name: /Test connection/ }))

    // A 5xx is rewritten to the friendly server-error sentence and shown as an error toast.
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('changing a Bitbucket edition to Server/DC reveals the per-edition note and drops Workspace', async () => {
    mountGets()
    const user = userEvent.setup()
    renderPage(<Settings />)

    const bbCard = await findCard('Bitbucket')
    await within(bbCard).findByDisplayValue('https://bitbucket.org')
    // Cloud edition → Workspace field is present.
    expect(within(bbCard).getByDisplayValue('bnc')).toBeInTheDocument()

    // The Edition select is the one currently showing the Cloud label.
    const editionSelect = within(bbCard).getByDisplayValue('Cloud (hosted, e.g. bitbucket.org)')
    await user.selectOptions(editionSelect, 'SERVER_DC')

    // Server/DC note appears and the Workspace input is gone.
    expect(await within(bbCard).findByText(/the project key \(app-id\) is entered on the Validate screen/)).toBeInTheDocument()
    expect(within(bbCard).queryByDisplayValue('bnc')).not.toBeInTheDocument()
  })
})