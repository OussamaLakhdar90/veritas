package ca.bnc.qe.veritas.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestParam;
import ca.bnc.qe.veritas.persistence.CostEntry;
import ca.bnc.qe.veritas.persistence.CostEntryRepository;
import ca.bnc.qe.veritas.persistence.RunStepRepository;
import ca.bnc.qe.veritas.persistence.SkillRun;
import ca.bnc.qe.veritas.persistence.SkillRunRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of LLM cost: the per-action ledger (every skill) plus legacy skill-run/step rollups. */
@RestController
@RequestMapping("/api/v1")
public class CostController {

    private final SkillRunRepository runs;
    private final RunStepRepository steps;
    private final CostEntryRepository costs;

    public CostController(SkillRunRepository runs, RunStepRepository steps, CostEntryRepository costs) {
        this.runs = runs;
        this.steps = steps;
        this.costs = costs;
    }

    @GetMapping("/runs")
    public List<SkillRun> recentRuns() {
        return runs.findAllByOrderByStartedAtDesc();
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String id) {
        return runs.findById(id)
                .map(run -> ResponseEntity.ok(Map.<String, Object>of(
                        "run", run,
                        "steps", steps.findBySkillRunIdOrderByOrdinalAsc(id))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The per-action cost ledger across all skills (most recent first). */
    @GetMapping("/costs")
    public List<CostEntry> costLedger() {
        return costs.findAllByOrderByCreatedAtDesc();
    }

    /** Aggregate spend: total, count, and a breakdown per skill (the dashboard's cost summary). */
    @GetMapping("/costs/summary")
    public Map<String, Object> costSummary() {
        List<CostEntry> all = costs.findAll();
        double total = all.stream().mapToDouble(CostEntry::getEstCostUsd).sum();
        Map<String, Double> bySkill = new LinkedHashMap<>();
        for (CostEntry e : all) {
            bySkill.merge(e.getSkill() == null ? "unknown" : e.getSkill(), e.getEstCostUsd(), Double::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalEstCostUsd", total);
        out.put("actions", all.size());
        out.put("bySkill", bySkill);
        return out;
    }

    /** Daily spend over the last {@code days} (zero-filled, oldest→newest) — backs the cost sparkline + weekly delta. */
    @GetMapping("/costs/trend")
    public List<CostTrendPoint> costTrend(@RequestParam(defaultValue = "30") int days) {
        int d = Math.max(1, Math.min(days, 365));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(d - 1L);
        Map<LocalDate, double[]> acc = new HashMap<>();   // [0] = usd, [1] = action count
        for (CostEntry e : costs.findAll()) {
            Instant c = e.getCreatedAt();
            if (c == null) {
                continue;
            }
            LocalDate day = c.atZone(ZoneOffset.UTC).toLocalDate();
            if (day.isBefore(from) || day.isAfter(today)) {
                continue;
            }
            double[] a = acc.computeIfAbsent(day, k -> new double[2]);
            a[0] += e.getEstCostUsd();
            a[1] += 1;
        }
        List<CostTrendPoint> out = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            double[] a = acc.getOrDefault(day, new double[2]);
            out.add(new CostTrendPoint(day.toString(), Math.round(a[0] * 10000.0) / 10000.0, (int) a[1]));
        }
        return out;
    }

    /** One day of spend. */
    public record CostTrendPoint(String date, double totalUsd, int actions) {}
}
