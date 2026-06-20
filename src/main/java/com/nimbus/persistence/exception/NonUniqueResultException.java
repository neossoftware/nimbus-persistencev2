package com.nimbus.persistence.exception;

public class NonUniqueResultException extends org.hibernate.NonUniqueResultException {

    public NonUniqueResultException(String msg) {
        super(msg);
    }
}
