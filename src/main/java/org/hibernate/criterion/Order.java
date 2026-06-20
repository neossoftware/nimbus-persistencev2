package org.hibernate.criterion;

/**
 * Hibernate 5 compatibility alias.
 * Returns com.nimbus.persistence.Order instances accepted by Criteria.addOrder().
 */
public final class Order {

    private Order() {}

    public static com.nimbus.persistence.Order asc(String propertyName) {
        return com.nimbus.persistence.Order.asc(propertyName);
    }

    public static com.nimbus.persistence.Order desc(String propertyName) {
        return com.nimbus.persistence.Order.desc(propertyName);
    }
}
