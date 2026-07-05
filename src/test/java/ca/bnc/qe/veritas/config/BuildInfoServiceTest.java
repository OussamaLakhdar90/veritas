package ca.bnc.qe.veritas.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

/** The build stamp resolves from git/build info and degrades gracefully when either (or both) is absent. */
class BuildInfoServiceTest {

    private static GitProperties git(String shortSha) {
        Properties p = new Properties();
        p.put("commit.id.abbrev", shortSha);
        return new GitProperties(p);
    }

    private static BuildProperties buildAt(Instant time) {
        Properties p = new Properties();
        p.put("time", String.valueOf(time.toEpochMilli()));
        return new BuildProperties(p);
    }

    @Test
    void resolvesShaAndBuildTime() {
        String stamp = BuildInfoService.resolve(git("abc1234"), buildAt(Instant.now()));
        assertThat(stamp).startsWith("abc1234").contains("built");
    }

    @Test
    void shaOnlyWhenNoBuildTime() {
        assertThat(BuildInfoService.resolve(git("abc1234"), null)).isEqualTo("abc1234").doesNotContain("built");
    }

    @Test
    void buildTimeOnlyWhenNoGit() {
        assertThat(BuildInfoService.resolve(null, buildAt(Instant.now()))).startsWith("built");
    }

    @Test
    void devBuildWhenNeitherIsPresent() {
        assertThat(BuildInfoService.resolve(null, null)).isEqualTo("dev build");
    }
}
