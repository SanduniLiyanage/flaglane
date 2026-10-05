package io.github.sanduniliyanage.flaglane.targeting.persistence;

import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** User overrides, always through an environment scope and a flag key (NFR-SEC-004). */
public interface UserOverrideRepository extends Repository<UserOverrideEntity, UUID> {

  List<UserOverrideEntity> saveAll(Iterable<UserOverrideEntity> overrides);

  @Query(
      "select o from UserOverrideEntity o where o.flagConfigId = ("
          + TargetingRuleRepository.CONFIGURATION
          + ") order by o.userKey")
  List<UserOverrideEntity> findAll(
      @Param("environment") EnvironmentScope environment, @Param("flagKey") String flagKey);

  /** Deletes the configuration's overrides at once, before their replacements are inserted. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "delete from UserOverrideEntity o where o.flagConfigId = ("
          + TargetingRuleRepository.CONFIGURATION
          + ")")
  int deleteAll(
      @Param("environment") EnvironmentScope environment, @Param("flagKey") String flagKey);
}
