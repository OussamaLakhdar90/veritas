package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void patchBumpPreservesAQualifierInsteadOfBumpingTheWrongSegment() {
        assertThat(PomVersionEditor.patchBump("1.0.9-SNAPSHOT")).isEqualTo("1.0.10-SNAPSHOT");   // not 1.1.9-SNAPSHOT
        assertThat(PomVersionEditor.patchBump("2.0.0-RC1")).isEqualTo("2.0.1-RC1");
        assertThat(PomVersionEditor.patchBump("1.0.0.RELEASE")).isEqualTo("1.0.1.RELEASE");
    }

    @Test
    void addManagedDependencyLandsInsideDependencyManagementEvenWithoutAnExistingDependenciesChild() {
        String pom = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <artifactId>bom</artifactId><version>1.0.0</version>
                <dependencyManagement></dependencyManagement>
                <dependencies>
                    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13</version></dependency>
                </dependencies>
            </project>
            """;
        String out = PomVersionEditor.addManagedDependency(pom, "org.yaml", "snakeyaml", "2.2");
        // The override must be pinned INSIDE dependencyManagement, not appended to the project <dependencies>.
        int snake = out.indexOf("snakeyaml");
        assertThat(snake).isGreaterThan(out.indexOf("<dependencyManagement>"));
        assertThat(snake).isLessThan(out.indexOf("</dependencyManagement>"));
        assertThat(snake).isLessThan(out.indexOf("junit"));   // before the project-level dependencies
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
    void propertyEditIgnoresACommentedOutDeclarationBeforeTheRealOne() {
        String pom = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <properties>
                    <!-- <lsist-api.version>0.0.1</lsist-api.version>  legacy, do not use -->
                    <lsist-api.version>1.0.4</lsist-api.version>
                </properties>
            </project>
            """;
        // The commented value must NOT be read as the effective one...
        assertThat(PomVersionEditor.propertyValue(pom, "lsist-api.version")).isEqualTo("1.0.4");
        // ...and the bump must land on the real property, leaving the comment untouched.
        String edited = PomVersionEditor.bumpProperty(pom, "lsist-api.version", "1.0.5");
        assertThat(edited).contains("<lsist-api.version>1.0.5</lsist-api.version>");
        assertThat(edited).contains("<!-- <lsist-api.version>0.0.1</lsist-api.version>");   // comment preserved verbatim
    }

    @Test
    void propertyEditIgnoresAnInactiveProfileCopyOutsideTheMainPropertiesBlock() {
        String pom = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <properties>
                    <lsist-bom.version>1.0.9</lsist-bom.version>
                </properties>
                <profiles>
                    <profile>
                        <id>legacy</id>
                        <properties><lsist-bom.version>0.9.0</lsist-bom.version></properties>
                    </profile>
                </profiles>
            </project>
            """;
        assertThat(PomVersionEditor.propertyValue(pom, "lsist-bom.version")).isEqualTo("1.0.9");
        String edited = PomVersionEditor.bumpProperty(pom, "lsist-bom.version", "1.0.10");
        assertThat(edited).contains("<lsist-bom.version>1.0.10</lsist-bom.version>");
        assertThat(edited).contains("<lsist-bom.version>0.9.0</lsist-bom.version>");   // profile copy untouched
    }

    @Test
    void rejectsAnInjectedValueRatherThanWritingItIntoAPomThatWillBeBuilt() {
        // An XML-injection payload (balanced tags to smuggle in a <build><plugins> block) must never be written.
        String evil = "1.0</version></properties><build><plugins><plugin>x</plugin></plugins></build><properties><x>1";
        assertThatThrownBy(() -> PomVersionEditor.bumpProjectVersion(POM, evil))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PomVersionEditor.bumpProperty(POM, "jackson.version", evil))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PomVersionEditor.bumpDependencyVersion(POM, "org.mozilla", "rhino", evil))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PomVersionEditor.addManagedDependency(POM, "org.yaml", "snake</artifactId><x>", "2.2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aModuleThatInheritsItsVersionHasNoOwnVersionToBumpAndNeverMangledADependency() {
        // Regression: a module with NO own <version> (it inherits from <parent>) but a literal dependency version
        // must NOT report the dependency's version as the project version and must NOT rewrite it as a "release".
        String module = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>g</groupId><artifactId>a</artifactId><version>3.0.0</version>
                </parent>
                <artifactId>lsist-test-framework-core</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>org.mozilla</groupId><artifactId>rhino</artifactId><version>1.7.14</version>
                    </dependency>
                </dependencies>
            </project>
            """;
        assertThat(PomVersionEditor.projectVersion(module)).isNull();   // not "1.7.14"
        assertThatThrownBy(() -> PomVersionEditor.bumpProjectVersion(module, "1.0.10"))
                .isInstanceOf(IllegalStateException.class);
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
