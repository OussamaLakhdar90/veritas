import { describe, expect, it } from "vitest"
import { screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { http, HttpResponse } from "msw"
import { server } from "./msw/server"
import { renderPage } from "./render"
import { api } from "../api"
import { useBackgroundScans } from "../lib/backgroundScans"
import { useCopilotAuth } from "../lib/copilotAuth"

// ── backgroundScans harness ──────────────────────────────────────────────────
// A tiny consumer that drives the BackgroundScansProvider through its public hook. Clicking "Track" registers
// a scan, which mounts the floating dock card that polls GET /scans/:id. Returning a TERMINAL status on the
// very first fetch lets the card resolve without fake timers.
function BgHarness({ id = "scan-1", service = "ciam-policies" }: { id?: string; service?: string }) {
  const { track } = useBackgroundScans()
  return (
    <button onClick={() => track({ id, service })}>Track</button>
  )
}

const completedScan = (over: Record<string, unknown> = {}) => ({
  id: "scan-1",
  serviceName: "ciam-policies",
  status: "COMPLETED",
  stage: "DONE",
  totalFindings: 3,
  totalEstCostUsd: 0,
  startedAt: new Date().toISOString(),
  specSources: "",
  ...over,
})

// ── copilotAuth harness ──────────────────────────────────────────────────────
// Surfaces the auth context state as text so a test can assert on needsCopilot/connected, plus a button that
// fires signIn() and one that makes an API call which fails with copilot-auth-required (the reactive trigger).
function AuthHarness() {
  const { needsCopilot, connected, loading, signIn } = useCopilotAuth()
  const fireBlockedCall = async () => {
    try {
      await api.generateStrategy("ciam-policies", { basis: "x" })
    } catch {
      /* the gate's onCopilotAuthRequired hook fires inside api.fail before this rejects */
    }
  }
  return (
    <div>
      <p>needsCopilot:{String(needsCopilot)}</p>
      <p>connected:{String(connected)}</p>
      <p>loading:{String(loading)}</p>
      <button onClick={signIn}>Manual sign in</button>
      <button onClick={fireBlockedCall}>Do AI action</button>
    </div>
  )
}

const loginStart = {
  id: "login-1",
  userCode: "WXYZ-1234",
  verificationUri: "https://github.com/login/device",
  expiresIn: 900,
}

describe("backgroundScans provider", () => {
  it("track() mounts a dock card that shows the tracked service name", async () => {
    server.use(http.get("*/api/v1/scans/:id", () => HttpResponse.json(completedScan())))
    const user = userEvent.setup()
    renderPage(<BgHarness />)

    await user.click(screen.getByRole("button", { name: "Track" }))
    expect(await screen.findByText("ciam-policies")).toBeInTheDocument()
  })

  it("resolves a COMPLETED scan: success toast + a Complete summary with the finding count", async () => {
    server.use(http.get("*/api/v1/scans/:id", () => HttpResponse.json(completedScan({ totalFindings: 3 }))))
    const user = userEvent.setup()
    renderPage(<BgHarness />)

    await user.click(screen.getByRole("button", { name: "Track" }))
    // The dock announces the outcome once via a success toast (the modal is gone).
    expect(await screen.findByText("ciam-policies: scan complete — 3 findings.")).toBeInTheDocument()
    expect(await screen.findByText(/Complete · 3 findings/)).toBeInTheDocument()
  })

  it("singular-izes the finding count when exactly one finding is reported", async () => {
    server.use(http.get("*/api/v1/scans/:id", () => HttpResponse.json(completedScan({ totalFindings: 1 }))))
    const user = userEvent.setup()
    renderPage(<BgHarness />)

    await user.click(screen.getByRole("button", { name: "Track" }))
    expect(await screen.findByText("ciam-policies: scan complete — 1 finding.")).toBeInTheDocument()
  })

  it("a COMPLETED scan offers a View findings link that navigates to the findings route", async () => {
    server.use(http.get("*/api/v1/scans/:id", () => HttpResponse.json(completedScan())))
    const user = userEvent.setup()
    renderPage(<BgHarness />, {
      extraRoutes: [{ path: "/findings/:scanId", element: <div>findings-page</div> }],
    })

    await user.click(screen.getByRole("button", { name: "Track" }))
    const link = await screen.findByRole("button", { name: /View findings/ })
    await user.click(link)
    expect(await screen.findByText("findings-page")).toBeInTheDocument()
  })

  it("a FAILED scan shows a validation-failed toast and an Open scan link", async () => {
    server.use(http.get("*/api/v1/scans/:id", () =>
      HttpResponse.json(completedScan({ status: "FAILED", stage: "DIFFING", totalFindings: 0 }))))
    const user = userEvent.setup()
    renderPage(<BgHarness />)

    await user.click(screen.getByRole("button", { name: "Track" }))
    expect(await screen.findByText("ciam-policies: validation failed.")).toBeInTheDocument()
    expect(await screen.findByText("Validation failed")).toBeInTheDocument()
    expect(screen.getByRole("button", { name: /Open scan/ })).toBeInTheDocument()
  })

  it("the Dismiss (x) button removes the card from the dock", async () => {
    server.use(http.get("*/api/v1/scans/:id", () => HttpResponse.json(completedScan())))
    const user = userEvent.setup()
    renderPage(<BgHarness />)

    await user.click(screen.getByRole("button", { name: "Track" }))
    expect(await screen.findByText(/Complete · 3 findings/)).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "Dismiss" }))
    await waitFor(() => expect(screen.queryByText(/Complete · 3 findings/)).not.toBeInTheDocument())
  })

  it("persists tracked scans to localStorage under veritas-bg-scans", async () => {
    server.use(http.get("*/api/v1/scans/:id", () => HttpResponse.json(completedScan())))
    const user = userEvent.setup()
    renderPage(<BgHarness />)

    await user.click(screen.getByRole("button", { name: "Track" }))
    await screen.findByText("ciam-policies")
    await waitFor(() => {
      const raw = localStorage.getItem("veritas-bg-scans")
      expect(raw).toBeTruthy()
      const list = JSON.parse(raw as string)
      expect(list).toHaveLength(1)
      expect(list[0].id).toBe("scan-1")
      expect(list[0].service).toBe("ciam-policies")
    })
  })

  it("track() is idempotent — tracking the same id twice keeps a single card", async () => {
    server.use(http.get("*/api/v1/scans/:id", () => HttpResponse.json(completedScan())))
    const user = userEvent.setup()
    renderPage(<BgHarness />)

    await user.click(screen.getByRole("button", { name: "Track" }))
    await user.click(screen.getByRole("button", { name: "Track" }))
    expect(await screen.findByText("ciam-policies")).toBeInTheDocument()
    await waitFor(() => {
      const list = JSON.parse(localStorage.getItem("veritas-bg-scans") as string)
      expect(list).toHaveLength(1)
    })
  })

  it("restores a previously tracked scan from localStorage on mount (no Track click needed)", async () => {
    localStorage.setItem(
      "veritas-bg-scans",
      JSON.stringify([{ id: "scan-1", service: "restored-svc", startedAt: Date.now() }]),
    )
    server.use(http.get("*/api/v1/scans/:id", () => HttpResponse.json(completedScan({ totalFindings: 2 }))))
    renderPage(<BgHarness />)

    expect(await screen.findByText("restored-svc")).toBeInTheDocument()
    expect(await screen.findByText(/Complete · 2 findings/)).toBeInTheDocument()
  })
})

describe("copilotAuth provider", () => {
  it("stays quiet for the mock engine — no banner, needsCopilot is false", async () => {
    // Base handlers already report engine=mock + connected=true.
    renderPage(<AuthHarness />)

    expect(await screen.findByText("needsCopilot:false")).toBeInTheDocument()
    expect(screen.queryByText("Connect GitHub Copilot")).not.toBeInTheDocument()
  })

  it("shows the connect banner when the http engine needs Copilot and it is not connected", async () => {
    server.use(
      http.get("*/api/v1/settings/llm", () =>
        HttpResponse.json({ active: "http", desired: "http", simulated: false, model: "gpt-4o" })),
      http.get("*/api/v1/settings/copilot/status", () =>
        HttpResponse.json({ authenticated: false, connected: false })),
    )
    renderPage(<AuthHarness />)

    expect(await screen.findByText(/Connect GitHub Copilot/)).toBeInTheDocument()
    expect(await screen.findByText("needsCopilot:true")).toBeInTheDocument()
    expect(screen.getByText("connected:false")).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument()
  })

  it("does not show the banner when the http engine is already connected", async () => {
    server.use(
      http.get("*/api/v1/settings/llm", () =>
        HttpResponse.json({ active: "http", desired: "http", simulated: false, model: "gpt-4o" })),
      http.get("*/api/v1/settings/copilot/status", () =>
        HttpResponse.json({ authenticated: true, connected: true })),
    )
    renderPage(<AuthHarness />)

    expect(await screen.findByText("needsCopilot:true")).toBeInTheDocument()
    expect(screen.getByText("connected:true")).toBeInTheDocument()
    expect(screen.queryByText(/Connect GitHub Copilot/)).not.toBeInTheDocument()
  })

  it("dismissing the banner hides it", async () => {
    server.use(
      http.get("*/api/v1/settings/llm", () =>
        HttpResponse.json({ active: "http", desired: "http", simulated: false, model: "gpt-4o" })),
      http.get("*/api/v1/settings/copilot/status", () =>
        HttpResponse.json({ authenticated: false, connected: false })),
    )
    const user = userEvent.setup()
    renderPage(<AuthHarness />)

    const banner = (await screen.findByText(/Connect GitHub Copilot/)).closest("div") as HTMLElement
    await user.click(within(banner).getByRole("button", { name: "Dismiss" }))
    await waitFor(() => expect(screen.queryByText(/Connect GitHub Copilot/)).not.toBeInTheDocument())
  })

  it("clicking Sign in starts the device flow and opens the sign-in modal", async () => {
    server.use(
      http.get("*/api/v1/settings/llm", () =>
        HttpResponse.json({ active: "http", desired: "http", simulated: false, model: "gpt-4o" })),
      http.get("*/api/v1/settings/copilot/status", () =>
        HttpResponse.json({ authenticated: false, connected: false })),
      http.post("*/api/v1/settings/copilot/login/start", () => HttpResponse.json(loginStart)),
      http.get("*/api/v1/settings/copilot/login/status", () =>
        HttpResponse.json({ state: "PENDING", message: "" })),
    )
    const user = userEvent.setup()
    renderPage(<AuthHarness />)

    await user.click(await screen.findByRole("button", { name: "Sign in" }))
    expect(await screen.findByText("Sign in to GitHub Copilot")).toBeInTheDocument()
    expect(screen.getByText(loginStart.userCode)).toBeInTheDocument()
  })

  it("auto-opens the sign-in modal when an AI API call fails with copilot-auth-required", async () => {
    server.use(
      http.post("*/api/v1/settings/copilot/login/start", () => HttpResponse.json(loginStart)),
      http.get("*/api/v1/settings/copilot/login/status", () =>
        HttpResponse.json({ state: "PENDING", message: "" })),
      // The blocked AI action: server replies 401 with the RFC-7807 copilot-auth-required code.
      http.post("*/api/v1/services/:s/strategies", () =>
        HttpResponse.json({ detail: "Sign in to Copilot", code: "copilot-auth-required" }, { status: 401 })),
    )
    const user = userEvent.setup()
    renderPage(<AuthHarness />)

    // Wait for the provider to settle (base handlers → mock engine), then fire the blocked call.
    await screen.findByText("needsCopilot:false")
    await user.click(screen.getByRole("button", { name: "Do AI action" }))

    expect(await screen.findByText("Sign in to GitHub Copilot")).toBeInTheDocument()
    expect(await screen.findByText(loginStart.userCode)).toBeInTheDocument()
  })

  it("closing the sign-in modal dismisses it (Close button)", async () => {
    server.use(
      http.get("*/api/v1/settings/llm", () =>
        HttpResponse.json({ active: "http", desired: "http", simulated: false, model: "gpt-4o" })),
      http.get("*/api/v1/settings/copilot/status", () =>
        HttpResponse.json({ authenticated: false, connected: false })),
      http.post("*/api/v1/settings/copilot/login/start", () => HttpResponse.json(loginStart)),
      http.get("*/api/v1/settings/copilot/login/status", () =>
        HttpResponse.json({ state: "PENDING", message: "" })),
    )
    const user = userEvent.setup()
    renderPage(<AuthHarness />)

    await user.click(await screen.findByRole("button", { name: "Sign in" }))
    const dialog = await screen.findByRole("dialog")
    // The modal has two "Close" controls (header X + footer button) — click the footer one.
    const closeButtons = within(dialog).getAllByRole("button", { name: "Close" })
    await user.click(closeButtons[closeButtons.length - 1])
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
  })

  it("does not auto-pop sign-in for a non-Copilot API failure", async () => {
    server.use(
      // A generic 500 — no copilot-auth-required code, so the gate must stay closed.
      http.post("*/api/v1/services/:s/strategies", () =>
        HttpResponse.json({ detail: "boom", status: 500 }, { status: 500 })),
    )
    const user = userEvent.setup()
    renderPage(<AuthHarness />)

    await screen.findByText("needsCopilot:false")
    await user.click(screen.getByRole("button", { name: "Do AI action" }))

    // Give the rejected call a tick; the modal must never appear.
    await waitFor(() => expect(screen.getByText("needsCopilot:false")).toBeInTheDocument())
    expect(screen.queryByText("Sign in to GitHub Copilot")).not.toBeInTheDocument()
  })
})