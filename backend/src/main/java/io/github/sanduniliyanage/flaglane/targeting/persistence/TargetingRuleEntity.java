package io.github.sanduniliyanage.flaglane.targeting.persistence;

import io.github.sanduniliyanage.flaglane.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A row of {@code targeting_rules}. Never edited in place: a configuration's rules are replaced as
 * a whole list (FR-RUL-004), so a row is only ever inserted or deleted.
 */
@Entity
@Immutable
@Table(name = "targeting_rules")
public class TargetingRuleEntity extends AssignedIdEntity {

  @Column(name = "flag_config_id", nullable = false, updatable = false)
  private UUID flagConfigId;

  @Column(name = "priority", nullable = false, updatable = false)
  private int priority;

  @Column(name = "attribute", nullable = false, updatable = false)
  private String attribute;

  @Column(name = "operator", nullable = false, updatable = false)
  private String operator;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "match_values", nullable = false, updatable = false)
  private List<Object> matchValues;

  @Column(name = "result_value", nullable = false, updatable = false)
  private boolean resultValue;

  protected TargetingRuleEntity() {}

  public TargetingRuleEntity(
      UUID id,
      UUID flagConfigId,
      int priority,
      String attribute,
      String operator,
      List<?> matchValues,
      boolean resultValue) {
    super(id);
    this.flagConfigId = flagConfigId;
    this.priority = priority;
    this.attribute = attribute;
    this.operator = operator;
    this.matchValues = Collections.unmodifiableList(new ArrayList<>(matchValues));
    this.resultValue = resultValue;
  }

  public UUID getFlagConfigId() {
    return flagConfigId;
  }

  public int getPriority() {
    return priority;
  }

  public String getAttribute() {
    return attribute;
  }

  public String getOperator() {
    return operator;
  }

  public List<Object> getMatchValues() {
    return Collections.unmodifiableList(matchValues);
  }

  public boolean getResultValue() {
    return resultValue;
  }
}
