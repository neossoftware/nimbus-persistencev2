package org.hibernate;

import com.nimbus.persistence.dialect.NimbusDialect;
import com.nimbus.persistence.mapping.EntityMetadata;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * Hibernate 5 compatibility alias for com.nimbus.persistence.Session.
 * Drop-in replacement: swap the JAR, keep all imports as-is.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class Session extends com.nimbus.persistence.Session {

    public Session(Connection connection,
                   Map<Class<?>, EntityMetadata> metadataMap,
                   Map<String, Class<?>> entityRegistry,
                   boolean showSql, NimbusDialect dialect) {
        super(connection, metadataMap, entityRegistry, showSql, dialect);
    }

    // ── Return org.hibernate subtypes from all factory methods ────────────────

    @Override
    protected Transaction newTransaction() {
        return new Transaction(connection);
    }

    @Override
    public Transaction beginTransaction() {
        return (Transaction) super.beginTransaction();
    }

    @Override
    public Transaction getTransaction() {
        return (Transaction) super.getTransaction();
    }

    @Override
    protected <T> com.nimbus.persistence.Criteria<T> newCriteria(Class<T> entityClass,
                                                                   EntityMetadata meta) {
        return (com.nimbus.persistence.Criteria<T>) new Criteria(
                connection, entityClass, meta, metadataMap, showSql, dialect);
    }

    @Override
    public Criteria createCriteria(Class entityClass) {
        return (Criteria) super.createCriteria(entityClass);
    }

    @Override
    protected <T> com.nimbus.persistence.Query<T> newQuery(String sql,
            Map<String, List<Integer>> paramPositions, Class<T> resultType,
            EntityMetadata meta, boolean isCount, boolean isNativeCount) {
        return (com.nimbus.persistence.Query<T>) new Query(connection, sql, paramPositions,
                resultType, meta, metadataMap, showSql, dialect, isCount, isNativeCount);
    }

    @Override
    public Query createQuery(String hql) {
        return (Query) super.createQuery(hql);
    }

    @Override
    public Query createNativeQuery(String sql) {
        return (Query) super.createNativeQuery(sql);
    }

    @Override
    public Query createSQLQuery(String sql) {
        return createNativeQuery(sql);
    }
}
