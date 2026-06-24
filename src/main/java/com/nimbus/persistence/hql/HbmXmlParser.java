package com.nimbus.persistence.hql;

import com.nimbus.persistence.annotation.FetchType;
import com.nimbus.persistence.annotation.GenerationType;
import com.nimbus.persistence.exception.NimbusPersistenceException;
import com.nimbus.persistence.mapping.ColumnMetadata;
import com.nimbus.persistence.mapping.EntityMetadata;
import com.nimbus.persistence.mapping.RelationMetadata;
import com.nimbus.persistence.util.ReflectionUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Hibernate {@code .hbm.xml} mapping files into {@link EntityMetadata}.
 * Supports: {@code <id>}, {@code <composite-id>}, {@code <property>},
 * {@code <many-to-one>}, {@code <set>/<one-to-many>}, {@code <set>/<many-to-many>}.
 */
public final class HbmXmlParser {

    private HbmXmlParser() {}

    public static List<EntityMetadata> parse(InputStream is, ClassLoader classLoader) {
        try {
            Document doc = buildDocument(is);

            // Read optional package="..." from <hibernate-mapping package="...">
            String defaultPackage = "";
            NodeList mappingNodes = doc.getElementsByTagName("hibernate-mapping");
            if (mappingNodes.getLength() > 0) {
                defaultPackage = ((Element) mappingNodes.item(0)).getAttribute("package");
                if (defaultPackage == null) defaultPackage = "";
            }

            List<EntityMetadata> result = new ArrayList<EntityMetadata>();
            NodeList classNodes = doc.getElementsByTagName("class");
            for (int i = 0; i < classNodes.getLength(); i++) {
                result.add(parseClass((Element) classNodes.item(i), classLoader, defaultPackage));
            }
            return result;
        } catch (NimbusPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new NimbusPersistenceException("Failed to parse HBM XML", e);
        }
    }

    // ── Class element ─────────────────────────────────────────────────────────

    private static EntityMetadata parseClass(Element classElem, ClassLoader cl,
                                              String defaultPackage) throws Exception {
        String className = classElem.getAttribute("name");
        // Prepend package when class name is unqualified (real-world HBM pattern)
        if (!className.contains(".") && !defaultPackage.isEmpty()) {
            className = defaultPackage + "." + className;
        }
        String tableName = classElem.getAttribute("table");
        if (tableName.isEmpty()) tableName = classElem.getAttribute("table");

        Class<?> entityClass;
        try {
            entityClass = cl.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new NimbusPersistenceException("HBM: cannot load class " + className, e);
        }

        if (tableName.isEmpty()) {
            tableName = ReflectionUtils.camelToSnake(entityClass.getSimpleName());
        }

        List<ColumnMetadata> pkColumns = new ArrayList<ColumnMetadata>();
        List<ColumnMetadata> columns   = new ArrayList<ColumnMetadata>();
        List<RelationMetadata> relations = new ArrayList<RelationMetadata>();

        // Iterate DIRECT children only (avoid picking up nested elements)
        NodeList children = classElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) node;
            String tag = child.getTagName();

            if ("id".equals(tag)) {
                pkColumns.add(parseSimpleId(child, entityClass));
            } else if ("composite-id".equals(tag)) {
                pkColumns.addAll(parseCompositeId(child, entityClass, cl, defaultPackage));
            } else if ("property".equals(tag)) {
                columns.add(parseProperty(child, entityClass));
            } else if ("many-to-one".equals(tag)) {
                relations.add(parseManyToOne(child, entityClass));
            } else if ("set".equals(tag) || "bag".equals(tag) || "list".equals(tag)) {
                RelationMetadata rel = parseCollection(child, entityClass);
                if (rel != null) relations.add(rel);
            }
        }

        return EntityMetadata.fromHbm(entityClass, tableName, pkColumns, columns, relations);
    }

    // ── <id> ─────────────────────────────────────────────────────────────────

    private static ColumnMetadata parseSimpleId(Element idElem, Class<?> entityClass) throws Exception {
        String fieldName  = idElem.getAttribute("name");
        String columnName = idElem.getAttribute("column");
        if (columnName.isEmpty()) columnName = fieldName;

        Field field = findField(entityClass, fieldName);

        GenerationType genType = null; // default = assigned (no generator element)
        NodeList generators = idElem.getElementsByTagName("generator");
        if (generators.getLength() > 0) {
            String genClass = ((Element) generators.item(0)).getAttribute("class");
            genType = mapGeneratorClass(genClass);
        }

        return ColumnMetadata.ofHbm(field, columnName, true, genType);
    }

    // ── <composite-id> ───────────────────────────────────────────────────────

    /**
     * Parses &lt;composite-id&gt;. Two styles are supported:
     * <ul>
     *   <li>Embedded key class: {@code <composite-id class="KeyFoo" name="id">} —
     *       key fields live in KeyFoo; {@code name} is the field in the entity that holds it.</li>
     *   <li>Inline (no class attr): key fields live directly on the entity class.</li>
     * </ul>
     */
    private static List<ColumnMetadata> parseCompositeId(Element compositeElem,
                                                           Class<?> entityClass,
                                                           ClassLoader cl,
                                                           String defaultPackage) throws Exception {
        List<ColumnMetadata> pks = new ArrayList<ColumnMetadata>();

        String keyClassName = compositeElem.getAttribute("class");
        String keyFieldName = compositeElem.getAttribute("name"); // field in entity holding the key

        Class<?> keyClass = entityClass;
        Field embeddedKeyField = null;

        if (keyClassName != null && !keyClassName.isEmpty()) {
            // Resolve unqualified name with the default package from <hibernate-mapping package="...">
            if (!keyClassName.contains(".") && !defaultPackage.isEmpty()) {
                keyClassName = defaultPackage + "." + keyClassName;
            }
            try {
                keyClass = cl.loadClass(keyClassName);
            } catch (ClassNotFoundException e) {
                throw new NimbusPersistenceException(
                        "HBM composite-id: cannot load key class " + keyClassName, e);
            }
            if (keyFieldName != null && !keyFieldName.isEmpty()) {
                embeddedKeyField = findField(entityClass, keyFieldName);
            }
        }

        NodeList keyProps = compositeElem.getElementsByTagName("key-property");
        for (int i = 0; i < keyProps.getLength(); i++) {
            Element kp = (Element) keyProps.item(i);
            String fieldName  = kp.getAttribute("name");
            String columnName = kp.getAttribute("column");
            if (columnName.isEmpty()) columnName = ReflectionUtils.camelToSnake(fieldName);
            Field leafField = findField(keyClass, fieldName);
            if (embeddedKeyField != null) {
                pks.add(ColumnMetadata.ofHbmComposite(embeddedKeyField, leafField, columnName));
            } else {
                // null generationType = ASSIGNED (user sets the value)
                pks.add(ColumnMetadata.ofHbm(leafField, columnName, true, null));
            }
        }
        return pks;
    }

    // ── <property> ───────────────────────────────────────────────────────────

    private static ColumnMetadata parseProperty(Element propElem, Class<?> entityClass) throws Exception {
        String fieldName  = propElem.getAttribute("name");
        String columnName = propElem.getAttribute("column");

        if (columnName.isEmpty()) {
            // Check nested <column name="..."/>
            NodeList colNodes = propElem.getElementsByTagName("column");
            if (colNodes.getLength() > 0) {
                columnName = ((Element) colNodes.item(0)).getAttribute("name");
            }
        }
        if (columnName.isEmpty()) {
            columnName = ReflectionUtils.camelToSnake(fieldName);
        }

        Field field = findField(entityClass, fieldName);
        return ColumnMetadata.ofHbm(field, columnName, false, null);
    }

    // ── <many-to-one> ────────────────────────────────────────────────────────

    private static RelationMetadata parseManyToOne(Element elem, Class<?> entityClass) throws Exception {
        String fieldName  = elem.getAttribute("name");
        String column     = elem.getAttribute("column");
        String fetchStr   = elem.getAttribute("fetch"); // "join" or "select"
        String insertAttr = elem.getAttribute("insert");
        String updateAttr = elem.getAttribute("update");

        Field field = findField(entityClass, fieldName);
        FetchType fetchType = "select".equals(fetchStr) ? FetchType.LAZY : FetchType.EAGER;

        if (column.isEmpty()) {
            column = ReflectionUtils.camelToSnake(fieldName) + "_id";
        }

        // insert="false" update="false" = shared PK/FK — do not include FK in INSERT/DDL
        boolean readOnly = "false".equals(insertAttr) && "false".equals(updateAttr);
        return readOnly
                ? RelationMetadata.manyToOneHbmReadOnly(field, column, fetchType)
                : RelationMetadata.manyToOneHbm(field, column, fetchType);
    }

    // ── <set>/<bag> ──────────────────────────────────────────────────────────

    private static RelationMetadata parseCollection(Element setElem,
                                                     Class<?> entityClass) throws Exception {
        String fieldName = setElem.getAttribute("name");
        String fetchStr  = setElem.getAttribute("fetch"); // "join" or "select"
        String lazyStr   = setElem.getAttribute("lazy");  // "true", "false", "extra"

        FetchType fetchType = FetchType.LAZY;
        if ("join".equals(fetchStr) || "false".equals(lazyStr)) {
            fetchType = FetchType.EAGER;
        }

        // <key column="emp_id"/>
        String keyColumn = null;
        NodeList keyNodes = setElem.getElementsByTagName("key");
        if (keyNodes.getLength() > 0) {
            keyColumn = ((Element) keyNodes.item(0)).getAttribute("column");
        }

        Field field = findField(entityClass, fieldName);

        // <one-to-many>
        NodeList otmNodes = setElem.getElementsByTagName("one-to-many");
        if (otmNodes.getLength() > 0) {
            return RelationMetadata.oneToManyHbm(field, keyColumn, fetchType);
        }

        // <many-to-many>
        NodeList mtmNodes = setElem.getElementsByTagName("many-to-many");
        if (mtmNodes.getLength() > 0) {
            Element mtm = (Element) mtmNodes.item(0);
            String inverseColumn = mtm.getAttribute("column");
            String joinTable     = setElem.getAttribute("table");
            return RelationMetadata.manyToManyHbm(field, joinTable, keyColumn, inverseColumn, fetchType);
        }

        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Field findField(Class<?> clazz, String name) {
        // Pass 1: exact match (fast path)
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        // Pass 2: case-insensitive — Hibernate 5 allows <property name="BusinessOccupation">
        // to match a Java field named businessOccupation
        current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (f.getName().equalsIgnoreCase(name)) {
                    f.setAccessible(true);
                    return f;
                }
            }
            current = current.getSuperclass();
        }
        // Pass 3: setter-based lookup — Hibernate 5 uses property access (getter/setter) by
        // default in HBM XML, so the field name may differ from the property name.
        // e.g. field=bitacoraFolioTrackL with setter setBitacoraFolioTrack() matches name="bitacoraFolioTrack".
        // Find setter setName() and return the unique field matching its parameter type.
        Field bySetterType = findFieldBySetterType(clazz, name);
        if (bySetterType != null) return bySetterType;

        throw new NimbusPersistenceException(
                "HBM: field not found: " + clazz.getName() + "." + name);
    }

    /**
     * Pass 3 of field resolution: find setter set+capitalize(name), then return the field
     * whose type matches the setter's parameter — only when exactly one field of that type
     * exists (avoids ambiguity for primitive/String fields that appear multiple times).
     */
    private static Field findFieldBySetterType(Class<?> clazz, String name) {
        if (name == null || name.isEmpty()) return null;
        String setterName = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Class<?> paramType = null;
        for (Method m : clazz.getMethods()) {
            if (m.getName().equalsIgnoreCase(setterName) && m.getParameterCount() == 1) {
                paramType = m.getParameterTypes()[0];
                break;
            }
        }
        if (paramType == null) return null;

        List<Field> candidates = new ArrayList<Field>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (f.getType().equals(paramType)) candidates.add(f);
            }
            current = current.getSuperclass();
        }
        if (candidates.size() == 1) {
            candidates.get(0).setAccessible(true);
            return candidates.get(0);
        }
        // Multiple fields of same type (e.g. two Strings): disambiguate by name proximity.
        // Pick the field whose name (case-insensitive) contains the property name or vice versa.
        // e.g. name="tagCode"  → tagCodeL   contains "tagcode" ✓
        //      name="label"    → labelStr   contains "label"   ✓
        String lowerName = name.toLowerCase();
        for (Field f : candidates) {
            String lowerField = f.getName().toLowerCase();
            if (lowerField.contains(lowerName) || lowerName.contains(lowerField)) {
                f.setAccessible(true);
                return f;
            }
        }
        return null; // ambiguous or not found — caller will throw
    }

    private static GenerationType mapGeneratorClass(String genClass) {
        if (genClass == null || genClass.isEmpty()) return GenerationType.AUTO;
        String g = genClass.toLowerCase().trim();
        if ("identity".equals(g) || "native".equals(g)) return GenerationType.IDENTITY;
        if ("sequence".equals(g))                        return GenerationType.SEQUENCE;
        if ("assigned".equals(g))                        return null; // user-assigned
        return GenerationType.IDENTITY;
    }

    private static Document buildDocument(InputStream is) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setNamespaceAware(false);
        try {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignore) {}

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) ->
                new org.xml.sax.InputSource(new java.io.StringReader("")));

        Document doc = builder.parse(is);
        doc.getDocumentElement().normalize();
        return doc;
    }
}
