import { http, HttpResponse } from 'msw'

// Base handlers every full-App render needs: the providers fetch the engine + Copilot status on mount. Stubbing
// them as mock-engine / connected keeps the banner + auth gate quiet so a test can focus on its own flow. A `*`
// origin wildcard matches regardless of jsdom's location. Per-test flows add their own handlers via server.use(...).
export const handlers = [
  http.get('*/api/v1/settings/llm', () =>
    HttpResponse.json({ active: 'mock', desired: 'mock', simulated: true, model: 'mock' })),
  http.get('*/api/v1/settings/copilot/status', () =>
    HttpResponse.json({ authenticated: true, connected: true })),
  // The service-picker datalist fetches this on any page that uses ServiceField; default to empty so unrelated
  // page tests don't see an unhandled request. Tests that exercise the picker override it via server.use(...).
  http.get('*/api/v1/services', () => HttpResponse.json([])),
  // The Reviews page loads recent verdicts on mount; default empty so unrelated tests don't see an unhandled request.
  http.get('*/api/v1/reviews/recent', () => HttpResponse.json([])),
  // The Defects page loads aggregate metrics; default to an empty (zero-total → hidden) summary.
  http.get('*/api/v1/defects/metrics', () =>
    HttpResponse.json({ total: 0, open: 0, closed: 0, bySeverity: {}, byStatusCategory: {}, byService: {} })),
  // The Overview pulls daily cost + scan trends for its sparkline + weekly deltas; default to empty series.
  http.get('*/api/v1/costs/trend', () => HttpResponse.json([])),
  http.get('*/api/v1/scans/trend', () => HttpResponse.json([])),
  // The TopBar alert bell polls unseen Snyk alerts app-wide; default empty so unrelated tests don't see it.
  http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])),
]
