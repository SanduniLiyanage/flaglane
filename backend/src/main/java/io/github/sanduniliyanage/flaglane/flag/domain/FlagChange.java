package io.github.sanduniliyanage.flaglane.flag.domain;

/**
 * The editable parts of a flag. A {@code null} field is left as it is; an empty description clears
 * the description. The key is not here: it never changes (FR-FLG-002).
 */
public record FlagChange(String name, String description, Boolean clientSideVisible) {}
