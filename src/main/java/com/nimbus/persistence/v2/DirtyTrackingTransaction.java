package com.nimbus.persistence.v2;

import com.nimbus.persistence.Transaction;

import java.sql.Connection;

/**
 * Transaction que detecta entidades dirty antes del commit.
 *
 * Nota de diseño: Transaction.begin() es package-private en com.nimbus.persistence,
 * por lo que CachingSession llama a super.beginTransaction() para iniciar la transacción,
 * y luego reemplaza currentTx con esta clase. El commit y rollback delegan
 * a la transacción original pero interceptan el commit para hacer flush de dirty entities.
 */
class DirtyTrackingTransaction extends Transaction {

    private final CachingSession session;
    private final Transaction delegate;

    DirtyTrackingTransaction(Connection conn, CachingSession session, Transaction delegate) {
        super(conn);
        this.session = session;
        this.delegate = delegate;
    }

    @Override
    public void commit() {
        // Antes de commit: flush dirty entities
        session.flushDirtyEntities();
        // El delegate ya está "active" y tiene la misma Connection
        delegate.commit();
    }

    @Override
    public void rollback() {
        delegate.rollback();
    }

    @Override
    public boolean isActive() {
        return delegate.isActive();
    }

    @Override
    public boolean wasCommitted() {
        return delegate.wasCommitted();
    }

    @Override
    public boolean wasRolledBack() {
        return delegate.wasRolledBack();
    }
}
