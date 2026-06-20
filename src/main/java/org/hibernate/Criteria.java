package org.hibernate;

import com.nimbus.persistence.dialect.NimbusDialect;
import com.nimbus.persistence.mapping.EntityMetadata;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * Hibernate 5 compatibility alias for com.nimbus.persistence.Criteria.
 * list() retorna raw List (sin generic) igual que Hibernate 5.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class Criteria extends com.nimbus.persistence.Criteria<Object> {

    public Criteria(Connection connection, Class<?> entityClass, EntityMetadata meta,
                    Map<Class<?>, EntityMetadata> metadataMap,
                    boolean showSql, NimbusDialect dialect) {
        super(connection, (Class<Object>) entityClass, meta, metadataMap, showSql, dialect);
    }

    @Override
    public List list() {
        return super.list();
    }

    // ── Hibernate 5 fluent chain — retorna org.hibernate.Criteria ────────────

    @Override
    public Criteria add(com.nimbus.persistence.Restrictions.Criterion criterion) {
        return (Criteria) super.add(criterion);
    }

    @Override
    public Criteria addOrder(com.nimbus.persistence.Order order) {
        return (Criteria) super.addOrder(order);
    }

    @Override
    public Criteria setMaxResults(int max) {
        return (Criteria) super.setMaxResults(max);
    }

    @Override
    public Criteria setFirstResult(int first) {
        return (Criteria) super.setFirstResult(first);
    }
}
