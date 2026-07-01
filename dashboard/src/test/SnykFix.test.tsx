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
})
