package ca.bnc.qe.veritas.engine.openapi;

import java.util.List;
import ca.bnc.qe.veritas.engine.model.ApiModel;

/** Result of parsing one OpenAPI/Swagger document: the IR plus any parser messages (→ L1 findings). */
public record SpecParse(ApiModel model, List<String> messages, boolean parsed) {}
