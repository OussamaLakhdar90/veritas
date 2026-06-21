package ca.bnc.qe.veritas.cli;

import java.util.concurrent.Callable;
import ca.bnc.qe.veritas.preflight.ConfigDoctor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/** Prints Veritas configuration readiness with remediation — run this first to learn how to configure. */
@Component
@Command(name = "doctor", description = "Check configuration readiness and show how to fix anything missing.")
public class DoctorCommand implements Callable<Integer> {

    private final ConfigDoctor doctor;

    public DoctorCommand(ConfigDoctor doctor) {
        this.doctor = doctor;
    }

    @Override
    public Integer call() {
        int missing = 0;
        System.out.println("Veritas configuration check:\n");
        for (ConfigDoctor.Check c : doctor.report()) {
            System.out.printf("  [%-7s] %s%n", c.status(), c.name());
            if (c.detail() != null && !c.detail().isBlank()) {
                System.out.println("            " + c.detail());
            }
            if (c.remediation() != null && !c.remediation().isBlank()) {
                System.out.println("            → " + c.remediation());
            }
            if ("MISSING".equals(c.status())) {
                missing++;
            }
        }
        System.out.println(missing == 0
                ? "\nReady. All required configuration is present."
                : "\n" + missing + " required item(s) missing — see the arrows above. Details: docs/configuration.md");
        return 0;
    }
}
