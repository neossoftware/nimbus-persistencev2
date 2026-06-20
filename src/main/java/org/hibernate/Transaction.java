package org.hibernate;

import java.sql.Connection;

/**
 * Hibernate 5 compatibility alias for com.nimbus.persistence.Transaction.
 */
public class Transaction extends com.nimbus.persistence.Transaction {

    public Transaction(Connection connection) {
        super(connection);
    }
}
