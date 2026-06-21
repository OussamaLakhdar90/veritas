package ca.bnc.qe.veritas.web;

import java.util.List;
import ca.bnc.qe.veritas.vcs.GitHost;
import ca.bnc.qe.veritas.vcs.RepoInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The app-id → repo-picker front door: list the repos a user can access under an app-id, and their branches. */
@RestController
@RequestMapping("/api/v1")
public class ReposController {

    private final GitHost gitHost;

    public ReposController(GitHost gitHost) {
        this.gitHost = gitHost;
    }

    @GetMapping("/repos")
    public List<RepoInfo> repos(@RequestParam String appId) {
        return gitHost.discoverRepos(appId);
    }

    @GetMapping("/repos/{slug}/branches")
    public List<String> branches(@PathVariable String slug) {
        return gitHost.listBranches(slug);
    }
}
