package com.nimbus.persistence.mapping;

import com.nimbus.persistence.annotation.FetchType;
import com.nimbus.persistence.annotation.JoinColumn;
import com.nimbus.persistence.annotation.ManyToOne;
import com.nimbus.persistence.annotation.OneToMany;
import com.nimbus.persistence.exception.NimbusPersistenceException;
import com.nimbus.persistence.util.ReflectionUtils;

import java.lang.reflect.Field;

public class RelationMetadata {

    public enum Type {
        MANY_TO_ONE,
        ONE_TO_MANY,
        ONE_TO_ONE,
        MANY_TO_MANY
    }

    private final Field field;
    private final Type type;
    private final String joinColumnName; // null for inverse-side @OneToMany and inverse @OneToOne
    private final String mappedBy;       // empty string = owner side
    private final FetchType fetchType;
    private final boolean insertable;    // false when insert="false" update="false" (shared PK/FK)
    // MANY_TO_MANY join table info
    private final String joinTable;
    private final String keyColumn;         // this entity's FK in join table
    private final String inverseJoinColumn; // target entity's FK in join table

    private RelationMetadata(Field field, Type type, String joinColumnName,
                             String mappedBy, FetchType fetchType, boolean insertable,
                             String joinTable, String keyColumn, String inverseJoinColumn) {
        this.field = field;
        this.type = type;
        this.joinColumnName = joinColumnName;
        this.mappedBy = mappedBy != null ? mappedBy : "";
        this.fetchType = fetchType;
        this.insertable = insertable;
        this.joinTable = joinTable;
        this.keyColumn = keyColumn;
        this.inverseJoinColumn = inverseJoinColumn;
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    public static RelationMetadata manyToOne(Field field) {
        FetchType fetchType = FetchType.EAGER;
        ManyToOne mto = field.getAnnotation(ManyToOne.class);
        if (mto != null) {
            fetchType = mto.fetch();
        } else {
            javax.persistence.ManyToOne jpaMto = field.getAnnotation(javax.persistence.ManyToOne.class);
            if (jpaMto != null) {
                fetchType = jpaMto.fetch() == javax.persistence.FetchType.LAZY
                        ? FetchType.LAZY : FetchType.EAGER;
            }
        }
        // respect @JoinColumn(insertable=false, updatable=false) for shared PK/FK pattern
        javax.persistence.JoinColumn jc = field.getAnnotation(javax.persistence.JoinColumn.class);
        boolean insertable = (jc == null) || jc.insertable();
        return new RelationMetadata(field, Type.MANY_TO_ONE, resolveJoinColumn(field), "",
                fetchType, insertable, null, null, null);
    }

    public static RelationMetadata oneToMany(Field field) {
        FetchType fetchType = FetchType.LAZY;
        String mappedBy = "";
        OneToMany otm = field.getAnnotation(OneToMany.class);
        if (otm != null) {
            fetchType = otm.fetch();
            mappedBy = otm.mappedBy();
        } else {
            javax.persistence.OneToMany jpaOtm = field.getAnnotation(javax.persistence.OneToMany.class);
            if (jpaOtm != null) {
                fetchType = jpaOtm.fetch() == javax.persistence.FetchType.EAGER
                        ? FetchType.EAGER : FetchType.LAZY;
                mappedBy = jpaOtm.mappedBy();
            }
        }
        return new RelationMetadata(field, Type.ONE_TO_MANY, null, mappedBy, fetchType,
                true, null, null, null);
    }

    public static RelationMetadata manyToMany(Field field) {
        javax.persistence.ManyToMany mtm = field.getAnnotation(javax.persistence.ManyToMany.class);
        FetchType fetchType = FetchType.LAZY;
        String mappedBy = "";
        if (mtm != null) {
            fetchType = mtm.fetch() == javax.persistence.FetchType.EAGER
                    ? FetchType.EAGER : FetchType.LAZY;
            mappedBy = mtm.mappedBy();
        }
        if (!mappedBy.isEmpty()) {
            // inverse side — join table info is on the owner; no columns here
            return new RelationMetadata(field, Type.MANY_TO_MANY, null, mappedBy, fetchType,
                    true, null, null, null);
        }
        // owner side — read @JoinTable for join table + both FK columns
        javax.persistence.JoinTable jt = field.getAnnotation(javax.persistence.JoinTable.class);
        String joinTable = null;
        String keyColumn = null;
        String inverseJoinColumn = null;
        if (jt != null) {
            joinTable = jt.name().isEmpty() ? null : jt.name();
            if (jt.joinColumns().length > 0) keyColumn = jt.joinColumns()[0].name();
            if (jt.inverseJoinColumns().length > 0) inverseJoinColumn = jt.inverseJoinColumns()[0].name();
        }
        return new RelationMetadata(field, Type.MANY_TO_MANY, null, "", fetchType,
                true, joinTable, keyColumn, inverseJoinColumn);
    }

    public static RelationMetadata oneToOne(Field field) {
        FetchType fetchType = FetchType.EAGER;
        String mappedBy = "";
        javax.persistence.OneToOne jpaOto = field.getAnnotation(javax.persistence.OneToOne.class);
        if (jpaOto != null) {
            fetchType = jpaOto.fetch() == javax.persistence.FetchType.LAZY
                    ? FetchType.LAZY : FetchType.EAGER;
            mappedBy = jpaOto.mappedBy();
        }
        String joinColName = mappedBy.isEmpty() ? resolveJoinColumn(field) : null;
        return new RelationMetadata(field, Type.ONE_TO_ONE, joinColName, mappedBy, fetchType,
                true, null, null, null);
    }

    // ── HBM factory methods ────────────────────────────────────────────────────

    /** HBM {@code <many-to-one>} — FK column known directly from XML. */
    public static RelationMetadata manyToOneHbm(Field field, String joinColumn, FetchType fetchType) {
        return new RelationMetadata(field, Type.MANY_TO_ONE, joinColumn, "", fetchType,
                true, null, null, null);
    }

    /**
     * HBM {@code <many-to-one insert="false" update="false">} — shared PK/FK pattern.
     * The FK column is the same as the PK column, so it must NOT be included in INSERT/DDL.
     */
    public static RelationMetadata manyToOneHbmReadOnly(Field field, String joinColumn, FetchType fetchType) {
        return new RelationMetadata(field, Type.MANY_TO_ONE, joinColumn, "", fetchType,
                false, null, null, null);
    }

    /**
     * HBM {@code <set>/<one-to-many>} — FK column from {@code <key column="..."/>}.
     * Stored in joinColumnName so loadOneToManyEager() can use it directly without mappedBy lookup.
     */
    public static RelationMetadata oneToManyHbm(Field field, String fkColumn, FetchType fetchType) {
        return new RelationMetadata(field, Type.ONE_TO_MANY, fkColumn, "", fetchType,
                true, null, null, null);
    }

    /** HBM {@code <set>/<many-to-many>} — join table + both FK columns. */
    public static RelationMetadata manyToManyHbm(Field field, String joinTable,
                                                   String keyColumn, String inverseJoinColumn,
                                                   FetchType fetchType) {
        return new RelationMetadata(field, Type.MANY_TO_MANY, null, "", fetchType,
                true, joinTable, keyColumn, inverseJoinColumn);
    }

    // Resolves @JoinColumn name (our annotation or JPA's)
    private static String resolveJoinColumn(Field field) {
        if (field.isAnnotationPresent(JoinColumn.class)) {
            JoinColumn jc = field.getAnnotation(JoinColumn.class);
            return jc.name().isEmpty()
                    ? ReflectionUtils.camelToSnake(field.getName()) + "_id"
                    : jc.name();
        }
        if (field.isAnnotationPresent(javax.persistence.JoinColumn.class)) {
            javax.persistence.JoinColumn jc = field.getAnnotation(javax.persistence.JoinColumn.class);
            return jc.name().isEmpty()
                    ? ReflectionUtils.camelToSnake(field.getName()) + "_id"
                    : jc.name();
        }
        return ReflectionUtils.camelToSnake(field.getName()) + "_id";
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Field getField() { return field; }
    public Type getType() { return type; }
    public String getJoinColumnName() { return joinColumnName; }
    public String getMappedBy() { return mappedBy; }
    public FetchType getFetchType() { return fetchType; }
    public boolean isInsertable() { return insertable; }
    public String getJoinTable() { return joinTable; }
    public String getKeyColumn() { return keyColumn; }
    public String getInverseJoinColumn() { return inverseJoinColumn; }

    /** Returns a copy of this relation with the join table name prefixed by schema. */
    public RelationMetadata withJoinTablePrefix(String schema) {
        if (joinTable == null || joinTable.contains(".")) return this;
        return new RelationMetadata(field, type, joinColumnName, mappedBy, fetchType,
                insertable, schema + "." + joinTable, keyColumn, inverseJoinColumn);
    }

    /** Owner side = has FK column in its own table (no mappedBy). */
    public boolean isOwnerSide() {
        return mappedBy == null || mappedBy.isEmpty();
    }

    // ── FK value resolution ───────────────────────────────────────────────────

    public Object getJoinColumnValue(Object entity) {
        try {
            field.setAccessible(true);
            Object related = field.get(entity);
            if (related == null) return null;

            Class<?> relatedClass = related.getClass();
            for (Field f : ReflectionUtils.getAllFields(relatedClass)) {
                if (f.isAnnotationPresent(com.nimbus.persistence.annotation.Id.class)
                        || f.isAnnotationPresent(javax.persistence.Id.class)) {
                    f.setAccessible(true);
                    return f.get(related);
                }
            }
            throw new NimbusPersistenceException(
                    "No @Id field found in related entity: " + relatedClass.getName());
        } catch (IllegalAccessException e) {
            throw new NimbusPersistenceException(
                    "Cannot access field: " + field.getName(), e);
        }
    }
}
