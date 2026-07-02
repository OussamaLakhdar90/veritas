import { describe, expect, it } from "vitest"
import { screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { useQueryClient } from "@tanstack/react-query"
import { http, HttpResponse } from "msw"
import { server } from "./msw/server"
import { renderPage } from "./render"
import { api } from "../api"
import { useCopilotAuth } from "../lib/copilotAuth"

// ── activityCenter harness ───────────────────────────────────────────────────
// The ActivityCenterProvider (mounted by renderPage) owns the ['activity'] poll. This harness forces the next
// poll on demand so a test can observe a status TRANSITION (RUNNING → COMPLETED) without fake timers.
function RefetchHarness() {
  const qc = useQueryClient()
  return (
    <button onClick={() => qc.invalidateQueries({ queryKey: ["activity"] })}>Refetch</button>
  )
}

const activityItem = (over: Record<string, unknown> = {}) => ({
  id: "act-1",
  type: "SCAN",
  label: "ciam-policies",
  status: "RUNNING",
  stage: "DIFFING",
  detail: "",
  needsAttention: false,
  startedAt: new Date().toISOString(),
  finishedAt: null,
  link: "/findings/scan-1",
  acked: false,
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

describe("activityCenter provider", () => {
  it("shows a dock card with the label and plain-language status for a RUNNING item", async () => {
    server.use(http.get("*/api/v1/activity", () => HttpResponse.json([activityItem()])))
    renderPage(<div />)

    expect(await screen.findByText("ciam-policies")).toBeInTheDocument()
    expect(screen.getByText("Running")).toBeInTheDocument()
  })

  it("hides terminal items unless they need attention and are not yet acked", async () => {
    server.use(http.get("*/api/v1/activity", () => HttpResponse.json([
      activityItem({ id: "a", label: "done-quiet", status: "COMPLETED" }),
      activityItem({ id: "b", label: "done-acked", status: "COMPLETED", needsAttention: true, acked: true }),
      activityItem({ id: "c", label: "failed-loud", status: "FAILED", needsAttention: true }),
    ])))
    renderPage(<div />)

    expect(await screen.findByText("failed-loud")).toBeInTheDocument()
    expect(screen.queryByText("done-quiet")).not.toBeInTheDocument()
    expect(screen.queryByText("done-acked")).not.toBeInTheDocument()
  })

  it("the card label links to the item's dashboard route", async () => {
    server.use(http.get("*/api/v1/activity", () => HttpResponse.json([activityItem()])))
    const user = userEvent.setup()
    renderPage(<div />, {
      extraRoutes: [{ path: "/findings/:scanId", element: <div>findings-page</div> }],
    })

    await user.click(await screen.findByRole("link", { name: "ciam-policies" }))
    expect(await screen.findByText("findings-page")).toBeInTheDocument()
  })

  it("the Dismiss X acks the item on the server and removes its card optimistically", async () => {
    let ackedIds: string[] = []
    server.use(
      http.get("*/api/v1/activity", () =>
        HttpResponse.json([activityItem({ label: "broken-train", status: "FAILED", needsAttention: true })])),
      http.post("*/api/v1/activity/ack", async ({ request }) => {
        ackedIds = ((await request.json()) as { ids: string[] }).ids
        return new HttpResponse(null, { status: 200 })
      }),
    )
    const user = userEvent.setup()
    renderPage(<div />)

    expect(await screen.findByText("broken-train")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "Dismiss" }))
    await waitFor(() => expect(screen.queryByText("broken-train")).not.toBeInTheDocument())
    await waitFor(() => expect(ackedIds).toEqual(["act-1"]))
  })

  it("toasts on an observed RUNNING → COMPLETED transition and records the dedup key", async () => {
    let polls = 0
    server.use(http.get("*/api/v1/activity", () => {
      polls += 1
      return HttpResponse.json([activityItem({ status: polls === 1 ? "RUNNING" : "COMPLETED", needsAttention: true })])
    }))
    const user = userEvent.setup()
    renderPage(<RefetchHarness />)

    expect(await screen.findByText("Running")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "Refetch" }))
    expect(await screen.findByText("ciam-policies finished")).toBeInTheDocument()
    const notified = JSON.parse(localStorage.getItem("veritas.activity.notified") as string)
    expect(notified).toContain("act-1:COMPLETED")
  })

  it("never re-toasts a transition already recorded in the dedup store (reload safety)", async () => {
    localStorage.setItem("veritas.activity.notified", JSON.stringify(["act-1:COMPLETED"]))
    let polls = 0
    server.use(http.get("*/api/v1/activity", () => {
      polls += 1
      return HttpResponse.json([activityItem({ status: polls === 1 ? "RUNNING" : "COMPLETED", needsAttention: true })])
    }))
    const user = userEvent.setup()
    renderPage(<RefetchHarness />)

    expect(await screen.findByText("Running")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "Refetch" }))
    // The card flips to its terminal state, but the already-announced toast must NOT reappear.
    expect(await screen.findByText("Completed")).toBeInTheDocument()
    expect(screen.queryByText("ciam-policies finished")).not.toBeInTheDocument()
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