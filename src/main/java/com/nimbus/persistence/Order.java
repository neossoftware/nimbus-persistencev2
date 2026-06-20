package com.nimbus.persistence;

import com.nimbus.persistence.mapping.EntityMetadata;

public final class Order {

    private final String fieldName;
    private final boolean ascending;

    private Order(String fieldName, boolean ascending) {
        this.fieldName = fieldName;
        this.ascending = ascending;
    }

    public static Order asc(String fieldName) {
        return new Order(fieldName, true);
    }

    public static Order desc(String fieldName) {
        return new Order(fieldName, false);
    }

    public String toSql(EntityMetadata meta) {
        String colName = meta.resolveColumnName(fieldName);
        return colName + (ascending ? " ASC" : " DESC");
    }

    public String getFieldName() {
        return fieldName;
    }

    public boolean isAscending() {
        return ascending;
    }
}
