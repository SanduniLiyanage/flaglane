package io.github.sanduniliyanage.flaglane.flag.persistence;

import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Flag configurations, always through a scope (NFR-SEC-004). A configuration is found by the flag's
 * key within the scope's project, never by a bare flag id.
 */
public interface FlagConfigRepository extends Repository<FlagConfigEntity, UUID> {

  List<FlagConfigEntity> saveAll(Iterable<FlagConfigEntity> configs);

  FlagConfigEntity saveAndFlush(FlagConfigEntity config);

  @Query(
      "select c from FlagConfigEntity c, FlagEntity f where f.id = c.flagId"
          + " and f.projectId = :#{#environment.projectId()} and f.key = :flagKey"
          + " and c.environmentId = :#{#environment.environmentId()}")
  Optional<FlagConfigEntity> find(
      @Param("environment") EnvironmentScope environment, @Param("flagKey") String flagKey);

  /** The environments of the project that already hold a configuration for this flag. */
  @Query(
      "select c.environmentId from FlagConfigEntity c, FlagEntity f where f.id = c.flagId"
          + " and f.projectId = :#{#project.projectId()} and f.key = :flagKey")
  List<UUID> findEnvironmentsConfigured(
      @Param("project") ProjectScope project, @Param("flagKey") String flagKey);
}
