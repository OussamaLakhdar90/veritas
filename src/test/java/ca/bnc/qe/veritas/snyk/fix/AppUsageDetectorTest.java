package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Which framework artifacts an app's pom actually uses drives what the cascade bumps for it. */
class AppUsageDetectorTest {

    private final AppUsageDetector detector = new AppUsageDetector(new FrameworkProperties());

    @Test
    void detectsApiConsumerFromPropertiesAndDependency() {
        String pom = """
            <project>
                <properties>
                    <lsist-bom.version>1.0.9</lsist-bom.version>
                    <lsist-api.version>1.0.4</lsist-api.version>
                </properties>
                <dependencies>
                    <dependency><groupId>ca.bnc.lsist</groupId>
                        <artifactId>lsist-test-framework-api</artifactId>
                        <version>${lsist-api.version}</version></dependency>
                </dependencies>
            </project>
            """;
        AppUsageDetector.AppUsage u = detector.detect(pom);
        assertThat(u.usesApi()).isTrue();
        assertThat(u.usesBom()).isTrue();
        assertThat(u.usesWeb()).isFalse();
        assertThat(u.usesCore()).isFalse();
        assertThat(u.usesAny()).isTrue();
    }

    @Test
    void detectsWebConsumer() {
        String pom = """
            <project><properties><lsist-web.version>2.0.0</lsist-web.version></properties></project>
            """;
        AppUsageDetector.AppUsage u = detector.detect(pom);
        assertThat(u.usesWeb()).isTrue();
        assertThat(u.usesApi()).isFalse();
    }

    @Test
    void detectsNoUsageForAnUnrelatedPom() {
        assertThat(detector.detect("<project><artifactId>x</artifactId></project>").usesAny()).isFalse();
        assertThat(detector.detect(null).usesAny()).isFalse();
    }
}
