package com.nimbus.persistence;

import com.nimbus.persistence.annotation.Entity;
import com.nimbus.persistence.exception.NimbusPersistenceException;
import com.nimbus.persistence.hql.HbmXmlParser;
import com.nimbus.persistence.mapping.EntityMetadata;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Configuration {

    private final Map<String, String> properties = new LinkedHashMap<String, String>();
    private final List<Class<?>> annotatedClasses = new ArrayList<Class<?>>();
    private final Map<String, Class<?>> entityRegistry = new LinkedHashMap<String, Class<?>>();
    /** EntityMetadata loaded from .hbm.xml files (no annotation processing needed). */
    private final List<EntityMetadata> hbmMappings = new ArrayList<EntityMetadata>();

    /**
     * Loads hibernate.cfg.xml from the classpath (same as Hibernate).
     */
    public Configuration configure() {
        return configure("hibernate.cfg.xml");
    }

    /**
     * Loads a configuration XML from the classpath.
     * Supports the standard Hibernate format:
     * &lt;property name="hibernate.connection.url"&gt;jdbc:...&lt;/property&gt;
     * &lt;mapping class="com.example.MyEntity"/&gt;
     */
    public Configuration configure(String resourcePath) {
        InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resourcePath);
        if (is == null) {
            is = Configuration.class.getClassLoader().getResourceAsStream(resourcePath);
        }
        if (is == null) {
            is = Configuration.class.getResourceAsStream("/" + resourcePath);
        }
        if (is == null) {
            throw new NimbusPersistenceException(
                    "Configuration file not found on classpath: " + resourcePath);
        }
        return parseConfigXml(is, resourcePath);
    }

    /** Loads the configuration from a {@link File} (enterprise / WAS pattern). */
    public Configuration configure(File file) {
        try {
            return parseConfigXml(new FileInputStream(file), file.getAbsolutePath());
        } catch (java.io.FileNotFoundException e) {
            throw new NimbusPersistenceException(
                    "Configuration file not found: " + file.getAbsolutePath(), e);
        }
    }

    /** Loads the configuration from a {@link URL} — typical {@code getResource()} usage. */
    public Configuration configure(URL url) {
        try {
            return parseConfigXml(url.openStream(), url.toString());
        } catch (java.io.IOException e) {
            throw new NimbusPersistenceException(
                    "Failed to open configuration URL: " + url, e);
        }
    }

    private Configuration parseConfigXml(InputStream is, String sourceName) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setValidating(false);
            factory.setNamespaceAware(false);
            try {
                factory.setFeature(
                        "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                factory.setFeature(
                        "http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature(
                        "http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignore) {}

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver(new org.xml.sax.EntityResolver() {
                public org.xml.sax.InputSource resolveEntity(String publicId, String systemId) {
                    return new org.xml.sax.InputSource(new java.io.StringReader(""));
                }
            });

            Document doc = builder.parse(is);
            doc.getDocumentElement().normalize();

            NodeList propertyNodes = doc.getElementsByTagName("property");
            for (int i = 0; i < propertyNodes.getLength(); i++) {
                Element elem = (Element) propertyNodes.item(i);
                String name  = elem.getAttribute("name");
                String value = elem.getTextContent().trim();
                if (!name.isEmpty()) {
                    properties.put(name, value);
                }
            }

            NodeList mappingNodes = doc.getElementsByTagName("mapping");
            for (int i = 0; i < mappingNodes.getLength(); i++) {
                Element elem = (Element) mappingNodes.item(i);
                String className = elem.getAttribute("class");
                String resource  = elem.getAttribute("resource");
                if (!className.isEmpty()) {
                    try {
                        addAnnotatedClass(Class.forName(className));
                    } catch (ClassNotFoundException e) {
                        throw new NimbusPersistenceException(
                                "Cannot load mapped class: " + className, e);
                    }
                } else if (!resource.isEmpty()) {
                    loadHbmResource(resource);
                }
            }
        } catch (NimbusPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new NimbusPersistenceException(
                    "Failed to parse configuration: " + sourceName, e);
        } finally {
            try { is.close(); } catch (Exception ignore) {}
        }
        return this;
    }

    /** Loads and registers all entity mappings from an HBM XML resource on the classpath. */
    private void loadHbmResource(String resource) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream is = cl.getResourceAsStream(resource);
        if (is == null) is = Configuration.class.getClassLoader().getResourceAsStream(resource);
        if (is == null) is = Configuration.class.getResourceAsStream("/" + resource);
        if (is == null) {
            throw new NimbusPersistenceException(
                    "HBM resource not found on classpath: " + resource);
        }
        List<EntityMetadata> parsed = HbmXmlParser.parse(is, cl);
        for (EntityMetadata meta : parsed) {
            hbmMappings.add(meta);
            String simpleName = meta.getEntityClass().getSimpleName();
            entityRegistry.put(simpleName, meta.getEntityClass());
            // Also register by fully-qualified name for HQL
            entityRegistry.put(meta.getEntityClass().getName(), meta.getEntityClass());
        }
    }

    /**
     * Registers an annotated entity class.
     */
    public Configuration addAnnotatedClass(Class<?> clazz) {
        if (!annotatedClasses.contains(clazz)) {
            annotatedClasses.add(clazz);
        }
        // Register in entityRegistry by @Entity(name=...) or simple class name
        String entityName = resolveEntityName(clazz);
        entityRegistry.put(entityName, clazz);
        // Also register by simple class name if different
        if (!entityName.equals(clazz.getSimpleName())) {
            entityRegistry.put(clazz.getSimpleName(), clazz);
        }
        return this;
    }

    private String resolveEntityName(Class<?> clazz) {
        if (clazz.isAnnotationPresent(javax.persistence.Entity.class)) {
            String name = clazz.getAnnotation(javax.persistence.Entity.class).name();
            if (!name.isEmpty()) return name;
        }
        if (clazz.isAnnotationPresent(Entity.class)) {
            String name = clazz.getAnnotation(Entity.class).name();
            if (!name.isEmpty()) return name;
        }
        return clazz.getSimpleName();
    }

    /**
     * Sets a Hibernate property.
     */
    public Configuration setProperty(String key, Object value) {
        properties.put(key, String.valueOf(value));
        return this;
    }

    /**
     * Sets a Hibernate property (string value).
     */
    public Configuration setProperty(String key, String value) {
        properties.put(key, value);
        return this;
    }

    /**
     * Builds and returns the SessionFactory.
     * Accepts either {@code hibernate.connection.url} (DriverManager) or
     * {@code hibernate.connection.datasource} (JNDI DataSource — WAS/JEE containers).
     */
    public SessionFactory buildSessionFactory() {
        String url        = properties.get("hibernate.connection.url");
        String datasource = properties.get("hibernate.connection.datasource");
        if ((url == null || url.isEmpty()) && (datasource == null || datasource.isEmpty())) {
            throw new NimbusPersistenceException(
                    "Missing required property: either hibernate.connection.url " +
                    "or hibernate.connection.datasource must be set");
        }

        // Build EntityMetadata map — annotated classes first, then HBM mappings
        Map<Class<?>, EntityMetadata> metadataMap = new LinkedHashMap<Class<?>, EntityMetadata>();
        for (Class<?> clazz : annotatedClasses) {
            try {
                metadataMap.put(clazz, EntityMetadata.of(clazz));
            } catch (Exception e) {
                throw new NimbusPersistenceException(
                        "Failed to build metadata for: " + clazz.getName(), e);
            }
        }
        for (EntityMetadata hbmMeta : hbmMappings) {
            metadataMap.put(hbmMeta.getEntityClass(), hbmMeta);
        }

        // Apply hibernate.default_schema — prefix every table name with "schema."
        String schema = properties.get("hibernate.default_schema");
        if (schema != null && !schema.trim().isEmpty()) {
            Map<Class<?>, EntityMetadata> schemaMap =
                    new LinkedHashMap<Class<?>, EntityMetadata>();
            for (Map.Entry<Class<?>, EntityMetadata> e : metadataMap.entrySet()) {
                schemaMap.put(e.getKey(), e.getValue().withSchemaPrefix(schema.trim()));
            }
            metadataMap = schemaMap;
        }

        return newSessionFactory(properties, metadataMap, entityRegistry);
    }

    /** Factory method — override in subclasses to return a different SessionFactory subtype. */
    protected SessionFactory newSessionFactory(Map<String, String> props,
                                               Map<Class<?>, EntityMetadata> metadataMap,
                                               Map<String, Class<?>> entityRegistry) {
        return new SessionFactory(props, metadataMap, entityRegistry);
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public Map<String, String> getProperties() {
        return properties;
    }

    public List<Class<?>> getAnnotatedClasses() {
        return annotatedClasses;
    }

    public Map<String, Class<?>> getEntityRegistry() {
        return entityRegistry;
    }
}
