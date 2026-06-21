package ca.bnc.qe.veritas.skill;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Declares one CLI/REST input a skill accepts, validated before any step runs. */
public record InputSpec(
        String name,
        String type,
        boolean required,
        @JsonProperty("default") String defaultValue
) {}
