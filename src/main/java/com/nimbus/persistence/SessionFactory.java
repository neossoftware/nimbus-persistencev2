package com.nimbus.persistence;

import com.nimbus.persistence.dialect.NimbusDialect;
import com.nimbus.persistence.exception.NimbusPersistenceException;
import com.nimbus.persistence.mapping.EntityMetadata;

import javax.naming.InitialContext;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

public class SessionFactory implements AutoCloseable {

    protected final Map<String, String> properties;
    protected final Map<Class<?>, EntityMetadata> metadataMap;
    protected final Map<String, Class<?>> entityRegistry;
    protected final boolean showSql;
    protected final NimbusDialect dialect;
    /** Non-null when {@code hibernate.connection.datasource} is configured (JNDI / WAS). */
    private final DataSource dataSource;

    public SessionFactory(Map<String, String> properties,
                          Map<Class<?>, EntityMetadata> metadataMap,
                          Map<String, Class<?>> entityRegistry) {
        this.properties = properties;
        this.metadataMap = metadataMap;
        this.entityRegistry = entityRegistry;
        this.showSql = Boolean.parseBoolean(
                properties.getOrDefault("hibernate.show_sql",
                        properties.getOrDefault("show_sql", "false")));
        this.dialect = NimbusDialect.fromProperty(
                properties.getOrDefault("hibernate.dialect",
                        properties.getOrDefault("dialect", null)));
        this.dataSource = resolveDataSource(properties);

        // Execute SchemaExport according to hbm2ddl.auto
        String ddl = properties.getOrDefault("hibernate.hbm2ddl.auto", "none");
        if (!"none".equals(ddl)) {
            Connection conn = null;
            try {
                conn = openConnection();
                new SchemaExport(metadataMap, showSql).execute(conn, ddl);
                conn.commit();
            } catch (SQLException e) {
                throw new NimbusPersistenceException("SchemaExport failed", e);
            } finally {
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (SQLException e) {
                        // ignore
                    }
                }
            }
        }
    }

    public Session openSession() {
        return newSession(openConnection());
    }

    /** Factory method — override in subclasses to return a different Session subtype. */
    protected Session newSession(Connection conn) {
        return new Session(conn, metadataMap, entityRegistry, showSql, dialect);
    }

    /**
     * Opens a JDBC connection.
     * Uses the JNDI DataSource when {@code hibernate.connection.datasource} is configured
     * (WAS / JEE containers); otherwise falls back to DriverManager.
     */
    protected Connection openConnection() {
        try {
            Connection conn;
            if (dataSource != null) {
                conn = dataSource.getConnection();
            } else {
                String driverClass = properties.get("hibernate.connection.driver_class");
                if (driverClass != null && !driverClass.isEmpty()) {
                    Class.forName(driverClass);
                }
                String url      = properties.get("hibernate.connection.url");
                String username = properties.get("hibernate.connection.username");
                String password = properties.get("hibernate.connection.password");
                conn = (username != null)
                        ? DriverManager.getConnection(url, username, password)
                        : DriverManager.getConnection(url);
            }
            // Manual commit mode mirrors Hibernate default behavior
            conn.setAutoCommit(false);
            return conn;
        } catch (Exception e) {
            throw new NimbusPersistenceException("Failed to open JDBC connection", e);
        }
    }

    /**
     * Looks up the JNDI DataSource at startup so every {@link #openSession()} call
     * only invokes {@link DataSource#getConnection()} — no repeated JNDI lookups.
     * Returns {@code null} when {@code hibernate.connection.datasource} is not set.
     */
    private static DataSource resolveDataSource(Map<String, String> props) {
        String jndiName = props.get("hibernate.connection.datasource");
        if (jndiName == null || jndiName.trim().isEmpty()) {
            return null;
        }
        try {
            InitialContext ctx = new InitialContext();
            Object obj = ctx.lookup(jndiName.trim());
            if (!(obj instanceof DataSource)) {
                throw new NimbusPersistenceException(
                        "JNDI name '" + jndiName + "' did not resolve to a javax.sql.DataSource " +
                        "(got: " + (obj == null ? "null" : obj.getClass().getName()) + ")");
            }
            return (DataSource) obj;
        } catch (NimbusPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new NimbusPersistenceException(
                    "JNDI lookup failed for datasource: " + jndiName, e);
        }
    }

    @Override
    public void close() {
        // noop in v1 — connections are managed per Session
    }

    public Map<Class<?>, EntityMetadata> getMetadataMap() {
        return metadataMap;
    }

    public Map<String, Class<?>> getEntityRegistry() {
        return entityRegistry;
    }

    public Map<String, String> getProperties() {
        return properties;
    }
}
