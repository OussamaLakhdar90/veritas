import { useEffect, useState } from 'react'
import { api, CodegenRun } from '../api'

function buildBadge(status?: string) {
  const map: Record<string, string> = { PASS: 'sev-info', REPAIRED: 'sev-minor', FAIL: 'sev-major', SKIPPED: 'sev-minor' }
  return <span className={map[status ?? ''] ?? 'sev-minor'}>{status ?? '—'}</span>
}

function parseList(json?: string): string[] {
  if (!json) return []
  try {
    const v = JSON.parse(json)
    return Array.isArray(v) ? v : []
  } catch {
    return []
  }
}

export function Codegen() {
  const [runs, setRuns] = useState<CodegenRun[] | null>(null)
  const [sel, setSel] = useState<CodegenRun | null>(null)
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const [repoSlug, setRepoSlug] = useState('')

  function load() {
    api.codegenRuns().then(setRuns).catch((e) => setErr(String(e)))
  }
  useEffect(load, [])

  async function publish(run: CodegenRun, allowFailedBuild = false) {
    if (!repoSlug) {
      setErr('Enter the output repo slug to open a PR.')
      return
    }
    setBusy(true)
    setErr('')
    try {
      const updated = await api.publishCodegen(run.id, repoSlug, 'main', allowFailedBuild)
      setSel(updated)
      load()
    } catch (e) {
      setErr(String(e))
    } finally {
      setBusy(false)
    }
  }

  if (err && !runs) return <p className="muted">No codegen runs yet ({err}).</p>
  if (!runs) return <p className="muted">Loading…</p>

  return (
    <div>
      <h1>Generate Tests</h1>
      <p className="muted">Template-driven test generation. Inspect a run's files, build status, and TODOs, then Approve &amp; Open PR.</p>
      {err && <p className="sev-major">{err}</p>}
      <div style={{ display: 'flex', gap: 16 }}>
        <div style={{ flex: '0 0 320px' }}>
          <table>
            <thead><tr><th>Service</th><th>Build</th></tr></thead>
            <tbody>
              {runs.length === 0 && <tr><td colSpan={2} className="muted">No runs. Trigger via POST /services/{'{'}service{'}'}/implement-tests or the CLI.</td></tr>}
              {runs.map((r) => (
                <tr key={r.id} style={{ cursor: 'pointer', background: sel?.id === r.id ? '#F1F3F6' : undefined }} onClick={() => setSel(r)}>
                  <td>{r.serviceName}</td>
                  <td>{buildBadge(r.buildStatus)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div style={{ flex: 1 }}>
          {!sel ? <p className="muted">Select a run.</p> : (
            <div>
              <h2 style={{ fontSize: 16 }}>{sel.serviceName} — build {buildBadge(sel.buildStatus)}</h2>
              <div className="card" style={{ margin: '8px 0' }}>
                <strong>Generated files</strong>
                <ul>{parseList(sel.filesWritten).map((f) => <li key={f}><code>{f}</code></li>)}</ul>
                {parseList(sel.todos).length > 0 && (
                  <>
                    <strong>TODOs (data/IDs that must pre-exist)</strong>
                    <ul>{parseList(sel.todos).map((t, i) => <li key={i}>{t}</li>)}</ul>
                  </>
                )}
              </div>
              {sel.prUrl ? (
                <p>PR opened: <a href={sel.prUrl} target="_blank" rel="noreferrer">{sel.prUrl}</a></p>
              ) : (
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  <input placeholder="output repo slug" value={repoSlug} onChange={(e) => setRepoSlug(e.target.value)} />
                  <button onClick={() => publish(sel)} disabled={busy}>{busy ? 'Publishing…' : 'Approve & Open PR'}</button>
                  {sel.buildStatus === 'FAIL' && (
                    <button onClick={() => publish(sel, true)} disabled={busy} title="Build failed — override the no-PR-on-FAIL guard">
                      Override (build failed)
                    </button>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
