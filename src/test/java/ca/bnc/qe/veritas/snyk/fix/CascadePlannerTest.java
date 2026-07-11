package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.snyk.fix.CascadePlanner.AppInput;
import ca.bnc.qe.veritas.snyk.fix.CascadePlanner.FrameworkPoms;
import org.junit.jupiter.api.Test;

/** The BOM → core → api/web → consumers cascade over realistic (lsist-shaped) pom fixtures. */
class CascadePlannerTest {

    private final FrameworkProperties fw = new FrameworkProperties();
    private final CascadePlanner planner = new CascadePlanner(fw, new AppUsageDetector(fw));

    private static final String BOM = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>ca.bnc.lsist</groupId>
                <artifactId>lsist-test-framework-bom</artifactId>
                <version>1.0.9</version>
                <packaging>pom</packaging>
                <properties>
                    <jackson.version>2.14.0</jackson.version>
                </properties>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>${jackson.version}</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

    private static String module(String artifact, String version) {
        return """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>ca.bnc.lsist</groupId>
                <artifactId>%s</artifactId>
                <version>%s</version>
                <properties>
                    <lsist-bom.version>1.0.9</lsist-bom.version>
                </properties>
            </project>
            """.formatted(artifact, version);
    }

    private static final String APP = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>ca.bnc.api.tests</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>application-tests</artifactId>
                <version>1.0.0</version>
                <properties>
                    <lsist-bom.version>1.0.9</lsist-bom.version>
                    <lsist-api.version>1.0.4</lsist-api.version>
                </properties>
            </project>
            """;

    private FrameworkPoms allFour() {
        return new FrameworkPoms(BOM, module("lsist-test-framework-core", "2.0.0"),
                module("lsist-test-framework-api", "1.0.4"), module("lsist-test-framework-web", "1.1.0"));
    }

    @Test
    void buildsTheOrderedTrainBomCoreApiWebThenConsumer() {
        List<CascadeStep> steps = planner.plan("com.fasterxml.jackson.core", "jackson-databind", "2.15.0",
                allFour(), List.of(new AppInput("app7576", "CIAM Profile", APP)));

        assertThat(steps).hasSize(5);
        assertThat(steps).extracting(CascadeStep::moduleLabel)
                .containsExactly("BOM", "core", "api", "web", "consumer:app7576");
        assertThat(steps).allSatisfy(s -> assertThat(s.manual()).isFalse());
        // Framework steps target the framework project; the consumer targets its own app-id.
        assertThat(steps.get(0).bitbucketProject()).isEqualTo("APP7488");
        assertThat(steps.get(4).bitbucketProject()).isEqualTo("app7576");
    }

    @Test
    void bomStepPinsTheSafeVersionAndReleasesANewBomVersion() {
        CascadeStep bom = planner.plan("com.fasterxml.jackson.core", "jackson-databind", "2.15.0",
                allFour(), List.of()).get(0);
        assertThat(bom.newModuleVersion()).isEqualTo("1.0.10");
        assertThat(bom.diffPreview()).contains("jackson.version").contains("2.14.0 → 2.15.0")
                .contains("1.0.9 → 1.0.10");
        // Applying the edits pins the fix + the new BOM version.
        String edited = CascadePlanner.applyEdits(BOM, bom.edits());
        assertThat(edited).contains("<jackson.version>2.15.0</jackson.version>");
        assertThat(edited).contains("<version>1.0.10</version>");
    }

    @Test
    void consumerBumpsOnlyTheFrameworkPropertiesItUses() {
        CascadeStep consumer = planner.plan("com.fasterxml.jackson.core", "jackson-databind", "2.15.0",
                allFour(), List.of(new AppInput("app7576", "CIAM Profile", APP))).get(4);
        String edited = CascadePlanner.applyEdits(APP, consumer.edits());
        assertThat(edited).contains("<lsist-bom.version>1.0.10</lsist-bom.version>");   // newBom
        assertThat(edited).contains("<lsist-api.version>1.0.5</lsist-api.version>");     // newApi
        // The app does not use web/core, so those are untouched.
        assertThat(edited).doesNotContain("lsist-web.version");
    }

    @Test
    void anUnmanagedTransitiveGetsAnAddedOverrideInTheBom() {
        CascadeStep bom = planner.plan("org.yaml", "snakeyaml", "2.2", new FrameworkPoms(BOM, null, null, null),
                List.of()).get(0);
        assertThat(bom.edits()).anySatisfy(e -> assertThat(e.kind()).isEqualTo(FixEditKind.ADD_OVERRIDE));
        String edited = CascadePlanner.applyEdits(BOM, bom.edits());
        assertThat(edited).contains("<artifactId>snakeyaml</artifactId>").contains("<version>2.2</version>");
    }

    @Test
    void bomStepActuallyBumpsAJackson3LiteralVersionForTheRenamedGroup() {
        // The exact reported coordinate: tools.jackson.core (Jackson 3's renamed group), managed with a LITERAL
        // version. The vulnerable version must be genuinely rewritten — this proves the fix fixes for the renamed group
        // (the group-agnostic exact match handles tools.jackson.core just like com.fasterxml.jackson.core).
        String bomPom = """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>ca.bnc.lsist</groupId>
                    <artifactId>lsist-test-framework-bom</artifactId>
                    <version>1.0.9</version>
                    <packaging>pom</packaging>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>tools.jackson.core</groupId>
                                <artifactId>jackson-databind</artifactId>
                                <version>3.1.1</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """;
        CascadeStep bom = planner.plan("tools.jackson.core", "jackson-databind", "3.1.4",
                new FrameworkPoms(bomPom, null, null, null), List.of()).get(0);
        assertThat(bom.manual()).isFalse();
        assertThat(bom.edits()).anySatisfy(e -> assertThat(e.kind()).isEqualTo(FixEditKind.MANAGED_BUMP));
        String edited = CascadePlanner.applyEdits(bomPom, bom.edits());
        assertThat(edited).contains("<groupId>tools.jackson.core</groupId>")
                .contains("<version>3.1.4</version>")          // the vulnerable version is really changed
                .doesNotContain("<version>3.1.1</version>");   // not left behind
    }

    @Test
    void bomStepIsManualNotAReleaseWhenTheDependencyIsAlreadyAtTheFixedVersion() {
        // The BOM already manages jackson at 2.14.0 (via ${jackson.version}). A "fix" TO 2.14.0 changes nothing about
        // the dependency — it must NOT bump the BOM's own <version> and open a change-less PR. It becomes an honest
        // manual "already safe" step instead. (The exact change-less-"fix" bug from the screenshot.)
        CascadeStep bom = planner.plan("com.fasterxml.jackson.core", "jackson-databind", "2.14.0",
                new FrameworkPoms(BOM, null, null, null), List.of()).get(0);
        assertThat(bom.manual()).isTrue();
        assertThat(bom.edits()).isEmpty();                                   // no VERSION_BUMP, no property edit
        assertThat(bom.reason()).contains("already pins").contains("2.14.0").contains("nothing to release");
    }

    @Test
    void bomStepIsManualWhenAManagedLiteralIsAlreadyAtTheFixedVersion() {
        // Same guard for a LITERAL managed version already at fixedIn (the renamed Jackson-3 group).
        String bomPom = """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>ca.bnc.lsist</groupId>
                    <artifactId>lsist-test-framework-bom</artifactId>
                    <version>1.0.9</version>
                    <packaging>pom</packaging>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>tools.jackson.core</groupId>
                                <artifactId>jackson-databind</artifactId>
                                <version>3.1.4</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """;
        CascadeStep bom = planner.plan("tools.jackson.core", "jackson-databind", "3.1.4",
                new FrameworkPoms(bomPom, null, null, null), List.of()).get(0);
        assertThat(bom.manual()).isTrue();
        assertThat(bom.edits()).isEmpty();
        assertThat(bom.reason()).contains("nothing to release");
    }

    @Test
    void bomStepStillReleasesWhenTheVulnPinActuallyMoves() {
        // Control: a real move (2.14.0 → 2.15.0) DOES pin the property AND release a new BOM version — the guard
        // must not over-fire and suppress a genuine fix.
        CascadeStep bom = planner.plan("com.fasterxml.jackson.core", "jackson-databind", "2.15.0",
                new FrameworkPoms(BOM, null, null, null), List.of()).get(0);
        assertThat(bom.manual()).isFalse();
        assertThat(bom.edits()).anySatisfy(e -> assertThat(e.kind()).isEqualTo(FixEditKind.PROPERTY_BUMP))
                .anySatisfy(e -> assertThat(e.kind()).isEqualTo(FixEditKind.VERSION_BUMP));   // the release bump rides along
    }

    @Test
    void anAddedOverrideIsLabelledHonestlyNotAsAConfirmedBump() {
        // A genuine transitive gets an ADD_OVERRIDE — the diff must say so ("Add managed override … verify it
        // applies"), so a reviewer never mistakes an added pin for an in-place bump of an existing managed version.
        CascadeStep bom = planner.plan("org.yaml", "snakeyaml", "2.2", new FrameworkPoms(BOM, null, null, null),
                List.of()).get(0);
        assertThat(bom.diffPreview()).contains("Add managed override").contains("snakeyaml").contains("verify it applies");
    }

    @Test
    void consumerThatImportsTheBomWithAnInlineVersionGetsThatVersionBumped() {
        // Very common: the app imports the framework BOM with an explicit <version> (no <lsist-bom.version> property).
        String app = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>ca.bnc.api.tests</groupId>
                <artifactId>ciam-eligibility-tests</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>ca.bnc.lsist</groupId>
                            <artifactId>lsist-test-framework-bom</artifactId>
                            <version>1.0.9</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;
        CascadeStep consumer = planner.plan("com.fasterxml.jackson.core", "jackson-databind", "2.15.0",
                allFour(), List.of(new AppInput("app7580", "CIAM Eligibility", app))).get(4);
        assertThat(consumer.manual()).isFalse();
        assertThat(consumer.edits()).anySatisfy(e -> assertThat(e.kind()).isEqualTo(FixEditKind.MANAGED_BUMP));
        String edited = CascadePlanner.applyEdits(app, consumer.edits());
        // The BOM import version advances to the new BOM release; the project version is untouched.
        assertThat(edited).contains("<artifactId>lsist-test-framework-bom</artifactId>");
        assertThat(edited).contains("<version>1.0.10</version>");
        assertThat(edited).contains("<version>1.0.0</version>");   // project version untouched
    }

    @Test
    void consumerThatInheritsTheFrameworkVersionIsAnHonestManualStepNotDroppedAsUnused() {
        // The app uses the framework (declares a dep) but its version is BOM-managed / inherited from a parent —
        // there is no local pointer to bump, so it must be an honest manual step, NOT "does not use it".
        String app = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>ca.bnc.api.tests</groupId><artifactId>parent</artifactId><version>2.0.0</version>
                </parent>
                <artifactId>ciam-access-tests</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>ca.bnc.lsist</groupId>
                        <artifactId>lsist-test-framework-api</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """;
        CascadeStep consumer = planner.plan("com.fasterxml.jackson.core", "jackson-databind", "2.15.0",
                allFour(), List.of(new AppInput("app7590", "CIAM Access", app))).get(4);
        assertThat(consumer.manual()).isTrue();
        assertThat(consumer.reason()).contains("upstream").doesNotContain("does not use");
    }

    @Test
    void consumerAlreadyOnTheSafeVersionIsBenignNotAFalseManualParentEdit() {
        // A re-run (or an override equal to the current value): the app's lsist-bom.version already equals the target.
        String app = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <artifactId>ciam-tests</artifactId>
                <version>1.0.0</version>
                <properties><lsist-bom.version>1.0.10</lsist-bom.version></properties>
            </project>
            """;
        // The BOM's new release is 1.0.10 (1.0.9 patch-bumped), which is exactly what the app already has.
        CascadeStep consumer = planner.plan("com.fasterxml.jackson.core", "jackson-databind", "2.15.0",
                allFour(), List.of(new AppInput("app7576", "CIAM", app))).get(4);
        assertThat(consumer.manual()).isTrue();
        assertThat(consumer.reason()).contains("already on the safe framework version")
                .doesNotContain("Update the parent by hand").doesNotContain("does not use");
    }

    @Test
    void consumerRepoSlugIsConfigurable() {
        fw.setConsumerRepo("ciam-autotests");
        CascadeStep consumer = planner.plan("com.fasterxml.jackson.core", "jackson-databind", "2.15.0",
                allFour(), List.of(new AppInput("app7576", "CIAM Profile", APP))).get(4);
        assertThat(consumer.repoSlug()).isEqualTo("ciam-autotests");
        fw.setConsumerRepo("application-tests");   // restore for the shared instance
    }

    @Test
    void aCoordinateWithXmlMetacharactersIsAManualStepNeverAPomEdit() {
        // Defence-in-depth at the sink: even if a malicious coordinate bypassed the controller, the pom editor
        // refuses to write it, so the BOM step becomes an honest manual step and is never applied or built.
        CascadeStep bom = planner.plan("org.evil",
                "art</artifactId><build><plugins><plugin>x</plugin></plugins></build><x>",
                "2.2", new FrameworkPoms(BOM, null, null, null), List.of()).get(0);
        assertThat(bom.manual()).isTrue();
    }

    @Test
    void anAppThatUsesNoneOfTheAffectedArtifactsIsMarkedManualNotSilent() {
        String unrelated = """
            <project><modelVersion>4.0.0</modelVersion>
                <artifactId>other-tests</artifactId><version>1.0.0</version>
                <properties><something.else>1.0</something.else></properties>
            </project>
            """;
        CascadeStep consumer = planner.plan("com.fasterxml.jackson.core", "jackson-databind", "2.15.0",
                new FrameworkPoms(BOM, null, null, null),
                List.of(new AppInput("app9999", "Unrelated", unrelated))).get(1);
        assertThat(consumer.manual()).isTrue();
        assertThat(consumer.reason()).contains("does not use");
    }
}
