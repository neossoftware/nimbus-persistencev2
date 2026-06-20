package org.hibernate;

import com.nimbus.persistence.dialect.NimbusDialect;
import com.nimbus.persistence.mapping.EntityMetadata;

import java.sql.Connection;
import java.util.Map;

/**
 * Hibernate 5 compatibility alias for com.nimbus.persistence.Criteria.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class Criteria extends com.nimbus.persistence.Criteria<Object> {

    public Criteria(Connection connection, Class<?> entityClass, EntityMetadata meta,
                    Map<Class<?>, EntityMetadata> metadataMap,
                    boolean showSql, NimbusDialect dialect) {
        super(connection, (Class<Object>) entityClass, meta, metadataMap, showSql, dialect);
    }
}
