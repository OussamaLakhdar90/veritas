package ca.bnc.qe.veritas.snyk.fix;

import java.util.ArrayList;
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
        List<CascadeStep> steps = new ArrayList<>();
        int order = 1;

        // New release versions for each framework module (patch bump of its own version).
        String newBom = ownBump(poms.bom());
        String newCore = ownBump(poms.core());
        String newApi = ownBump(poms.api());
        String newWeb = ownBump(poms.web());
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
        // 1) Pin the safe version of the vulnerable dependency.
        String token = PomVersionEditor.dependencyVersionToken(pom, groupId, artifactId);
        if (token != null && token.startsWith("${")) {
            String prop = token.substring(2, token.length() - 1);
            edits.add(PomEdit.property(prop, PomVersionEditor.propertyValue(pom, prop), fixedIn));
        } else if (token != null) {
            edits.add(PomEdit.managed(groupId, artifactId, token, fixedIn));
        } else {
            edits.add(PomEdit.override(groupId, artifactId, fixedIn));
        }
        // 2) Bump any framework module versions the BOM itself pins.
        addPresentPropertyBumps(pom, edits, newVersions);
        // 3) Release a new BOM version.
        addOwnVersionBump(pom, edits, newBom);
        return build(order, fw.getProject(), fw.getBomRepo(), "pom.xml", "BOM", edits, newBom, pom);
    }

    private CascadeStep moduleStep(int order, String repo, String label, String pom, String newOwn,
                                   Map<String, String> newVersions) {
        List<PomEdit> edits = new ArrayList<>();
        addPresentPropertyBumps(pom, edits, newVersions);
        addOwnVersionBump(pom, edits, newOwn);
        return build(order, fw.getProject(), repo, "pom.xml", label, edits, newOwn, pom);
    }

    private CascadeStep consumerStep(int order, AppInput app, Map<String, String> newVersions) {
        AppUsageDetector.AppUsage use = usage.detect(app.pomContent());
        List<PomEdit> edits = new ArrayList<>();
        maybeBump(app.pomContent(), edits, fw.getBomVersionProperty(), use.usesBom(), newVersions);
        maybeBump(app.pomContent(), edits, fw.getCoreVersionProperty(), use.usesCore(), newVersions);
        maybeBump(app.pomContent(), edits, fw.getApiVersionProperty(), use.usesApi(), newVersions);
        maybeBump(app.pomContent(), edits, fw.getWebVersionProperty(), use.usesWeb(), newVersions);
        String label = "consumer:" + app.appId();
        if (edits.isEmpty()) {
            return CascadeStep.manual(order, app.appId(), fw.getConsumerRepo(), "pom.xml", label,
                    "This app does not use the affected framework artifact — nothing to bump.");
        }
        return build(order, app.appId(), fw.getConsumerRepo(), "pom.xml", label, edits, null, app.pomContent());
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

    /** Apply an ordered list of edits to a pom (used by the planner to validate + by the verifier to write). */
    public static String applyEdits(String pom, List<PomEdit> edits) {
        String out = pom;
        for (PomEdit e : edits) {
            out = switch (e.kind()) {
                case PROPERTY_BUMP -> PomVersionEditor.bumpProperty(out, e.property(), e.newVersion());
                case MANAGED_BUMP -> PomVersionEditor.bumpDependencyVersion(out, e.groupId(), e.artifactId(), e.newVersion());
                case ADD_OVERRIDE -> PomVersionEditor.addManagedDependency(out, e.groupId(), e.artifactId(), e.newVersion());
                case VERSION_BUMP -> PomVersionEditor.bumpProjectVersion(out, e.newVersion());
                case PLUGIN_BUMP -> out;
            };
        }
        return out;
    }

    /** The four framework poms (any may be null when not fetched). */
    public record FrameworkPoms(String bom, String core, String api, String web) {}

    /** One selected app-id + its {@code application-tests} root pom. */
    public record AppInput(String appId, String appName, String pomContent) {}
}
