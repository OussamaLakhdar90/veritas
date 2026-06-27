import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { GenerateApiTests } from '../pages/GenerateApiTests'

const repo = { slug: 'ciam-policies', name: 'CIAM Policies', description: '', defaultBranch: 'develop', projectKey: 'CIAM' }

const scratchPlan = {
  serviceName: 'ciam-policies', mode: 'SCRATCH', filesScanned: 0,
  items: [
    { status: 'GAP', method: 'POST', path: '/policies', signature: 'POST /policies', reason: 'No tests yet — generate one.' },
    { status: 'GAP', method: 'GET', path: '/policies/{id}', signature: 'GET /policies/{id}', reason: 'No tests yet — generate one.' },
  ],
}

function mock(plan: Record<string, unknown> = scratchPlan) {
  server.use(
    http.get('*/api/v1/repos', () => HttpResponse.json([repo])),
    http.get('*/api/v1/repos/:slug/branches', () => HttpResponse.json(['develop', 'main'])),
    http.post('*/api/v1/services/:service/test-gen/plan', () => HttpResponse.json(plan)),
  )
}

/** Drive the wizard to the plan step: app → find repos → pick service → next → see the plan. */
async function toPlan(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText('APP7571'), 'APP7571')
  await user.click(screen.getByRole('button', { name: /Find repos/ }))
  await user.selectOptions(await screen.findByRole('combobox'), 'ciam-policies')
  await user.click(screen.getByRole('button', { name: /Next/ }))
  await user.click(await screen.findByRole('button', { name: /See the plan/ }))
}

describe('Generate API Tests wizard', () => {
  it('walks service → destination → plan and shows the reconciliation', async () => {
    mock()
    const user = userEvent.setup()
    renderPage(<GenerateApiTests />, { path: '/generate-api-tests', route: '/generate-api-tests' })

    expect(screen.getByText('Which service do you want tests for?')).toBeInTheDocument()
    await toPlan(user)

    // Plan step: scratch banner, the two gaps as new tests, both selected by default.
    expect(await screen.findByText("Here's what we'd do")).toBeInTheDocument()
    expect(screen.getByText(/create a fresh set/)).toBeInTheDocument()
    expect(screen.getByText('POST /policies')).toBeInTheDocument()
    expect(screen.getByText('GET /policies/{id}')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Generate selected \(2\)/ })).toBeInTheDocument()
  })

  it('lets the user deselect a gap before generating', async () => {
    mock()
    const user = userEvent.setup()
    renderPage(<GenerateApiTests />, { path: '/generate-api-tests', route: '/generate-api-tests' })
    await toPlan(user)
    await screen.findByText("Here's what we'd do")

    await user.click(screen.getByLabelText('Select POST /policies'))
    expect(screen.getByRole('button', { name: /Generate selected \(1\)/ })).toBeInTheDocument()
  })

  it('shows the refactor banner and a covered row when an existing test repo is scanned', async () => {
    mock({
      serviceName: 'ciam-policies', mode: 'REFACTOR', filesScanned: 7,
      items: [
        { status: 'CURRENT', method: 'POST', path: '/policies', signature: 'POST /policies', existingRef: 'PolicyTest.java', reason: 'Covered by an existing test — leave as is.' },
        { status: 'GAP', method: 'GET', path: '/policies/{id}', signature: 'GET /policies/{id}', reason: 'Endpoint not covered — add a test.' },
        { status: 'ORPHAN', method: 'GET', path: '/legacy/export', signature: '/legacy/export', existingRef: 'LegacyTest.java', reason: 'A test points at a path not in the current API — review.' },
      ],
    })
    const user = userEvent.setup()
    renderPage(<GenerateApiTests />, { path: '/generate-api-tests', route: '/generate-api-tests' })
    await toPlan(user)

    expect(await screen.findByText(/Scanned 7 existing test files/)).toBeInTheDocument()
    expect(screen.getByText('covered')).toBeInTheDocument()
    expect(screen.getByText('flag')).toBeInTheDocument()
    // Only the single GAP is selectable/selected by default.
    expect(screen.getByRole('button', { name: /Generate selected \(1\)/ })).toBeInTheDocument()
  })

  it('generates the selected tests, then opens a PR only on an explicit click', async () => {
    const genRun = {
      id: 'cg-1', serviceName: 'ciam-policies', buildStatus: 'SKIPPED',
      filesWritten: JSON.stringify(['src/test/java/PolicyTest.java']),
      todos: JSON.stringify(['A valid policy id must exist before these run']),
    }
    let published = false
    mock()
    server.use(
      http.post('*/api/v1/services/:service/test-gen/generate', () => HttpResponse.json(genRun)),
      http.post('*/api/v1/codegen-runs/:id/publish', () => {
        published = true
        return HttpResponse.json({ ...genRun, prUrl: 'https://bitbucket.example/pr/42' })
      }),
    )
    const user = userEvent.setup()
    renderPage(<GenerateApiTests />, { path: '/generate-api-tests', route: '/generate-api-tests' })
    await toPlan(user)
    await screen.findByText("Here's what we'd do")

    // Generate the two gaps → lands on the review step with the files + the not-compiled note.
    await user.click(screen.getByRole('button', { name: /Generate selected \(2\)/ }))
    expect(await screen.findByText('Review & open a pull request')).toBeInTheDocument()
    expect(screen.getByText('src/test/java/PolicyTest.java')).toBeInTheDocument()
    expect(screen.getByText(/weren't compiled here/)).toBeInTheDocument()
    expect(screen.getByText(/A valid policy id must exist/)).toBeInTheDocument()

    // No PR yet — pushing is a separate, explicit click.
    expect(published).toBe(false)
    await user.click(screen.getByRole('button', { name: /Open pull request/ }))
    expect(await screen.findByText('https://bitbucket.example/pr/42')).toBeInTheDocument()
    expect(published).toBe(true)
  })

  it('surfaces an error toast when the plan request fails', async () => {
    server.use(
      http.get('*/api/v1/repos', () => HttpResponse.json([repo])),
      http.get('*/api/v1/repos/:slug/branches', () => HttpResponse.json(['develop'])),
      http.post('*/api/v1/services/:service/test-gen/plan', () =>
        HttpResponse.json({ detail: 'Could not clone the repo', status: 500 }, { status: 500 })),
    )
    const user = userEvent.setup()
    renderPage(<GenerateApiTests />, { path: '/generate-api-tests', route: '/generate-api-tests' })
    await toPlan(user)

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    // Stayed on the destination step — the failure didn't advance to a blank plan.
    expect(screen.getByText('Where are the tests?')).toBeInTheDocument()
  })
})
