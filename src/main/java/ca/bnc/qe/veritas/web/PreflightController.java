package ca.bnc.qe.veritas.web;

import java.util.List;
import ca.bnc.qe.veritas.preflight.ConfigDoctor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Config-readiness report the dashboard/CLI use to guide setup. */
@RestController
@RequestMapping("/api/v1")
public class PreflightController {

    private final ConfigDoctor doctor;

    public PreflightController(ConfigDoctor doctor) {
        this.doctor = doctor;
    }

    @GetMapping("/preflight")
    public List<ConfigDoctor.Check> preflight() {
        return doctor.report();
    }
}
