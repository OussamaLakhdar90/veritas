package ca.bnc.qe.veritas.snyk.fix;

import org.springframework.stereotype.Component;

/**
 * Detects which lsist framework artifacts an app's {@code application-tests} pom actually uses, so the cascade only
 * bumps what's relevant (and skips an app that doesn't use the affected artifact). Uses both the version property
 * and a direct dependency as evidence.
 */
@Component
public class AppUsageDetector {

    private final FrameworkProperties fw;

    public AppUsageDetector(FrameworkProperties fw) {
        this.fw = fw;
    }

    /** Which framework artifacts this pom pins/uses. */
    public AppUsage detect(String pom) {
        if (pom == null) {
            return new AppUsage(false, false, false, false);
        }
        return new AppUsage(
                has(pom, fw.getBomVersionProperty(), fw.getBomRepo()),
                has(pom, fw.getCoreVersionProperty(), fw.getCoreRepo()),
                has(pom, fw.getApiVersionProperty(), fw.getApiRepo()),
                has(pom, fw.getWebVersionProperty(), fw.getWebRepo()));
    }

    private boolean has(String pom, String versionProperty, String artifactId) {
        return PomVersionEditor.propertyValue(pom, versionProperty) != null
                || PomVersionEditor.dependencyVersionToken(pom, fw.getGroup(), artifactId) != null
                || pom.contains("<artifactId>" + artifactId + "</artifactId>");
    }

    /** Which framework artifacts an app's pom pins. */
    public record AppUsage(boolean usesBom, boolean usesCore, boolean usesApi, boolean usesWeb) {
        public boolean usesAny() {
            return usesBom || usesCore || usesApi || usesWeb;
        }
    }
}
