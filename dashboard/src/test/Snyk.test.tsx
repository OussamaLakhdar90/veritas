import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { Snyk } from '../pages/Snyk'

function renderSnyk() {
  return renderPage(<Snyk />, { path: '/snyk', route: '/snyk' })
}

const emptyBase = () => {
  server.use(
    http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([])),
    http.get('*/api/v1/snyk/watches', () => HttpResponse.json([])),
    http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
  )
}

describe('Snyk page', () => {
  it('shows the Snyk-branded header and the add-watch card', async () => {
    emptyBase()
    renderSnyk()
    expect(await screen.findByText('Watch a repository')).toBeInTheDocument()
    // Snyk brand mark is present (aria-label="Snyk").
    expect(screen.getAllByLabelText('Snyk').length).toBeGreaterThan(0)
  })

  it('shows the empty state when no repos are watched', async () => {
    emptyBase()
    renderSnyk()
    expect(await screen.findByText('No repositories watched yet')).toBeInTheDocument()
  })

  it('renders a watched repo with its severity counts', async () => {
    server.use(
      http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/watches', () => HttpResponse.json([{
        id: 'w1', orgId: 'o1', orgSlug: 'app7576', orgName: 'CIAM Profile', targetId: 't1',
        repoSlug: 'application-tests', enabled: true, critical: 4, high: 1, medium: 12, low: 0,
        fixable: 3, projectCount: 14, lastPolled: '2026-07-01T12:00:00.000Z',
      }])),
    )
    renderSnyk()
    expect(await screen.findByText('application-tests')).toBeInTheDocument()
    expect(screen.getByText('app7576')).toBeInTheDocument()
  })

  it('surfaces a new critical alert banner', async () => {
    server.use(
      http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/watches', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([{
        id: 'a1', watchId: 'w1', orgSlug: 'app7576', repoSlug: 'application-tests',
        severity: 'critical', message: 'New critical vulnerability in application-tests (app7576).', seen: false,
      }])),
    )
    renderSnyk()
    expect(await screen.findByText(/New critical vulnerability/)).toBeInTheDocument()
    expect(screen.getByText('critical')).toBeInTheDocument()
  })

  it('loads issues for a watch and shows fix info (upgrade vs no-supported-fix)', async () => {
    server.use(
      http.get('*/api/v1/snyk/orgs', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
      http.get('*/api/v1/snyk/watches', () => HttpResponse.json([{
        id: 'w1', orgId: 'o1', orgSlug: 'app7576', orgName: 'CIAM Profile', targetId: 't1',
        repoSlug: 'application-tests', enabled: true, critical: 1, high: 1, medium: 0, low: 0,
        fixable: 1, projectCount: 1, lastPolled: '2026-07-01T12:00:00.000Z',
      }])),
      http.get('*/api/v1/snyk/watches/w1/issues', () => HttpResponse.json([
        { projectName: 'profile-management/pom.xml', issueId: 'i1', severity: 'critical', title: 'Deserialization',
          pkgName: 'com.fasterxml.jackson.core:jackson-databind', pkgVersion: '3.1.1', cve: 'CVE-2020-1',
          cwe: 'CWE-502', cvss: 9.2, riskScore: 298, fixable: false, fixedIn: null },
        { projectName: 'profile-management/pom.xml', issueId: 'i2', severity: 'high', title: 'Recursion',
          pkgName: 'org.apache.commons:commons-lang3', pkgVersion: '3.12.0', cve: 'CVE-2024-2',
          cwe: 'CWE-674', cvss: 7.5, riskScore: 182, fixable: true, fixedIn: '3.18.0' },
      ])),
    )
    const user = userEvent.setup()
    renderSnyk()

    await user.click(await screen.findByTitle('View vulnerabilities'))

    expect(await screen.findByText('Upgrade to 3.18.0')).toBeInTheDocument()
    expect(screen.getByText('No safe version — tracked only')).toBeInTheDocument()
    expect(screen.getByText('com.fasterxml.jackson.core:jackson-databind')).toBeInTheDocument()
  })
})
