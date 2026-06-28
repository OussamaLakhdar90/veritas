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
]
