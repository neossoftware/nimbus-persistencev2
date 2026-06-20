package org.hibernate.cfg;

import com.nimbus.persistence.mapping.EntityMetadata;

import java.io.File;
import java.net.URL;
import java.util.Map;

/**
 * Hibernate 5 compatibility alias for com.nimbus.persistence.Configuration.
 * All fluent methods are overridden to return org.hibernate.cfg.Configuration
 * so that the enterprise chaining pattern works without changes:
 * <pre>
 *   new Configuration()
 *       .configure(url)
 *       .addAnnotatedClass(MyEntity.class)
 *       .setProperty("hibernate.default_schema", "MYSCHEMA")
 *       .buildSessionFactory();
 * </pre>
 */
public class Configuration extends com.nimbus.persistence.Configuration {

    // ── Fluent overrides — return org.hibernate.cfg.Configuration ────────────

    @Override
    public Configuration configure() {
        super.configure();
        return this;
    }

    @Override
    public Configuration configure(String resourcePath) {
        super.configure(resourcePath);
        return this;
    }

    @Override
    public Configuration configure(File file) {
        super.configure(file);
        return this;
    }

    @Override
    public Configuration configure(URL url) {
        super.configure(url);
        return this;
    }

    @Override
    public Configuration addAnnotatedClass(Class<?> clazz) {
        super.addAnnotatedClass(clazz);
        return this;
    }

    @Override
    public Configuration setProperty(String key, String value) {
        super.setProperty(key, value);
        return this;
    }

    @Override
    public Configuration setProperty(String key, Object value) {
        super.setProperty(key, value);
        return this;
    }

    // ── Factory — produce org.hibernate.SessionFactory ───────────────────────

    @Override
    protected com.nimbus.persistence.SessionFactory newSessionFactory(
            Map<String, String> props,
            Map<Class<?>, EntityMetadata> metadataMap,
            Map<String, Class<?>> entityRegistry) {
        return new org.hibernate.SessionFactory(props, metadataMap, entityRegistry);
    }

    @Override
    public org.hibernate.SessionFactory buildSessionFactory() {
        return (org.hibernate.SessionFactory) super.buildSessionFactory();
    }
}
