package io.github.sanduniliyanage.flaglane.project.persistence;

import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Environments, always through a scope (NFR-SEC-004). Every query names the project as well as the
 * environment, so even a scope built wrongly could not reach another project's rows.
 */
public interface EnvironmentRepository extends Repository<EnvironmentEntity, UUID> {

  /** Inserts and flushes, so a key taken within the project fails here. */
  EnvironmentEntity saveAndFlush(EnvironmentEntity environment);

  List<EnvironmentEntity> saveAll(Iterable<EnvironmentEntity> environments);

  @Query(
      "select e from EnvironmentEntity e where e.projectId = :#{#project.projectId()}"
          + " order by e.createdAt, e.key")
  List<EnvironmentEntity> findAll(@Param("project") ProjectScope project);

  @Query(
      "select e from EnvironmentEntity e where e.id = :#{#environment.environmentId()}"
          + " and e.projectId = :#{#environment.projectId()}")
  Optional<EnvironmentEntity> find(@Param("environment") EnvironmentScope environment);

  /** Marks one environment's ruleset as changed: the next ETag and stream event say so. */
  @Modifying
  @Query(
      "update EnvironmentEntity e set e.rulesetVersion = e.rulesetVersion + 1"
          + " where e.id = :#{#environment.environmentId()}"
          + " and e.projectId = :#{#environment.projectId()}")
  int bumpRulesetVersion(@Param("environment") EnvironmentScope environment);

  /** Marks every environment of the project as changed, for a change to a flag itself. */
  @Modifying
  @Query(
      "update EnvironmentEntity e set e.rulesetVersion = e.rulesetVersion + 1"
          + " where e.projectId = :#{#project.projectId()}")
  int bumpRulesetVersions(@Param("project") ProjectScope project);

  /** Keys not yet revoked; an environment holding one cannot be deleted (FR-ENV-003). */
  @Query(
      value =
          "select count(*) from api_keys k join environments e on e.id = k.environment_id"
              + " where k.environment_id = :#{#environment.environmentId()}"
              + " and e.project_id = :#{#environment.projectId()} and k.revoked_at is null",
      nativeQuery = true)
  long countActiveKeys(@Param("environment") EnvironmentScope environment);

  /**
   * Deletes the environment. Its keys, flag configurations, rules and overrides go with it by
   * cascade; its audit entries stay, still naming it (ADR-022).
   */
  @Modifying
  @Query(
      "delete from EnvironmentEntity e where e.id = :#{#environment.environmentId()}"
          + " and e.projectId = :#{#environment.projectId()}")
  int delete(@Param("environment") EnvironmentScope environment);
}
