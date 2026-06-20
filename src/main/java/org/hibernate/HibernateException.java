package org.hibernate;

public class HibernateException extends RuntimeException {

    public HibernateException(String message) {
        super(message);
    }

    public HibernateException(String message, Throwable cause) {
        super(message, cause);
    }

    public HibernateException(Throwable cause) {
        super(cause);
    }
}
