package ca.bnc.qe.veritas.web;

import java.util.List;
import ca.bnc.qe.veritas.activity.ActivityItem;
import ca.bnc.qe.veritas.activity.ActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Activity Center feed: one cheap poll for every running/waiting/recent task + acknowledgements. */
@RestController
@RequestMapping("/api/v1/activity")
public class ActivityController {

    private final ActivityService activity;

    public ActivityController(ActivityService activity) {
        this.activity = activity;
    }

    @GetMapping
    public List<ActivityItem> feed() {
        return activity.feed();
    }

    public record AckRequest(List<String> ids) {}

    @PostMapping("/ack")
    public void acknowledge(@RequestBody AckRequest req) {
        if (req != null && req.ids() != null) {
            activity.acknowledge(req.ids());
        }
    }
}
