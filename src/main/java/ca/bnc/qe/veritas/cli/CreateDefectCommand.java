package ca.bnc.qe.veritas.cli;

import java.util.concurrent.Callable;
import ca.bnc.qe.veritas.defect.DefectService;
import ca.bnc.qe.veritas.persistence.DefectLink;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Create a Jira defect from a contract-validation finding. */
@Component
@Command(name = "create-defect", description = "Create a Jira defect from a finding.")
public class CreateDefectCommand implements Callable<Integer> {

    private final DefectService defectService;

    @Option(names = "--finding", required = true, description = "Finding id to raise a defect for.")
    private String findingId;

    @Option(names = "--project", required = true, description = "Jira project key.")
    private String project;

    @Option(names = "--type", description = "Jira issue type (default: Bug).")
    private String type = "Bug";

    public CreateDefectCommand(DefectService defectService) {
        this.defectService = defectService;
    }

    @Override
    public Integer call() {
        DefectLink link = defectService.createDefect(findingId, project, type, "local");
        System.out.println("Defect " + link.getJiraKey() + " -> " + (link.getJiraUrl() == null ? "(no url)" : link.getJiraUrl()));
        return 0;
    }
}
