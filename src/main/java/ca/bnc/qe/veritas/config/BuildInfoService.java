package ca.bnc.qe.veritas.config;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

/**
 * The build/commit stamp of the RUNNING app — a short git SHA + build time — resolved from Spring Boot's
 * {@link GitProperties}/{@link BuildProperties} (generated at build time by the git-commit-id + build-info plugins).
 * Surfaced in the contract report footer and a startup log so any report identifies exactly which build produced it:
 * the difference between "a real bug" and "a stale build". Degrades gracefully when the info is absent.
 */
@Component
@Slf4j
public class BuildInfoService {

    private static final DateTimeFormatter STAMP_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final String stamp;

    public BuildInfoService(ObjectProvider<GitProperties> git, ObjectProvider<BuildProperties> build) {
        this.stamp = resolve(git.getIfAvailable(), build.getIfAvailable());
    }

    /** {@code "<shortSha> · built <time>"}, degrading to build time alone, then to "dev build" when neither exists. */
    static String resolve(GitProperties git, BuildProperties build) {
        StringBuilder sb = new StringBuilder();
        if (git != null && git.getShortCommitId() != null && !git.getShortCommitId().isBlank()) {
            sb.append(git.getShortCommitId());
        }
        Instant when = build != null && build.getTime() != null ? build.getTime()
                : (git != null ? git.getCommitTime() : null);
        if (when != null) {
            sb.append(sb.length() > 0 ? " · built " : "built ").append(STAMP_TIME.format(when));
        }
        return sb.length() == 0 ? "dev build" : sb.toString();
    }

    /** e.g. {@code "2c91c3b · built 2026-07-05 18:40"} — stable for the life of the running build. */
    public String stamp() {
        return stamp;
    }

    @PostConstruct
    void logStamp() {
        log.info("Veritas build: {}", stamp);
    }
}
