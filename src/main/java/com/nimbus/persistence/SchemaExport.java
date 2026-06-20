package com.nimbus.persistence;

import com.nimbus.persistence.exception.NimbusPersistenceException;
import com.nimbus.persistence.mapping.ColumnMetadata;
import com.nimbus.persistence.mapping.EntityMetadata;
import com.nimbus.persistence.mapping.RelationMetadata;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SchemaExport {

    private final Map<Class<?>, EntityMetadata> metadataMap;
    private final boolean showSql;

    public SchemaExport(Map<Class<?>, EntityMetadata> metadataMap, boolean showSql) {
        this.metadataMap = metadataMap;
        this.showSql = showSql;
    }

    public void execute(Connection conn, String mode) throws SQLException {
        String m = mode.toLowerCase().trim();
        if ("create".equals(m) || "create-drop".equals(m)) {
            dropAll(conn);
            createAll(conn);
        } else if ("update".equals(m)) {
            createIfNotExists(conn);
        } else if ("validate".equals(m)) {
            validateSchema(conn);
        }
        // "none" => do nothing
    }

    private void dropAll(Connection conn) throws SQLException {
        List<EntityMetadata> metas = new ArrayList<EntityMetadata>(metadataMap.values());
        Collections.reverse(metas);
        try (Statement stmt = conn.createStatement()) {
            for (EntityMetadata meta : metas) {
                String sql = "DROP TABLE IF EXISTS " + meta.getTableName() + " CASCADE";
                if (showSql) {
                    System.out.println("[NimbusPersistence] DDL: " + sql);
                }
                try {
                    stmt.executeUpdate(sql);
                } catch (SQLException e) {
                    // Some databases don't support CASCADE; try without
                    try {
                        String sqlNoCascade = "DROP TABLE IF EXISTS " + meta.getTableName();
                        stmt.executeUpdate(sqlNoCascade);
                    } catch (SQLException e2) {
                        // Ignore drop errors
                    }
                }
            }
        }
    }

    private void createAll(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (EntityMetadata meta : metadataMap.values()) {
                String sql = generateCreateTableSql(meta);
                if (showSql) System.out.println("[NimbusPersistence] DDL: " + sql);
                stmt.executeUpdate(sql);
                // Create join tables for @ManyToMany relations
                for (RelationMetadata rel : meta.getRelations()) {
                    if (rel.getType() == RelationMetadata.Type.MANY_TO_MANY
                            && rel.getJoinTable() != null) {
                        String joinSql = generateJoinTableSql(rel);
                        if (showSql) System.out.println("[NimbusPersistence] DDL: " + joinSql);
                        try { stmt.executeUpdate(joinSql); } catch (SQLException ignore) {}
                    }
                }
            }
        }
    }

    private String generateJoinTableSql(RelationMetadata rel) {
        return "CREATE TABLE IF NOT EXISTS " + rel.getJoinTable()
                + " (" + rel.getKeyColumn() + " BIGINT NOT NULL"
                + ", " + rel.getInverseJoinColumn() + " BIGINT NOT NULL"
                + ", PRIMARY KEY (" + rel.getKeyColumn() + ", " + rel.getInverseJoinColumn() + "))";
    }

    private void createIfNotExists(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (EntityMetadata meta : metadataMap.values()) {
                // Try to create; ignore if already exists
                String sql = generateCreateTableSql(meta);
                // Replace CREATE TABLE with CREATE TABLE IF NOT EXISTS
                sql = sql.replaceFirst("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ");
                if (showSql) {
                    System.out.println("[NimbusPersistence] DDL: " + sql);
                }
                try {
                    stmt.executeUpdate(sql);
                } catch (SQLException e) {
                    // Table already exists - ignore
                }
            }
        }
    }

    private void validateSchema(Connection conn) throws SQLException {
        DatabaseMetaData dbMeta = conn.getMetaData();
        for (EntityMetadata meta : metadataMap.values()) {
            String tableName = meta.getTableName().toUpperCase();
            ResultSet tables = dbMeta.getTables(null, null, tableName, new String[]{"TABLE"});
            boolean found = tables.next();
            tables.close();
            if (!found) {
                // Try lower case
                tableName = meta.getTableName().toLowerCase();
                tables = dbMeta.getTables(null, null, tableName, new String[]{"TABLE"});
                found = tables.next();
                tables.close();
            }
            if (!found) {
                throw new NimbusPersistenceException(
                        "Schema validation failed: table not found: " + meta.getTableName());
            }
        }
    }

    private String generateCreateTableSql(EntityMetadata meta) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ");
        sb.append(meta.getTableName());
        sb.append(" (");

        List<String> columnDefs = new ArrayList<String>();

        if (meta.isCompositeId()) {
            // Composite PK: each key column is a regular column, PK constraint added at end
            for (ColumnMetadata pk : meta.getPkColumns()) {
                columnDefs.add(pk.getColumnName() + " " + getSqlType(pk) + " NOT NULL");
            }
        } else {
            columnDefs.add(generatePkColumnDef(meta.getPkColumn()));
        }

        for (ColumnMetadata col : meta.getColumns()) {
            columnDefs.add(generateColumnDef(col));
        }

        for (RelationMetadata rel : meta.getRelations()) {
            if ((rel.getType() == RelationMetadata.Type.MANY_TO_ONE
                    || rel.getType() == RelationMetadata.Type.ONE_TO_ONE)
                    && rel.isOwnerSide() && rel.isInsertable()) {
                columnDefs.add(rel.getJoinColumnName() + " BIGINT");
            }
        }

        if (meta.isCompositeId()) {
            List<String> pkColNames = new ArrayList<String>();
            for (ColumnMetadata pk : meta.getPkColumns()) {
                pkColNames.add(pk.getColumnName());
            }
            columnDefs.add("PRIMARY KEY (" + join(", ", pkColNames) + ")");
        }

        sb.append(join(", ", columnDefs));
        sb.append(")");

        return sb.toString();
    }

    private String generatePkColumnDef(ColumnMetadata col) {
        StringBuilder sb = new StringBuilder(col.getColumnName());
        sb.append(" ");

        if (col.isGeneratedIdentity()) {
            Class<?> type = col.getField().getType();
            if (type == long.class || type == Long.class) {
                sb.append("BIGSERIAL");
            } else {
                sb.append("SERIAL");
            }
        } else {
            sb.append(getSqlType(col));
        }

        sb.append(" PRIMARY KEY");
        return sb.toString();
    }

    private String generateColumnDef(ColumnMetadata col) {
        if (!col.getColumnDefinition().isEmpty()) {
            return col.getColumnName() + " " + col.getColumnDefinition();
        }

        StringBuilder sb = new StringBuilder(col.getColumnName());
        sb.append(" ");
        sb.append(getSqlType(col));

        if (!col.isNullable()) {
            sb.append(" NOT NULL");
        }
        if (col.isUnique()) {
            sb.append(" UNIQUE");
        }

        return sb.toString();
    }

    private String getSqlType(ColumnMetadata col) {
        Class<?> type = col.getField().getType();

        // @Type overrides everything else
        if (col.isYesNo() || col.isTrueFalse()) return "CHAR(1)";
        if (col.isNumericBoolean())              return "SMALLINT";
        if (col.isClobType())                    return "CLOB";
        if (col.isBlobType())                    return "BLOB";
        if (col.isDateType())                    return "DATE";
        if (col.isTimestampType())               return "TIMESTAMP";
        if (col.isTimeType())                    return "TIME";

        // @Lob overrides normal type mapping
        if (col.isLob()) {
            if (type == String.class || type == char[].class || type == Character[].class) {
                return "TEXT";
            }
            return "BYTEA"; // byte[], Blob, Serializable
        }

        // @Temporal overrides Date/Calendar mapping
        if (col.getTemporalType() != null) {
            switch (col.getTemporalType()) {
                case DATE:      return "DATE";
                case TIME:      return "TIME";
                case TIMESTAMP: return "TIMESTAMP";
            }
        }

        // @Enumerated — ORDINAL=INTEGER, STRING=VARCHAR
        if (type.isEnum()) {
            if (col.getEnumeratedType() == javax.persistence.EnumType.ORDINAL) {
                return "INTEGER";
            }
            return "VARCHAR(" + col.getLength() + ")";
        }

        if (type == String.class) {
            return "VARCHAR(" + col.getLength() + ")";
        }
        if (type == int.class || type == Integer.class) {
            return "INTEGER";
        }
        if (type == long.class || type == Long.class) {
            return "BIGINT";
        }
        if (type == double.class || type == Double.class) {
            return "DOUBLE PRECISION";
        }
        if (type == float.class || type == Float.class) {
            return "REAL";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "BOOLEAN";
        }
        if (type == java.util.Date.class || type == java.sql.Timestamp.class) {
            return "TIMESTAMP";
        }
        if (type == java.sql.Date.class) {
            return "DATE";
        }
        if (type == BigDecimal.class) {
            int precision = col.getPrecision() > 0 ? col.getPrecision() : 19;
            int scale = col.getScale() > 0 ? col.getScale() : 2;
            return "NUMERIC(" + precision + "," + scale + ")";
        }
        if (type == byte[].class) {
            return "BYTEA";
        }
        if (type == Short.class || type == short.class) {
            return "SMALLINT";
        }
        return "TEXT";
    }

    private String join(String separator, List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
