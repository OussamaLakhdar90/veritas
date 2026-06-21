package ca.bnc.qe.veritas.engine.model;

public record FieldModel(
        String jsonName,
        String type,
        String format,
        boolean required,
        ConstraintSet constraints,
        String refSchema,
        SourceRef source
) {}
