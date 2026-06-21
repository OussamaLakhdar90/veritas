package ca.bnc.qe.veritas.engine.model;

public record ParamModel(
        String name,
        ParamLocation location,
        String type,
        String format,
        boolean required,
        ConstraintSet constraints,
        SourceRef source
) {}
