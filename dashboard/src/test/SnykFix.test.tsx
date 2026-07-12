import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { SnykFixWizard } from '../components/SnykFixWizard'
import type { SnykIssueView } from '../api'

const issue: SnykIssueView = {
  projectName: 'profile-management/pom.xml', issueId: 'i2', severity: 'high', title: 'Recursion',
  pkgName: 'org.apache.commons:commons-lang3', pkgVersion: '3.12.0', cve: 'CVE-2024-2', cwe: 'CWE-674',
  cvss: 7.5, riskScore: 182, fixable: true, fixedIn: '3.18.0',
}

function renderWizard() {
  return renderPage(
    <SnykFixWizard open onClose={() => {}} issue={issue} watchId="w1"
      apps={[{ slug: 'app7576', name: 'CIAM Profile' }]} defaultApp="app7576" />,
    {},
  )
}

const breakingTrain = {
  id: 't1', coordinate: 'org.apache.commons:commons-lang3', oldVersion: '3.12.0', fixedIn: '3.18.0',
  severity: 'high', appIds: 'APP7576', jiraKey: 'CIAM-1', status: 'AWAITING_MANUAL_FIX',
  stageDetail: 'Breaking change — adapt the branches.', breaking: true, reactorPassed: false,
  reactorFailingLabel: 'consumer:app7576', verdict: { available: true, breaking: true, confidence: 80, reasons: [] },
  steps: [{ order: 1, moduleLabel: 'BOM', bitbucketProject: 'APP7488', repoSlug: 'lsist-test-framework-bom',
    branch: 'veritas/snyk-fix-abc', pomPath: 'pom.xml', diffPreview: 'jackson 2.14 → 2.15; release 1.0.9 → 1.0.10',
    newModuleVersion: '1.0.10', status: 'BRANCH_PUSHED', manual: false, reviewers: ['alice'] }],
}

describe('Snyk fix wizard', () => {
  it('collects app-ids + Jira, starts the fix, and shows the breaking-change release train', async () => {
    let posted: { appIds?: string[]; jiraKey?: string } = {}
    server.use(
      http.post('*/api/v1/snyk/fixes', async ({ request }) => {
        posted = (await request.json()) as typeof posted
        return HttpResponse.json({ trainId: 't1' }, { status: 202 })
      }),
      http.get('*/api/v1/snyk/fixes/t1', () => HttpResponse.json(breakingTrain)),
    )
    const user = userEvent.setup()
    renderWizard()

    // app7576 is pre-selected via defaultApp; supply an existing Jira key, then start.
    await user.type(screen.getByPlaceholderText('CIAM-1234'), 'CIAM-1')
    await user.click(screen.getByRole('button', { name: /Start fix/ }))

    // The release train renders: breaking status + the held-PR action + the BOM step.
    expect(await screen.findByText('Needs your changes')).toBeInTheDocument()
    expect(screen.getByText('BOM')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Open the PRs/ })).toBeInTheDocument()

    // The app-id was upper-cased to the Bitbucket project key, and the Jira key was passed through.
    expect(posted.appIds).toEqual(['APP7576'])
    expect(posted.jiraKey).toBe('CIAM-1')
  })

  it('pauses on a review step, lets the user edit a version, and confirms', async () => {
    const planTrain = {
      id: 't2', coordinate: 'org.apache.commons:commons-lang3', oldVersion: '3.12.0', fixedIn: '3.18.0',
      severity: 'high', appIds: 'APP7576', jiraKey: 'CIAM-1', status: 'AWAITING_CONFIRM',
      stageDetail: 'Review the cascade, then confirm to run it.', breaking: false, reactorPassed: null,
      reactorFailingLabel: null, verdict: null,
      steps: [{ order: 1, moduleLabel: 'BOM', bitbucketProject: 'APP7488', repoSlug: 'lsist-test-framework-bom',
        branch: 'veritas/snyk-fix-x', pomPath: 'pom.xml', diffPreview: 'jackson 2.14 → 2.15; 1.0.9 → 1.0.10',
        newModuleVersion: '1.0.10', status: 'PLANNED', manual: false, reviewers: ['alice'] }],
    }
    const openedTrain = { ...planTrain, status: 'PR_OPEN',
      steps: [{ ...planTrain.steps[0], status: 'PR_OPEN', prUrl: 'https://bitbucket/pr/1' }] }
    let confirmed = false
    let confirmBody: { versionOverrides?: Record<string, string> } = {}
    server.use(
      http.post('*/api/v1/snyk/fixes', () => HttpResponse.json({ trainId: 't2' }, { status: 202 })),
      http.get('*/api/v1/snyk/fixes/t2', () => HttpResponse.json(confirmed ? openedTrain : planTrain)),
      http.post('*/api/v1/snyk/fixes/t2/confirm', async ({ request }) => {
        confirmBody = (await request.json()) as typeof confirmBody
        confirmed = true
        return HttpResponse.json(openedTrain, { status: 202 })
      }),
    )
    const user = userEvent.setup()
    renderWizard()
    await user.type(screen.getByPlaceholderText('CIAM-1234'), 'CIAM-1')
    await user.click(screen.getByRole('button', { name: /Start fix/ }))

    // The review step appears with the editable framework version pre-filled.
    const versionInput = await screen.findByDisplayValue('1.0.10')
    await user.clear(versionInput)
    await user.type(versionInput, '1.1.0')
    await user.click(screen.getByRole('button', { name: /Confirm & run/ }))

    // After confirm the train advances (PR_OPEN → mark-merged action), and the edit was sent.
    expect(await screen.findByRole('button', { name: /Mark all merged/ })).toBeInTheDocument()
    expect(confirmBody.versionOverrides).toEqual({ BOM: '1.1.0' })
  })

  it('shows a retryable error (not an endless spinner) when the train fails to load', async () => {
    let failNext = true
    server.use(
      http.post('*/api/v1/snyk/fixes', () => HttpResponse.json({ trainId: 't1' }, { status: 202 })),
      http.get('*/api/v1/snyk/fixes/t1', () =>
        failNext ? new HttpResponse(null, { status: 500 }) : HttpResponse.json(breakingTrain)),
    )
    const user = userEvent.setup()
    renderWizard()
    await user.type(screen.getByPlaceholderText('CIAM-1234'), 'CIAM-1')
    await user.click(screen.getByRole('button', { name: /Start fix/ }))

    // The first poll 500s → an error card with a Retry action, not a permanent "Starting…" spinner.
    expect(await screen.findByText(/Couldn’t load the fix train/)).toBeInTheDocument()
    expect(screen.queryByText('Starting the fix…')).not.toBeInTheDocument()

    // Retry once the endpoint recovers → the train renders.
    failNext = false
    await user.click(screen.getByRole('button', { name: /Retry/ }))
    expect(await screen.findByText('BOM')).toBeInTheDocument()
  })

  it('lets Veritas open the held PRs (breaking-change path)', async () => {
    let opened = false
    const openedTrain = {
      ...breakingTrain, status: 'PR_OPEN',
      steps: [{ ...breakingTrain.steps[0], status: 'PR_OPEN', prUrl: 'https://bitbucket/pr/1' }],
    }
    server.use(
      http.post('*/api/v1/snyk/fixes', () => HttpResponse.json({ trainId: 't1' }, { status: 202 })),
      http.get('*/api/v1/snyk/fixes/t1', () => HttpResponse.json(opened ? openedTrain : breakingTrain)),
      http.post('*/api/v1/snyk/fixes/t1/open-prs', () => {
        opened = true
        return HttpResponse.json(openedTrain)
      }),
    )
    const user = userEvent.setup()
    renderWizard()
    await user.type(screen.getByPlaceholderText('CIAM-1234'), 'CIAM-1')
    await user.click(screen.getByRole('button', { name: /Start fix/ }))
    await user.click(await screen.findByRole('button', { name: /Open the PRs/ }))

    // After opening, the train advances to PR_OPEN → the "mark merged" action appears.
    expect(await screen.findByRole('button', { name: /Mark all merged/ })).toBeInTheDocument()
  })

  it('shows the lifecycle phase stepper: a fix that stopped at CHECKING pinpoints that exact phase', async () => {
    // The user's exact failure: a single fix died at CHECKING (before any module step existed) with no visible
    // progress. The stepper must render the four lifecycle phases and surface where it stopped, not a bare badge.
    const failedAtChecking = {
      id: 't1', coordinate: 'org.apache.commons:commons-lang3', oldVersion: '3.12.0', fixedIn: '3.18.0',
      severity: 'high', appIds: 'APP7576', jiraKey: 'CIAM-1', status: 'FAILED', failedStage: 'CHECKING',
      errorMessage: 'The breaking-change check could not complete.', breaking: false, reactorPassed: null,
      verdict: null, steps: [],
    }
    server.use(
      http.post('*/api/v1/snyk/fixes', () => HttpResponse.json({ trainId: 't1' }, { status: 202 })),
      http.get('*/api/v1/snyk/fixes/t1', () => HttpResponse.json(failedAtChecking)),
    )
    const user = userEvent.setup()
    renderWizard()
    await user.type(screen.getByPlaceholderText('CIAM-1234'), 'CIAM-1')
    await user.click(screen.getByRole('button', { name: /Start fix/ }))

    // All four lifecycle phases render by name — the "which operation" view the user asked for…
    expect(await screen.findByText('Getting the code')).toBeInTheDocument()
    expect(screen.getByText('Checking for breaking changes')).toBeInTheDocument()
    expect(screen.getByText('Building & compiling locally')).toBeInTheDocument()
    expect(screen.getByText('Opening the pull requests')).toBeInTheDocument()
    // …and the failure is explained (surfaced on the failed phase + the failure box), not left as a red badge.
    expect(screen.getAllByText(/The breaking-change check could not complete/).length).toBeGreaterThan(0)
    // The failure box names the FRIENDLY phase, never the raw backend stage code (no "Failed at CHECKING").
    expect(screen.getByText(/Failed at Checking for breaking changes/)).toBeInTheDocument()
    expect(screen.queryByText(/Failed at CHECKING/)).not.toBeInTheDocument()
  })

  it('shows the live stepper: the module that failed the build is pinpointed with an action-needed banner', async () => {
    const liveTrain = {
      id: 't1', coordinate: 'org.apache.commons:commons-lang3', oldVersion: '3.12.0', fixedIn: '3.18.0',
      severity: 'high', appIds: 'APP7576', jiraKey: 'CIAM-1', status: 'AWAITING_MANUAL_FIX',
      stageDetail: 'Action needed — the local build failed at core; the version-bump branches are pushed.',
      breaking: true, reactorPassed: false, reactorFailingLabel: 'core', failedStepOrder: 2,
      verdict: { available: true, breaking: true, confidence: 80, reasons: [] },
      steps: [
        { order: 1, moduleLabel: 'BOM', bitbucketProject: 'P', repoSlug: 'bom', branch: 'b', pomPath: 'pom.xml',
          diffPreview: 'bump', status: 'BRANCH_PUSHED', manual: false, reviewers: [] },
        { order: 2, moduleLabel: 'core', bitbucketProject: 'P', repoSlug: 'core', branch: 'b', pomPath: 'pom.xml',
          diffPreview: 'bump', reason: 'reactor build failed here', status: 'BRANCH_PUSHED', manual: false, reviewers: [] },
      ],
    }
    server.use(
      http.post('*/api/v1/snyk/fixes', () => HttpResponse.json({ trainId: 't1' }, { status: 202 })),
      http.get('*/api/v1/snyk/fixes/t1', () => HttpResponse.json(liveTrain)),
    )
    const user = userEvent.setup()
    renderWizard()
    await user.type(screen.getByPlaceholderText('CIAM-1234'), 'CIAM-1')
    await user.click(screen.getByRole('button', { name: /Start fix/ }))

    // A prominent "action needed" banner names the exact module that broke the build…
    expect(await screen.findByText(/Action needed — the local build failed at core/)).toBeInTheDocument()
    // …and the failed module's step (failedStepOrder = 2) surfaces its reason, pinpointing the étape.
    expect(screen.getByText('reactor build failed here')).toBeInTheDocument()
    // The "Building & compiling locally" phase is flagged as the error étape (reactorPassed=false), NOT a green
    // "done" — only "Getting the code" + "Checking for breaking changes" completed, so exactly two done badges.
    expect(screen.getAllByText('done')).toHaveLength(2)
  })
})
