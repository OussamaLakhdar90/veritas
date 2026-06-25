import { ReactElement } from 'react'
import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { ToastProvider } from '../components/Toast'
import { CopilotAuthProvider } from '../lib/copilotAuth'
import { BackgroundScansProvider } from '../lib/backgroundScans'

/** A fresh, retry-free, cache-free QueryClient per test so polling/mutations are deterministic. */
function freshClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  })
}

interface RouteSpec {
  path: string
  element: ReactElement
}

/**
 * Render a single page under the real providers (react-query + Toast) and a MemoryRouter. `path`/`route` drive a
 * param route (e.g. /findings/:scanId); `extraRoutes` provide navigation targets so a `nav('/x')` lands on a
 * marker the test can assert on.
 */
export function renderPage(
  ui: ReactElement,
  opts: { path?: string; route?: string; extraRoutes?: RouteSpec[] } = {},
) {
  const path = opts.path ?? '/'
  const route = opts.route ?? path
  return render(
    <QueryClientProvider client={freshClient()}>
      <ToastProvider>
        <MemoryRouter initialEntries={[route]}>
          <BackgroundScansProvider>
            <CopilotAuthProvider>
              <Routes>
                <Route path={path} element={ui} />
                {(opts.extraRoutes ?? []).map((r) => (
                  <Route key={r.path} path={r.path} element={r.element} />
                ))}
              </Routes>
            </CopilotAuthProvider>
          </BackgroundScansProvider>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  )
}
