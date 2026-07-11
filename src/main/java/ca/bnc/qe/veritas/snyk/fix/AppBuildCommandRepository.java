package ca.bnc.qe.veritas.snyk.fix;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** The per-app reactor-command cache. One row per (appId, repoSlug); the advisor re-derives on a pom-hash change. */
public interface AppBuildCommandRepository extends JpaRepository<AppBuildCommand, String> {

    Optional<AppBuildCommand> findByAppIdAndRepoSlug(String appId, String repoSlug);
}
