package ca.bnc.qe.veritas.demo;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ca.bnc.qe.veritas.config.GateProperties;
import ca.bnc.qe.veritas.contract.FindingMapper;
import ca.bnc.qe.veritas.contract.ReleaseVerdict;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import ca.bnc.qe.veritas.persistence.CodegenRunRepository;
import ca.bnc.qe.veritas.persistence.CostEntry;
import ca.bnc.qe.veritas.persistence.CostEntryRepository;
import ca.bnc.qe.veritas.persistence.DefectLink;
import ca.bnc.qe.veritas.persistence.DefectLinkRepository;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.GateDecision;
import ca.bnc.qe.veritas.persistence.GateDecisionRepository;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.persistence.TestCase;
import ca.bnc.qe.veritas.persistence.TestCaseRepository;
import ca.bnc.qe.veritas.persistence.TestCondition;
import ca.bnc.qe.veritas.persistence.TestConditionRepository;
import ca.bnc.qe.veritas.persistence.TestPlan;
import ca.bnc.qe.veritas.persistence.TestPlanRepository;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import ca.bnc.qe.veritas.snyk.SnykAlert;
import ca.bnc.qe.veritas.snyk.SnykAlertRepository;
import ca.bnc.qe.veritas.snyk.SnykSnapshot;
import ca.bnc.qe.veritas.snyk.SnykSnapshotRepository;
import ca.bnc.qe.veritas.snyk.SnykVuln;
import ca.bnc.qe.veritas.snyk.SnykVulnRepository;
import ca.bnc.qe.veritas.snyk.SnykWatch;
import ca.bnc.qe.veritas.snyk.SnykWatchRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStep;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStepRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrain;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrainRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds a realistic 30-day demo portfolio (8 BNC-flavoured services, scans with fidelity-score history,
 * dispositioned findings, Jira defect links, AI costs, a pending approval gate, the full Snyk graph and
 * test assets) so the executive dashboard demos as a deployed fleet instead of a science project.
 *
 * HONESTY RULES: gated behind {@code veritas.demo.seed=true} (off by default), surfaces a visible
 * "demo data" badge via {@code LlmSettingsController} — a bank demo is never silently rigged. Idempotent:
 * skips entirely when any scan already exists. Synthetic Bitbucket/Jira links are plausible but DO NOT
 * resolve — the presenter clicks evidence only on a live scan. Leave {@code veritas.snyk.poll-enabled}
 * false in demo mode, or the real poller overwrites the seeded security posture at boot.
 *
 * Fix trains are seeded only in states the startup reconcilers leave alone (DONE / AWAITING_CONFIRM);
 * finding rows exist for each service's LATEST scan (the one every dashboard read path uses).
 */
@Component
@ConditionalOnProperty(name = "veritas.demo.seed", havingValue = "true")
@Slf4j
public class DemoPortfolioSeeder implements ApplicationRunner {

    /** name, appId, fidelity ramp oldest→latest (latest drives the scorecard letter grade). */
    private record Svc(String name, String appId, int[] scores) {}

    private static final List<Svc> SERVICES = List.of(
            new Svc("ciam-policies", "APP7571", new int[]{71, 76, 80, 84}),
            new Svc("payments-transfer", "APP7580", new int[]{78, 83, 88, 92}),
            new Svc("cards-activation", "APP7412", new int[]{69, 74, 81, 87}),
            new Svc("accounts-summary", "APP7355", new int[]{85, 89, 93, 95}),
            new Svc("onboarding-kyc", "APP7628", new int[]{72, 77, 79, 83}),
            new Svc("fx-rates", "APP7290", new int[]{88, 90, 94, 96}),
            new Svc("loans-pricing", "APP7501", new int[]{55, 58, 60, 62}),   // the D — the question the VP asks
            new Svc("notifications-hub", "APP7733", new int[]{80, 84, 86, 91}));

    private static final String OWNER = "demo";
    private static final String MODEL = "claude-sonnet-4.6";
    private static final int[] SCAN_DAYS_AGO = {27, 18, 9, 2};
    /** Statuses a human used to dismiss a finding — excluded from the release verdict (mirrors the gate). */
    private static final Set<String> DISMISSED = Set.of("REJECTED", "FALSE_POSITIVE", "WONT_FIX");

    private final ScanRepository scans;
    private final FindingRecordRepository findings;
    private final CostEntryRepository costs;
    private final DefectLinkRepository defects;
    private final GateDecisionRepository gates;
    private final SnykWatchRepository watches;
    private final SnykSnapshotRepository snapshots;
    private final SnykVulnRepository vulns;
    private final SnykAlertRepository alerts;
    private final SnykFixTrainRepository trains;
    private final SnykFixStepRepository steps;
    private final TestStrategyRepository strategies;
    private final TestConditionRepository conditions;
    private final TestCaseRepository cases;
    private final TestPlanRepository plans;
    private final CodegenRunRepository codegenRuns;
    private final JdbcTemplate jdbc;
    private final GateProperties gate;

    @SuppressWarnings("java:S107")   // a seeder legitimately touches every aggregate root once
    public DemoPortfolioSeeder(ScanRepository scans, FindingRecordRepository findings, CostEntryRepository costs,
                               DefectLinkRepository defects, GateDecisionRepository gates,
                               SnykWatchRepository watches, SnykSnapshotRepository snapshots,
                               SnykVulnRepository vulns, SnykAlertRepository alerts,
                               SnykFixTrainRepository trains, SnykFixStepRepository steps,
                               TestStrategyRepository strategies, TestConditionRepository conditions,
                               TestCaseRepository cases, TestPlanRepository plans,
                               CodegenRunRepository codegenRuns, JdbcTemplate jdbc, GateProperties gate) {
        this.scans = scans;
        this.findings = findings;
        this.costs = costs;
        this.defects = defects;
        this.gates = gates;
        this.watches = watches;
        this.snapshots = snapshots;
        this.vulns = vulns;
        this.alerts = alerts;
        this.trains = trains;
        this.steps = steps;
        this.strategies = strategies;
        this.conditions = conditions;
        this.cases = cases;
        this.plans = plans;
        this.codegenRuns = codegenRuns;
        this.jdbc = jdbc;
        this.gate = gate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (scans.count() > 0) {
            log.info("Demo seed skipped — the database already holds {} scan(s)", scans.count());
            return;
        }
        log.warn("Seeding the DEMO portfolio (veritas.demo.seed=true) — synthetic data, badge shown in the UI");
        List<Scan> latest = seedScans();
        List<FindingRecord> latestFindings = seedFindings(latest);
        seedDefects(latestFindings);
        seedCosts(latest);
        seedGates(latest);
        seedSnyk();
        seedTestAssets();
        log.info("Demo portfolio seeded: {} services, {} scans, {} findings",
                SERVICES.size(), scans.count(), findings.count());
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS).plus(10, ChronoUnit.HOURS);
    }

    /** Four scans per service over ~30 days; each carries a persisted release verdict + severity counts. */
    private List<Scan> seedScans() {
        List<Scan> latestPerService = new ArrayList<>();
        for (Svc svc : SERVICES) {
            Scan last = null;
            for (int i = 0; i < svc.scores().length; i++) {
                Scan s = new Scan();
                s.setServiceName(svc.name());
                s.setAppId(svc.appId());
                s.setRepoSlug(svc.name());
                s.setGitRef("develop");
                s.setSpecSources("contract/openapi.yaml");
                s.setStatus(RunStatus.COMPLETED);
                s.setStage("DONE");
                s.setModel(MODEL);
                s.setOwner(OWNER);
                Instant start = daysAgo(SCAN_DAYS_AGO[i]);
                s.setQueuedAt(start);
                s.setStartedAt(start);
                s.setFinishedAt(start.plus(3 + i, ChronoUnit.MINUTES).plusSeconds(42));
                // Demo release verdict from the quality proxy: high = clean PASS, mid = additive-drift WARN,
                // low = consumer-breaking FAIL (a FAIL always carries ≥1 breaking finding, per the gate).
                int q = svc.scores()[i];
                s.setReleaseSafe(q >= 90 ? "PASS" : q >= 78 ? "WARN" : "FAIL");
                s.setBlockingCount(q < 65 ? 1 : 0);
                s.setBreakingCount(q >= 78 ? 0 : Math.max(1, (85 - q) / 8));
                s.setTotalFindings(Math.max(1, (100 - svc.scores()[i]) / 6));
                s.setTotalEstCostUsd(0.30 + (i * 0.04));
                s.setConfidence(88.0 + i * 2);
                s.setCoverageGaps(svc.scores()[i] >= 90 ? 0 : 1 + (i % 2));
                last = scans.save(s);
            }
            latestPerService.add(last);
        }
        return latestPerService;
    }

    /**
     * Dispositioned findings on each service's LATEST scan — the human-in-the-loop governance proof. Findings are
     * seeded to MATCH the service's release band (clean PASS = no gating findings, WARN = non-breaking additive drift,
     * FAIL = consumer-breaking findings), then the scan's persisted verdict + counts are RE-DERIVED from those findings
     * via the same {@link ReleaseVerdict} the dashboard recomputes live — so the recent-scans row and the executive
     * rollup can never disagree.
     */
    private List<FindingRecord> seedFindings(List<Scan> latest) {
        List<FindingRecord> out = new ArrayList<>();
        for (int i = 0; i < latest.size(); i++) {
            Scan scan = latest.get(i);
            boolean clean = "PASS".equals(scan.getReleaseSafe());
            boolean weak = "FAIL".equals(scan.getReleaseSafe());
            List<FindingRecord> svc = new ArrayList<>();
            // A disputed advisory on every service — the AI-dispute channel is excluded from the gate, so it never
            // moves the verdict (a positional path-variable rename is non-breaking anyway) yet keeps a clean PASS honest.
            svc.add(finding(scan, "PATH_VAR_NAME_MISMATCH", "MINOR", "GET /" + scan.getServiceName() + "/{id}",
                    "Path variable named {app} in the spec vs {appId} in the code — positional, non-breaking; a naming convention decision.",
                    "OPEN", true, i));
            if (!clean) {
                // WARN + FAIL: non-breaking additive / documentation drift → at least WARN. One is filed as a Jira
                // defect (JIRA_CREATED, still counted) so the defect donut has volume without needing breaking findings.
                svc.add(finding(scan, "SCHEMA_FIELD_MISSING", "MINOR", "GET /" + scan.getServiceName() + "/summary",
                        "Response field `lastUpdatedBy` exists in the code but is not documented in the spec.",
                        "ACCEPTED", false, i));
                svc.add(finding(scan, "STATUS_CODE_MISSING", "MINOR", "GET /" + scan.getServiceName() + "/{id}",
                        "The code can return 404 (service layer throw) — the spec documents only 200.",
                        "JIRA_CREATED", false, i));
            }
            if (weak) {
                // FAIL: the consumer-breaking findings that drive the gate to FAIL.
                svc.add(finding(scan, "SCHEMA_FIELD_TYPE_MISMATCH", "MAJOR", "POST /" + scan.getServiceName(),
                        "Field `amount` is a string in the spec but a numeric BigDecimal in the code — a consumer parsing per spec breaks.",
                        "JIRA_CREATED", false, i));
                svc.add(finding(scan, "MISSING_ENDPOINT", "CRITICAL", "DELETE /" + scan.getServiceName() + "/{id}",
                        "Endpoint exists in the code but is absent from the spec — an undocumented (shadow) API surface.",
                        "OPEN", false, i));
                svc.add(finding(scan, "PARAM_REQUIRED_MISMATCH", "MAJOR", "GET /" + scan.getServiceName(),
                        "Query param `context` is required by the code but optional in the spec.",
                        "JIRA_CREATED", false, i));
            }
            if (i % 4 == 1) {
                // A dead-spec finding a human already dismissed (REJECTED → excluded from the gate) — disposition variety.
                svc.add(finding(scan, "SPEC_DRIFT", "MINOR", "GET /" + scan.getServiceName() + "/health",
                        "Spec documents an endpoint the code no longer serves (dead spec).", "REJECTED", false, i));
            }
            // Re-derive the persisted verdict + counts from the ACTUAL findings (excluding human-dismissed, exactly as
            // the executive rollup does) so persisted and live can never disagree.
            List<Finding> live = svc.stream()
                    .filter(r -> r.getStatus() == null || !DISMISSED.contains(r.getStatus()))
                    .map(FindingMapper::toFinding).toList();
            ReleaseVerdict verdict = ReleaseVerdict.of(live, gate);
            scan.setReleaseSafe(verdict.releaseSafe());
            scan.setBlockingCount((int) verdict.blocking());
            scan.setBreakingCount((int) verdict.breaking());
            scan.setTotalFindings(svc.size());
            scans.save(scan);
            out.addAll(svc);
        }
        return findings.saveAll(out);
    }

    @SuppressWarnings("java:S107")
    private FindingRecord finding(Scan scan, String type, String severity, String endpoint, String summary,
                                  String status, boolean aiDisputed, int seed) {
        FindingRecord r = new FindingRecord();
        r.setScanId(scan.getId());
        r.setFingerprint(UUID.nameUUIDFromBytes((scan.getServiceName() + type + endpoint).getBytes()).toString());
        r.setType(type);
        r.setLayer("L4");
        r.setSeverity(severity);
        r.setConfidence("HIGH");
        r.setOrigin("DETERMINISTIC");
        r.setEndpoint(endpoint);
        r.setSpecSource("contract/openapi.yaml");
        r.setSummary(summary);
        r.setStatus(status);
        r.setAiDisputed(aiDisputed);
        if (aiDisputed) {
            r.setAiDisputeReason("The AI re-checked its own finding and flagged it as a likely convention choice, not a defect.");
        }
        r.setCodeFile("src/main/java/ca/bnc/" + scan.getServiceName().replace('-', '/') + "/api/Controller.java");
        r.setCodeStartLine(40 + (seed * 7) % 90);
        r.setCodeEndLine(44 + (seed * 7) % 90);
        if (!"OPEN".equals(status)) {
            r.setReviewedBy("m.tremblay");
            r.setReviewedAt(daysAgo(1 + seed % 3));
            r.setReviewNote("REJECTED".equals(status) ? "Known consumer contract — documented exception." : "Confirmed against the code.");
        }
        return r;
    }

    /** Jira defect links with a done / in-progress mix — feeds the defect donut + resolution gauge. */
    private void seedDefects(List<FindingRecord> latestFindings) {
        String[][] jira = {
                {"DEMO-101", "Done", "done"}, {"DEMO-102", "Done", "done"}, {"DEMO-103", "Done", "done"},
                {"DEMO-104", "In Progress", "indeterminate"}, {"DEMO-105", "In Progress", "indeterminate"},
                {"DEMO-106", "To Do", "new"}};
        List<FindingRecord> jiraFindings = latestFindings.stream()
                .filter(f -> "JIRA_CREATED".equals(f.getStatus())).limit(jira.length).toList();
        for (int i = 0; i < jiraFindings.size(); i++) {
            FindingRecord f = jiraFindings.get(i);
            DefectLink d = new DefectLink();
            d.setFindingId(f.getId());
            d.setScanId(f.getScanId());
            d.setServiceName(serviceOf(f.getScanId()));
            d.setSeverity(f.getSeverity());
            d.setJiraKey(jira[i][0]);
            d.setJiraUrl("https://jira.bnc.ca/browse/" + jira[i][0]);
            d.setJiraStatus(jira[i][1]);
            d.setJiraStatusCategory(jira[i][2]);
            d.setCreatedInJira(true);
            d.setCreatedBy(OWNER);
            d.setLastSyncedAt(daysAgo(0));
            d = defects.save(d);
            backdate("defect_link", d.getId(), daysAgo(12 - i));
        }
    }

    private String serviceOf(String scanId) {
        return scans.findById(scanId).map(Scan::getServiceName).orElse("unknown");
    }

    /** AI spend over 30 days incl. snyk-fix entries (the security card's "AI cost" reads that skill). */
    private void seedCosts(List<Scan> latest) {
        for (Scan s : latest) {
            for (int day : SCAN_DAYS_AGO) {
                CostEntry c = new CostEntry();
                c.setSkill("validate-contract");
                c.setAction("reconcile");
                c.setModel(MODEL);
                c.setBillingMode("USAGE_CREDITS");
                c.setEstTokensIn(48_000);
                c.setEstTokensOut(9_500);
                c.setEstCostUsd(0.42);
                c.setOwner(OWNER);
                c.setRefId(s.getId());
                c = costs.save(c);
                backdate("cost_entry", c.getId(), daysAgo(day));
            }
        }
        for (int i = 0; i < 3; i++) {
            CostEntry c = new CostEntry();
            c.setSkill("snyk-fix");
            c.setAction("breaking-change-judge");
            c.setModel(MODEL);
            c.setBillingMode("USAGE_CREDITS");
            c.setEstTokensIn(21_000);
            c.setEstTokensOut(1_800);
            c.setEstCostUsd(0.11);
            c.setOwner(OWNER);
            c = costs.save(c);
            backdate("cost_entry", c.getId(), daysAgo(6 + i * 4));
        }
    }

    /** One PENDING approval (the DecisionQueue moment) + two decided ones (the audit trail). */
    private void seedGates(List<Scan> latest) {
        GateDecision pending = new GateDecision();
        pending.setRunId(latest.get(0).getId());
        pending.setAction("CREATE_DEFECT");
        pending.setStatus("PENDING");
        gates.save(pending);
        for (int i = 0; i < 2; i++) {
            GateDecision g = new GateDecision();
            g.setRunId(latest.get(i + 1).getId());
            g.setAction(i == 0 ? "CREATE_DEFECT" : "XRAY_CREATE_TEST");
            g.setStatus("APPROVED");
            g.setApprover("m.tremblay");
            g.setDecidedAt(daysAgo(3 + i));
            g = gates.save(g);
            backdate("gate_decision", g.getId(), daysAgo(4 + i));
        }
    }

    /** Watches with a DECLINING 30-day posture, current vulns, one unseen alert, and fix trains in
     *  reconciler-safe states only (DONE ×2 for the MTTR story, AWAITING_CONFIRM ×1 for the queue). */
    private void seedSnyk() {
        String[][] apps = {{"APP7571", "ciam-policies"}, {"APP7580", "payments-transfer"}, {"APP7412", "cards-activation"}};
        int[][] ramp = {{2, 5, 8, 4}, {1, 4, 6, 3}, {1, 3, 4, 2}, {0, 2, 3, 1}};   // critical/high/medium/low per week
        List<SnykWatch> saved = new ArrayList<>();
        for (String[] app : apps) {
            SnykWatch w = new SnykWatch();
            w.setOrgId("demo-org");
            w.setOrgSlug("bnc-demo");
            w.setOrgName("BNC (demo)");
            w.setTargetId(UUID.randomUUID().toString());
            w.setRepoSlug(app[0] + "/application-tests");
            w.setEnabled(true);
            saved.add(watches.save(w));
        }
        for (SnykWatch w : saved) {
            SnykSnapshot latest = null;
            for (int week = 0; week < ramp.length; week++) {
                SnykSnapshot snap = new SnykSnapshot();
                snap.setWatchId(w.getId());
                snap.setTakenAt(daysAgo(24 - week * 8));
                snap.setCritical(ramp[week][0]);
                snap.setHigh(ramp[week][1]);
                snap.setMedium(ramp[week][2]);
                snap.setLow(ramp[week][3]);
                snap.setProjectCount(3);
                snap.setFixableCount(ramp[week][1] + ramp[week][0]);
                latest = snapshots.save(snap);
            }
            seedVulns(latest);
        }
        SnykAlert alert = new SnykAlert();
        alert.setWatchId(saved.get(0).getId());
        alert.setOrgSlug("bnc-demo");
        alert.setRepoSlug(saved.get(0).getRepoSlug());
        alert.setSeverity("high");
        alert.setMessage("New high vulnerability in org.apache.commons:commons-lang3 — a safe version is available.");
        alert.setSeen(false);
        alerts.save(alert);
        seedFixTrains(saved);
    }

    private void seedVulns(SnykSnapshot snap) {
        String[][] rows = {
                {"critical", "Remote Code Execution", "org.springframework:spring-web", "6.1.2", "6.1.14", "CVE-2026-2140", "9.8"},
                {"high", "Denial of Service", "com.fasterxml.jackson.core:jackson-databind", "2.15.2", "2.17.1", "CVE-2026-1187", "7.5"},
                {"medium", "Information Exposure", "org.apache.httpcomponents:httpclient", "4.5.13", "4.5.14", "CVE-2025-9310", "5.3"}};
        for (String[] r : rows) {
            SnykVuln v = new SnykVuln();
            v.setSnapshotId(snap.getId());
            v.setProjectId(UUID.randomUUID().toString());
            v.setProjectName("application-tests");
            v.setIssueId("SNYK-JAVA-" + r[2].replaceAll("[^A-Za-z0-9]", "").toUpperCase() + "-" + r[6].replace(".", ""));
            v.setSeverity(r[0]);
            v.setTitle(r[1]);
            v.setPkgName(r[2]);
            v.setPkgVersion(r[3]);
            v.setFixedIn(r[4]);
            v.setCve(r[5]);
            v.setCvss(Double.parseDouble(r[6]));
            v.setFixable(true);
            vulns.save(v);
        }
    }

    private void seedFixTrains(List<SnykWatch> saved) {
        for (int i = 0; i < 2; i++) {
            SnykFixTrain t = new SnykFixTrain();
            t.setWatchId(saved.get(i).getId());
            t.setIssueId("SNYK-JAVA-SPRINGWEB-98");
            t.setCoordinate("org.springframework:spring-web");
            t.setOldVersion("6.1.2");
            t.setFixedIn("6.1.14");
            t.setSeverity("critical");
            t.setAppIds(saved.get(i).getRepoSlug().split("/")[0]);
            t.setJiraKey("DEMO-12" + i);
            t.setStatus("DONE");
            t.setBreaking(false);
            t.setReactorPassed(true);
            t.setOwner(OWNER);
            t.setStartedAt(daysAgo(9 - i * 3));
            t.setFinishedAt(daysAgo(7 - i * 3));
            t = trains.save(t);
            backdate("snyk_fix_train", t.getId(), daysAgo(9 - i * 3));
            String[] modules = {"lsist-test-framework-bom", "lsist-test-framework-core", "lsist-test-framework-api", "application-tests"};
            for (int m = 0; m < modules.length; m++) {
                SnykFixStep st = new SnykFixStep();
                st.setTrainId(t.getId());
                st.setStepOrder(m);
                st.setBitbucketProject(m < 3 ? "APP7488" : t.getAppIds());
                st.setRepoSlug(modules[m]);
                st.setBranch("snyk-fix/spring-web-6.1.14");
                st.setModuleLabel(modules[m]);
                st.setStatus("MERGED");
                st.setPrUrl("https://git.bnc.ca/projects/" + st.getBitbucketProject() + "/repos/" + modules[m] + "/pull-requests/" + (12 + m));
                st.setPrOpenedBy("VERITAS");
                steps.save(st);
            }
        }
        SnykFixTrain waiting = new SnykFixTrain();
        waiting.setWatchId(saved.get(2).getId());
        waiting.setIssueId("SNYK-JAVA-JACKSONDATABIND-71");
        waiting.setCoordinate("com.fasterxml.jackson.core:jackson-databind");
        waiting.setOldVersion("2.15.2");
        waiting.setFixedIn("2.17.1");
        waiting.setSeverity("high");
        waiting.setAppIds(saved.get(2).getRepoSlug().split("/")[0]);
        waiting.setStatus("AWAITING_CONFIRM");   // human-wait: the startup reconciler leaves it alone
        waiting.setOwner(OWNER);
        waiting.setStartedAt(daysAgo(1));
        trains.save(waiting);
    }

    /** Non-zero strategy/condition/case/plan/codegen counts for every service (the pipeline table). */
    private void seedTestAssets() {
        for (int i = 0; i < SERVICES.size(); i++) {
            Svc svc = SERVICES.get(i);
            TestStrategy ts = new TestStrategy();
            ts.setServiceName(svc.name());
            ts.setContentMarkdown("# Test strategy — " + svc.name() + "\n\nRisk-based, ISTQB-aligned (demo).");
            ts.setStatus(i % 3 == 0 ? "DRAFT" : "APPROVED");
            ts.setSource("CODE");
            ts.setOwner(OWNER);
            ts.setVersion(1);
            ts.setLineageId(UUID.randomUUID().toString());
            ts.setConfidence(90.0);
            ts = strategies.save(ts);
            for (int c = 0; c < 3; c++) {
                TestCondition tc = new TestCondition();
                tc.setServiceName(svc.name());
                tc.setConditionRef("TC-" + svc.appId() + "-" + (c + 1));
                tc.setDescription("Boundary and contract checks for " + svc.name() + " (" + (c + 1) + ")");
                tc.setPriority("P" + (1 + c % 3));
                tc.setAutomation(c == 0 ? "AUTOMATED" : c == 1 ? "CANDIDATE" : "MANUAL");
                tc.setStatus("APPROVED");
                tc.setTestStrategyId(ts.getId());
                tc.setOwner(OWNER);
                conditions.save(tc);
            }
            for (int k = 0; k < 4; k++) {
                TestCase tcase = new TestCase();
                tcase.setServiceName(svc.name());
                tcase.setTitle("Verify " + svc.name() + " contract behaviour #" + (k + 1));
                tcase.setLevel("System");
                tcase.setAutomation(k % 2 == 0 ? "AUTOMATED" : "CANDIDATE");
                tcase.setPriority("P" + (1 + k % 3));
                tcase.setStatus(k < 2 ? "APPROVED" : "DRAFT");
                tcase.setOwner(OWNER);
                cases.save(tcase);
            }
            if (i % 2 == 0) {
                TestPlan tp = new TestPlan();
                tp.setServiceName(svc.name());
                tp.setKind("RELEASE");
                tp.setFixVersion("2026.07");
                tp.setStatus("DRAFT");
                tp.setRiskCount(6);
                tp.setOwner(OWNER);
                plans.save(tp);
            }
            if (i % 2 == 1) {
                CodegenRun run = new CodegenRun();
                run.setServiceName(svc.name());
                run.setTemplateSource("vendored");
                run.setOutputRepo(svc.appId() + "/application-tests");
                run.setBranch("veritas/generated-tests");
                run.setBuildStatus(i == 5 ? "REPAIRED" : "PASS");
                run.setPrUrl("https://git.bnc.ca/projects/" + svc.appId() + "/repos/application-tests/pull-requests/7");
                run.setEstCostUsd(0.55);
                codegenRuns.save(run);
            }
        }
    }

    /** Back-date a @CreationTimestamp column (not settable through the entity) so 30 days of history don't
     *  all pile on "today". Defensive: a dialect/naming surprise logs a warning instead of failing startup. */
    private void backdate(String table, String id, Instant createdAt) {
        try {
            jdbc.update("UPDATE " + table + " SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), id);
        } catch (Exception e) {
            log.warn("Could not back-date {}.created_at (demo polish only): {}", table, e.getMessage());
        }
    }
}
