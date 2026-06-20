package com.nimbus.persistence;

import com.nimbus.persistence.exception.NimbusPersistenceException;

import java.sql.Connection;
import java.sql.SQLException;

public class Transaction {

    private final Connection connection;
    private boolean active = false;
    private boolean committed = false;
    private boolean rolledBack = false;

    public Transaction(Connection connection) {
        this.connection = connection;
    }

    void begin() {
        try {
            connection.setAutoCommit(false);
            active = true;
            committed = false;
            rolledBack = false;
        } catch (SQLException e) {
            throw new NimbusPersistenceException("Failed to begin transaction", e);
        }
    }

    public void commit() {
        try {
            connection.commit();
            active = false;
            committed = true;
        } catch (SQLException e) {
            throw new NimbusPersistenceException("commit failed", e);
        }
    }

    public void rollback() {
        try {
            connection.rollback();
            active = false;
            rolledBack = true;
        } catch (SQLException e) {
            throw new NimbusPersistenceException("rollback failed", e);
        }
    }

    public boolean isActive() {
        return active;
    }

    public boolean wasCommitted() {
        return committed;
    }

    public boolean wasRolledBack() {
        return rolledBack;
    }
}
