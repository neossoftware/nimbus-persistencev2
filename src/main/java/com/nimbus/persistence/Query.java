package com.nimbus.persistence;

import com.nimbus.persistence.dialect.NimbusDialect;
import com.nimbus.persistence.exception.NimbusPersistenceException;
import com.nimbus.persistence.exception.NonUniqueResultException;
import com.nimbus.persistence.mapping.EntityMetadata;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class Query<T> {

    private final Connection connection;
    private final String sql;
    private final Map<String, List<Integer>> paramPositions;
    private final Class<T> resultType;
    private final EntityMetadata entityMeta;
    private final Map<Class<?>, EntityMetadata> metadataMap;
    private final boolean showSql;
    private final NimbusDialect dialect;
    private final boolean isCount;
    private final boolean isNativeCount;

    private final Map<String, Object> params = new LinkedHashMap<String, Object>();
    private Integer maxResults;
    private Integer firstResult;
    private org.hibernate.transform.ResultTransformer resultTransformer;

    /** Legacy constructor — defaults to H2 dialect. */
    public Query(Connection connection, String sql,
                 Map<String, List<Integer>> paramPositions,
                 Class<T> resultType, EntityMetadata entityMeta,
                 Map<Class<?>, EntityMetadata> metadataMap,
                 boolean showSql, boolean isCount, boolean isNativeCount) {
        this(connection, sql, paramPositions, resultType, entityMeta,
                metadataMap, showSql, NimbusDialect.H2, isCount, isNativeCount);
    }

    public Query(Connection connection, String sql,
                 Map<String, List<Integer>> paramPositions,
                 Class<T> resultType, EntityMetadata entityMeta,
                 Map<Class<?>, EntityMetadata> metadataMap,
                 boolean showSql, NimbusDialect dialect,
                 boolean isCount, boolean isNativeCount) {
        this.connection = connection;
        this.sql = sql;
        this.paramPositions = paramPositions;
        this.resultType = resultType;
        this.entityMeta = entityMeta;
        this.metadataMap = metadataMap;
        this.showSql = showSql;
        this.dialect = dialect;
        this.isCount = isCount;
        this.isNativeCount = isNativeCount;
    }

    // ── setParameter — API JPA estándar ──────────────────────────────────────

    public Query<T> setParameter(String name, Object value) {
        params.put(name, value);
        return this;
    }

    public Query<T> setParameter(int position, Object value) {
        params.put("__pos_" + position, value);
        return this;
    }

    // ── Typed setters — API Hibernate 5 (deprecated en 6, soportados aquí) ──
    // Permiten migrar código legado sin cambiar cada llamada una por una.

    public Query<T> setString(String name, String value) {
        return setParameter(name, value);
    }

    public Query<T> setString(int position, String value) {
        return setParameter(position, value);
    }

    public Query<T> setInteger(String name, Integer value) {
        return setParameter(name, value);
    }

    public Query<T> setInteger(int position, Integer value) {
        return setParameter(position, value);
    }

    public Query<T> setLong(String name, Long value) {
        return setParameter(name, value);
    }

    public Query<T> setLong(int position, Long value) {
        return setParameter(position, value);
    }

    public Query<T> setDouble(String name, Double value) {
        return setParameter(name, value);
    }

    public Query<T> setDouble(int position, Double value) {
        return setParameter(position, value);
    }

    public Query<T> setFloat(String name, Float value) {
        return setParameter(name, value);
    }

    public Query<T> setFloat(int position, Float value) {
        return setParameter(position, value);
    }

    public Query<T> setBoolean(String name, Boolean value) {
        return setParameter(name, value);
    }

    public Query<T> setBoolean(int position, Boolean value) {
        return setParameter(position, value);
    }

    public Query<T> setBigDecimal(String name, BigDecimal value) {
        return setParameter(name, value);
    }

    public Query<T> setBigDecimal(int position, BigDecimal value) {
        return setParameter(position, value);
    }

    public Query<T> setDate(String name, Date value) {
        return setParameter(name, value);
    }

    public Query<T> setDate(int position, Date value) {
        return setParameter(position, value);
    }

    public Query<T> setTimestamp(String name, Date value) {
        return setParameter(name, value);
    }

    public Query<T> setTimestamp(int position, Date value) {
        return setParameter(position, value);
    }

    public Query<T> setCalendar(String name, Calendar value) {
        return setParameter(name, value == null ? null : value.getTime());
    }

    public Query<T> setCalendar(int position, Calendar value) {
        return setParameter(position, value == null ? null : value.getTime());
    }

    public Query<T> setCharacter(String name, char value) {
        return setParameter(name, String.valueOf(value));
    }

    public Query<T> setByte(String name, byte value) {
        return setParameter(name, value);
    }

    public Query<T> setShort(String name, Short value) {
        return setParameter(name, value);
    }

    /**
     * Hibernate 5: setEntity() — vincula la PK de una entidad como parámetro.
     * Equivalente a setParameter(name, entity.id).
     */
    public Query<T> setEntity(String name, Object entity) {
        if (entity == null) {
            return setParameter(name, null);
        }
        // Busca el @Id en la entidad y usa su valor
        for (java.lang.reflect.Field f : getAllFields(entity.getClass())) {
            if (f.isAnnotationPresent(javax.persistence.Id.class)
                    || f.isAnnotationPresent(com.nimbus.persistence.annotation.Id.class)) {
                try {
                    f.setAccessible(true);
                    return setParameter(name, f.get(entity));
                } catch (IllegalAccessException e) {
                    throw new NimbusPersistenceException("Cannot read @Id from entity for setEntity()", e);
                }
            }
        }
        throw new NimbusPersistenceException("No @Id field found in entity: " + entity.getClass().getName());
    }

    private static List<java.lang.reflect.Field> getAllFields(Class<?> clazz) {
        List<java.lang.reflect.Field> fields = new ArrayList<java.lang.reflect.Field>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (java.lang.reflect.Field f : current.getDeclaredFields()) {
                fields.add(f);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    public Query<T> setMaxResults(int max) {
        this.maxResults = max;
        return this;
    }

    public Query<T> setFirstResult(int first) {
        this.firstResult = first;
        return this;
    }

    public Query<T> setResultTransformer(org.hibernate.transform.ResultTransformer transformer) {
        this.resultTransformer = transformer;
        return this;
    }

    public List<T> list() {
        return getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<T> getResultList() {
        String finalSql = buildFinalSql();
        if (showSql) {
            System.out.println("[NimbusPersistence] SQL: " + finalSql);
        }
        List<T> results = new ArrayList<T>();
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = connection.prepareStatement(finalSql);
            bindParams(ps);
            rs = ps.executeQuery();

            // ResultTransformer path — reads aliases from metadata, bypasses entity mapping
            if (resultTransformer != null) {
                java.sql.ResultSetMetaData rsMeta = rs.getMetaData();
                int colCount = rsMeta.getColumnCount();
                String[] aliases = new String[colCount];
                for (int i = 1; i <= colCount; i++) {
                    String label = rsMeta.getColumnLabel(i);   // AS alias takes precedence
                    aliases[i - 1] = (label != null && !label.isEmpty())
                            ? label : rsMeta.getColumnName(i);
                }
                while (rs.next()) {
                    Object[] tuple = new Object[colCount];
                    for (int i = 1; i <= colCount; i++) {
                        tuple[i - 1] = rs.getObject(i);
                    }
                    results.add((T) resultTransformer.transformTuple(tuple, aliases));
                }
                results = (List<T>) resultTransformer.transformList(results);
            } else {
                // tempSession shares this Query's connection — must NOT close it here
                @SuppressWarnings("resource")
                Session tempSession = new Session(connection, metadataMap,
                        new LinkedHashMap<String, Class<?>>(), showSql);
                while (rs.next()) {
                    if (isCount || isNativeCount) {
                        results.add((T) rs.getObject(1));
                    } else if (resultType != null && entityMeta != null) {
                        results.add(tempSession.mapResultSet(rs, resultType, entityMeta));
                    } else {
                        int colCount = rs.getMetaData().getColumnCount();
                        if (colCount == 1) {
                            results.add((T) rs.getObject(1));
                        } else {
                            Object[] row = new Object[colCount];
                            for (int col = 1; col <= colCount; col++) {
                                row[col - 1] = rs.getObject(col);
                            }
                            results.add((T) row);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new NimbusPersistenceException("Query.list() failed: " + finalSql, e);
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
        return results;
    }

    public T uniqueResult() {
        List<T> list = list();
        if (list.isEmpty()) {
            return null;
        }
        if (list.size() > 1) {
            throw new NonUniqueResultException("Query returned " + list.size() + " results");
        }
        return list.get(0);
    }

    public T getSingleResult() {
        T result = uniqueResult();
        if (result == null) {
            throw new NimbusPersistenceException("No result found (getSingleResult)");
        }
        return result;
    }

    public Optional<T> uniqueResultOptional() {
        return Optional.ofNullable(uniqueResult());
    }

    public int executeUpdate() {
        String finalSql = buildFinalSql();
        if (showSql) {
            System.out.println("[NimbusPersistence] SQL: " + finalSql);
        }
        PreparedStatement ps = null;
        try {
            ps = connection.prepareStatement(finalSql);
            bindParams(ps);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new NimbusPersistenceException("Query.executeUpdate() failed", e);
        } finally {
            closeQuietly(ps);
        }
    }

    private String buildFinalSql() {
        String s = sql;
        if (maxResults != null) {
            s = dialect.applyLimit(s, maxResults);
        }
        if (firstResult != null) {
            s = s + " OFFSET " + firstResult;
        }
        return s;
    }

    private void bindParams(PreparedStatement ps) throws SQLException {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Handle positional params (__pos_N)
            if (key.startsWith("__pos_")) {
                int pos = Integer.parseInt(key.substring(6));
                ps.setObject(pos, value);
                continue;
            }

            // Handle named params
            List<Integer> positions = paramPositions.get(key);
            if (positions == null) {
                continue;
            }
            for (int pos : positions) {
                if (value == null) {
                    ps.setNull(pos, java.sql.Types.NULL);
                } else {
                    ps.setObject(pos, value);
                }
            }
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
