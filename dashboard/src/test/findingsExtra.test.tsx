import { describe, expect, it } from 'vitest'
import { fireEvent, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { Findings } from '../pages/Findings'

// A small fixture set spanning severities, confidences, layers and endpoints so the filter / sort / select
// branches all have something distinct to assert on.
const blocker = {
  id: 'fnd-blocker', type: 'MISSING_ENDPOINT', severity: 'BLOCKER', layer: 'L1',
  endpoint: 'DELETE /accounts', summary: 'Code deletes accounts the spec never documents',
  status: 'OPEN', confidence: 'LOW',   // BLOCKER + LOW => riskyConfidence (warning icon)
}
const critical = {
  id: 'fnd-critical', type: 'EXTRA_ENDPOINT', severity: 'CRITICAL', layer: 'L2',
  endpoint: 'GET /policies', summary: 'Spec documents GET /policies but the code never serves it',
  status: 'OPEN', confidence: 'HIGH',
  codeFile: 'src/main/java/Controller.java', codeStartLine: 42,
  codeUrl: 'https://bitbucket.example/Controller.java#42',
}
const minor = {
  id: 'fnd-minor', type: 'SIGNATURE_MISMATCH', severity: 'MINOR', layer: 'L4',
  endpoint: 'POST /alpha', summary: 'Response field type differs from the spec',
  status: 'OPEN', confidence: 'MEDIUM',
}
const allThree = [blocker, critical, minor]

function renderFindings(data: unknown[]) {
  server.use(http.get('*/api/v1/scans/:id/findings', () => HttpResponse.json(data)))
  return renderPage(<Findings />, { path: '/findings/:scanId', route: '/findings/scan-1' })
}

// The endpoint cell carries a unique font-mono string per finding; reading rows by endpoint order lets us
// assert how sorting reorders the table.
function endpointOrder() {
  return screen.getAllByRole('row')
    .slice(1)   // drop the header row
    .map((r) => within(r).queryByText(/^(GET|POST|DELETE) /)?.textContent)
    .filter(Boolean) as string[]
}

describe('Findings — severity filter chips', () => {
  it('shows an "All N" chip plus a per-severity chip with its count', async () => {
    renderFindings(allThree)
    expect(await screen.findByText(critical.summary)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /All 3/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Blocker 1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Critical 1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Minor 1' })).toBeInTheDocument()
  })

  it('clicking a severity chip narrows the table to that severity', async () => {
    const user = userEvent.setup()
    renderFindings(allThree)
    expect(await screen.findByText(critical.summary)).toBeInTheDocument()
    // Pre-filter: all three summaries are present.
    expect(screen.getByText(blocker.summary)).toBeInTheDocument()
    expect(screen.getByText(minor.summary)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Critical 1' }))

    expect(screen.getByText(critical.summary)).toBeInTheDocument()
    expect(screen.queryByText(blocker.summary)).not.toBeInTheDocument()
    expect(screen.queryByText(minor.summary)).not.toBeInTheDocument()
  })

  it('does not render a chip for a severity that has no findings', async () => {
    renderFindings([critical])
    expect(await screen.findByText(critical.summary)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^MAJOR/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^INFO/ })).not.toBeInTheDocument()
  })
})

describe('Findings — sortable columns', () => {
  it('defaults to severity order (BLOCKER before CRITICAL before MINOR)', async () => {
    renderFindings(allThree)
    expect(await screen.findByText(critical.summary)).toBeInTheDocument()
    expect(endpointOrder()).toEqual(['DELETE /accounts', 'GET /policies', 'POST /alpha'])
  })

  it('clicking the Endpoint header sorts rows alphabetically, then reverses on a second click', async () => {
    const user = userEvent.setup()
    renderFindings(allThree)
    expect(await screen.findByText(critical.summary)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Endpoint/ }))
    expect(endpointOrder()).toEqual(['DELETE /accounts', 'GET /policies', 'POST /alpha'])

    await user.click(screen.getByRole('button', { name: /Endpoint/ }))
    expect(endpointOrder()).toEqual(['POST /alpha', 'GET /policies', 'DELETE /accounts'])
  })

  it('marks the active sort column via aria-sort', async () => {
    const user = userEvent.setup()
    renderFindings(allThree)
    expect(await screen.findByText(critical.summary)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Area/ }))
    const header = screen.getByRole('button', { name: /Area/ }).closest('th')!
    expect(header).toHaveAttribute('aria-sort', 'ascending')
  })
})

describe('Findings — risky confidence + evidence link', () => {
  it('flags a high-severity low-confidence finding with a verify-before-acting warning', async () => {
    renderFindings([blocker])
    expect(await screen.findByText(blocker.summary)).toBeInTheDocument()
    expect(screen.getByTitle(/only low-confidence here — verify before acting/i)).toBeInTheDocument()
    expect(screen.getByText('Low confidence')).toBeInTheDocument()
  })

  it('renders the code evidence as an external Bitbucket link when codeUrl is present', async () => {
    renderFindings([critical])
    expect(await screen.findByText(critical.summary)).toBeInTheDocument()
    const link = screen.getByRole('link', { name: /Controller\.java:42/ })
    expect(link).toHaveAttribute('href', critical.codeUrl)
    expect(link).toHaveAttribute('target', '_blank')
  })
})

describe('Findings — bulk select + bulk raise defect', () => {
  it('select-all ticks every raiseable row and surfaces the bulk action bar', async () => {
    const user = userEvent.setup()
    renderFindings(allThree)
    expect(await screen.findByText(critical.summary)).toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: 'Select all' }))

    expect(screen.getByText('3 selected')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Raise 3 defects/ })).toBeInTheDocument()
    // Every per-row checkbox is now checked.
    for (const cb of screen.getAllByRole('checkbox', { name: /^Select / })) {
      expect(cb).toBeChecked()
    }
  })

  it('a single per-row checkbox shows "1 selected" and a single-defect action', async () => {
    const user = userEvent.setup()
    renderFindings(allThree)
    expect(await screen.findByText(critical.summary)).toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: 'Select GET /policies' }))
    expect(screen.getByText('1 selected')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Raise 1 defects/ })).toBeInTheDocument()
  })

  it('bulk raise-defect POSTs a defect for each selected finding, then clears the selection', async () => {
    const posted: string[] = []
    server.use(
      http.post('*/api/v1/findings/:id/defect', ({ params }) => {
        posted.push(params.id as string)
        return HttpResponse.json({ jiraKey: 'CIAM-1', jiraUrl: 'https://jira/CIAM-1' })
      }),
    )
    const user = userEvent.setup()
    renderFindings(allThree)
    expect(await screen.findByText(critical.summary)).toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: 'Select all' }))
    await user.click(screen.getByRole('button', { name: /Raise 3 defects/ }))

    // Bulk modal opens with the project-key field.
    const key = await screen.findByPlaceholderText('CIAM')
    fireEvent.change(key, { target: { value: 'CIAM' } })
    await user.click(screen.getByRole('button', { name: /Create 3/ }))

    expect(await screen.findByText('Created 3 defects.')).toBeInTheDocument()
    expect(posted.sort()).toEqual([blocker.id, critical.id, minor.id].sort())
    // Selection bar is gone once the bulk modal reported done.
    expect(screen.queryByText('3 selected')).not.toBeInTheDocument()
  })
})

describe('Findings — single raise-defect DefectModal', () => {
  it('opens the modal, requires a project key, then POSTs the defect', async () => {
    let body: { projectKey?: string; issueType?: string } | null = null
    server.use(
      http.post('*/api/v1/findings/:id/defect', async ({ request }) => {
        body = (await request.json()) as { projectKey?: string; issueType?: string }
        return HttpResponse.json({ jiraKey: 'CIAM-9', jiraUrl: 'https://jira/CIAM-9' })
      }),
    )
    const user = userEvent.setup()
    renderFindings([critical])
    expect(await screen.findByText(critical.summary)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Raise defect/ }))
    expect(await screen.findByText('Raise a Jira defect')).toBeInTheDocument()
    // The single-defect modal previews the finding's endpoint.
    expect(within(screen.getByRole('dialog')).getByText(critical.endpoint)).toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('CIAM'), { target: { value: 'CIAM' } })
    await user.click(screen.getByRole('button', { name: /Create defect/ }))

    expect(await screen.findByText('Created 1 defect.')).toBeInTheDocument()
    expect(body).toEqual({ projectKey: 'CIAM', issueType: 'Bug' })
  })

  it('force-uppercases the project key as the reviewer types', async () => {
    const user = userEvent.setup()
    renderFindings([critical])
    expect(await screen.findByText(critical.summary)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Raise defect/ }))
    const input = await screen.findByPlaceholderText('CIAM')
    await user.type(input, 'c')   // a single lowercase keystroke
    expect(input).toHaveValue('C')
  })

  it('refuses an empty project key with an error toast instead of POSTing', async () => {
    let called = false
    server.use(http.post('*/api/v1/findings/:id/defect', () => {
      called = true
      return HttpResponse.json({ jiraKey: 'X', jiraUrl: 'y' })
    }))
    const user = userEvent.setup()
    renderFindings([critical])
    expect(await screen.findByText(critical.summary)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Raise defect/ }))
    expect(await screen.findByText('Raise a Jira defect')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Create defect/ }))

    expect(await screen.findByText('Enter a project key.')).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('surfaces a failure toast when the defect POST fails', async () => {
    server.use(http.post('*/api/v1/findings/:id/defect', () =>
      HttpResponse.json({ detail: 'Jira is unavailable' }, { status: 502 })))
    const user = userEvent.setup()
    renderFindings([critical])
    expect(await screen.findByText(critical.summary)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Raise defect/ }))
    expect(await screen.findByText('Raise a Jira defect')).toBeInTheDocument()
    fireEvent.change(screen.getByPlaceholderText('CIAM'), { target: { value: 'CIAM' } })
    await user.click(screen.getByRole('button', { name: /Create defect/ }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})