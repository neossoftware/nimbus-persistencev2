package org.hibernate;

import com.nimbus.persistence.dialect.NimbusDialect;
import com.nimbus.persistence.mapping.EntityMetadata;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * Hibernate 5 compatibility alias for com.nimbus.persistence.Query.
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
}
