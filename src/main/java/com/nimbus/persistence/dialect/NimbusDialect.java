package com.nimbus.persistence.dialect;

/**
 * Lightweight dialect abstraction — only covers what nimbus-persistencev2 actually generates:
 * LIMIT clause syntax. Everything else (cache, transaction factory, outer join) is accepted
 * from the XML config and silently ignored.
 */
public enum NimbusDialect {

    H2 {
        @Override
        public String applyLimit(String sql, int n) {
            return sql + " LIMIT " + n;
        }
    },

    POSTGRESQL {
        @Override
        public String applyLimit(String sql, int n) {
            return sql + " LIMIT " + n;
        }
    },

    DB2390 {
        // DB2 for z/OS: no LIMIT keyword — uses FETCH FIRST n ROWS ONLY
        @Override
        public String applyLimit(String sql, int n) {
            return sql + " FETCH FIRST " + n + " ROWS ONLY";
        }
    };

    public abstract String applyLimit(String sql, int n);

    /**
     * Resolves a dialect from the Hibernate {@code dialect} property value.
     * Accepts full class names (e.g. {@code org.hibernate.dialect.DB2390Dialect})
     * or short aliases ({@code H2}, {@code PostgreSQL}, {@code DB2390}).
     * Defaults to {@link #H2} when null or unrecognised.
     */
    public static NimbusDialect fromProperty(String value) {
        if (value == null || value.trim().isEmpty()) {
            return H2;
        }
        String v = value.trim().toLowerCase();
        if (v.contains("db2390") || v.contains("db2/390")) {
            return DB2390;
        }
        if (v.contains("db2")) {
            return DB2390;  // plain DB2 also uses FETCH FIRST
        }
        if (v.contains("postgresql") || v.contains("postgres")) {
            return POSTGRESQL;
        }
        if (v.contains("h2")) {
            return H2;
        }
        // Unknown dialect — default to H2 (safe, standard SQL)
        return H2;
    }
}
