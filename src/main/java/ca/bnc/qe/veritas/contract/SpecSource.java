package ca.bnc.qe.veritas.contract;

/** A spec source to resolve into a {@link SpecInput}: a kind plus its reference (path, URL, or page id). */
public record SpecSource(SpecSourceKind kind, String ref) {}
