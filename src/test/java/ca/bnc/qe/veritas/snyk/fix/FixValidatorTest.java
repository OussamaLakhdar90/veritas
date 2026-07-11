package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The deterministic effective-version resolver that decides whether a fix actually raised the coordinate to fixedIn. */
class FixValidatorTest {

    @Test
    void resolvesAManagedLiteralVersion() {
        String pom = """
                <project><dependencyManagement><dependencies>
                    <dependency><groupId>tools.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId><version>3.1.4</version></dependency>
                </dependencies></dependencyManagement></project>
                """;
        assertThat(FixValidator.effectiveVersion(pom, "tools.jackson.core", "jackson-databind")).isEqualTo("3.1.4");
        assertThat(FixValidator.managesAtVersion(pom, "tools.jackson.core", "jackson-databind", "3.1.4")).isTrue();
        assertThat(FixValidator.managesAtVersion(pom, "tools.jackson.core", "jackson-databind", "3.1.1")).isFalse();
    }

    @Test
    void followsAPropertyReferenceToItsDeclaredValue() {
        String pom = """
                <project>
                    <properties><jackson.version>2.15.0</jackson.version></properties>
                    <dependencyManagement><dependencies>
                        <dependency><groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-databind</artifactId><version>${jackson.version}</version></dependency>
                    </dependencies></dependencyManagement>
                </project>
                """;
        assertThat(FixValidator.effectiveVersion(pom, "com.fasterxml.jackson.core", "jackson-databind"))
                .isEqualTo("2.15.0");
        assertThat(FixValidator.managesAtVersion(pom, "com.fasterxml.jackson.core", "jackson-databind", "2.15.0"))
                .isTrue();
    }

    @Test
    void returnsNullWhenTheCoordinateIsNotManagedHere() {
        assertThat(FixValidator.effectiveVersion("<project/>", "com.x", "y")).isNull();
        assertThat(FixValidator.effectiveVersion(null, "com.x", "y")).isNull();
        assertThat(FixValidator.managesAtVersion("<project/>", "com.x", "y", "1.0")).isFalse();
        assertThat(FixValidator.managesAtVersion("<project/>", "com.x", "y", null)).isFalse();
    }
}
