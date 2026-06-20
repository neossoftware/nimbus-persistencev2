package org.hibernate;

public class NonUniqueResultException extends HibernateException {

    public NonUniqueResultException(String message) {
        super(message);
    }
}
