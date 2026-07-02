package ca.bnc.qe.veritas.snyk.fix;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Where the lsist test-framework lives and how its modules reference each other, for the Snyk fix cascade. All the
 * repos are in one Bitbucket project (BNC: {@code APP7488}); each downstream module (and the consumers) pins its
 * upstreams through {@code <lsist-*.version>} properties. Fully overridable — the planner only ever edits properties
 * that actually exist in a given pom, and the local reactor build is the safety net if a name here is wrong.
 */
@Component
@ConfigurationProperties(prefix = "veritas.snyk.framework")
@Getter
@Setter
public class FrameworkProperties {

    private String project = "APP7488";
    private String branch = "develop";
    private String group = "ca.bnc.lsist";

    private String bomRepo = "lsist-test-framework-bom";
    private String coreRepo = "lsist-test-framework-core";
    private String apiRepo = "lsist-test-framework-api";
    private String webRepo = "lsist-test-framework-web";

    /**
     * The repo (under each watched app-id's Bitbucket project) that holds the consumer poms Veritas bumps.
     * Override per deployment if your test repos are not named {@code application-tests}.
     */
    private String consumerRepo = "application-tests";

    /** The version-alignment properties a downstream pom uses to pin its upstreams. */
    private String bomVersionProperty = "lsist-bom.version";
    private String coreVersionProperty = "lsist-core.version";
    private String apiVersionProperty = "lsist-api.version";
    private String webVersionProperty = "lsist-web.version";

    /** Fallback PR reviewers (Bitbucket usernames) used when git history yields no suggestion. */
    private List<String> defaultReviewers = List.of();

    /** All four framework version properties — the planner bumps whichever are present in a given pom. */
    public List<String> frameworkVersionProperties() {
        return List.of(bomVersionProperty, coreVersionProperty, apiVersionProperty, webVersionProperty);
    }
}
