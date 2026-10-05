package io.github.sanduniliyanage.flaglane.common.errors;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Which data access failures mean "the database is unreachable", answered with a 503. */
class DatabaseUnreachableTest {

  @Test
  void noConnectionAtAllIsUnreachable() {
    assertThat(
            ApiExceptionHandler.isDatabaseUnreachable(
                new CannotCreateTransactionException(
                    "no connection", new SQLTransientConnectionException("timeout"))))
        .isTrue();
  }

  @Test
  void aConnectionClassSqlStateAnywhereInTheChainIsUnreachable() {
    RuntimeException failure =
        new JpaSystemException(
            new RuntimeException("rollback failed", new SQLException("gone", "08006")));

    assertThat(ApiExceptionHandler.isDatabaseUnreachable(failure)).isTrue();
  }

  @Test
  void aBrokenSocketIsUnreachable() {
    assertThat(
            ApiExceptionHandler.isDatabaseUnreachable(
                new JpaSystemException(new RuntimeException(new SocketException("reset")))))
        .isTrue();
    assertThat(
            ApiExceptionHandler.isDatabaseUnreachable(
                new DataAccessResourceFailureException("I/O error")))
        .isTrue();
  }

  @Test
  void aConstraintViolationIsNot() {
    assertThat(
            ApiExceptionHandler.isDatabaseUnreachable(
                new DataIntegrityViolationException(
                    "duplicate", new SQLException("unique", "23505"))))
        .isFalse();
  }
}
