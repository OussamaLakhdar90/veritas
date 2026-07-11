import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
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

/** A machine-driven, in-flight train (VERIFYING) — the state the live progress bar + phase stepper renders for. */
function inFlightTrain(over: Record<string, unknown> = {}) {
  return {
    id: 't9', coordinate: 'org.apache.commons:commons-lang3', oldVersion: '3.12.0', fixedIn: '3.18.0',
    severity: 'high', appIds: 'APP7576', jiraKey: 'CIAM-1', status: 'VERIFYING',
    createdAt: '2026-07-10T20:00:00Z', breaking: false, reactorPassed: null,
    verdict: { available: false, breaking: false, confidence: 0, reasons: [] },
    steps: [
      { order: 1, moduleLabel: 'BOM', bitbucketProject: 'P', repoSlug: 'bom', branch: 'b', pomPath: 'pom.xml',
        diffPreview: 'bump', status: 'RUNNING', manual: false, reviewers: [] },
    ],
    ...over,
  }
}

/** A train paused at AWAITING_CONFIRM: the cascade is planned but nothing has run — the review-&-edit step. */
function awaitingConfirmTrain(over: Record<string, unknown> = {}) {
  return {
    id: 't9', coordinate: 'org.apache.commons:commons-lang3', oldVersion: '3.12.0', fixedIn: '3.18.0',
    severity: 'high', appIds: 'APP7576', jiraKey: 'CIAM-1', status: 'AWAITING_CONFIRM',
    breaking: false, verdict: { available: false, breaking: false, confidence: 0, reasons: [] },
    steps: [
      { order: 1, moduleLabel: 'BOM', bitbucketProject: 'P', repoSlug: 'bom', branch: 'b', pomPath: 'pom.xml',
        diffPreview: '3.12.0 → 3.18.0', newModuleVersion: '3.18.0', status: 'PLANNED', manual: false, reviewers: [] },
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

  it('renders the live progress bar + 4-phase stepper for an in-flight train (not a dump to Activity)', async () => {
    server.use(http.get('*/api/v1/snyk/fixes/t9', () => HttpResponse.json(inFlightTrain())))
    renderDetail()

    // The lifecycle stepper (the progress bar the user asked for) renders all four phases in place.
    expect(await screen.findByText('Getting the code')).toBeInTheDocument()
    expect(screen.getByText('Checking for breaking changes')).toBeInTheDocument()
    expect(screen.getByText('Opening the pull requests')).toBeInTheDocument()
    // "Building & testing locally" is both the active headline and its stepper row → present at least twice.
    expect(screen.getAllByText('Building & testing locally').length).toBeGreaterThanOrEqual(2)
  })

  it('reviews the planned cascade and confirms it with the edited version + reviewers', async () => {
    let confirmBody: { versionOverrides?: Record<string, string>; reviewerOverrides?: Record<string, string[]> } | null = null
    server.use(
      http.get('*/api/v1/snyk/fixes/t9', () => HttpResponse.json(awaitingConfirmTrain())),
      http.post('*/api/v1/snyk/fixes/t9/confirm', async ({ request }) => {
        confirmBody = (await request.json()) as typeof confirmBody
        return HttpResponse.json(awaitingConfirmTrain({ status: 'VERIFYING' }))
      }),
    )
    const user = userEvent.setup()
    renderDetail()

    // The confirm state explains what to do and renders the editable cascade (version + reviewers).
    expect(await screen.findByText(/Review the versions and reviewers/i)).toBeInTheDocument()
    const version = screen.getByLabelText(/New version/i)
    await user.clear(version)
    await user.type(version, '3.19.0')
    await user.type(screen.getByLabelText(/Reviewers/i), 'carol')
    await user.click(screen.getByRole('button', { name: /Confirm & run/i }))

    // The user's edits ride through to the confirm call (version keyed by module, reviewers keyed by step order).
    await waitFor(() => expect(confirmBody).not.toBeNull())
    expect(confirmBody!.versionOverrides).toEqual({ BOM: '3.19.0' })
    expect(confirmBody!.reviewerOverrides).toEqual({ 1: ['carol'] })
  })

  it('records a manually-opened PR from the deep-link (awaiting-manual step)', async () => {
    let recorded = false
    server.use(
      http.get('*/api/v1/snyk/fixes/t9', () =>
        HttpResponse.json(recorded ? awaitingManualTrain({ status: 'PR_OPEN' }) : awaitingManualTrain())),
      http.post('*/api/v1/snyk/fixes/t9/steps/:order/pr', () => {
        recorded = true
        return HttpResponse.json(awaitingManualTrain({ status: 'PR_OPEN' }))
      }),
    )
    const user = userEvent.setup()
    renderDetail()

    // Each pushed-but-unopened step offers a "paste your PR URL → record" affordance right on the deep-link.
    const urlInput = (await screen.findAllByPlaceholderText(/Paste the PR URL/i))[0]
    await user.type(urlInput, 'http://host/my-pr')
    await user.click(screen.getAllByRole('button', { name: /Record my PR/i })[0])

    await waitFor(() => expect(recorded).toBe(true))
  })

  it('surfaces the AI reasoning, the live Jira status chip, and a view-branch link on held steps', async () => {
    server.use(http.get('*/api/v1/snyk/fixes/t9', () => HttpResponse.json(awaitingManualTrain({
      jiraStatus: 'In Review',
      verdict: { available: true, breaking: true, confidence: 80,
        reasons: ['commons-lang3 3.x removed StringUtils.foo used in AppX'], migrationNotes: 'Replace foo() with bar().' },
      steps: [
        { order: 1, moduleLabel: 'BOM', bitbucketProject: 'APP7488', repoSlug: 'bom',
          branch: 'feature/CIAM-1-snyk-fix-app-7488', pomPath: 'pom.xml', diffPreview: 'bump',
          status: 'BRANCH_PUSHED', manual: false, reviewers: [],
          branchUrl: 'https://bb/projects/APP7488/repos/bom/browse?at=refs/heads/feature/CIAM-1-snyk-fix-app-7488' },
      ],
    }))))
    renderDetail()

    // The AI's REASONING renders — not just "80% confidence".
    expect(await screen.findByText(/removed StringUtils.foo/)).toBeInTheDocument()
    expect(screen.getByText(/Replace foo\(\) with bar\(\)/)).toBeInTheDocument()
    // The live Jira status chip shows the key + workflow status.
    expect(screen.getByText('Jira CIAM-1 · In Review')).toBeInTheDocument()
    // A pushed-but-no-PR step offers a "View branch" link to Bitbucket (the "we said pushed but I can't find it" fix).
    const branchLink = screen.getByRole('link', { name: /View branch/i })
    expect(branchLink.getAttribute('href')).toContain('refs/heads/feature/CIAM-1-snyk-fix-app-7488')
  })

  it('cancels a waiting train from the deep-link — no more forced PR or DB delete', async () => {
    let cancelled = false
    server.use(
      http.get('*/api/v1/snyk/fixes/t9', () => HttpResponse.json(
        cancelled ? awaitingManualTrain({ status: 'CANCELLED', stageDetail: 'Fix abandoned.' }) : awaitingManualTrain())),
      http.post('*/api/v1/snyk/fixes/t9/cancel', () => {
        cancelled = true
        return HttpResponse.json(awaitingManualTrain({ status: 'CANCELLED', stageDetail: 'Fix abandoned.' }))
      }),
    )
    const user = userEvent.setup()
    renderDetail()

    // Two-click inline confirm: first click arms it, the second abandons the fix.
    await user.click(await screen.findByRole('button', { name: /Cancel fix/i }))
    await user.click(await screen.findByRole('button', { name: /Click again to abandon/i }))

    // The train goes to the muted Cancelled state; the Open-PRs action is gone — a real third choice, no dead-end.
    await waitFor(() => expect(cancelled).toBe(true))
    expect(await screen.findByText(/Cancelled/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Open the PRs/ })).not.toBeInTheDocument()
  })

  it('shows the exact edit, the branch name, and the commit sha on a pushed step (no longer blind)', async () => {
    server.use(http.get('*/api/v1/snyk/fixes/t9', () => HttpResponse.json(awaitingManualTrain({
      steps: [
        { order: 1, moduleLabel: 'BOM', bitbucketProject: 'APP7488', repoSlug: 'bom',
          branch: 'feature/CIAM-1-snyk-fix-app-7488', commitSha: 'abc1234def5678901234', pomPath: 'pom.xml',
          diffPreview: 'Bump jackson-databind 3.1.1 → 3.1.4', status: 'BRANCH_PUSHED', manual: false, reviewers: [] },
      ],
    }))))
    renderDetail()

    // The exact edit (the WHAT), the branch NAME as text, and the commit sha are all visible — the "blind stepper" fix.
    expect(await screen.findByText('Bump jackson-databind 3.1.1 → 3.1.4')).toBeInTheDocument()
    expect(screen.getByText('feature/CIAM-1-snyk-fix-app-7488')).toBeInTheDocument()
    expect(screen.getByText(/abc1234/)).toBeInTheDocument()
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
