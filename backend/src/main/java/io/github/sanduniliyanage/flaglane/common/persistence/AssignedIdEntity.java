package io.github.sanduniliyanage.flaglane.common.persistence;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * An entity whose UUID is generated in application code, so it is complete before it is inserted
 * (docs/DATABASE.md).
 *
 * <p>Spring Data treats an entity with a non-null id as already persisted and merges it, which
 * costs a {@code SELECT} before every insert. Tracking newness explicitly makes {@code save} a
 * plain {@code INSERT} for a new entity.
 */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {

  @Id private UUID id;

  @Transient private boolean isNew = true;

  /** For JPA. */
  protected AssignedIdEntity() {}

  /**
   * @param id never null; Hibernate refuses to insert an entity whose assigned id is missing
   */
  protected AssignedIdEntity(UUID id) {
    this.id = id;
  }

  /** The id. Never null for an entity constructed by the application or loaded by JPA. */
  public UUID id() {
    return id;
  }

  /**
   * Spring Data's accessor, which it declares nullable for generated ids. Application code uses
   * {@link #id()}.
   */
  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostPersist
  @PostLoad
  void markPersisted() {
    isNew = false;
  }
}
