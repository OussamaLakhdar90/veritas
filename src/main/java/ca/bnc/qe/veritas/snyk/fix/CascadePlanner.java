package ca.bnc.qe.veritas.snyk.fix;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns one fixable Snyk issue + the framework/consumer poms into the ordered release train
 * <b>BOM → core → api/web → consumers</b>. Deterministic version propagation: the BOM pins the safe version and
 * releases a new BOM version; each downstream bumps whichever {@code <lsist-*.version>} upstream properties it
 * actually has, plus its own release version; each selected app bumps only the properties it uses. Steps whose edits
 * can't be applied to the real pom are marked {@code manual} (never silently skipped) — and the whole train is
 * gated by the local reactor build downstream, so a wrong property name blocks the PRs rather than shipping a break.
 */
@Component
public class CascadePlanner {

    private final FrameworkProperties fw;
    private final AppUsageDetector usage;

    public CascadePlanner(FrameworkProperties fw, AppUsageDetector usage) {
        this.fw = fw;
        this.usage = usage;
    }

    public List<CascadeStep> plan(String groupId, String artifactId, String fixedIn,
                                  FrameworkPoms poms, List<AppInput> apps) {
        return plan(groupId, artifactId, fixedIn, poms, apps, Map.of());
    }

    /**
     * As {@link #plan}, but with user-chosen new release versions per framework module (keyed by label
     * {@code BOM|core|api|web}). Overrides only apply to a module that is present; downstream {@code <lsist-*.version>}
     * pointers pick up the overridden value automatically. Used by the confirm step after the user edits the plan.
     */
    public List<CascadeStep> plan(String groupId, String artifactId, String fixedIn,
                                  FrameworkPoms poms, List<AppInput> apps, Map<String, String> versionOverrides) {
        List<CascadeStep> steps = new ArrayList<>();
        int order = 1;

        // New release versions for each framework module (patch bump of its own version, or the user's override).
        String newBom = pick("BOM", ownBump(poms.bom()), versionOverrides);
        String newCore = pick("core", ownBump(poms.core()), versionOverrides);
        String newApi = pick("api", ownBump(poms.api()), versionOverrides);
        String newWeb = pick("web", ownBump(poms.web()), versionOverrides);
        Map<String, String> newVersions = new LinkedHashMap<>();
        putIf(newVersions, fw.getBomVersionProperty(), newBom);
        putIf(newVersions, fw.getCoreVersionProperty(), newCore);
        putIf(newVersions, fw.getApiVersionProperty(), newApi);
        putIf(newVersions, fw.getWebVersionProperty(), newWeb);

        if (poms.bom() != null) {
            steps.add(bomStep(order++, poms.bom(), groupId, artifactId, fixedIn, newBom, newVersions));
        }
        if (poms.core() != null) {
            steps.add(moduleStep(order++, fw.getCoreRepo(), "core", poms.core(), newCore, newVersions));
        }
        if (poms.api() != null) {
            steps.add(moduleStep(order++, fw.getApiRepo(), "api", poms.api(), newApi, newVersions));
        }
        if (poms.web() != null) {
            steps.add(moduleStep(order++, fw.getWebRepo(), "web", poms.web(), newWeb, newVersions));
        }
        for (AppInput app : apps) {
            steps.add(consumerStep(order++, app, newVersions));
        }
        return steps;
    }

    private CascadeStep bomStep(int order, String pom, String groupId, String artifactId, String fixedIn,
                                String newBom, Map<String, String> newVersions) {
        List<PomEdit> edits = new ArrayList<>();
        // 0) NEVER downgrade. If the BOM already manages the coordinate strictly ABOVE the requested safe version,
        //    applying fixedIn would LOWER it — a security "fix" must never regress a dependency. Refuse with an honest
        //    manual step instead of a downgrade PR. (The exactly-at-fixedIn case is benign and falls through to the
        //    "already pins … nothing to release" no-op branch below — it isn't a downgrade, so it isn't flagged here.)
        String current = FixValidator.effectiveVersion(pom, groupId, artifactId);
        if (current != null && VersionCompare.compare(current, fixedIn) > 0) {
            return CascadeStep.manual(order, fw.getProject(), fw.getBomRepo(), "pom.xml", "BOM",
                    "The BOM already manages " + groupId + ":" + artifactId + " at " + current + " — ABOVE the "
                            + "requested safe version " + fixedIn + ", so it was NOT downgraded. Nothing to release "
                            + "(if a fix is still expected, verify the Snyk advisory's affected-version range).");
        }
        // 1) Pin the safe version of the vulnerable dependency (the coordinate is genuinely below fixedIn here).
        addVulnPin(pom, edits, groupId, artifactId, fixedIn);
        // 2) Bump any framework module versions the BOM itself pins.
        addPresentPropertyBumps(pom, edits, newVersions);
        // 3) Release a new BOM version — ONLY when the BOM actually changed something. A release bump on top of a
        //    no-op vuln pin (the dependency already at fixedIn) would push a commit that moves only the BOM's own
        //    <version> and fixes nothing, yet opens a PR + advances Jira as "fixed" — the exact change-less-"fix" bug.
        if (!edits.isEmpty()) {
            addOwnVersionBump(pom, edits, newBom);
        } else {
            // Honest terminal-for-this-step: the BOM already ships the safe version, so there is nothing to release.
            return CascadeStep.manual(order, fw.getProject(), fw.getBomRepo(), "pom.xml", "BOM",
                    "The BOM already pins " + groupId + ":" + artifactId + " at the safe version " + fixedIn
                            + " — nothing to release (no PR opened).");
        }
        return build(order, fw.getProject(), fw.getBomRepo(), "pom.xml", "BOM", edits, newBom, pom);
    }

    /**
     * Pin the safe version of the vulnerable dependency in the BOM — but only when it is a SUBSTANTIVE change. If the
     * BOM already manages the coordinate at {@code fixedIn} (a stale Snyk re-alert, or an idempotent relaunch after a
     * prior bump), adding a no-op {@code old==new} edit here is what let a release-only commit ship as a "fix". Mirrors
     * the {@code old != new} guard every other bump already has. Returns {@code true} when a real pin was added.
     */
    private boolean addVulnPin(String pom, List<PomEdit> edits, String groupId, String artifactId, String fixedIn) {
        String token = PomVersionEditor.dependencyVersionToken(pom, groupId, artifactId);
        if (token != null && token.startsWith("${")) {
            String prop = token.substring(2, token.length() - 1);
            String cur = PomVersionEditor.propertyValue(pom, prop);
            if (cur != null && !cur.equals(fixedIn)) {
                edits.add(PomEdit.property(prop, cur, fixedIn));
                return true;
            }
            return false;   // the property is already at fixedIn (or unresolved) — no substantive pin
        }
        if (token != null) {
            if (!token.equals(fixedIn)) {
                edits.add(PomEdit.managed(groupId, artifactId, token, fixedIn));
                return true;
            }
            return false;   // the managed literal is already at fixedIn
        }
        edits.add(PomEdit.override(groupId, artifactId, fixedIn));   // not managed here → a new override is substantive
        return true;
    }

    private CascadeStep moduleStep(int order, String repo, String label, String pom, String newOwn,
                                   Map<String, String> newVersions) {
        List<PomEdit> edits = new ArrayList<>();
        addPresentPropertyBumps(pom, edits, newVersions);
        addOwnVersionBump(pom, edits, newOwn);
        return build(order, fw.getProject(), repo, "pom.xml", label, edits, newOwn, pom);
    }

    /** The outcome of trying to bump one framework module in a consumer pom. */
    private enum Bump { BUMPED, ALREADY_CURRENT, INHERITED, SKIP }

    private CascadeStep consumerStep(int order, AppInput app, Map<String, String> newVersions) {
        String pom = app.pomContent();
        AppUsageDetector.AppUsage use = usage.detect(pom);
        List<PomEdit> edits = new ArrayList<>();
        // A consumer picks up a (usually transitive) fix by advancing its framework-version pointer. Bump whichever
        // pointer it actually has — a <lsist-*.version> property OR an inline dependency/BOM-import version.
        EnumSet<Bump> outcomes = EnumSet.noneOf(Bump.class);
        outcomes.add(addConsumerBump(pom, edits, fw.getBomVersionProperty(), fw.getBomRepo(), use.usesBom(), newVersions));
        outcomes.add(addConsumerBump(pom, edits, fw.getCoreVersionProperty(), fw.getCoreRepo(), use.usesCore(), newVersions));
        outcomes.add(addConsumerBump(pom, edits, fw.getApiVersionProperty(), fw.getApiRepo(), use.usesApi(), newVersions));
        outcomes.add(addConsumerBump(pom, edits, fw.getWebVersionProperty(), fw.getWebRepo(), use.usesWeb(), newVersions));
        String label = "consumer:" + app.appId();
        if (!edits.isEmpty()) {
            return build(order, app.appId(), fw.getConsumerRepo(), "pom.xml", label, edits, null, pom);
        }
        if (outcomes.contains(Bump.INHERITED)) {
            // Honest, actionable state — NOT "doesn't use it": the app uses the framework but the version comes
            // from a parent pom or is BOM-managed, so there's no local pointer here to bump.
            return CascadeStep.manual(order, app.appId(), fw.getConsumerRepo(), "pom.xml", label,
                    "This app uses the framework but pins its version upstream (a parent pom or the BOM) — "
                            + "no local <lsist-*.version> or inline version to bump. Update the parent by hand.");
        }
        if (outcomes.contains(Bump.ALREADY_CURRENT)) {
            // Benign: a local pointer exists and is already on the target version — nothing to do (not a work item).
            return CascadeStep.manual(order, app.appId(), fw.getConsumerRepo(), "pom.xml", label,
                    "This app is already on the safe framework version — nothing to bump.");
        }
        return CascadeStep.manual(order, app.appId(), fw.getConsumerRepo(), "pom.xml", label,
                "This app does not use the affected framework artifact — nothing to bump.");
    }

    /**
     * Bump a consumer's pointer to one framework module: prefer its {@code <lsist-*.version>} property, else its
     * inline dependency/BOM-import version. Distinguishes an already-current local pointer (benign) from a version
     * inherited upstream (needs a manual parent edit).
     */
    private Bump addConsumerBump(String pom, List<PomEdit> edits, String prop, String artifactId,
                                 boolean uses, Map<String, String> newVersions) {
        if (!uses) {
            return Bump.SKIP;
        }
        String newVal = newVersions.get(prop);
        if (newVal == null) {
            return Bump.SKIP;   // this module isn't part of this fix's version set (its pom wasn't fetched)
        }
        String curProp = PomVersionEditor.propertyValue(pom, prop);
        if (curProp != null) {
            if (curProp.equals(newVal)) {
                return Bump.ALREADY_CURRENT;
            }
            edits.add(PomEdit.property(prop, curProp, newVal));
            return Bump.BUMPED;
        }
        String token = PomVersionEditor.dependencyVersionToken(pom, fw.getGroup(), artifactId);
        if (token != null && !token.startsWith("${")) {
            if (token.equals(newVal)) {
                return Bump.ALREADY_CURRENT;
            }
            edits.add(PomEdit.managed(fw.getGroup(), artifactId, token, newVal));
            return Bump.BUMPED;
        }
        return Bump.INHERITED;   // uses the module, but no local property/inline version to bump
    }

    /** Bump each framework version property that is present in this pom to its new value. */
    private void addPresentPropertyBumps(String pom, List<PomEdit> edits, Map<String, String> newVersions) {
        for (String prop : fw.frameworkVersionProperties()) {
            maybeBump(pom, edits, prop, true, newVersions);
        }
    }

    private void maybeBump(String pom, List<PomEdit> edits, String prop, boolean applicable, Map<String, String> nv) {
        if (!applicable) {
            return;
        }
        String newVal = nv.get(prop);
        String cur = PomVersionEditor.propertyValue(pom, prop);
        if (newVal != null && cur != null && !cur.equals(newVal)) {
            edits.add(PomEdit.property(prop, cur, newVal));
        }
    }

    private void addOwnVersionBump(String pom, List<PomEdit> edits, String newOwn) {
        String own = PomVersionEditor.projectVersion(pom);
        if (own != null && newOwn != null && !own.equals(newOwn)) {
            edits.add(PomEdit.ownVersion(own, newOwn));
        }
    }

    private String ownBump(String pom) {
        if (pom == null) {
            return null;
        }
        String own = PomVersionEditor.projectVersion(pom);
        return own == null ? null : PomVersionEditor.patchBump(own);
    }

    /** Apply the edits to validate them + build the diff preview; a failure to apply becomes an honest manual step. */
    private CascadeStep build(int order, String project, String repo, String pomPath, String label,
                              List<PomEdit> edits, String newModuleVersion, String pom) {
        if (edits.isEmpty()) {
            return CascadeStep.manual(order, project, repo, pomPath, label, "Nothing to change.");
        }
        try {
            applyEdits(pom, edits);   // validates every edit applies cleanly
        } catch (RuntimeException e) {
            return CascadeStep.manual(order, project, repo, pomPath, label,
                    "Couldn't apply automatically (" + e.getMessage() + ") — edit this pom by hand.");
        }
        String preview = edits.stream().map(PomEdit::describe).collect(Collectors.joining("; "));
        return new CascadeStep(order, project, repo, fw.getBranch(), pomPath, label, edits, newModuleVersion,
                preview, false, null);
    }

    private void putIf(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /** The user's override for a module's new version if given (and the module is present), else the computed bump. */
    private String pick(String label, String computed, Map<String, String> overrides) {
        if (computed == null) {
            return null;   // module absent — nothing to version
        }
        String override = overrides.get(label);
        return override != null && !override.isBlank() ? override.trim() : computed;
    }

    /** Apply an ordered list of edits to a pom (used by the planner to validate + by the verifier to write). */
    public static String applyEdits(String pom, List<PomEdit> edits) {
        String out = pom;
        for (PomEdit e : edits) {
            out = switch (e.kind()) {
                case PROPERTY_BUMP -> PomVersionEditor.bumpProperty(out, e.property(), e.newVersion());
                case MANAGED_BUMP -> PomVersionEditor.bumpDependencyVersion(out, e.groupId(), e.artifactId(), e.newVersion());
                case ADD_OVERRIDE -> PomVersionEditor.addManagedDependency(out, e.groupId(), e.artifactId(), e.newVersion());
                case VERSION_BUMP -> PomVersionEditor.bumpProjectVersion(out, e.newVersion());
            };
        }
        return out;
    }

    /** The four framework poms (any may be null when not fetched). */
    public record FrameworkPoms(String bom, String core, String api, String web) {}

    /** One selected app-id + its {@code application-tests} root pom. */
    public record AppInput(String appId, String appName, String pomContent) {}
}
