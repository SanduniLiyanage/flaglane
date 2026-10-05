package io.github.sanduniliyanage.flaglane.evaluation;

/** Where the engine's warnings go. The engine rate-limits before calling it. */
@FunctionalInterface
interface WarningSink {

  /**
   * @param flagKey the flag concerned, already made safe to print
   * @param message what happened and what was returned instead
   * @param cause the exception behind it, or {@code null}
   */
  void warn(String flagKey, String message, Throwable cause);

  /**
   * The JDK's platform logger, so {@code evaluation/} needs no logging dependency. Spring Boot
   * routes it through java.util.logging into the application's log configuration.
   */
  static WarningSink platformLogger() {
    System.Logger logger = System.getLogger(Evaluator.class.getName());
    return (flagKey, message, cause) ->
        logger.log(System.Logger.Level.WARNING, "Flag '" + flagKey + "' " + message, cause);
  }
}
