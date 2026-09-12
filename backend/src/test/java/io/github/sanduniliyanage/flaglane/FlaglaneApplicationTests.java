package io.github.sanduniliyanage.flaglane;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class FlaglaneApplicationTests {

  @Container private static final FlaglanePostgres POSTGRES = new FlaglanePostgres();

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    POSTGRES.registerProperties(registry);
  }

  @Test
  void contextLoads() {}
}
