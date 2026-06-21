import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { api, CoverageItem, Deliverable, TestPlan } from '../api'

const RISK_COLOR: Record<string, string> = {
  'VERY HIGH': '#6B21A8', VH: '#6B21A8', HIGH: '#C2122D', H: '#C2122D',
  MEDIUM: '#C2410C', M: '#C2410C', LOW: '#CA8A04', L: '#CA8A04', 'VERY LOW': '#3A4658', VL: '#3A4658',
}
const MATCH_COLOR: Record<string, string> = {
  MATCHED: '#1E8E5A', CREATED: '#8A6A1E', GAP: '#C2410C', ORPHAN: '#6B7280', DEAD: '#6B7280',
}

function Badge({ text, color }: { text: string; color: string }) {
  return <span style={{ background: color, color: '#fff', fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 999 }}>{text}</span>
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section style={{ marginTop: 22 }}>
      <h2 style={{ fontSize: 16, borderBottom: '1px solid #E3E6EB', paddingBottom: 6 }}>{title}</h2>
      {children}
    </section>
  )
}

export function TestPlanDetail() {
  const { id } = useParams()
  const [plan, setPlan] = useState<TestPlan | null>(null)
  const [coverage, setCoverage] = useState<CoverageItem[]>([])
  const [err, setErr] = useState('')

  useEffect(() => {
    if (!id) return
    api.testPlan(id).then((r) => { setPlan(r.plan); setCoverage(r.coverage || []) }).catch((e) => setErr(String(e)))
  }, [id])

  if (err) return <p className="muted">Could not load plan ({err}).</p>
  if (!plan) return <p className="muted">Loading…</p>

  let d: Deliverable = {}
  try { d = plan.deliverableJson ? JSON.parse(plan.deliverableJson) : {} } catch { /* keep empty */ }
  const conf = d.selfReview?.confidence ?? plan.confidence

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
        <h1 style={{ marginBottom: 0 }}>
          {plan.serviceName} — Release Test Plan {plan.fixVersion ? `(${plan.fixVersion})` : ''}
        </h1>
        {conf != null && (
          <div style={{ textAlign: 'center' }}>
            <div style={{ fontSize: 26, fontWeight: 700, color: conf >= 70 ? '#1E8E5A' : '#C2410C' }}>{Math.round(conf)}%</div>
            <div className="muted" style={{ fontSize: 11 }}>self-review confidence</div>
          </div>
        )}
      </div>
      <p className="muted">{plan.kind} · {plan.status} · est. ${(plan.estCostUsd ?? 0).toFixed(4)}</p>

      {d.executiveSummary && (
        <Section title="Executive summary"><p>{d.executiveSummary}</p></Section>
      )}

      {d.scope && (
        <Section title="Scope & objectives">
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <div><strong>Objectives</strong><ul>{(d.scope.objectives || []).map((x, i) => <li key={i}>{x}</li>)}</ul></div>
            <div><strong>In scope</strong><ul>{(d.scope.inScope || []).map((x, i) => <li key={i}>{x}</li>)}</ul>
              <strong>Out of scope</strong><ul>{(d.scope.outOfScope || []).map((x, i) => <li key={i}>{x}</li>)}</ul></div>
          </div>
          {d.scope.assumptions?.length ? <p className="muted">Assumptions: {d.scope.assumptions.join('; ')}</p> : null}
        </Section>
      )}

      {d.riskRegister?.length ? (
        <Section title={`Risk register (${d.riskRegister.length})`}>
          <table>
            <thead><tr><th>ID</th><th>Risk</th><th>Quality char.</th><th>L</th><th>I</th><th>Level</th><th>Mitigation</th><th>Cite</th></tr></thead>
            <tbody>
              {d.riskRegister.map((r) => (
                <tr key={r.id}>
                  <td>{r.id}</td><td>{r.description}</td><td>{r.qualityCharacteristic ?? '—'}</td>
                  <td>{r.likelihood ?? '—'}</td><td>{r.impact ?? '—'}</td>
                  <td><Badge text={r.level} color={RISK_COLOR[r.level?.toUpperCase()] || '#3A4658'} /></td>
                  <td>{r.mitigation ?? '—'}</td><td className="muted">{r.citation ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
      ) : null}

      {d.testApproach && (
        <Section title="Test approach">
          <p><strong>Levels:</strong> {(d.testApproach.levels || []).join(', ')} · <strong>Types:</strong> {(d.testApproach.types || []).join(', ')}</p>
          {d.testApproach.techniques?.length ? (
            <table>
              <thead><tr><th>Technique</th><th>Rationale</th><th>Cite</th></tr></thead>
              <tbody>{d.testApproach.techniques.map((t, i) => (
                <tr key={i}><td>{t.name}</td><td>{t.rationale ?? '—'}</td><td className="muted">{t.citation ?? ''}</td></tr>
              ))}</tbody>
            </table>
          ) : null}
          {d.testApproach.entryCriteria?.length ? <p className="muted">Entry: {d.testApproach.entryCriteria.join('; ')}</p> : null}
        </Section>
      )}

      <Section title={`Requirements Traceability Matrix (${coverage.length})`}>
        <table>
          <thead><tr><th>Requirement</th><th>Required case</th><th>Dimension</th><th>Status</th><th>Matched test</th></tr></thead>
          <tbody>
            {coverage.map((c, i) => (
              <tr key={i}>
                <td>{c.requirementKey ?? '—'}</td><td>{c.requiredCaseRef}</td><td>{c.dimension}</td>
                <td><Badge text={c.matchStatus} color={MATCH_COLOR[c.matchStatus] || '#6B7280'} /></td>
                <td>{c.matchedTestKey ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>

      {d.exitCriteria?.length ? (
        <Section title="Exit criteria (S.M.A.R.T.)">
          <table>
            <thead><tr><th>Criterion</th><th>Metric</th><th>SMART</th><th>Cite</th></tr></thead>
            <tbody>{d.exitCriteria.map((e, i) => (
              <tr key={i}><td>{e.criterion}</td><td>{e.metric ?? '—'}</td><td>{e.smart ? '✓' : '—'}</td><td className="muted">{e.citation ?? ''}</td></tr>
            ))}</tbody>
          </table>
        </Section>
      ) : null}

      {d.estimation && (
        <Section title="Estimation">
          <p>{d.estimation.technique ?? '—'} · {d.estimation.effortDays != null ? `${d.estimation.effortDays} person-days` : '—'} <span className="muted">{d.estimation.basis ?? ''} {d.estimation.citation ?? ''}</span></p>
        </Section>
      )}

      {d.selfReview && (
        <Section title="Self-review">
          {d.selfReview.rubricChecks?.length ? (
            <ul>{d.selfReview.rubricChecks.map((c, i) => (
              <li key={i}>{c.pass ? '✓' : '✗'} {c.check}{c.note ? <span className="muted"> — {c.note}</span> : null}</li>
            ))}</ul>
          ) : null}
          {d.selfReview.blindSpots?.length ? (
            <><strong>Blind spots</strong><ul>{d.selfReview.blindSpots.map((b, i) => <li key={i} style={{ color: '#C2410C' }}>{b}</li>)}</ul></>
          ) : null}
        </Section>
      )}
    </div>
  )
}
