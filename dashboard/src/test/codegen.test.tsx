import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { Codegen } from '../pages/Codegen'

const run = (over: Record<string, unknown> = {}) => ({
  id: 'cg-1',
  serviceName: 'ciam-policies',
  templateSource: 'ciam-autotests/templates',
  buildStatus: 'PASS',
  filesWritten: JSON.stringify(['src/test/PolicyTest.java', 'src/test/AuthTest.java']),
  todos: JSON.stringify(['Create a policy with id POL-1 before running']),
  ...over,
})

function renderCodegen() {
  return renderPage(<Codegen />, { path: '/codegen', route: '/codegen' })
}

describe('Codegen — Generate tests workspace', () => {
  it('shows the loading state while the runs query is in flight', async () => {
    server.use(
      http.get('*/api/v1/codegen-runs', async () => {
        // Never-resolving (well past the assertion) keeps the query pending so the Loading branch renders.
        await new Promise((r) => setTimeout(r, 10_000))
        return HttpResponse.json([])
      }),
    )
    renderCodegen()
    expect(await screen.findByText(/Loading/)).toBeInTheDocument()
  })

  it('renders the empty state when there are no generation runs', async () => {
    server.use(http.get('*/api/v1/codegen-runs', () => HttpResponse.json([])))
    renderCodegen()
    expect(await screen.findByText('No generation runs yet')).toBeInTheDocument()
  })

  it('surfaces an error state when the runs query fails', async () => {
    server.use(
      http.get('*/api/v1/codegen-runs', () =>
        HttpResponse.json({ detail: 'The database is unavailable', status: 500 }, { status: 500 })),
    )
    renderCodegen()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('lists runs, and prompts to select one before any is picked', async () => {
    server.use(
      http.get('*/api/v1/codegen-runs', () =>
        HttpResponse.json([run(), run({ id: 'cg-2', serviceName: 'ciam-cards', buildStatus: 'FAIL' })])),
    )
    renderCodegen()

    expect(await screen.findByText('ciam-policies')).toBeInTheDocument()
    expect(screen.getByText('ciam-cards')).toBeInTheDocument()
    // Detail pane starts on the "pick a run" placeholder.
    expect(screen.getByText('Select a run')).toBeInTheDocument()
  })

  it('opens a run detail with its files, template and TODOs when selected', async () => {
    server.use(http.get('*/api/v1/codegen-runs', () => HttpResponse.json([run()])))
    const user = userEvent.setup()
    renderCodegen()

    await user.click(await screen.findByText('ciam-policies'))

    expect(await screen.findByText('Generated files')).toBeInTheDocument()
    expect(screen.getByText('src/test/PolicyTest.java')).toBeInTheDocument()
    expect(screen.getByText('src/test/AuthTest.java')).toBeInTheDocument()
    expect(screen.getByText(/Template: ciam-autotests\/templates/)).toBeInTheDocument()
    expect(screen.getByText('Create a policy with id POL-1 before running')).toBeInTheDocument()
  })

  it('blocks publishing with a toast when the repo slug is empty', async () => {
    server.use(http.get('*/api/v1/codegen-runs', () => HttpResponse.json([run()])))
    const user = userEvent.setup()
    renderCodegen()

    await user.click(await screen.findByText('ciam-policies'))
    await user.click(await screen.findByRole('button', { name: /Approve & open PR/ }))

    expect(await screen.findByText('Enter the output repo slug.')).toBeInTheDocument()
  })

  it('publishes a PR (POST) and renders the returned PR url with a success toast', async () => {
    const prUrl = 'https://bitbucket.example/pr/42'
    // onSuccess invalidates the list, so the refetched run must reflect the new PR for the detail pane.
    let published = false
    server.use(
      http.get('*/api/v1/codegen-runs', () => HttpResponse.json([published ? run({ prUrl }) : run()])),
      http.post('*/api/v1/codegen-runs/:id/publish', ({ request }) => {
        const url = new URL(request.url)
        // The page passes the typed slug + targetBranch=main + allowFailedBuild=false.
        expect(url.searchParams.get('repoSlug')).toBe('ciam-autotests')
        expect(url.searchParams.get('targetBranch')).toBe('main')
        expect(url.searchParams.get('allowFailedBuild')).toBe('false')
        published = true
        return HttpResponse.json(run({ prUrl }))
      }),
    )
    const user = userEvent.setup()
    renderCodegen()

    await user.click(await screen.findByText('ciam-policies'))
    await user.type(await screen.findByPlaceholderText('ciam-autotests'), 'ciam-autotests')
    await user.click(screen.getByRole('button', { name: /Approve & open PR/ }))

    expect(await screen.findByText('PR opened.')).toBeInTheDocument()
    expect(screen.getByText(prUrl)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: new RegExp(prUrl) })).toHaveAttribute('href', prUrl)
  })

  it('offers an override on a FAILED build and sends allowFailedBuild=true', async () => {
    let publishedAllowFailed: string | null = null
    server.use(
      http.get('*/api/v1/codegen-runs', () => HttpResponse.json([run({ buildStatus: 'FAIL' })])),
      http.post('*/api/v1/codegen-runs/:id/publish', ({ request }) => {
        publishedAllowFailed = new URL(request.url).searchParams.get('allowFailedBuild')
        return HttpResponse.json(run({ buildStatus: 'FAIL', prUrl: 'https://bitbucket.example/pr/99' }))
      }),
    )
    const user = userEvent.setup()
    renderCodegen()

    await user.click(await screen.findByText('ciam-policies'))
    await user.type(await screen.findByPlaceholderText('ciam-autotests'), 'ciam-autotests')
    await user.click(screen.getByRole('button', { name: /Override \(build failed\)/ }))

    expect(await screen.findByText('PR opened.')).toBeInTheDocument()
    expect(publishedAllowFailed).toBe('true')
  })

  it('surfaces an error toast when the publish POST fails', async () => {
    server.use(
      http.get('*/api/v1/codegen-runs', () => HttpResponse.json([run()])),
      http.post('*/api/v1/codegen-runs/:id/publish', () =>
        HttpResponse.json({ detail: 'Branch protection rejected the push', status: 422 }, { status: 422 })),
    )
    const user = userEvent.setup()
    renderCodegen()

    await user.click(await screen.findByText('ciam-policies'))
    await user.type(await screen.findByPlaceholderText('ciam-autotests'), 'ciam-autotests')
    await user.click(screen.getByRole('button', { name: /Approve & open PR/ }))

    expect(await screen.findByText('Branch protection rejected the push')).toBeInTheDocument()
  })
})
