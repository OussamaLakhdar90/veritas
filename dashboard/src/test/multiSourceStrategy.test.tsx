import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { MultiSourceStrategy } from '../pages/MultiSourceStrategy'

const preview = (over: Record<string, unknown> = {}) => ({
  snapshotId: 'snap-1',
  features: [
    { featureId: 'f1', displayName: 'Get policy', status: 'IMPLEMENTED', pinned: false,
      units: [{ id: 'CODE-1', source: 'CODE', type: 'ENDPOINT', title: 'GET /policies' }] },
    { featureId: 'f2', displayName: 'Create policy', status: 'PLANNED', pinned: false,
      units: [{ id: 'JIRA-1', source: 'JIRA', type: 'REQUIREMENT', title: 'Create' }] },
  ],
  gaps: [], mix: { code: true, jira: true, confluence: false },
  redactionCount: 0, fetchFailures: [], hardFail: false, estimatedCostUsd: 0.07, carryForwardNotes: [],
  ...over,
})

async function previewFromJira(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText('ciam-policies'), 'ciam-policies')
  await user.click(screen.getByText('Jira (issues by JQL or epic)'))   // tick the source toggle → reveals the JQL field
  await user.type(screen.getByPlaceholderText(/project = CIAM/), 'project = CIAM')
  await user.click(screen.getByRole('button', { name: /Preview the feature index/ }))
}

describe('Multi-source strategy wizard', () => {
  it('previews the clustered features, then async-generates and navigates to the strategy', async () => {
    server.use(
      http.post('*/api/v1/services/:s/multi-source-strategy/preview', () => HttpResponse.json(preview())),
      http.post('*/api/v1/multi-source-strategy/snapshots/:id/strategy', () =>
        HttpResponse.json({ snapshotId: 'snap-1', status: 'GENERATING' }, { status: 202 })),
      // The poll's first fetch already reports done, so it navigates without needing fake timers.
      http.get('*/api/v1/multi-source-strategy/snapshots/:id', () =>
        HttpResponse.json(preview({ generatedStrategyId: 'st-1' }))),
    )
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />, { extraRoutes: [{ path: '/test-strategy', element: <div>strategy-page</div> }] })

    await previewFromJira(user)

    expect(await screen.findByText('Get policy')).toBeInTheDocument()
    expect(screen.getByText('Create policy')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Generate strategy/ }))
    expect(await screen.findByText('strategy-page')).toBeInTheDocument()   // navigated once the poll saw generatedStrategyId
  })

  it('blocks generation when a selected source returned no usable evidence (hardFail)', async () => {
    server.use(http.post('*/api/v1/services/:s/multi-source-strategy/preview', () =>
      HttpResponse.json(preview({ hardFail: true, fetchFailures: ['Jira: nothing fetched'] }))))
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />)

    await previewFromJira(user)

    expect(await screen.findByText(/no usable evidence/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Generate strategy/ })).toBeDisabled()
  })
})
