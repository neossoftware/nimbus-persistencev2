package org.hibernate;

import com.nimbus.persistence.mapping.EntityMetadata;

import java.sql.Connection;
import java.util.Map;

/**
 * Hibernate 5 compatibility alias for com.nimbus.persistence.SessionFactory.
 */
public class SessionFactory extends com.nimbus.persistence.SessionFactory {

    public SessionFactory(Map<String, String> properties,
                          Map<Class<?>, EntityMetadata> metadataMap,
                          Map<String, Class<?>> entityRegistry) {
        super(properties, metadataMap, entityRegistry);
    }

    @Override
    protected Session newSession(Connection conn) {
        return new Session(conn, metadataMap, entityRegistry, showSql, dialect);
    }

    @Override
    public Session openSession() {
        return (Session) super.openSession();
    }
}
