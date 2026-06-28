package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Auto-detects the lsist framework sources for a test repo: override → vendored → -sources.jar from local Maven. */
class FrameworkSourceLocatorTest {

    private static final String WORLDKEY_SOURCE = """
            package ca.bnc.lsist.core.base;
            public abstract class AbstractTestBase {
                public enum WorldKey { RAW_RESPONSE, ROBOT_TOKEN, CONTEXT }
            }
            """;

    @Test
    void configuredOverrideWins(@TempDir Path tmp) throws Exception {
        Path override = Files.createDirectory(tmp.resolve("override"));

        var located = new FrameworkSourceLocator(override.toString(), "").locate(tmp.resolve("unused-repo"));

        assertThat(located).isPresent();
        assertThat(located.get().dir()).isEqualTo(override);
        assertThat(located.get().temporary()).isFalse();
    }

    @Test
    void autoDetectsVendoredWorldKeyInRepoTree(@TempDir Path tmp) throws Exception {
        Path pkg = Files.createDirectories(tmp.resolve("repo/src/main/java/ca/bnc/lsist/core/base"));
        Files.writeString(pkg.resolve("AbstractTestBase.java"), WORLDKEY_SOURCE);

        var located = new FrameworkSourceLocator("", "").locate(tmp.resolve("repo"));

        assertThat(located).isPresent();
        assertThat(located.get().temporary()).isFalse();
        String block = new FrameworkApiExtractor().extract(located.get().dir()).orElseThrow();
        assertThat(block).contains("RAW_RESPONSE").contains("ROBOT_TOKEN");
    }

    @Test
    void resolvesAndUnzipsSourcesJarFromLocalMavenRepo(@TempDir Path tmp) throws Exception {
        // a test repo whose pom references the framework dependency
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Files.writeString(repo.resolve("pom.xml"),
                "<project><dependencies><dependency>"
                        + "<groupId>ca.bnc.lsist</groupId><artifactId>lsist-test-framework-core</artifactId>"
                        + "<version>1.0.0</version></dependency></dependencies></project>");
        // a fake local Maven repo holding the -sources.jar
        Path jarDir = Files.createDirectories(tmp.resolve("m2/ca/bnc/lsist/lsist-test-framework-core/1.0.0"));
        Path jar = jarDir.resolve("lsist-test-framework-core-1.0.0-sources.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("ca/bnc/lsist/core/base/AbstractTestBase.java"));
            zip.write(WORLDKEY_SOURCE.getBytes());
            zip.closeEntry();
        }

        var located = new FrameworkSourceLocator("", tmp.resolve("m2").toString()).locate(repo);

        assertThat(located).isPresent();
        assertThat(located.get().temporary()).isTrue();   // unzipped into a temp dir
        String block = new FrameworkApiExtractor().extract(located.get().dir()).orElseThrow();
        assertThat(block).contains("WorldKey constants").contains("ROBOT_TOKEN");
    }

    @Test
    void emptyWhenRepoHasNoFrameworkAndNoJar(@TempDir Path tmp) throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo"));   // no pom, no WorldKey

        var located = new FrameworkSourceLocator("", tmp.resolve("empty-m2").toString()).locate(repo);

        assertThat(located).isEmpty();
    }
}
