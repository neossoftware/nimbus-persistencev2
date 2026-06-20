package com.nimbus.persistence;

import com.nimbus.persistence.dialect.NimbusDialect;
import com.nimbus.persistence.exception.NimbusPersistenceException;
import com.nimbus.persistence.exception.NonUniqueResultException;
import com.nimbus.persistence.mapping.EntityMetadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Criteria<T> {

    private final Connection connection;
    private final Class<T> entityClass;
    private final EntityMetadata meta;
    private final Map<Class<?>, EntityMetadata> metadataMap;
    private final boolean showSql;
    private final NimbusDialect dialect;
    private final List<Restrictions.Criterion> criterions = new ArrayList<Restrictions.Criterion>();
    private final List<Order> orders = new ArrayList<Order>();
    private Integer maxResults;
    private Integer firstResult;

    /** Legacy constructor — defaults to H2 dialect. */
    public Criteria(Connection connection, Class<T> entityClass, EntityMetadata meta,
                    Map<Class<?>, EntityMetadata> metadataMap, boolean showSql) {
        this(connection, entityClass, meta, metadataMap, showSql, NimbusDialect.H2);
    }

    public Criteria(Connection connection, Class<T> entityClass, EntityMetadata meta,
                    Map<Class<?>, EntityMetadata> metadataMap, boolean showSql, NimbusDialect dialect) {
        this.connection = connection;
        this.entityClass = entityClass;
        this.meta = meta;
        this.metadataMap = metadataMap;
        this.showSql = showSql;
        this.dialect = dialect;
    }

    public Criteria<T> add(Restrictions.Criterion criterion) {
        criterions.add(criterion);
        return this;
    }

    public Criteria<T> addOrder(Order order) {
        orders.add(order);
        return this;
    }

    public Criteria<T> setMaxResults(int max) {
        this.maxResults = max;
        return this;
    }

    public Criteria<T> setFirstResult(int first) {
        this.firstResult = first;
        return this;
    }

    public List<T> list() {
        String sql = buildSql();
        if (showSql) {
            System.out.println("[NimbusPersistence] SQL: " + sql);
        }
        List<T> results = new ArrayList<T>();
        try {
            PreparedStatement ps = connection.prepareStatement(sql);
            try {
                bindCriterions(ps);
                ResultSet rs = ps.executeQuery();
                try {
                    Session tempSession = new Session(connection, metadataMap,
                            new LinkedHashMap<String, Class<?>>(), showSql);
                    while (rs.next()) {
                        results.add(tempSession.mapResultSet(rs, entityClass, meta));
                    }
                } finally {
                    rs.close();
                }
            } finally {
                ps.close();
            }
        } catch (Exception e) {
            throw new NimbusPersistenceException(
                    "Criteria.list() failed for: " + entityClass.getSimpleName(), e);
        }
        return results;
    }

    public T uniqueResult() {
        List<T> results = list();
        if (results.isEmpty()) {
            return null;
        }
        if (results.size() > 1) {
            throw new NonUniqueResultException(
                    "Criteria returned " + results.size() + " results for "
                    + entityClass.getSimpleName());
        }
        return results.get(0);
    }

    public long count() {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM ");
        sb.append(meta.getTableName());

        if (!criterions.isEmpty()) {
            sb.append(" WHERE ");
            boolean first = true;
            for (Restrictions.Criterion c : criterions) {
                if (!first) {
                    sb.append(" AND ");
                }
                sb.append(c.toSql(meta));
                first = false;
            }
        }

        String sql = sb.toString();
        if (showSql) {
            System.out.println("[NimbusPersistence] SQL: " + sql);
        }

        try {
            PreparedStatement ps = connection.prepareStatement(sql);
            try {
                bindCriterions(ps);
                ResultSet rs = ps.executeQuery();
                try {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                } finally {
                    rs.close();
                }
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new NimbusPersistenceException(
                    "Criteria.count() failed for: " + entityClass.getSimpleName(), e);
        }
        return 0L;
    }

    private String buildSql() {
        StringBuilder sb = new StringBuilder("SELECT * FROM ");
        sb.append(meta.getTableName());

        if (!criterions.isEmpty()) {
            sb.append(" WHERE ");
            boolean first = true;
            for (Restrictions.Criterion c : criterions) {
                if (!first) {
                    sb.append(" AND ");
                }
                sb.append(c.toSql(meta));
                first = false;
            }
        }

        if (!orders.isEmpty()) {
            sb.append(" ORDER BY ");
            boolean first = true;
            for (Order o : orders) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(o.toSql(meta));
                first = false;
            }
        }

        if (maxResults != null) {
            return dialect.applyLimit(sb.toString(), maxResults)
                    + (firstResult != null ? " OFFSET " + firstResult : "");
        }

        if (firstResult != null) {
            sb.append(" OFFSET ").append(firstResult);
        }

        return sb.toString();
    }

    private void bindCriterions(PreparedStatement ps) throws SQLException {
        int[] idx = new int[]{1};
        for (Restrictions.Criterion c : criterions) {
            c.bind(ps, idx);
        }
    }
}
