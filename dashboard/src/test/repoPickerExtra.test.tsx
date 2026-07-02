import { describe, expect, it, beforeEach } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { RepoPicker } from '../pages/RepoPicker'

// Two repos so the filter, the sort, and the favourites-pinning are all observable.
const repos = [
  { slug: 'ciam-policies', name: 'CIAM Policies', description: 'Policy service', defaultBranch: 'develop', projectKey: 'CIAM' },
  { slug: 'auth-gateway', name: 'Auth Gateway', description: 'Edge login proxy', defaultBranch: 'main', projectKey: 'CIAM' },
]

function stubRepos() {
  server.use(http.get('*/api/v1/repos', () => HttpResponse.json(repos)))
}
function stubBranches(list: string[] = ['develop', 'main', 'release/1.0']) {
  server.use(http.get('*/api/v1/repos/:slug/branches', () => HttpResponse.json(list)))
}

// Run a search for APP7571 and wait for the repo table to appear.
async function findRepos(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText('APP7571'), 'APP7571')
  await user.click(screen.getByRole('button', { name: /Find repos/ }))
  expect(await screen.findByText('ciam-policies')).toBeInTheDocument()
}

// Open the Validate modal for the first matching repo (alpha-sorted → auth-gateway is first).
async function openValidate(user: ReturnType<typeof userEvent.setup>, name = /^Validate$/) {
  const buttons = await screen.findAllByRole('button', { name })
  await user.click(buttons[0])
}

describe('RepoPicker — favourites, recents, and the filter', () => {
  beforeEach(() => {
    stubRepos()
    stubBranches()
    server.use(http.post('*/api/v1/scans', () => HttpResponse.json({ scanId: 'scan-1', status: 'RUNNING' }, { status: 202 })))
  })

  it('records the searched app-id as a recent chip and re-searches when clicked', async () => {
    const user = userEvent.setup()
    renderPage(<RepoPicker />)

    await findRepos(user)

    // The "Recent:" row now carries an APP7571 chip — clicking it re-runs the search.
    const recentChip = await screen.findByRole('button', { name: 'APP7571' })
    expect(recentChip).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('veritas-recent-appids') || '[]')).toContain('APP7571')

    await user.click(recentChip)
    expect(await screen.findByText('auth-gateway')).toBeInTheDocument()
  })

  it('pre-seeded recents render before any search and trigger a search on click', async () => {
    localStorage.setItem('veritas-recent-appids', JSON.stringify(['APP9000']))
    const user = userEvent.setup()
    renderPage(<RepoPicker />)

    const seeded = await screen.findByRole('button', { name: 'APP9000' })
    await user.click(seeded)
    expect(await screen.findByText('ciam-policies')).toBeInTheDocument()
  })

  it('pins a repo to the top (star) and persists it, then unpins it', async () => {
    const user = userEvent.setup()
    renderPage(<RepoPicker />)
    await findRepos(user)

    // Alpha order: auth-gateway, then ciam-policies. Pin ciam-policies via the star in its own row.
    const ciamRow = screen.getByText('ciam-policies').closest('tr') as HTMLElement
    await user.click(within(ciamRow).getByRole('button', { name: 'Pin' }))

    // Now persisted under the appId/slug key, and the star flips to "Unpin".
    expect(JSON.parse(localStorage.getItem('veritas-fav-repos') || '[]')).toContain('APP7571/ciam-policies')
    expect(within(ciamRow).getByRole('button', { name: 'Unpin' })).toBeInTheDocument()

    // The first data row is now ciam-policies (pinned floats to the top).
    const firstRowSlug = screen.getAllByRole('row')[1]
    expect(within(firstRowSlug).getByText('ciam-policies')).toBeInTheDocument()

    // Unpin it again — the key is removed from storage.
    await user.click(within(ciamRow).getByRole('button', { name: 'Unpin' }))
    expect(JSON.parse(localStorage.getItem('veritas-fav-repos') || '[]')).not.toContain('APP7571/ciam-policies')
  })

  it('filters the repo list by slug/description and shows the empty message for no match', async () => {
    const user = userEvent.setup()
    renderPage(<RepoPicker />)
    await findRepos(user)

    const filter = screen.getByLabelText('Filter repositories')
    // Matches by slug substring → only auth-gateway remains.
    await user.type(filter, 'gateway')
    expect(screen.queryByText('ciam-policies')).not.toBeInTheDocument()
    expect(screen.getByText('auth-gateway')).toBeInTheDocument()

    // Matches by description → only the policy service remains.
    await user.clear(filter)
    await user.type(filter, 'policy service')
    expect(await screen.findByText('ciam-policies')).toBeInTheDocument()
    expect(screen.queryByText('auth-gateway')).not.toBeInTheDocument()

    // No match → the "No repos match" row.
    await user.clear(filter)
    await user.type(filter, 'zzz-nothing')
    expect(await screen.findByText(/No repos match/)).toBeInTheDocument()
  })
})

describe('RepoPicker — Validate modal inputs', () => {
  beforeEach(() => {
    stubRepos()
    server.use(http.post('*/api/v1/scans', () => HttpResponse.json({ scanId: 'scan-1', status: 'RUNNING' }, { status: 202 })))
  })

  it('shows the discovered branches in a select and lets you pick one', async () => {
    stubBranches(['develop', 'main', 'release/1.0'])
    const user = userEvent.setup()
    renderPage(<RepoPicker />)
    await findRepos(user)
    await openValidate(user)

    expect(await screen.findByText(/Validate auth-gateway/)).toBeInTheDocument()
    // The branch control is a <select> (combobox) once branches loaded. auth-gateway's defaultBranch
    // is "main", which is in the list, so it's kept as the selected value.
    const branchSelect = await screen.findByDisplayValue('main')
    expect(branchSelect.tagName).toBe('SELECT')
    await user.selectOptions(branchSelect, 'release/1.0')
    expect((branchSelect as HTMLSelectElement).value).toBe('release/1.0')
  })

  it('falls back to a free-text branch input when no branches are returned', async () => {
    stubBranches([])
    const user = userEvent.setup()
    renderPage(<RepoPicker />)
    await findRepos(user)
    await openValidate(user)

    expect(await screen.findByText(/Validate auth-gateway/)).toBeInTheDocument()
    // No branch list → an <input> placeholder "master" is shown instead of a select.
    const branchInput = await screen.findByPlaceholderText('master')
    expect(branchInput.tagName).toBe('INPUT')
    expect(screen.getByText(/branch list unavailable/)).toBeInTheDocument()
  })

  it('switches the spec source kind and re-labels the reference field (LIVE_DOCS then CONFLUENCE)', async () => {
    stubBranches()
    const user = userEvent.setup()
    renderPage(<RepoPicker />)
    await findRepos(user)
    await openValidate(user)

    expect(await screen.findByText(/Validate auth-gateway/)).toBeInTheDocument()
    // Default REPO_PATH: a "Spec path" field pre-filled with openapi.yaml.
    expect(screen.getByText('Spec path')).toBeInTheDocument()
    expect(screen.getByDisplayValue('openapi.yaml')).toBeInTheDocument()

    const kind = screen.getByDisplayValue('File in the repo')
    await user.selectOptions(kind, 'LIVE_DOCS')
    // Field relabels to "API docs URL" and the ref resets (empty) → its placeholder shows.
    expect(await screen.findByText('API docs URL')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('https://service.bnc.ca/v3/api-docs')).toBeInTheDocument()
    expect(screen.queryByDisplayValue('openapi.yaml')).not.toBeInTheDocument()

    await user.selectOptions(kind, 'CONFLUENCE')
    expect(await screen.findByText('Confluence page (URL or ID)')).toBeInTheDocument()
  })

  it('blocks starting when the spec reference is empty (validation toast)', async () => {
    stubBranches()
    const user = userEvent.setup()
    renderPage(<RepoPicker />)
    await findRepos(user)
    await openValidate(user)

    expect(await screen.findByText(/Validate auth-gateway/)).toBeInTheDocument()
    // Switch to LIVE_DOCS → the ref empties; running now must complain instead of POSTing.
    await user.selectOptions(screen.getByDisplayValue('File in the repo'), 'LIVE_DOCS')
    await user.click(screen.getByRole('button', { name: /Run validation/ }))
    expect(await screen.findByText(/Enter the api docs url/i)).toBeInTheDocument()
  })

  it('lets you pick a Thoroughness option', async () => {
    stubBranches()
    const user = userEvent.setup()
    renderPage(<RepoPicker />)
    await findRepos(user)
    await openValidate(user)

    expect(await screen.findByText(/Validate auth-gateway/)).toBeInTheDocument()
    const thoroughness = screen.getByDisplayValue(/Standard — balanced/)
    await user.selectOptions(thoroughness, 'DEEP')
    expect((thoroughness as HTMLSelectElement).value).toBe('DEEP')
  })
})

describe('RepoPicker — failed scan and run-in-background', () => {
  beforeEach(() => {
    stubRepos()
    stubBranches()
  })

  it('surfaces a start error toast when POST /scans fails', async () => {
    server.use(http.post('*/api/v1/scans', () =>
      HttpResponse.json({ detail: 'Bitbucket token rejected', status: 403 }, { status: 403 })))
    const user = userEvent.setup()
    renderPage(<RepoPicker />)
    await findRepos(user)
    await openValidate(user)

    expect(await screen.findByText(/Validate auth-gateway/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Run validation/ }))
    expect(await screen.findByText(/Could not start the scan/)).toBeInTheDocument()
  })

  it('renders the FAILED panel (failedStage + errorMessage) and offers Try again', async () => {
    server.use(
      http.post('*/api/v1/scans', () => HttpResponse.json({ scanId: 'scan-9', status: 'RUNNING' }, { status: 202 })),
      // First poll already terminal-FAILED at the EXTRACTING step with a message.
      http.get('*/api/v1/scans/:id', () =>
        HttpResponse.json({
          id: 'scan-9', status: 'FAILED', stage: 'FAILED', failedStage: 'EXTRACTING',
          errorMessage: 'Could not parse the OpenAPI document', totalFindings: 0,
        })),
    )
    const user = userEvent.setup()
    renderPage(<RepoPicker />)
    await findRepos(user)
    await openValidate(user)

    expect(await screen.findByText(/Validate auth-gateway/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Run validation/ }))

    // The failed panel: heading (appears in the live header + the bottom panel), the backend message
    // (shown on the failed step row + the panel), and the inline "Adjust the inputs" link.
    expect((await screen.findAllByText('Validation failed')).length).toBeGreaterThan(0)
    expect(screen.getAllByText('Could not parse the OpenAPI document').length).toBeGreaterThan(0)
    expect(screen.getByText(/Adjust the inputs and try again/)).toBeInTheDocument()

    // The footer offers "Try again" → returns to the form (clears the scan id).
    await user.click(screen.getByRole('button', { name: /Try again/ }))
    expect(await screen.findByText('Spec path')).toBeInTheDocument()
  })

  it('clicking "Adjust the inputs and try again" also returns to the form', async () => {
    server.use(
      http.post('*/api/v1/scans', () => HttpResponse.json({ scanId: 'scan-8', status: 'RUNNING' }, { status: 202 })),
      http.get('*/api/v1/scans/:id', () =>
        HttpResponse.json({ id: 'scan-8', status: 'FAILED', stage: 'FAILED', failedStage: 'CLONING',
          errorMessage: 'Branch not found', totalFindings: 0 })),
    )
    const user = userEvent.setup()
    renderPage(<RepoPicker />)
    await findRepos(user)
    await openValidate(user)

    expect(await screen.findByText(/Validate auth-gateway/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Run validation/ }))

    expect((await screen.findAllByText('Branch not found')).length).toBeGreaterThan(0)
    await user.click(screen.getByText(/Adjust the inputs and try again/))
    expect(await screen.findByText('Spec path')).toBeInTheDocument()
  })

  it('closes the modal on "Run in background" — the server-truth Activity dock shows the running scan', async () => {
    server.use(
      http.post('*/api/v1/scans', () => HttpResponse.json({ scanId: 'scan-bg', status: 'RUNNING' }, { status: 202 })),
      // Stays RUNNING so the footer keeps the "Run in background" affordance.
      http.get('*/api/v1/scans/:id', () =>
        HttpResponse.json({ id: 'scan-bg', status: 'RUNNING', stage: 'EXTRACTING', startedAt: new Date().toISOString(), totalFindings: 0 })),
      // The Activity Center feed already knows about the running scan — no client-side tracking involved.
      http.get('*/api/v1/activity', () => HttpResponse.json([
        { id: 'scan-bg', type: 'SCAN', label: 'auth-gateway', status: 'RUNNING', stage: 'EXTRACTING',
          detail: '', needsAttention: false, startedAt: new Date().toISOString(), finishedAt: null,
          link: '/findings/scan-bg', acked: false },
      ])),
    )
    const user = userEvent.setup()
    renderPage(<RepoPicker />)
    await findRepos(user)
    await openValidate(user)

    expect(await screen.findByText(/Validate auth-gateway/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Run validation/ }))

    // Once RUNNING, the footer flips to "Run in background"; clicking just closes the modal.
    const bgButton = await screen.findByRole('button', { name: /Run in background/ })
    await user.click(bgButton)

    // The modal is gone, and the floating Activity dock card shows the scan (label + plain status),
    // straight from the server feed — no localStorage tracking anymore.
    expect(screen.queryByText(/Validating auth-gateway/)).not.toBeInTheDocument()
    const dock = (await screen.findByRole('button', { name: 'Dismiss' })).closest('div[class*="pointer-events-auto"]') as HTMLElement
    expect(within(dock).getByText('auth-gateway')).toBeInTheDocument()
    expect(within(dock).getByText('Running')).toBeInTheDocument()
  })
})