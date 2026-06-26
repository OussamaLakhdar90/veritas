import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { Reviews } from '../pages/Reviews'

const result = (over: Record<string, unknown> = {}) => ({
  id: 'rev-1',
  targetKey: 'CIAM-101',
  verdict: 'PASS',
  score: 87,
  confidence: 92.4,
  ...over,
})

function renderReviews() {
  return renderPage(<Reviews />)
}

describe('Reviews page', () => {
  it('renders the form and the empty state before any review is run', () => {
    renderReviews()

    expect(screen.getByText('Review test cases')).toBeInTheDocument()
    expect(screen.getByText('New review')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('project = CIAM AND issuetype = Test')).toBeInTheDocument()
    // The right-hand panel starts on the "no review yet" empty state.
    expect(screen.getByText('No review yet')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Run review/ })).toBeInTheDocument()
  })

  it('runs a review by JQL (POST /reviews) and lists the per-test verdicts', async () => {
    server.use(
      http.post('*/api/v1/reviews', () => HttpResponse.json([result(), result({ id: 'rev-2', targetKey: 'CIAM-102', verdict: 'FAIL', score: 41, confidence: 70 })])),
    )
    const user = userEvent.setup()
    renderReviews()

    await user.type(screen.getByPlaceholderText('project = CIAM AND issuetype = Test'), 'project = CIAM')
    await user.click(screen.getByRole('button', { name: /Run review/ }))

    // Success toast reflects the count, and the results table renders each verdict + score.
    expect(await screen.findByText('Reviewed 2 tests.')).toBeInTheDocument()
    expect(screen.getByText('Results (2)')).toBeInTheDocument()
    expect(screen.getByText('CIAM-101')).toBeInTheDocument()
    expect(screen.getByText('PASS')).toBeInTheDocument()
    expect(screen.getByText('87')).toBeInTheDocument()
    expect(screen.getByText('92%')).toBeInTheDocument()
    expect(screen.getByText('CIAM-102')).toBeInTheDocument()
    expect(screen.getByText('FAIL')).toBeInTheDocument()
  })

  it('singularises the toast when exactly one test is reviewed', async () => {
    server.use(http.post('*/api/v1/reviews', () => HttpResponse.json([result()])))
    const user = userEvent.setup()
    renderReviews()

    await user.type(screen.getByPlaceholderText('project = CIAM AND issuetype = Test'), 'key = CIAM-101')
    await user.click(screen.getByRole('button', { name: /Run review/ }))

    expect(await screen.findByText('Reviewed 1 test.')).toBeInTheDocument()
    expect(screen.getByText('Results (1)')).toBeInTheDocument()
  })

  it('shows the "no tests matched" empty state when the JQL returns nothing', async () => {
    server.use(http.post('*/api/v1/reviews', () => HttpResponse.json([])))
    const user = userEvent.setup()
    renderReviews()

    await user.type(screen.getByPlaceholderText('project = CIAM AND issuetype = Test'), 'project = NOPE')
    await user.click(screen.getByRole('button', { name: /Run review/ }))

    expect(await screen.findByText('Reviewed 0 tests.')).toBeInTheDocument()
    expect(screen.getByText('No tests matched')).toBeInTheDocument()
  })

  it('blocks submission with an error toast when the JQL is empty', async () => {
    const user = userEvent.setup()
    renderReviews()

    // No JQL typed → clicking Run review must not POST; it surfaces a guard toast instead.
    await user.click(screen.getByRole('button', { name: /Run review/ }))
    expect(await screen.findByText('Enter a JQL query.')).toBeInTheDocument()
    // Still on the initial empty state — nothing was reviewed.
    expect(screen.getByText('No review yet')).toBeInTheDocument()
  })

  it('runs the review on Enter inside the JQL field', async () => {
    server.use(http.post('*/api/v1/reviews', () => HttpResponse.json([result()])))
    const user = userEvent.setup()
    renderReviews()

    const input = screen.getByPlaceholderText('project = CIAM AND issuetype = Test')
    await user.type(input, 'project = CIAM{Enter}')

    expect(await screen.findByText('Reviewed 1 test.')).toBeInTheDocument()
    expect(screen.getByText('CIAM-101')).toBeInTheDocument()
  })

  it('surfaces an error toast when the review POST fails', async () => {
    server.use(
      http.post('*/api/v1/reviews', () =>
        HttpResponse.json({ detail: 'The database is unavailable', status: 500 }, { status: 500 })),
    )
    const user = userEvent.setup()
    renderReviews()

    await user.type(screen.getByPlaceholderText('project = CIAM AND issuetype = Test'), 'project = CIAM')
    await user.click(screen.getByRole('button', { name: /Run review/ }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    // No results table appeared — the failure didn't masquerade as success.
    expect(screen.queryByText(/^Results/)).not.toBeInTheDocument()
    expect(screen.getByText('No review yet')).toBeInTheDocument()
  })

  it('toggles the "post to Jira" checkbox and sends apply=true in the request body', async () => {
    let body: { jql?: string; apply?: boolean } = {}
    server.use(
      http.post('*/api/v1/reviews', async ({ request }) => {
        body = (await request.json()) as { jql?: string; apply?: boolean }
        return HttpResponse.json([result()])
      }),
    )
    const user = userEvent.setup()
    renderReviews()

    await user.type(screen.getByPlaceholderText('project = CIAM AND issuetype = Test'), 'project = CIAM')
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: /Run review/ }))

    expect(await screen.findByText('Reviewed 1 test.')).toBeInTheDocument()
    expect(body.apply).toBe(true)
    expect(body.jql).toBe('project = CIAM')
  })

  it('renders em-dashes for missing verdict/score/confidence fields', async () => {
    server.use(
      http.post('*/api/v1/reviews', () =>
        HttpResponse.json([{ id: 'rev-x' }])),
    )
    const user = userEvent.setup()
    renderReviews()

    await user.type(screen.getByPlaceholderText('project = CIAM AND issuetype = Test'), 'project = CIAM')
    await user.click(screen.getByRole('button', { name: /Run review/ }))

    expect(await screen.findByText('Results (1)')).toBeInTheDocument()
    // targetKey, verdict, score, confidence all absent → four em-dash cells.
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(4)
  })
})
