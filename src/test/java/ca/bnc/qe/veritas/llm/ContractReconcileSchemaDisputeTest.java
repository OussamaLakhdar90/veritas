package ca.bnc.qe.veritas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The reconcile schema's dispute channel is bounded: a dispute REQUIRES a non-blank reason, the field is OPTIONAL
 * (existing replies without it stay valid — back-compat with the mock gateway and every current fixture).
 */
class ContractReconcileSchemaDisputeTest {

    private final ResponseSchemaValidator validator = new ResponseSchemaValidator(new DefaultResourceLoader());
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    @Test
    void aValidDisputeWithAReasonIsAccepted() throws Exception {
        JsonNode node = json("{\"correctedYaml\":\"x\",\"findings\":[],"
                + "\"disputedFindings\":[{\"findingId\":\"FID\",\"reason\":\"the advice maps this status\"}]}");
        assertThatCode(() -> validator.validate(node, "contract-reconcile.schema.json")).doesNotThrowAnyException();
    }

    @Test
    void aDisputeWithoutAReasonIsRejected() throws Exception {
        JsonNode node = json("{\"correctedYaml\":\"x\",\"findings\":[],"
                + "\"disputedFindings\":[{\"findingId\":\"FID\",\"reason\":\"\"}]}");
        assertThatThrownBy(() -> validator.validate(node, "contract-reconcile.schema.json"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void aReplyWithoutAnyDisputesStillValidates() throws Exception {
        JsonNode node = json("{\"correctedYaml\":\"x\",\"findings\":[]}");
        assertThatCode(() -> validator.validate(node, "contract-reconcile.schema.json")).doesNotThrowAnyException();
        assertThat(node.has("disputedFindings")).isFalse();
    }
}
