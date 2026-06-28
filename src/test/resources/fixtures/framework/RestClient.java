package ca.bnc.lsist.core.rest;

import io.restassured.response.Response;
import java.util.Map;

/** Fixture stand-in for the lsist RestClient (parsed by FrameworkApiExtractor, not compiled). */
public class RestClient {

    public Response get(String endpoint, String jwt, String context) {
        return null;
    }

    public Response post(String endpoint, String jwt, String body, String context) {
        return null;
    }

    public Response delete(String endpoint, String jwt, String context) {
        return null;
    }

    public String getApiUrl(String template, Map<String, String> vars) {
        return null;
    }

    private void internalHelper() {
    }
}
