package fixtures.meta;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/things")
public class MetaController {

    @ApiGet("/{id}")
    public String getThing(@PathVariable("id") String id) {
        return null;
    }
}
