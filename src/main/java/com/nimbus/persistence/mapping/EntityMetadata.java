package com.nimbus.persistence.mapping;

import com.nimbus.persistence.annotation.Entity;
import com.nimbus.persistence.annotation.Id;
import com.nimbus.persistence.annotation.ManyToOne;
import com.nimbus.persistence.annotation.MappedSuperclass;
import com.nimbus.persistence.annotation.OneToMany;
import com.nimbus.persistence.annotation.Table;
import com.nimbus.persistence.annotation.Transient;
import com.nimbus.persistence.exception.NimbusPersistenceException;
import com.nimbus.persistence.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class EntityMetadata {

    private final Class<?> entityClass;
    private final String tableName;
    private final List<ColumnMetadata> pkColumns; // 1 = simple id, 2+ = composite-id
    private final List<ColumnMetadata> columns;
    private final List<RelationMetadata> relations;

    private EntityMetadata(Class<?> entityClass, String tableName,
                           List<ColumnMetadata> pkColumns, List<ColumnMetadata> columns,
                           List<RelationMetadata> relations) {
        this.entityClass = entityClass;
        this.tableName = tableName;
        this.pkColumns = Collections.unmodifiableList(pkColumns);
        this.columns = Collections.unmodifiableList(columns);
        this.relations = Collections.unmodifiableList(relations);
    }

    /**
     * Creates EntityMetadata from HBM XML descriptors.
     * All mapping info comes from the parsed XML, no annotations required.
     */
    public static EntityMetadata fromHbm(Class<?> entityClass, String tableName,
                                          List<ColumnMetadata> pkColumns,
                                          List<ColumnMetadata> columns,
                                          List<RelationMetadata> relations) {
        if (pkColumns == null || pkColumns.isEmpty()) {
            throw new NimbusPersistenceException(
                    "HBM mapping must have at least one <id> or <composite-id>: " + entityClass.getName());
        }
        return new EntityMetadata(entityClass, tableName, pkColumns, columns, relations);
    }

    public static EntityMetadata of(Class<?> clazz) {
        boolean isEntity = clazz.isAnnotationPresent(Entity.class)
                        || clazz.isAnnotationPresent(javax.persistence.Entity.class);
        if (!isEntity) {
            throw new NimbusPersistenceException(
                    "Class not annotated with @Entity: " + clazz.getName());
        }

        String tableName = resolveTableName(clazz);
        List<Field> allFields = getAllFields(clazz);

        List<ColumnMetadata> pkColumns = new ArrayList<ColumnMetadata>();
        List<ColumnMetadata> columns = new ArrayList<ColumnMetadata>();
        List<RelationMetadata> relations = new ArrayList<RelationMetadata>();

        for (Field field : allFields) {
            if (field.isAnnotationPresent(Transient.class)
                    || field.isAnnotationPresent(javax.persistence.Transient.class)) {
                continue;
            }
            if (field.isAnnotationPresent(ManyToOne.class)
                    || field.isAnnotationPresent(javax.persistence.ManyToOne.class)) {
                relations.add(RelationMetadata.manyToOne(field));
                continue;
            }
            if (field.isAnnotationPresent(OneToMany.class)
                    || field.isAnnotationPresent(javax.persistence.OneToMany.class)) {
                relations.add(RelationMetadata.oneToMany(field));
                continue;
            }
            if (field.isAnnotationPresent(javax.persistence.OneToOne.class)) {
                relations.add(RelationMetadata.oneToOne(field));
                continue;
            }
            if (field.isAnnotationPresent(javax.persistence.ManyToMany.class)) {
                relations.add(RelationMetadata.manyToMany(field));
                continue;
            }
            if (Collection.class.isAssignableFrom(field.getType())) {
                continue;
            }
            if (field.getType().isAnnotationPresent(Entity.class)
                    || field.getType().isAnnotationPresent(javax.persistence.Entity.class)) {
                continue;
            }

            ColumnMetadata col = ColumnMetadata.of(field);
            if (field.isAnnotationPresent(Id.class)
                    || field.isAnnotationPresent(javax.persistence.Id.class)) {
                pkColumns.add(col); // collect ALL @Id fields (supports @IdClass composite PKs)
            } else {
                columns.add(col);
            }
        }

        if (pkColumns.isEmpty()) {
            throw new NimbusPersistenceException(
                    "@Id field not found in: " + clazz.getName());
        }

        return new EntityMetadata(clazz, tableName, pkColumns, columns, relations);
    }

    private static String resolveTableName(Class<?> clazz) {
        if (clazz.isAnnotationPresent(javax.persistence.Table.class)) {
            javax.persistence.Table table = clazz.getAnnotation(javax.persistence.Table.class);
            if (!table.name().isEmpty()) return table.name();
        }
        if (clazz.isAnnotationPresent(Table.class)) {
            Table table = clazz.getAnnotation(Table.class);
            if (!table.name().isEmpty()) return table.name();
        }
        javax.persistence.Entity jpaEntity = clazz.getAnnotation(javax.persistence.Entity.class);
        if (jpaEntity != null && !jpaEntity.name().isEmpty()) {
            return ReflectionUtils.camelToSnake(jpaEntity.name());
        }
        Entity entity = clazz.getAnnotation(Entity.class);
        if (entity != null && !entity.name().isEmpty()) {
            return ReflectionUtils.camelToSnake(entity.name());
        }
        return ReflectionUtils.camelToSnake(clazz.getSimpleName());
    }

    public static List<Field> getAllFields(Class<?> clazz) {
        List<Field> result = new ArrayList<Field>();
        List<Class<?>> hierarchy = new ArrayList<Class<?>>();

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            hierarchy.add(0, current);
            Class<?> superClass = current.getSuperclass();
            if (superClass == null || superClass == Object.class) {
                break;
            }
            if (superClass.isAnnotationPresent(MappedSuperclass.class)
                    || superClass.isAnnotationPresent(javax.persistence.MappedSuperclass.class)
                    || superClass.isAnnotationPresent(Entity.class)
                    || superClass.isAnnotationPresent(javax.persistence.Entity.class)) {
                current = superClass;
            } else {
                break;
            }
        }

        for (Class<?> cls : hierarchy) {
            for (Field f : cls.getDeclaredFields()) {
                result.add(f);
            }
        }

        return result;
    }

    public Class<?> getEntityClass() { return entityClass; }
    public String getTableName() { return tableName; }
    public List<ColumnMetadata> getColumns() { return columns; }
    public List<RelationMetadata> getRelations() { return relations; }

    /** Returns the single PK column. For composite-id returns the first column. */
    public ColumnMetadata getPkColumn() { return pkColumns.get(0); }

    /** All PK columns — 1 for simple id, 2+ for composite-id. */
    public List<ColumnMetadata> getPkColumns() { return pkColumns; }

    /** True when the entity has a composite primary key (2+ columns). */
    public boolean isCompositeId() { return pkColumns.size() > 1; }

    public List<ColumnMetadata> getAllColumns() {
        List<ColumnMetadata> all = new ArrayList<ColumnMetadata>();
        all.addAll(pkColumns);
        all.addAll(columns);
        return all;
    }

    public List<ColumnMetadata> getInsertColumns() {
        List<ColumnMetadata> result = new ArrayList<ColumnMetadata>();
        for (ColumnMetadata pk : pkColumns) {
            if (!pk.isGeneratedIdentity()) {
                result.add(pk);
            }
        }
        result.addAll(columns);
        return result;
    }

    public List<ColumnMetadata> getUpdateColumns() {
        return new ArrayList<ColumnMetadata>(columns);
    }

    public EntityMetadata withSchemaPrefix(String schema) {
        List<RelationMetadata> prefixedRelations = new ArrayList<RelationMetadata>();
        for (RelationMetadata rel : relations) {
            prefixedRelations.add(rel.withJoinTablePrefix(schema));
        }
        return new EntityMetadata(entityClass, schema + "." + tableName,
                new ArrayList<ColumnMetadata>(pkColumns),
                new ArrayList<ColumnMetadata>(columns),
                prefixedRelations);
    }

    public ColumnMetadata getColumnByFieldName(String fieldName) {
        for (ColumnMetadata pk : pkColumns) {
            if (pk.getField().getName().equals(fieldName)) return pk;
        }
        for (ColumnMetadata col : columns) {
            if (col.getField().getName().equals(fieldName)) return col;
        }
        return null;
    }

    public String resolveColumnName(String fieldName) {
        ColumnMetadata col = getColumnByFieldName(fieldName);
        if (col != null) {
            return col.getColumnName();
        }
        for (RelationMetadata rel : relations) {
            if (rel.getField().getName().equals(fieldName)
                    && rel.getType() == RelationMetadata.Type.MANY_TO_ONE) {
                return rel.getJoinColumnName();
            }
        }
        return ReflectionUtils.camelToSnake(fieldName);
    }
}
