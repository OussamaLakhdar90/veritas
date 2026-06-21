import { HashRouter, Routes, Route, NavLink } from 'react-router-dom'
import { Dashboard } from './pages/Dashboard'
import { RepoPicker } from './pages/RepoPicker'
import { Findings } from './pages/Findings'
import { TestPlans } from './pages/TestPlans'
import { TestPlanDetail } from './pages/TestPlanDetail'
import { TestCases } from './pages/TestCases'
import { Codegen } from './pages/Codegen'
import { Costs } from './pages/Costs'
import { Defects } from './pages/Defects'
import { Gates } from './pages/Gates'

export function App() {
  return (
    <HashRouter>
      <div className="app">
        <aside className="side">
          <div className="brand"><span className="mark">V</span> Veritas</div>
          <nav>
            <NavLink to="/" end>Dashboard</NavLink>
            <NavLink to="/repos">Validate (app-id)</NavLink>
            <NavLink to="/defects">Defects</NavLink>
            <NavLink to="/test-plans">Test Plans</NavLink>
            <NavLink to="/test-cases">Test Cases</NavLink>
            <NavLink to="/generate-tests">Generate Tests</NavLink>
            <NavLink to="/gates">Gates</NavLink>
            <NavLink to="/costs">Cost</NavLink>
          </nav>
          <div className="who">QE · BNC</div>
        </aside>
        <main className="main">
          <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/repos" element={<RepoPicker />} />
            <Route path="/findings/:scanId" element={<Findings />} />
            <Route path="/defects" element={<Defects />} />
            <Route path="/test-plans" element={<TestPlans />} />
            <Route path="/test-plans/:id" element={<TestPlanDetail />} />
            <Route path="/test-cases" element={<TestCases />} />
            <Route path="/generate-tests" element={<Codegen />} />
            <Route path="/gates" element={<Gates />} />
            <Route path="/costs" element={<Costs />} />
          </Routes>
        </main>
      </div>
    </HashRouter>
  )
}
