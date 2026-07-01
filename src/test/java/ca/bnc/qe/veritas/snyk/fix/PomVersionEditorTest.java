package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Golden tests for the format-preserving pom edits. */
class PomVersionEditorTest {

    private static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.3.13</version>
                </parent>
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
                        <dependency>
                            <groupId>org.mozilla</groupId>
                            <artifactId>rhino</artifactId>
                            <version>1.7.14</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

    @Test
    void patchBumpIncrementsLastNumericSegment() {
        assertThat(PomVersionEditor.patchBump("1.0.9")).isEqualTo("1.0.10");
        assertThat(PomVersionEditor.patchBump("1.7.15.1")).isEqualTo("1.7.15.2");
        assertThat(PomVersionEditor.patchBump("3.18.0")).isEqualTo("3.18.1");
    }

    @Test
    void readsPropertyAndDependencyTokens() {
        assertThat(PomVersionEditor.propertyValue(POM, "jackson.version")).isEqualTo("2.14.0");
        assertThat(PomVersionEditor.propertyValue(POM, "missing.version")).isNull();
        assertThat(PomVersionEditor.dependencyVersionToken(POM, "com.fasterxml.jackson.core", "jackson-databind"))
                .isEqualTo("${jackson.version}");
        assertThat(PomVersionEditor.dependencyVersionToken(POM, "org.mozilla", "rhino")).isEqualTo("1.7.14");
        assertThat(PomVersionEditor.dependencyVersionToken(POM, "org.absent", "nope")).isNull();
    }

    @Test
    void bumpsAPropertyValueInPlace() {
        String out = PomVersionEditor.bumpProperty(POM, "jackson.version", "2.15.0");
        assertThat(out).contains("<jackson.version>2.15.0</jackson.version>");
        assertThat(out).doesNotContain("2.14.0");
    }

    @Test
    void bumpsALiteralDependencyVersion() {
        String out = PomVersionEditor.bumpDependencyVersion(POM, "org.mozilla", "rhino", "1.7.15.1");
        assertThat(out).contains("<artifactId>rhino</artifactId>");
        assertThat(out).contains("<version>1.7.15.1</version>");
        assertThat(out).doesNotContain("1.7.14");
    }

    @Test
    void readsAndBumpsTheProjectOwnVersionNotTheParentVersion() {
        assertThat(PomVersionEditor.projectVersion(POM)).isEqualTo("1.0.9");
        String out = PomVersionEditor.bumpProjectVersion(POM, "1.0.10");
        assertThat(out).contains("<version>3.3.13</version>");   // parent version untouched
        assertThat(out).contains("<artifactId>lsist-test-framework-bom</artifactId>\n    <version>1.0.10</version>");
    }

    @Test
    void addsAManagedDependencyOverrideForATransitive() {
        String out = PomVersionEditor.addManagedDependency(POM, "org.yaml", "snakeyaml", "2.2");
        assertThat(out).contains("<artifactId>snakeyaml</artifactId>");
        assertThat(out).contains("<version>2.2</version>");
        // Inserted inside the existing dependencyManagement/dependencies block.
        assertThat(out.indexOf("snakeyaml")).isGreaterThan(out.indexOf("<dependencyManagement>"));
        assertThat(out.indexOf("snakeyaml")).isLessThan(out.indexOf("</dependencyManagement>"));
    }
}
