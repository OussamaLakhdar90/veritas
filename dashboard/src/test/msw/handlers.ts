import { http, HttpResponse } from 'msw'

// Base handlers every full-App render needs: the providers fetch the engine + Copilot status on mount. Stubbing
// them as mock-engine / connected keeps the banner + auth gate quiet so a test can focus on its own flow. A `*`
// origin wildcard matches regardless of jsdom's location. Per-test flows add their own handlers via server.use(...).
export const handlers = [
  http.get('*/api/v1/settings/llm', () =>
    HttpResponse.json({ active: 'mock', desired: 'mock', simulated: true, model: 'mock' })),
  http.get('*/api/v1/settings/copilot/status', () =>
    HttpResponse.json({ authenticated: true, connected: true })),
]
