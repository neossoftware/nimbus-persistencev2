package com.nimbus.persistence.mapping;

import com.nimbus.persistence.annotation.Column;
import com.nimbus.persistence.annotation.GeneratedValue;
import com.nimbus.persistence.annotation.GenerationType;
import com.nimbus.persistence.annotation.Id;
import com.nimbus.persistence.exception.NimbusPersistenceException;
import com.nimbus.persistence.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Date;

public class ColumnMetadata {

    private final Field field;
    private final String columnName;
    private final boolean nullable;
    private final int length;
    private final boolean pk;
    private final GenerationType generationType;
    private final boolean unique;
    private final int precision;
    private final int scale;
    private final String columnDefinition;

    // JPA extended semantics
    private final javax.persistence.EnumType enumeratedType;  // null = not an enum column
    private final javax.persistence.TemporalType temporalType; // null = use field Java type
    private final boolean lob;

    private ColumnMetadata(Field field, String columnName, boolean nullable, int length,
                           boolean pk, GenerationType generationType, boolean unique,
                           int precision, int scale, String columnDefinition,
                           javax.persistence.EnumType enumeratedType,
                           javax.persistence.TemporalType temporalType,
                           boolean lob) {
        this.field = field;
        this.columnName = columnName;
        this.nullable = nullable;
        this.length = length;
        this.pk = pk;
        this.generationType = generationType;
        this.unique = unique;
        this.precision = precision;
        this.scale = scale;
        this.columnDefinition = columnDefinition;
        this.enumeratedType = enumeratedType;
        this.temporalType = temporalType;
        this.lob = lob;
    }

    /**
     * Creates a ColumnMetadata from HBM XML descriptor.
     * No annotations required — column name and generation strategy come from XML.
     * Pass {@code null} for generationType when the PK is user-assigned (composite-id).
     */
    public static ColumnMetadata ofHbm(Field field, String columnName,
                                        boolean isPk, GenerationType generationType) {
        return new ColumnMetadata(field, columnName, true, 255, isPk, generationType,
                false, 0, 0, "", null, null, false);
    }

    public static ColumnMetadata of(Field field) {
        boolean isPk = field.isAnnotationPresent(Id.class)
                    || field.isAnnotationPresent(javax.persistence.Id.class);

        String colName;
        boolean nullable = true;
        int length = 255;
        boolean unique = false;
        String columnDefinition = "";
        int precision = 0;
        int scale = 0;

        if (field.isAnnotationPresent(Column.class)) {
            Column col = field.getAnnotation(Column.class);
            colName = col.name().isEmpty() ? ReflectionUtils.camelToSnake(field.getName()) : col.name();
            nullable = col.nullable();
            length = col.length();
            unique = col.unique();
            columnDefinition = col.columnDefinition();
            precision = col.precision();
            scale = col.scale();
        } else if (field.isAnnotationPresent(javax.persistence.Column.class)) {
            javax.persistence.Column col = field.getAnnotation(javax.persistence.Column.class);
            colName = col.name().isEmpty() ? ReflectionUtils.camelToSnake(field.getName()) : col.name();
            nullable = col.nullable();
            length = col.length();
            unique = col.unique();
            columnDefinition = col.columnDefinition();
            precision = col.precision();
            scale = col.scale();
        } else {
            colName = ReflectionUtils.camelToSnake(field.getName());
        }

        GenerationType genType = null;
        if (isPk && field.isAnnotationPresent(GeneratedValue.class)) {
            genType = field.getAnnotation(GeneratedValue.class).strategy();
        } else if (isPk && field.isAnnotationPresent(javax.persistence.GeneratedValue.class)) {
            genType = mapJpaGenerationType(
                    field.getAnnotation(javax.persistence.GeneratedValue.class).strategy());
        }

        // @Enumerated — STRING is the safe default if the annotation is present but value omitted
        javax.persistence.EnumType enumeratedType = null;
        if (field.isAnnotationPresent(javax.persistence.Enumerated.class)) {
            enumeratedType = field.getAnnotation(javax.persistence.Enumerated.class).value();
        } else if (field.getType().isEnum()) {
            // Un-annotated enum field: default to STRING (matches Hibernate default)
            enumeratedType = javax.persistence.EnumType.STRING;
        }

        // @Temporal
        javax.persistence.TemporalType temporalType = null;
        if (field.isAnnotationPresent(javax.persistence.Temporal.class)) {
            temporalType = field.getAnnotation(javax.persistence.Temporal.class).value();
        }

        // @Lob
        boolean isLob = field.isAnnotationPresent(javax.persistence.Lob.class);

        return new ColumnMetadata(field, colName, nullable, length, isPk, genType, unique,
                precision, scale, columnDefinition, enumeratedType, temporalType, isLob);
    }

    private static GenerationType mapJpaGenerationType(javax.persistence.GenerationType jpa) {
        switch (jpa) {
            case IDENTITY: return GenerationType.IDENTITY;
            case SEQUENCE: return GenerationType.SEQUENCE;
            case TABLE:    return GenerationType.TABLE;
            default:       return GenerationType.AUTO;
        }
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Field getField() { return field; }
    public String getColumnName() { return columnName; }
    public boolean isNullable() { return nullable; }
    public int getLength() { return length; }
    public boolean isPk() { return pk; }
    public GenerationType getGenerationType() { return generationType; }
    public boolean isUnique() { return unique; }
    public int getPrecision() { return precision; }
    public int getScale() { return scale; }
    public String getColumnDefinition() { return columnDefinition; }
    public javax.persistence.EnumType getEnumeratedType() { return enumeratedType; }
    public javax.persistence.TemporalType getTemporalType() { return temporalType; }
    public boolean isLob() { return lob; }

    public boolean isGeneratedIdentity() {
        return generationType == GenerationType.IDENTITY
                || generationType == GenerationType.AUTO
                || generationType == GenerationType.SEQUENCE;
    }

    // ── Read / Write ──────────────────────────────────────────────────────────

    public Object getValue(Object entity) {
        return ReflectionUtils.getFieldValue(field, entity);
    }

    public void setValue(Object entity, Object value) {
        if (value == null) {
            ReflectionUtils.setFieldValue(field, entity, null);
            return;
        }
        Object converted = field.getType().isEnum()
                ? convertEnum(value, field.getType())
                : ReflectionUtils.convertToJavaType(value, field.getType());
        ReflectionUtils.setFieldValue(field, entity, converted);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object convertEnum(Object value, Class<?> enumType) {
        Class<? extends Enum> eClass = (Class<? extends Enum>) enumType;
        // Integer/Number → stored as ORDINAL
        if (value instanceof Number) {
            int ordinal = ((Number) value).intValue();
            Enum<?>[] constants = (Enum<?>[]) eClass.getEnumConstants();
            if (ordinal >= 0 && ordinal < constants.length) {
                return constants[ordinal];
            }
            throw new NimbusPersistenceException(
                    "Invalid enum ordinal " + ordinal + " for " + enumType.getName());
        }
        // String → stored as STRING name
        return Enum.valueOf(eClass, value.toString());
    }

    public void bindToStatement(PreparedStatement ps, int idx, Object entity) throws SQLException {
        field.setAccessible(true);
        Object value;
        try {
            value = field.get(entity);
        } catch (IllegalAccessException e) {
            throw new NimbusPersistenceException("Cannot read field: " + field.getName(), e);
        }
        bindValue(ps, idx, value);
    }

    public void bindValue(PreparedStatement ps, int idx, Object value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.NULL);
            return;
        }

        // @Enumerated handling — before the generic type switch
        if (value instanceof Enum) {
            if (enumeratedType == javax.persistence.EnumType.ORDINAL) {
                ps.setInt(idx, ((Enum<?>) value).ordinal());
            } else {
                // STRING (default)
                ps.setString(idx, ((Enum<?>) value).name());
            }
            return;
        }

        Class<?> type = field.getType();
        if (type == String.class) {
            ps.setString(idx, (String) value);
        } else if (type == int.class || type == Integer.class) {
            if (value instanceof Number) {
                ps.setInt(idx, ((Number) value).intValue());
            } else {
                ps.setInt(idx, Integer.parseInt(value.toString()));
            }
        } else if (type == long.class || type == Long.class) {
            if (value instanceof Number) {
                ps.setLong(idx, ((Number) value).longValue());
            } else {
                ps.setLong(idx, Long.parseLong(value.toString()));
            }
        } else if (type == double.class || type == Double.class) {
            if (value instanceof Number) {
                ps.setDouble(idx, ((Number) value).doubleValue());
            } else {
                ps.setDouble(idx, Double.parseDouble(value.toString()));
            }
        } else if (type == float.class || type == Float.class) {
            if (value instanceof Number) {
                ps.setFloat(idx, ((Number) value).floatValue());
            } else {
                ps.setFloat(idx, Float.parseFloat(value.toString()));
            }
        } else if (type == boolean.class || type == Boolean.class) {
            if (value instanceof Boolean) {
                ps.setBoolean(idx, (Boolean) value);
            } else {
                ps.setBoolean(idx, Boolean.parseBoolean(value.toString()));
            }
        } else if (type == java.sql.Date.class) {
            if (value instanceof java.sql.Date) {
                ps.setDate(idx, (java.sql.Date) value);
            } else if (value instanceof Date) {
                ps.setDate(idx, new java.sql.Date(((Date) value).getTime()));
            } else {
                ps.setObject(idx, value);
            }
        } else if (type == Timestamp.class) {
            if (value instanceof Timestamp) {
                ps.setTimestamp(idx, (Timestamp) value);
            } else if (value instanceof Date) {
                ps.setTimestamp(idx, new Timestamp(((Date) value).getTime()));
            } else {
                ps.setObject(idx, value);
            }
        } else if (type == Date.class) {
            // @Temporal controls precision; JDBC still accepts Timestamp for all three cases
            if (temporalType == javax.persistence.TemporalType.DATE) {
                Date d = (Date) value;
                ps.setDate(idx, new java.sql.Date(d.getTime()));
            } else if (temporalType == javax.persistence.TemporalType.TIME) {
                Date d = (Date) value;
                ps.setTime(idx, new java.sql.Time(d.getTime()));
            } else {
                // TIMESTAMP or no @Temporal
                if (value instanceof Timestamp) {
                    ps.setTimestamp(idx, (Timestamp) value);
                } else if (value instanceof java.sql.Date) {
                    ps.setTimestamp(idx, new Timestamp(((java.sql.Date) value).getTime()));
                } else {
                    ps.setTimestamp(idx, new Timestamp(((Date) value).getTime()));
                }
            }
        } else if (type == BigDecimal.class) {
            if (value instanceof BigDecimal) {
                ps.setBigDecimal(idx, (BigDecimal) value);
            } else {
                ps.setBigDecimal(idx, new BigDecimal(value.toString()));
            }
        } else {
            ps.setObject(idx, value);
        }
    }
}
