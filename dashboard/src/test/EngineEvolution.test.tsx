import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { EngineEvolution } from '../pages/EngineEvolution'

const train = {
  id: 'tr-1', findingType: 'STATUS_CODE_MISSING', aiSuggestedSeverity: 'MAJOR', aiSuggested: true,
  aiRationale: 'Response-shape risk a running consumer depends on.', finalSeverity: 'MAJOR',
  voteCount: 5, distinctServices: 3, voteBreakdown: 'MAJOR:4, CRITICAL:1', status: 'PROPOSED',
}

function renderEvolve(trains: unknown[]) {
  server.use(http.get('*/api/v1/engine-evolution/proposals', () => HttpResponse.json(trains)))
  return renderPage(<EngineEvolution />, { path: '/engine-evolution', route: '/engine-evolution' })
}

describe('Engine Evolution', () => {
  it('shows a proposal with the AI suggestion, rationale, and the learning-debt count', async () => {
    renderEvolve([train])
    expect(await screen.findByText('STATUS_CODE_MISSING')).toBeInTheDocument()
    expect(screen.getByText(/Response-shape risk/)).toBeInTheDocument()
    expect(screen.getByText(/learning debt/)).toBeInTheDocument()
  })

  it('opening the classification PR POSTs the open-pr endpoint', async () => {
    let opened = false
    server.use(http.post('*/api/v1/engine-evolution/proposals/tr-1/open-pr', () => {
      opened = true
      return HttpResponse.json({ ...train, status: 'PR_OPEN', prUrl: 'https://host/pr/1' })
    }))
    const user = userEvent.setup()
    renderEvolve([train])
    await user.click(await screen.findByRole('button', { name: /Open PR/ }))
    expect(await screen.findByText('Classification PR opened.')).toBeInTheDocument()
    expect(opened).toBe(true)
  })

  it('overriding the severity reveals a required comment and challenges the proposal', async () => {
    let body: { severity?: string; comment?: string } | null = null
    server.use(http.post('*/api/v1/engine-evolution/proposals/tr-1/challenge', async ({ request }) => {
      body = (await request.json()) as { severity?: string; comment?: string }
      return HttpResponse.json({ ...train, finalSeverity: body.severity, status: 'CHALLENGED' })
    }))
    const user = userEvent.setup()
    renderEvolve([train])
    await screen.findByText('STATUS_CODE_MISSING')

    // Override MAJOR → CRITICAL reveals the comment field; Save posts {severity, comment}.
    await user.selectOptions(screen.getByLabelText('Final severity'), 'CRITICAL')
    await user.type(await screen.findByLabelText('Why override?'), 'It breaks a running consumer.')
    await user.click(screen.getByRole('button', { name: 'Save override' }))

    expect(await screen.findByText('Decision saved.')).toBeInTheDocument()
    expect(body).toEqual({ severity: 'CRITICAL', comment: 'It breaks a running consumer.' })
  })
})
