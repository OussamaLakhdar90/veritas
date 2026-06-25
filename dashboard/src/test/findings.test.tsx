import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { Findings } from '../pages/Findings'

const finding = {
  id: 'fnd-1', type: 'MISSING_ENDPOINT', severity: 'CRITICAL', layer: 'L2',
  endpoint: 'GET /policies', summary: 'Spec documents GET /policies but the code never serves it',
  status: 'OPEN', confidence: 'HIGH',
}

function renderFindings() {
  return renderPage(<Findings />, { path: '/findings/:scanId', route: '/findings/scan-1' })
}

describe('Findings disposition', () => {
  it('accepts a finding (PATCH) and shows the success toast', async () => {
    server.use(
      http.get('*/api/v1/scans/:id/findings', () => HttpResponse.json([finding])),
      http.patch('*/api/v1/findings/:id', () => HttpResponse.json({ ...finding, status: 'ACCEPTED' })),
    )
    const user = userEvent.setup()
    renderFindings()

    expect(await screen.findByText(finding.summary)).toBeInTheDocument()
    await user.click(screen.getByTitle(/Accept — this is a real difference/))
    expect(await screen.findByText('Marked Accepted.')).toBeInTheDocument()
  })

  it('rejecting opens a confirm modal, then PATCHes REJECTED', async () => {
    server.use(
      http.get('*/api/v1/scans/:id/findings', () => HttpResponse.json([finding])),
      http.patch('*/api/v1/findings/:id', () => HttpResponse.json({ ...finding, status: 'REJECTED' })),
    )
    const user = userEvent.setup()
    renderFindings()

    expect(await screen.findByText(finding.summary)).toBeInTheDocument()
    await user.click(screen.getByTitle(/Reject — not applicable/))
    expect(await screen.findByText('Reject this finding?')).toBeInTheDocument()   // the confirm modal (was destructive)
    await user.click(screen.getByRole('button', { name: /Reject finding/ }))
    expect(await screen.findByText('Marked Rejected.')).toBeInTheDocument()
  })

  it('surfaces an error toast when the PATCH fails (not a silent success)', async () => {
    server.use(
      http.get('*/api/v1/scans/:id/findings', () => HttpResponse.json([finding])),
      http.patch('*/api/v1/findings/:id', () =>
        HttpResponse.json({ detail: 'The database is unavailable', status: 500 }, { status: 500 })),
    )
    const user = userEvent.setup()
    renderFindings()

    expect(await screen.findByText(finding.summary)).toBeInTheDocument()
    await user.click(screen.getByTitle(/Accept — this is a real difference/))
    expect(await screen.findByRole('alert')).toBeInTheDocument()   // an error toast, not a false "Marked Accepted."
  })
})
