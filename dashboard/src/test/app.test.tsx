import { afterEach, describe, expect, it } from "vitest"
import { render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { http, HttpResponse } from "msw"
import { server } from "./msw/server"
import { ToastProvider } from "../components/Toast"
import { App } from "../App"

// App owns its OWN HashRouter + ActivityCenterProvider + CopilotAuthProvider, so renderPage (a MemoryRouter)
// would double-wrap the router. Render the bare <App/> under only the two providers the shell still needs.
function renderApp(): ReturnType<typeof render> {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <App />
      </ToastProvider>
    </QueryClientProvider>,
  )
}

// Deep-link before render: HashRouter reads window.location.hash on mount.
function gotoHash(hash: string): void {
  window.location.hash = hash
}

// Every route the landing Dashboard touches: scans list + the three KPI/widget feeds.
function stubDashboard(): void {
  server.use(
    http.get("*/api/v1/scans", () => HttpResponse.json([])),
    http.get("*/api/v1/preflight", () => HttpResponse.json([])),
    http.get("*/api/v1/costs/summary", () =>
      HttpResponse.json({ totalEstCostUsd: 0, actions: 0, bySkill: {} })),
    http.get("*/api/v1/defects", () => HttpResponse.json([])),
  )
}

// The sidebar items, exactly as the grouped NAV_GROUPS in App.tsx render them (English source labels).
const NAV_LABELS = [
  "Overview", "Get started", "Validate a service", "Defects", "Test Strategy", "Multi-source",
  "Test Plans", "Test Cases", "Reviews", "Generate API Tests", "Local generation",
  "Gates", "Cost", "Glossary", "Settings",
]

afterEach(() => {
  window.location.hash = ""
})

describe("App shell — sidebar", () => {
  it("renders the Veritas brand and tagline", async () => {
    stubDashboard()
    gotoHash("#/")
    renderApp()

    expect(await screen.findByText("Veritas")).toBeInTheDocument()
    expect(screen.getByText("API Quality Platform")).toBeInTheDocument()
    expect(screen.getByText("QE · National Bank of Canada")).toBeInTheDocument()
  })

  it("shows the simulated-data badge when the active engine is the mock", async () => {
    stubDashboard()
    gotoHash("#/")
    renderApp()
    // The base handlers report the mock engine, so the header warns that results are simulated.
    expect(await screen.findByText("Simulated data")).toBeInTheDocument()
  })

  it("renders every nav link from the NAV array as a link role", async () => {
    stubDashboard()
    gotoHash("#/")
    renderApp()

    const nav = await screen.findByRole("navigation")
    for (const label of NAV_LABELS) {
      expect(within(nav).getByRole("link", { name: label })).toBeInTheDocument()
    }
  })

  it("points each nav link at its hash route (href reflects the `to` target)", async () => {
    stubDashboard()
    gotoHash("#/")
    renderApp()

    const nav = await screen.findByRole("navigation")
    expect(within(nav).getByRole("link", { name: "Cost" })).toHaveAttribute("href", "#/costs")
    expect(within(nav).getByRole("link", { name: "Defects" })).toHaveAttribute("href", "#/defects")
    expect(within(nav).getByRole("link", { name: "Settings" })).toHaveAttribute("href", "#/settings")
    expect(within(nav).getByRole("link", { name: "Glossary" })).toHaveAttribute("href", "#/glossary")
  })
})

describe("App shell — landing route", () => {
  it("renders the Dashboard (Overview) on the root hash", async () => {
    stubDashboard()
    gotoHash("#/")
    renderApp()

    expect(await screen.findByRole("heading", { name: "Overview" })).toBeInTheDocument()
    expect(screen.getByText("API accuracy, test coverage and cost across your services.")).toBeInTheDocument()
  })

  it("deep-links straight to the Cost page when the hash points there", async () => {
    stubDashboard()
    gotoHash("#/costs")
    renderApp()

    expect(await screen.findByRole("heading", { name: "AI spend" })).toBeInTheDocument()
  })
})

describe("App shell — dark-mode toggle", () => {
  it("starts in light mode showing the Moon (Toggle theme) button", async () => {
    stubDashboard()
    gotoHash("#/")
    renderApp()

    expect(await screen.findByRole("button", { name: "Toggle theme" })).toBeInTheDocument()
    expect(document.documentElement).not.toHaveClass("dark")
  })

  it("flips the <html> dark class on when the toggle is clicked", async () => {
    stubDashboard()
    gotoHash("#/")
    const user = userEvent.setup()
    renderApp()

    const toggle = await screen.findByRole("button", { name: "Toggle theme" })
    expect(document.documentElement).not.toHaveClass("dark")
    await user.click(toggle)
    expect(document.documentElement).toHaveClass("dark")
  })

  it("flips dark mode back off on a second click (and persists the choice)", async () => {
    stubDashboard()
    gotoHash("#/")
    const user = userEvent.setup()
    renderApp()

    const toggle = await screen.findByRole("button", { name: "Toggle theme" })
    await user.click(toggle)
    expect(document.documentElement).toHaveClass("dark")
    expect(localStorage.getItem("veritas-theme")).toBe("dark")
    await user.click(toggle)
    expect(document.documentElement).not.toHaveClass("dark")
    expect(localStorage.getItem("veritas-theme")).toBe("light")
  })
})

describe("App shell — navigation changes the route", () => {
  it("clicking Cost renders the Costs page heading", async () => {
    stubDashboard()
    gotoHash("#/")
    const user = userEvent.setup()
    renderApp()

    expect(await screen.findByRole("heading", { name: "Overview" })).toBeInTheDocument()
    await user.click(screen.getByRole("link", { name: "Cost" }))
    expect(await screen.findByRole("heading", { name: "AI spend" })).toBeInTheDocument()
  })

  it("clicking Defects renders the Defects page heading", async () => {
    stubDashboard()
    gotoHash("#/")
    const user = userEvent.setup()
    renderApp()

    expect(await screen.findByRole("heading", { name: "Overview" })).toBeInTheDocument()
    await user.click(screen.getByRole("link", { name: "Defects" }))
    expect(await screen.findByRole("heading", { name: "Defects" })).toBeInTheDocument()
  })

  it("clicking Settings renders the Settings page heading", async () => {
    stubDashboard()
    server.use(
      http.get("*/api/v1/settings/connections", () =>
        HttpResponse.json({ bitbucket: {}, jira: {}, confluence: {}, xray: {} })),
      http.get("*/api/v1/settings/secrets", () => HttpResponse.json({})),
    )
    gotoHash("#/")
    const user = userEvent.setup()
    renderApp()

    expect(await screen.findByRole("heading", { name: "Overview" })).toBeInTheDocument()
    await user.click(screen.getByRole("link", { name: "Settings" }))
    expect(await screen.findByRole("heading", { name: "Settings" })).toBeInTheDocument()
  })

  it("returns to the Overview when the Overview link is clicked from another page", async () => {
    stubDashboard()
    gotoHash("#/costs")
    const user = userEvent.setup()
    renderApp()

    expect(await screen.findByRole("heading", { name: "AI spend" })).toBeInTheDocument()
    await user.click(screen.getByRole("link", { name: "Overview" }))
    expect(await screen.findByRole("heading", { name: "Overview" })).toBeInTheDocument()
  })
})