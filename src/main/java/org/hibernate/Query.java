package org.hibernate;

import com.nimbus.persistence.dialect.NimbusDialect;
import com.nimbus.persistence.mapping.EntityMetadata;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * Hibernate 5 compatibility alias for com.nimbus.persistence.Query.
 * list() returns raw List (no generic) to match Hibernate 5 API — callers use
 * @SuppressWarnings("unchecked") when assigning to List<EntityType>, same as before.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class Query extends com.nimbus.persistence.Query<Object> {

    public Query(Connection connection, String sql,
                 Map<String, List<Integer>> paramPositions,
                 Class<?> resultType, EntityMetadata entityMeta,
                 Map<Class<?>, EntityMetadata> metadataMap,
                 boolean showSql, NimbusDialect dialect,
                 boolean isCount, boolean isNativeCount) {
        super(connection, sql, paramPositions,
                (Class<Object>) resultType,
                entityMeta, metadataMap, showSql, dialect, isCount, isNativeCount);
    }

    /**
     * Hibernate 5 returned raw List — override to restore that behavior so existing
     * code with @SuppressWarnings("unchecked") List<MyEntity> x = query.list()
     * compiles without error.
     */
    @Override
    public List list() {
        return super.list();
    }

    @Override
    public List getResultList() {
        return super.getResultList();
    }

    // ── Hibernate 5 chaining — return org.hibernate.Query for fluent API ──────

    @Override
    public Query setParameter(String name, Object value) {
        return (Query) super.setParameter(name, value);
    }

    @Override
    public Query setString(String name, String value) {
        return (Query) super.setString(name, value);
    }

    @Override
    public Query setInteger(String name, Integer value) {
        return (Query) super.setInteger(name, value);
    }

    @Override
    public Query setLong(String name, Long value) {
        return (Query) super.setLong(name, value);
    }

    @Override
    public Query setBoolean(String name, Boolean value) {
        return (Query) super.setBoolean(name, value);
    }

    @Override
    public Query setDate(String name, java.util.Date value) {
        return (Query) super.setDate(name, value);
    }

    @Override
    public Query setTimestamp(String name, java.util.Date value) {
        return (Query) super.setTimestamp(name, value);
    }

    @Override
    public Query setMaxResults(int max) {
        return (Query) super.setMaxResults(max);
    }

    @Override
    public Query setFirstResult(int first) {
        return (Query) super.setFirstResult(first);
    }

    @Override
    public Query setResultTransformer(org.hibernate.transform.ResultTransformer transformer) {
        return (Query) super.setResultTransformer(transformer);
    }

    public Query setParameterList(String name, java.util.Collection<?> values) {
        return (Query) super.setParameterList(name, values);
    }

    public Query setParameterList(String name, Object[] values) {
        return (Query) super.setParameterList(name, values);
    }
}
