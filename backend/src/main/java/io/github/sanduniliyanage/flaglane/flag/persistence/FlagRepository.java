package io.github.sanduniliyanage.flaglane.flag.persistence;

import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Flags, always through a project scope (NFR-SEC-004). */
public interface FlagRepository extends Repository<FlagEntity, UUID> {

  /** Inserts and flushes, so a key taken in the project, archived or not, fails here. */
  FlagEntity saveAndFlush(FlagEntity flag);

  @Query("select f from FlagEntity f where f.projectId = :#{#project.projectId()} order by f.key")
  List<FlagEntity> findAll(@Param("project") ProjectScope project);

  @Query(
      "select f from FlagEntity f where f.projectId = :#{#project.projectId()}"
          + " and f.archivedAt is null order by f.key")
  List<FlagEntity> findLive(@Param("project") ProjectScope project);

  @Query("select f from FlagEntity f where f.projectId = :#{#project.projectId()} and f.key = :key")
  Optional<FlagEntity> find(@Param("project") ProjectScope project, @Param("key") String key);
}
