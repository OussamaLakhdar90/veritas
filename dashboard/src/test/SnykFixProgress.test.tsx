import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { SnykFixDetail } from '../pages/SnykFixDetail'

/** A breaking-change train paused at AWAITING_MANUAL_FIX with the real reactor build output captured. */
function awaitingManualTrain(over: Record<string, unknown> = {}) {
  return {
    id: 't9', coordinate: 'org.apache.commons:commons-lang3', oldVersion: '3.12.0', fixedIn: '3.18.0',
    severity: 'high', appIds: 'APP7576', jiraKey: 'CIAM-1', status: 'AWAITING_MANUAL_FIX', failedStage: 'VERIFYING',
    stageDetail: 'Action needed — the local build failed at core.', breaking: true, reactorPassed: false,
    reactorFailingLabel: 'core', reactorOutputTail: 'BUILD FAILURE: cannot find symbol method foo()',
    failedStepOrder: 2, verdict: { available: true, breaking: true, confidence: 80, reasons: [] },
    steps: [
      { order: 1, moduleLabel: 'BOM', bitbucketProject: 'P', repoSlug: 'bom', branch: 'b', pomPath: 'pom.xml',
        diffPreview: 'bump', status: 'BRANCH_PUSHED', manual: false, reviewers: [] },
      { order: 2, moduleLabel: 'core', bitbucketProject: 'P', repoSlug: 'core', branch: 'b', pomPath: 'pom.xml',
        diffPreview: 'bump', reason: 'reactor build failed here', status: 'BRANCH_PUSHED', manual: false, reviewers: [] },
    ],
    ...over,
  }
}

function renderDetail(id = 't9') {
  return renderPage(<SnykFixDetail />, { path: '/snyk/fix/:trainId', route: `/snyk/fix/${id}` })
}

describe('Snyk fix deep-link (Activity row → live, actionable progress)', () => {
  it('explains what to do, shows the real build output, and offers the action for a waiting train', async () => {
    server.use(http.get('*/api/v1/snyk/fixes/t9', () => HttpResponse.json(awaitingManualTrain())))
    renderDetail()

    // The "waiting for you" state now EXPLAINS what's needed (was: "does nothing and explains nothing")…
    expect(await screen.findByText(/needs a human/i)).toBeInTheDocument()
    // …surfaces the actual mvn build error (previously captured but never rendered)…
    expect(screen.getByText(/BUILD FAILURE: cannot find symbol/)).toBeInTheDocument()
    // …and gives the user the action right here on the deep-link page.
    expect(screen.getByRole('button', { name: /Open the PRs/ })).toBeInTheDocument()
  })

  it('resolves a waiting train from the deep-link: opening the held PRs advances it', async () => {
    let opened = false
    server.use(
      http.get('*/api/v1/snyk/fixes/t9', () =>
        HttpResponse.json(opened ? awaitingManualTrain({ status: 'PR_OPEN' }) : awaitingManualTrain())),
      http.post('*/api/v1/snyk/fixes/t9/open-prs', () => {
        opened = true
        return HttpResponse.json(awaitingManualTrain({ status: 'PR_OPEN' }))
      }),
    )
    const user = userEvent.setup()
    renderDetail()
    await user.click(await screen.findByRole('button', { name: /Open the PRs/ }))

    // The train advances to PR_OPEN → the closing action ("Mark all merged") is now offered — no dead-end.
    expect(await screen.findByRole('button', { name: /Mark all merged/ })).toBeInTheDocument()
  })

  it('shows a retryable error (not a blank page) when the train can’t be loaded', async () => {
    let failNext = true
    server.use(http.get('*/api/v1/snyk/fixes/t9', () =>
      failNext ? new HttpResponse(null, { status: 500 }) : HttpResponse.json(awaitingManualTrain())))
    renderDetail()

    expect(await screen.findByText(/Couldn’t load the fix train/)).toBeInTheDocument()
    const user = userEvent.setup()
    failNext = false
    await user.click(screen.getByRole('button', { name: /Retry/ }))
    expect(await screen.findByText(/needs a human/i)).toBeInTheDocument()
  })
})
