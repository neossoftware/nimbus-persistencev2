package org.hibernate.criterion;

import com.nimbus.persistence.Restrictions.Criterion;

import java.util.Collection;

/**
 * Hibernate 5 compatibility alias for com.nimbus.persistence.Restrictions.
 */
public final class Restrictions {

    private Restrictions() {}

    public static Criterion eq(String fieldName, Object value) {
        return com.nimbus.persistence.Restrictions.eq(fieldName, value);
    }

    public static Criterion ne(String fieldName, Object value) {
        return com.nimbus.persistence.Restrictions.ne(fieldName, value);
    }

    public static Criterion gt(String fieldName, Object value) {
        return com.nimbus.persistence.Restrictions.gt(fieldName, value);
    }

    public static Criterion lt(String fieldName, Object value) {
        return com.nimbus.persistence.Restrictions.lt(fieldName, value);
    }

    public static Criterion ge(String fieldName, Object value) {
        return com.nimbus.persistence.Restrictions.ge(fieldName, value);
    }

    public static Criterion le(String fieldName, Object value) {
        return com.nimbus.persistence.Restrictions.le(fieldName, value);
    }

    public static Criterion like(String fieldName, String pattern) {
        return com.nimbus.persistence.Restrictions.like(fieldName, pattern);
    }

    public static Criterion ilike(String fieldName, String pattern) {
        return com.nimbus.persistence.Restrictions.ilike(fieldName, pattern);
    }

    public static Criterion isNull(String fieldName) {
        return com.nimbus.persistence.Restrictions.isNull(fieldName);
    }

    public static Criterion isNotNull(String fieldName) {
        return com.nimbus.persistence.Restrictions.isNotNull(fieldName);
    }

    public static Criterion between(String fieldName, Object lo, Object hi) {
        return com.nimbus.persistence.Restrictions.between(fieldName, lo, hi);
    }

    public static Criterion in(String fieldName, Collection<?> values) {
        return com.nimbus.persistence.Restrictions.in(fieldName, values);
    }

    public static Criterion and(Criterion... criterions) {
        return com.nimbus.persistence.Restrictions.and(criterions);
    }

    public static Criterion or(Criterion... criterions) {
        return com.nimbus.persistence.Restrictions.or(criterions);
    }
}
