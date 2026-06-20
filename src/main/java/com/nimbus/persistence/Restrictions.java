package com.nimbus.persistence;

import com.nimbus.persistence.mapping.EntityMetadata;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class Restrictions {

    private Restrictions() {
    }

    public interface Criterion {
        String toSql(EntityMetadata meta);
        void bind(PreparedStatement ps, int[] idx) throws SQLException;
    }

    // ─── Simple comparison criterions ──────────────────────────────────────────

    public static Criterion eq(final String fieldName, final Object value) {
        return new SimpleCriterion(fieldName, "=", value);
    }

    public static Criterion ne(final String fieldName, final Object value) {
        return new SimpleCriterion(fieldName, "<>", value);
    }

    public static Criterion gt(final String fieldName, final Object value) {
        return new SimpleCriterion(fieldName, ">", value);
    }

    public static Criterion lt(final String fieldName, final Object value) {
        return new SimpleCriterion(fieldName, "<", value);
    }

    public static Criterion ge(final String fieldName, final Object value) {
        return new SimpleCriterion(fieldName, ">=", value);
    }

    public static Criterion le(final String fieldName, final Object value) {
        return new SimpleCriterion(fieldName, "<=", value);
    }

    public static Criterion like(final String fieldName, final String pattern) {
        return new SimpleCriterion(fieldName, "LIKE", pattern);
    }

    public static Criterion ilike(final String fieldName, final String pattern) {
        return new Criterion() {
            public String toSql(EntityMetadata meta) {
                String col = meta.resolveColumnName(fieldName);
                return "LOWER(" + col + ") LIKE LOWER(?)";
            }
            public void bind(PreparedStatement ps, int[] idx) throws SQLException {
                ps.setObject(idx[0]++, pattern);
            }
        };
    }

    public static Criterion isNull(final String fieldName) {
        return new Criterion() {
            public String toSql(EntityMetadata meta) {
                return meta.resolveColumnName(fieldName) + " IS NULL";
            }
            public void bind(PreparedStatement ps, int[] idx) throws SQLException {
                // No binding needed
            }
        };
    }

    public static Criterion isNotNull(final String fieldName) {
        return new Criterion() {
            public String toSql(EntityMetadata meta) {
                return meta.resolveColumnName(fieldName) + " IS NOT NULL";
            }
            public void bind(PreparedStatement ps, int[] idx) throws SQLException {
                // No binding needed
            }
        };
    }

    public static Criterion between(final String fieldName, final Object lo, final Object hi) {
        return new Criterion() {
            public String toSql(EntityMetadata meta) {
                return meta.resolveColumnName(fieldName) + " BETWEEN ? AND ?";
            }
            public void bind(PreparedStatement ps, int[] idx) throws SQLException {
                ps.setObject(idx[0]++, lo);
                ps.setObject(idx[0]++, hi);
            }
        };
    }

    public static Criterion in(final String fieldName, final Collection<?> values) {
        return new Criterion() {
            public String toSql(EntityMetadata meta) {
                String col = meta.resolveColumnName(fieldName);
                StringBuilder sb = new StringBuilder(col);
                sb.append(" IN (");
                boolean first = true;
                for (Object ignored : values) {
                    if (!first) {
                        sb.append(", ");
                    }
                    sb.append("?");
                    first = false;
                }
                sb.append(")");
                return sb.toString();
            }
            public void bind(PreparedStatement ps, int[] idx) throws SQLException {
                for (Object val : values) {
                    if (val == null) {
                        ps.setNull(idx[0]++, Types.NULL);
                    } else {
                        ps.setObject(idx[0]++, val);
                    }
                }
            }
        };
    }

    public static Criterion and(final Criterion... criterions) {
        return new Criterion() {
            public String toSql(EntityMetadata meta) {
                List<String> parts = new ArrayList<String>();
                for (Criterion c : criterions) {
                    parts.add("(" + c.toSql(meta) + ")");
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.size(); i++) {
                    if (i > 0) sb.append(" AND ");
                    sb.append(parts.get(i));
                }
                return sb.toString();
            }
            public void bind(PreparedStatement ps, int[] idx) throws SQLException {
                for (Criterion c : criterions) {
                    c.bind(ps, idx);
                }
            }
        };
    }

    public static Criterion or(final Criterion... criterions) {
        return new Criterion() {
            public String toSql(EntityMetadata meta) {
                List<String> parts = new ArrayList<String>();
                for (Criterion c : criterions) {
                    parts.add("(" + c.toSql(meta) + ")");
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.size(); i++) {
                    if (i > 0) sb.append(" OR ");
                    sb.append(parts.get(i));
                }
                return sb.toString();
            }
            public void bind(PreparedStatement ps, int[] idx) throws SQLException {
                for (Criterion c : criterions) {
                    c.bind(ps, idx);
                }
            }
        };
    }

    // ─── Internal helper class ─────────────────────────────────────────────────

    private static class SimpleCriterion implements Criterion {
        private final String fieldName;
        private final String operator;
        private final Object value;

        SimpleCriterion(String fieldName, String operator, Object value) {
            this.fieldName = fieldName;
            this.operator = operator;
            this.value = value;
        }

        public String toSql(EntityMetadata meta) {
            String col = meta.resolveColumnName(fieldName);
            if (value == null) {
                if ("=".equals(operator)) {
                    return col + " IS NULL";
                } else if ("<>".equals(operator)) {
                    return col + " IS NOT NULL";
                }
            }
            return col + " " + operator + " ?";
        }

        public void bind(PreparedStatement ps, int[] idx) throws SQLException {
            if (value != null) {
                ps.setObject(idx[0]++, value);
            }
        }
    }
}
