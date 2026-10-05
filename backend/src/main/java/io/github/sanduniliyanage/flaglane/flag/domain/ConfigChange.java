package io.github.sanduniliyanage.flaglane.flag.domain;

/**
 * The editable parts of a configuration. A {@code null} field is left as it is. There is no off
 * value here: disabling a flag returns {@code false}, and nothing may make it otherwise (ADR-009).
 *
 * @param rolloutBasisPoints 0 to 10000; the web layer converts the whole percentage it accepts
 * @param rolloutSalt changing it re-buckets every user of the flag (ADR-010)
 */
public record ConfigChange(
    Boolean enabled, Boolean fallthroughValue, Integer rolloutBasisPoints, String rolloutSalt) {}
