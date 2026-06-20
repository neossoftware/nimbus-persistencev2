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
}
