package io.github.sanduniliyanage.flaglane.project.persistence;

import io.github.sanduniliyanage.flaglane.common.tenancy.OwnerScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Projects, always through a scope (NFR-SEC-004). There is no method that takes a bare id or
 * returns rows across owners.
 */
public interface ProjectRepository extends Repository<ProjectEntity, UUID> {

  /** Inserts and flushes, so a taken key fails here, inside the service's transaction. */
  ProjectEntity saveAndFlush(ProjectEntity project);

  @Query(
      "select p from ProjectEntity p where p.ownerId = :#{#owner.userId()}"
          + " order by p.createdAt, p.key")
  List<ProjectEntity> findAll(@Param("owner") OwnerScope owner);

  @Query(
      "select p from ProjectEntity p where p.id = :#{#project.projectId()}"
          + " and p.ownerId = :#{#project.userId()}")
  Optional<ProjectEntity> find(@Param("project") ProjectScope project);
}
