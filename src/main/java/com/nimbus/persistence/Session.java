package com.nimbus.persistence;

import com.nimbus.persistence.annotation.FetchType;
import com.nimbus.persistence.dialect.NimbusDialect;
import com.nimbus.persistence.exception.NimbusPersistenceException;
import com.nimbus.persistence.hql.HqlParser;
import com.nimbus.persistence.mapping.ColumnMetadata;
import com.nimbus.persistence.mapping.EntityMetadata;
import com.nimbus.persistence.mapping.RelationMetadata;
import com.nimbus.persistence.util.ReflectionUtils;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Session implements AutoCloseable {

    protected final Connection connection;
    protected final Map<Class<?>, EntityMetadata> metadataMap;
    protected final Map<String, Class<?>> entityRegistry;
    protected final boolean showSql;
    protected final NimbusDialect dialect;
    protected Transaction currentTx;
    protected boolean closed = false;

    public Session(Connection connection,
                   Map<Class<?>, EntityMetadata> metadataMap,
                   Map<String, Class<?>> entityRegistry,
                   boolean showSql) {
        this(connection, metadataMap, entityRegistry, showSql, NimbusDialect.H2);
    }

    public Session(Connection connection,
                   Map<Class<?>, EntityMetadata> metadataMap,
                   Map<String, Class<?>> entityRegistry,
                   boolean showSql, NimbusDialect dialect) {
        this.connection = connection;
        this.metadataMap = metadataMap;
        this.entityRegistry = entityRegistry;
        this.showSql = showSql;
        this.dialect = dialect;
    }

    // ── Transaction ──────────────────────────────────────────────────────────

    public Transaction beginTransaction() {
        currentTx = newTransaction();
        currentTx.begin();
        return currentTx;
    }

    /** Factory method — override to return a Transaction subtype. */
    protected Transaction newTransaction() {
        return new Transaction(connection);
    }

    public Transaction getTransaction() {
        return currentTx;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    /**
     * INSERT — returns the generated ID.
     */
    @SuppressWarnings("unchecked")
    public <T> Serializable save(T entity) {
        EntityMetadata meta = requireMetadata(entity.getClass());
        List<ColumnMetadata> insertCols = meta.getInsertColumns();
        List<RelationMetadata> manyToOneRels = getManyToOneRelations(meta);

        String sql = buildInsertSql(meta.getTableName(), insertCols, manyToOneRels);
        if (showSql) {
            log("SQL: " + sql);
        }

        PreparedStatement ps = null;
        try {
            boolean needsGeneratedKey = !meta.isCompositeId()
                    && meta.getPkColumn().isGeneratedIdentity();
            ps = connection.prepareStatement(sql,
                    needsGeneratedKey ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
            int idx = 1;
            for (ColumnMetadata col : insertCols) {
                col.bindToStatement(ps, idx++, entity);
            }
            // Bind FK values from @ManyToOne relations
            for (RelationMetadata rel : manyToOneRels) {
                Object related = getRelatedObject(rel, entity);
                if (related != null) {
                    EntityMetadata relMeta = metadataMap.get(related.getClass());
                    if (relMeta != null) {
                        Object pkVal = relMeta.getPkColumn().getValue(related);
                        ps.setObject(idx++, pkVal);
                    } else {
                        ps.setNull(idx++, Types.NULL);
                    }
                } else {
                    ps.setNull(idx++, Types.NULL);
                }
            }

            ps.executeUpdate();

            Serializable returnKey = null;
            if (needsGeneratedKey) {
                // Retrieve generated key and set it back on the entity
                ResultSet keys = ps.getGeneratedKeys();
                try {
                    if (keys.next()) {
                        Object genKey = keys.getObject(1);
                        Class<?> pkType = meta.getPkColumn().getField().getType();
                        meta.getPkColumn().setValue(entity, convertKey(genKey, pkType));
                        returnKey = (Serializable) meta.getPkColumn().getValue(entity);
                    }
                } finally {
                    keys.close();
                }
            }

            // Insert join table rows for @ManyToMany owner-side relations
            // (must happen AFTER PK is set on entity)
            for (RelationMetadata rel : meta.getRelations()) {
                if (rel.getType() == RelationMetadata.Type.MANY_TO_MANY
                        && rel.getJoinTable() != null) {
                    insertManyToManyRows(entity, meta, rel);
                }
            }

            return returnKey;
        } catch (Exception e) {
            throw new NimbusPersistenceException(
                    "save() failed for " + entity.getClass().getSimpleName(), e);
        } finally {
            closeQuietly(ps);
        }
    }

    /**
     * SELECT by PK. Returns null if not found.
     * For composite-PK entities, {@code id} should be either the @IdClass object
     * (with same field names as the @Id fields) or the entity itself.
     */
    public <T> T get(Class<T> type, Serializable id) {
        EntityMetadata meta = requireMetadata(type);

        if (meta.isCompositeId()) {
            return getByCompositeId(type, id, meta);
        }

        String sql = "SELECT * FROM " + meta.getTableName()
                + " WHERE " + meta.getPkColumn().getColumnName() + " = ?";
        if (showSql) {
            log("SQL: " + sql);
        }

        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = connection.prepareStatement(sql);
            ps.setObject(1, id);
            rs = ps.executeQuery();
            if (rs.next()) {
                return mapResultSet(rs, type, meta);
            }
        } catch (Exception e) {
            throw new NimbusPersistenceException("get() failed for " + type.getSimpleName(), e);
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
        return null;
    }

    /**
     * Handles composite-PK get().
     * The id object is either an @IdClass instance (same field names as @Id fields)
     * or the entity itself (for HBM-style composite-id where fields live on the entity).
     */
    private <T> T getByCompositeId(Class<T> type, Object id, EntityMetadata meta) {
        List<ColumnMetadata> pks = meta.getPkColumns();
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < pks.size(); i++) {
            if (i > 0) where.append(" AND ");
            where.append(pks.get(i).getColumnName()).append(" = ?");
        }
        String sql = "SELECT * FROM " + meta.getTableName() + " WHERE " + where;
        if (showSql) log("SQL: " + sql);

        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = connection.prepareStatement(sql);
            for (int i = 0; i < pks.size(); i++) {
                String fieldName = pks.get(i).getField().getName();
                Object val = extractFieldValue(id, fieldName);
                ps.setObject(i + 1, val);
            }
            rs = ps.executeQuery();
            if (rs.next()) {
                return mapResultSet(rs, type, meta);
            }
        } catch (Exception e) {
            throw new NimbusPersistenceException("get() failed for " + type.getSimpleName(), e);
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
        return null;
    }

    /** Reads a field value from an object by field name using reflection. */
    private static Object extractFieldValue(Object obj, String fieldName) {
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new NimbusPersistenceException("Cannot access field '" + fieldName + "'", e);
            }
        }
        throw new NimbusPersistenceException("Field '" + fieldName + "' not found in " + obj.getClass().getName());
    }

    /**
     * Like get() but throws if not found.
     */
    public <T> T load(Class<T> type, Serializable id) {
        T entity = get(type, id);
        if (entity == null) {
            throw new NimbusPersistenceException(
                    "Entity not found: " + type.getSimpleName() + "#" + id);
        }
        return entity;
    }

    /**
     * UPDATE.
     */
    public void update(Object entity) {
        EntityMetadata meta = requireMetadata(entity.getClass());
        List<ColumnMetadata> updateCols = meta.getUpdateColumns();
        List<RelationMetadata> manyToOneRels = getManyToOneRelations(meta);

        StringBuilder sb = new StringBuilder("UPDATE ");
        sb.append(meta.getTableName());
        sb.append(" SET ");

        List<String> sets = new ArrayList<String>();
        for (ColumnMetadata col : updateCols) {
            sets.add(col.getColumnName() + " = ?");
        }
        for (RelationMetadata rel : manyToOneRels) {
            sets.add(rel.getJoinColumnName() + " = ?");
        }
        sb.append(joinStrings(", ", sets));
        sb.append(" WHERE ");
        sb.append(meta.getPkColumn().getColumnName());
        sb.append(" = ?");

        String sql = sb.toString();
        if (showSql) {
            log("SQL: " + sql);
        }

        PreparedStatement ps = null;
        try {
            ps = connection.prepareStatement(sql);
            int idx = 1;
            for (ColumnMetadata col : updateCols) {
                col.bindToStatement(ps, idx++, entity);
            }
            for (RelationMetadata rel : manyToOneRels) {
                Object related = getRelatedObject(rel, entity);
                if (related != null) {
                    EntityMetadata relMeta = metadataMap.get(related.getClass());
                    if (relMeta != null) {
                        ps.setObject(idx++, relMeta.getPkColumn().getValue(related));
                    } else {
                        ps.setNull(idx++, Types.NULL);
                    }
                } else {
                    ps.setNull(idx++, Types.NULL);
                }
            }
            meta.getPkColumn().bindToStatement(ps, idx, entity);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new NimbusPersistenceException(
                    "update() failed for " + entity.getClass().getSimpleName(), e);
        } finally {
            closeQuietly(ps);
        }
    }

    /**
     * DELETE by entity (uses its PK).
     */
    public void delete(Object entity) {
        EntityMetadata meta = requireMetadata(entity.getClass());
        String sql = "DELETE FROM " + meta.getTableName()
                + " WHERE " + meta.getPkColumn().getColumnName() + " = ?";
        if (showSql) {
            log("SQL: " + sql);
        }

        PreparedStatement ps = null;
        try {
            ps = connection.prepareStatement(sql);
            meta.getPkColumn().bindToStatement(ps, 1, entity);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new NimbusPersistenceException(
                    "delete() failed for " + entity.getClass().getSimpleName(), e);
        } finally {
            closeQuietly(ps);
        }
    }

    /**
     * Save or update depending on whether PK is null/zero.
     */
    public void saveOrUpdate(Object entity) {
        EntityMetadata meta = requireMetadata(entity.getClass());
        Object pkVal = meta.getPkColumn().getValue(entity);
        if (pkVal == null || (pkVal instanceof Number && ((Number) pkVal).longValue() == 0)) {
            save(entity);
        } else {
            update(entity);
        }
    }

    public void persist(Object entity) {
        save(entity);
    }

    public void merge(Object entity) {
        saveOrUpdate(entity);
    }

    public void flush() {
        try {
            connection.commit();
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new NimbusPersistenceException("flush() failed", e);
        }
    }

    public void clear() {
        // No cache in v1 — noop
    }

    public void evict(Object entity) {
        // No cache in v1 — noop
    }

    public void refresh(Object entity) {
        EntityMetadata meta = requireMetadata(entity.getClass());
        Object pk = meta.getPkColumn().getValue(entity);
        if (pk == null) {
            return;
        }
        Object fresh = get(entity.getClass(), (Serializable) pk);
        if (fresh != null) {
            for (ColumnMetadata col : meta.getAllColumns()) {
                col.setValue(entity, col.getValue(fresh));
            }
        }
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public <T> Query<T> createQuery(String hql) {
        return createQuery(hql, null);
    }

    @SuppressWarnings("unchecked")
    public <T> Query<T> createQuery(String hql, Class<T> resultType) {
        HqlParser parser = new HqlParser(metadataMap, entityRegistry).parse(hql);
        Class<T> resolvedType = resultType != null ? resultType : (Class<T>) parser.getResultType();
        EntityMetadata meta = (resolvedType != null && metadataMap.containsKey(resolvedType))
                ? metadataMap.get(resolvedType) : null;
        // Proyección compleja (CASE WHEN, agregados) → resultType null, Query devuelve Object
        Class<T> effectiveType = parser.isProjection() ? null : resolvedType;
        EntityMetadata effectiveMeta = parser.isProjection() ? null : meta;
        return newQuery(parser.getSql(), parser.getParamPositions(),
                effectiveType, effectiveMeta, parser.isCount(), parser.isNativeCount());
    }

    /** Factory method — override to return a Query subtype. */
    @SuppressWarnings("unchecked")
    protected <T> Query<T> newQuery(String sql, Map<String, List<Integer>> paramPositions,
                                    Class<T> resultType, EntityMetadata meta,
                                    boolean isCount, boolean isNativeCount) {
        return new Query<T>(connection, sql, paramPositions, resultType, meta,
                metadataMap, showSql, dialect, isCount, isNativeCount);
    }

    public <T> Criteria<T> createCriteria(Class<T> entityClass) {
        EntityMetadata meta = requireMetadata(entityClass);
        return newCriteria(entityClass, meta);
    }

    /** Factory method — override to return a Criteria subtype. */
    @SuppressWarnings("unchecked")
    protected <T> Criteria<T> newCriteria(Class<T> entityClass, EntityMetadata meta) {
        return new Criteria<T>(connection, entityClass, meta, metadataMap, showSql, dialect);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Query createNativeQuery(String sql) {
        return newQuery(sql, new LinkedHashMap<String, List<Integer>>(),
                null, null, false, false);
    }

    /** Alias de createNativeQuery() — nombre original en Hibernate 5, deprecated en 5.2. */
    @SuppressWarnings("rawtypes")
    public Query createSQLQuery(String sql) {
        return createNativeQuery(sql);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    protected EntityMetadata requireMetadata(Class<?> clazz) {
        EntityMetadata meta = metadataMap.get(clazz);
        if (meta == null) {
            throw new NimbusPersistenceException(
                    "Class not registered: " + clazz.getName()
                    + ". Use Configuration.addAnnotatedClass().");
        }
        return meta;
    }

    /**
     * Maps a ResultSet row to a Java object (full load: ManyToOne + OneToMany EAGER).
     */
    protected <T> T mapResultSet(ResultSet rs, Class<T> type, EntityMetadata meta) throws Exception {
        return mapResultSet(rs, type, meta, false);
    }

    /**
     * Maps a ResultSet row to a Java object.
     * @param shallow when true, skips collection loading to prevent infinite recursion
     *                when loading elements of a @OneToMany collection.
     */
    @SuppressWarnings("unchecked")
    private <T> T mapResultSet(ResultSet rs, Class<T> type, EntityMetadata meta, boolean shallow) throws Exception {
        T obj = type.getDeclaredConstructor().newInstance();

        // PK (simple or composite)
        Object pkVal = null;
        if (meta.isCompositeId()) {
            for (ColumnMetadata pk : meta.getPkColumns()) {
                try {
                    Object v = rs.getObject(pk.getColumnName());
                    if (v != null) pk.setValue(obj, convertType(v, pk.getField().getType()));
                } catch (SQLException ignore) {}
            }
        } else {
            pkVal = rs.getObject(meta.getPkColumn().getColumnName());
            if (pkVal != null) {
                meta.getPkColumn().setValue(obj, convertType(pkVal, meta.getPkColumn().getField().getType()));
            }
        }

        // Regular columns
        for (ColumnMetadata col : meta.getColumns()) {
            try {
                Object val = rs.getObject(col.getColumnName());
                if (val != null) {
                    col.setValue(obj, convertType(val, col.getField().getType()));
                }
            } catch (SQLException ignore) {
                // Column not in this ResultSet — skip
            }
        }

        if (!shallow) {
            // @ManyToOne / @OneToOne owner side: load by FK if EAGER
            for (RelationMetadata rel : meta.getRelations()) {
                if ((rel.getType() == RelationMetadata.Type.MANY_TO_ONE
                        || (rel.getType() == RelationMetadata.Type.ONE_TO_ONE && rel.isOwnerSide()))
                        && rel.getFetchType() == FetchType.EAGER) {
                    try {
                        Object fkVal = rs.getObject(rel.getJoinColumnName());
                        if (fkVal != null) {
                            EntityMetadata relMeta = metadataMap.get(rel.getField().getType());
                            if (relMeta != null) {
                                Object related = get(rel.getField().getType(), (Serializable) fkVal);
                                rel.getField().setAccessible(true);
                                rel.getField().set(obj, related);
                            }
                        }
                    } catch (SQLException ignore) {
                        // FK column not in this ResultSet — skip
                    }
                }
            }

            // @OneToMany EAGER: secondary SELECT per collection
            for (RelationMetadata rel : meta.getRelations()) {
                if (rel.getType() == RelationMetadata.Type.ONE_TO_MANY
                        && rel.getFetchType() == FetchType.EAGER && pkVal != null) {
                    loadOneToManyEager(obj, pkVal, rel, meta);
                }
            }

            // @ManyToMany EAGER: SELECT via join table
            for (RelationMetadata rel : meta.getRelations()) {
                if (rel.getType() == RelationMetadata.Type.MANY_TO_MANY
                        && rel.getFetchType() == FetchType.EAGER && pkVal != null) {
                    loadManyToManyEager(obj, pkVal, rel);
                }
            }
        }

        return obj;
    }

    /**
     * Executes a secondary SELECT to load a @OneToMany EAGER collection.
     * Uses shallow mapping for elements to avoid circular loading.
     */
    @SuppressWarnings("unchecked")
    private void loadOneToManyEager(Object owner, Object ownerPk,
                                     RelationMetadata rel, EntityMetadata ownerMeta) {
        try {
            java.lang.reflect.ParameterizedType pt =
                    (java.lang.reflect.ParameterizedType) rel.getField().getGenericType();
            Class<?> elementClass = (Class<?>) pt.getActualTypeArguments()[0];
            EntityMetadata elementMeta = metadataMap.get(elementClass);
            if (elementMeta == null) return;

            // HBM path: FK column stored directly in joinColumnName
            // Annotation path: resolve FK via mappedBy field name in target entity
            String fkColumn = rel.getJoinColumnName();
            if (fkColumn == null) {
                String mappedBy = rel.getMappedBy();
                for (RelationMetadata elemRel : elementMeta.getRelations()) {
                    if (elemRel.getField().getName().equals(mappedBy)) {
                        fkColumn = elemRel.getJoinColumnName();
                        break;
                    }
                }
            }
            if (fkColumn == null) return;

            String sql = "SELECT * FROM " + elementMeta.getTableName()
                    + " WHERE " + fkColumn + " = ?";
            if (showSql) System.out.println("Hibernate: " + sql);

            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setObject(1, ownerPk);
            ResultSet rs2 = ps.executeQuery();

            List<Object> elements = new ArrayList<Object>();
            while (rs2.next()) {
                // shallow=true: skip back-reference to avoid infinite recursion
                elements.add(mapResultSet(rs2, elementClass, elementMeta, true));
            }
            rs2.close();
            ps.close();

            rel.getField().setAccessible(true);
            if (java.util.Set.class.isAssignableFrom(rel.getField().getType())) {
                rel.getField().set(owner, new java.util.LinkedHashSet<Object>(elements));
            } else {
                rel.getField().set(owner, elements);
            }
        } catch (Exception e) {
            throw new com.nimbus.persistence.exception.NimbusPersistenceException(
                    "Failed to load @OneToMany collection: " + rel.getField().getName(), e);
        }
    }

    /**
     * Loads a @ManyToMany EAGER collection via the join table.
     */
    @SuppressWarnings("unchecked")
    private void loadManyToManyEager(Object owner, Object ownerPk, RelationMetadata rel) {
        try {
            java.lang.reflect.ParameterizedType pt =
                    (java.lang.reflect.ParameterizedType) rel.getField().getGenericType();
            Class<?> elementClass = (Class<?>) pt.getActualTypeArguments()[0];
            EntityMetadata elementMeta = metadataMap.get(elementClass);
            if (elementMeta == null) return;

            String sql = "SELECT t.* FROM " + elementMeta.getTableName() + " t"
                    + " JOIN " + rel.getJoinTable()
                    + " j ON j." + rel.getInverseJoinColumn()
                    + " = t." + elementMeta.getPkColumn().getColumnName()
                    + " WHERE j." + rel.getKeyColumn() + " = ?";
            if (showSql) System.out.println("Hibernate: " + sql);

            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setObject(1, ownerPk);
            ResultSet rs2 = ps.executeQuery();

            List<Object> elements = new ArrayList<Object>();
            while (rs2.next()) {
                elements.add(mapResultSet(rs2, elementClass, elementMeta, true));
            }
            rs2.close();
            ps.close();

            rel.getField().setAccessible(true);
            if (java.util.Set.class.isAssignableFrom(rel.getField().getType())) {
                rel.getField().set(owner, new java.util.LinkedHashSet<Object>(elements));
            } else {
                rel.getField().set(owner, elements);
            }
        } catch (Exception e) {
            throw new com.nimbus.persistence.exception.NimbusPersistenceException(
                    "Failed to load @ManyToMany collection: " + rel.getField().getName(), e);
        }
    }

    /**
     * Inserts rows into the join table for a @ManyToMany owner-side relation.
     */
    @SuppressWarnings("unchecked")
    private void insertManyToManyRows(Object owner, EntityMetadata ownerMeta, RelationMetadata rel) {
        try {
            rel.getField().setAccessible(true);
            Object collection = rel.getField().get(owner);
            if (collection == null) return;

            Iterable<?> items = (Iterable<?>) collection;
            Object ownerPk = ownerMeta.getPkColumn().getValue(owner);

            String sql = "INSERT INTO " + rel.getJoinTable()
                    + " (" + rel.getKeyColumn() + ", " + rel.getInverseJoinColumn() + ")"
                    + " VALUES (?, ?)";
            if (showSql) System.out.println("[NimbusPersistence] SQL: " + sql);

            PreparedStatement ps = connection.prepareStatement(sql);
            for (Object elem : items) {
                EntityMetadata elemMeta = metadataMap.get(elem.getClass());
                if (elemMeta == null) continue;
                Object elemPk = elemMeta.getPkColumn().getValue(elem);
                ps.setObject(1, ownerPk);
                ps.setObject(2, elemPk);
                ps.addBatch();
            }
            ps.executeBatch();
            ps.close();
        } catch (Exception e) {
            throw new com.nimbus.persistence.exception.NimbusPersistenceException(
                    "Failed to insert @ManyToMany join rows: " + rel.getField().getName(), e);
        }
    }

    /**
     * Converts a ResultSet value to the target Java type.
     */
    protected Object convertType(Object val, Class<?> targetType) {
        return ReflectionUtils.convertToJavaType(val, targetType);
    }

    protected Object convertKey(Object key, Class<?> targetType) {
        return ReflectionUtils.convertToJavaType(key, targetType);
    }

    protected String buildInsertSql(String table, List<ColumnMetadata> cols,
                                    List<RelationMetadata> rels) {
        StringBuilder sb = new StringBuilder("INSERT INTO ");
        sb.append(table);
        sb.append(" (");

        List<String> colNames = new ArrayList<String>();
        for (ColumnMetadata col : cols) {
            colNames.add(col.getColumnName());
        }
        for (RelationMetadata rel : rels) {
            colNames.add(rel.getJoinColumnName());
        }

        sb.append(joinStrings(", ", colNames));
        sb.append(") VALUES (");

        List<String> placeholders = new ArrayList<String>();
        for (int i = 0; i < colNames.size(); i++) {
            placeholders.add("?");
        }
        sb.append(joinStrings(", ", placeholders));
        sb.append(")");

        return sb.toString();
    }

    private List<RelationMetadata> getManyToOneRelations(EntityMetadata meta) {
        List<RelationMetadata> result = new ArrayList<RelationMetadata>();
        for (RelationMetadata rel : meta.getRelations()) {
            if ((rel.getType() == RelationMetadata.Type.MANY_TO_ONE
                    || (rel.getType() == RelationMetadata.Type.ONE_TO_ONE && rel.isOwnerSide()))
                    && rel.isInsertable()) {
                result.add(rel);
            }
        }
        return result;
    }

    private Object getRelatedObject(RelationMetadata rel, Object entity) {
        try {
            rel.getField().setAccessible(true);
            return rel.getField().get(entity);
        } catch (IllegalAccessException e) {
            throw new NimbusPersistenceException(
                    "Cannot access relation field: " + rel.getField().getName(), e);
        }
    }

    private String joinStrings(String separator, List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    protected void log(String msg) {
        System.out.println("[NimbusPersistence] " + msg);
    }

    public boolean isOpen() {
        return !closed;
    }

    @Override
    public void close() {
        if (!closed) {
            try {
                if (currentTx != null && currentTx.isActive()) {
                    currentTx.rollback();
                }
                connection.close();
            } catch (SQLException e) {
                // ignore
            }
            closed = true;
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
