package com.nimbus.persistence.exception;

public class NimbusPersistenceException extends org.hibernate.HibernateException {

    public NimbusPersistenceException(String msg) {
        super(msg);
    }

    public NimbusPersistenceException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
