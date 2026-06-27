package ca.bnc.qe.veritas.persistence;

/** Projection for a "serviceName → row count" group-by — used to build the service catalog (browse/recent-work). */
public interface ServiceCount {
    String getName();

    long getCount();
}
