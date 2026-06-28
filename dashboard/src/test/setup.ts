import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, beforeEach, vi } from 'vitest'
import { server } from './msw/server'
import i18n from '../i18n'

// The app defaults to French (NBC/Québec); tests assert the English source strings, so pin every test to English.
beforeEach(() => { i18n.changeLanguage('en') })

// Every fetch a flow makes must be explicitly stubbed — an unhandled request fails the test loudly (catches a
// missed endpoint or a silent real-network call).
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  cleanup()
  localStorage.clear()
})
afterAll(() => server.close())

// useDarkMode (and others) read window.matchMedia — jsdom doesn't implement it, so provide a no-op shim.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }),
})
