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
  it('shows recent reviews on load so prior verdicts are reopenable', async () => {
    server.use(http.get('*/api/v1/reviews/recent', () => HttpResponse.json([
      result({ id: 'rev-old', targetKey: 'CIAM-9', verdict: 'PASS', score: 90, confidence: 88 }),
    ])))
    renderReviews()
    expect(await screen.findByText('Recent reviews')).toBeInTheDocument()
    expect(screen.getByText('CIAM-9')).toBeInTheDocument()
  })

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
    expect(screen.getByText('Pass')).toBeInTheDocument()
    expect(screen.getByText('87')).toBeInTheDocument()
    expect(screen.getByText('92%')).toBeInTheDocument()
    expect(screen.getByText('CIAM-102')).toBeInTheDocument()
    expect(screen.getByText('Fail')).toBeInTheDocument()
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

  it('expands a result row to reveal the C1–C6 rubric, gaps and corrected steps', async () => {
    const deliverable = {
      score: 87, verdict: 'PASS',
      rubric: [{ criterion: 'C1 Atomicity', score: 4, note: 'one behaviour per test' }, { criterion: 'C3 Oracle', score: 2 }],
      gaps: [{ criterion: 'C4', issue: 'No negative path for expired token', citation: 'ISTQB-2.3' }],
      correctedSteps: [{ action: 'POST /policies with an expired token', data: 'token=EXPIRED', expected: '401 Unauthorized' }],
      selfReview: { confidence: 88, blindSpots: ['Concurrency not assessed'] },
    }
    server.use(http.post('*/api/v1/reviews', () =>
      HttpResponse.json([result({ deliverableJson: JSON.stringify(deliverable) })])))
    const user = userEvent.setup()
    renderReviews()

    await user.type(screen.getByPlaceholderText('project = CIAM AND issuetype = Test'), 'project = CIAM')
    await user.click(screen.getByRole('button', { name: /Run review/ }))
    expect(await screen.findByText('Results (1)')).toBeInTheDocument()

    // Detail is hidden until the row is expanded.
    expect(screen.queryByText('C1 Atomicity')).not.toBeInTheDocument()
    await user.click(screen.getByText('CIAM-101'))

    expect(await screen.findByText('C1 Atomicity')).toBeInTheDocument()
    expect(screen.getByText(/one behaviour per test/)).toBeInTheDocument()
    expect(screen.getByText(/No negative path for expired token/)).toBeInTheDocument()
    expect(screen.getByText('POST /policies with an expired token')).toBeInTheDocument()
    expect(screen.getByText('401 Unauthorized')).toBeInTheDocument()
    expect(screen.getByText('Concurrency not assessed')).toBeInTheDocument()
  })

  it('loads candidates by JQL, lets the user deselect one, and reviews only the selected subset', async () => {
    let body: { testKeys?: string[] } = {}
    server.use(
      http.get('*/api/v1/reviews/candidates', () => HttpResponse.json([
        { key: 'CIAM-101', summary: 'Create policy', testType: 'Manual', steps: 3 },
        { key: 'CIAM-102', summary: 'Get policy', testType: 'Manual', steps: 2 },
      ])),
      http.post('*/api/v1/reviews', async ({ request }) => {
        body = (await request.json()) as { testKeys?: string[] }
        return HttpResponse.json([result()])
      }),
    )
    const user = userEvent.setup()
    renderReviews()

    await user.type(screen.getByPlaceholderText('project = CIAM AND issuetype = Test'), 'project = CIAM')
    await user.click(screen.getByRole('button', { name: /Load tests/ }))

    // both candidates listed, all selected by default
    expect(await screen.findByText('Create policy')).toBeInTheDocument()
    expect(screen.getByText('2 of 2 selected')).toBeInTheDocument()

    // deselect CIAM-102 → review only CIAM-101
    await user.click(screen.getByLabelText('Select CIAM-102'))
    expect(screen.getByText('1 of 2 selected')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Review selected \(1\)/ }))

    expect(await screen.findByText('Reviewed 1 test.')).toBeInTheDocument()
    expect(body.testKeys).toEqual(['CIAM-101'])
  })

  it('shows an error toast when loading candidates returns none', async () => {
    server.use(http.get('*/api/v1/reviews/candidates', () => HttpResponse.json([])))
    const user = userEvent.setup()
    renderReviews()
    await user.type(screen.getByPlaceholderText('project = CIAM AND issuetype = Test'), 'project = NOPE')
    await user.click(screen.getByRole('button', { name: /Load tests/ }))
    expect(await screen.findByText('The JQL returned no Xray tests.')).toBeInTheDocument()
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
