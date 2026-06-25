import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { RepoPicker } from '../pages/RepoPicker'

describe('Validate a service (RepoPicker)', () => {
  it('finds repos by app-id, starts a validation, and navigates to findings when it completes', async () => {
    server.use(
      http.get('*/api/v1/repos', () =>
        HttpResponse.json([{ slug: 'ciam-policies', name: 'CIAM Policies', defaultBranch: 'develop', appId: 'APP7571' }])),
      http.get('*/api/v1/repos/:slug/branches', () => HttpResponse.json(['develop', 'main'])),
      http.post('*/api/v1/scans', () => HttpResponse.json({ scanId: 'scan-1', status: 'RUNNING' }, { status: 202 })),
      // First poll already reports DONE → the stepper effect navigates without needing fake timers.
      http.get('*/api/v1/scans/:id', () =>
        HttpResponse.json({ id: 'scan-1', status: 'COMPLETED', stage: 'DONE', totalFindings: 2 })),
    )
    const user = userEvent.setup()
    renderPage(<RepoPicker />, { extraRoutes: [{ path: '/findings/:scanId', element: <div>findings-page</div> }] })

    await user.type(screen.getByPlaceholderText('APP7571'), 'APP7571')
    await user.click(screen.getByRole('button', { name: /Find repos/ }))

    // The repo rendered → open its Validate modal.
    await user.click(await screen.findByRole('button', { name: /^Validate$/ }))
    expect(await screen.findByText(/Validate ciam-policies/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Run validation/ }))
    expect(await screen.findByText('findings-page')).toBeInTheDocument()
  })
})
