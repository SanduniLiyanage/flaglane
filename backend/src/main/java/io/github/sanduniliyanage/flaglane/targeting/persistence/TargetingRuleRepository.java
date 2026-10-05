package io.github.sanduniliyanage.flaglane.targeting.persistence;

import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Targeting rules, always through an environment scope and a flag key (NFR-SEC-004): the
 * configuration they belong to is found inside the scope's project, never named by id.
 */
public interface TargetingRuleRepository extends Repository<TargetingRuleEntity, UUID> {

  /** The configuration of {@code :flagKey} in the scope's environment, as a subquery. */
  String CONFIGURATION =
      "select c.id from FlagConfigEntity c, FlagEntity f where f.id = c.flagId"
          + " and f.projectId = :#{#environment.projectId()} and f.key = :flagKey"
          + " and c.environmentId = :#{#environment.environmentId()}";

  List<TargetingRuleEntity> saveAll(Iterable<TargetingRuleEntity> rules);

  @Query(
      "select r from TargetingRuleEntity r where r.flagConfigId = ("
          + CONFIGURATION
          + ") order by r.priority")
  List<TargetingRuleEntity> findAll(
      @Param("environment") EnvironmentScope environment, @Param("flagKey") String flagKey);

  /**
   * Deletes the configuration's rules at once, in SQL, before their replacements are inserted.
   * Removing entities one by one would not do: Hibernate flushes inserts before deletes, and the
   * new rule at priority 0 would meet the old one there.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from TargetingRuleEntity r where r.flagConfigId = (" + CONFIGURATION + ")")
  int deleteAll(
      @Param("environment") EnvironmentScope environment, @Param("flagKey") String flagKey);
}
