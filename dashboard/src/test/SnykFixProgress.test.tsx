import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { SnykFixDetail } from '../pages/SnykFixDetail'

/** A fix train stuck at the local build with the exact mvn failure the user hit. */
function failedTrain(over: Record<string, unknown> = {}) {
  return {
    id: 't9', coordinate: 'org.apache.commons:commons-lang3', oldVersion: '3.12.0', fixedIn: '3.18.0',
    severity: 'high', appIds: 'APP7576', jiraKey: 'CIAM-1', status: 'FAILED', failedStage: 'BUILDING',
    errorMessage: 'Cannot run program "mvn"', breaking: false, reactorPassed: null, verdict: null, steps: [],
    ...over,
  }
}

function renderDetail(id = 't9') {
  return renderPage(<SnykFixDetail />, { path: '/snyk/fix/:trainId', route: `/snyk/fix/${id}` })
}

describe('Snyk fix deep-link (Activity row → live progress)', () => {
  it('renders the live lifecycle stepper for the train in the URL and surfaces the mvn failure', async () => {
    server.use(http.get('*/api/v1/snyk/fixes/t9', () => HttpResponse.json(failedTrain())))
    renderDetail()

    // Clicking an Activity fix row now lands here (route /snyk/fix/:trainId) and shows the SAME four-phase stepper
    // the wizard uses — the "which operation is running" view — instead of the bare Snyk page (the dead click).
    expect(await screen.findByText('Getting the code')).toBeInTheDocument()
    expect(screen.getByText('Building & testing locally')).toBeInTheDocument()
    expect(screen.getByText('Opening the pull requests')).toBeInTheDocument()
    // The failure is explained on the page (surfaced on the failed phase + the failure box), not a red badge.
    expect(screen.getAllByText(/Cannot run program "mvn"/).length).toBeGreaterThan(0)
  })

  it('shows a retryable error (not a blank page) when the train can’t be loaded', async () => {
    let failNext = true
    server.use(http.get('*/api/v1/snyk/fixes/t9', () =>
      failNext ? new HttpResponse(null, { status: 500 }) : HttpResponse.json(failedTrain())))
    renderDetail()

    expect(await screen.findByText(/Couldn’t load the fix train/)).toBeInTheDocument()
    const user = userEvent.setup()
    failNext = false
    await user.click(screen.getByRole('button', { name: /Retry/ }))
    expect(await screen.findByText('Getting the code')).toBeInTheDocument()
  })
})
