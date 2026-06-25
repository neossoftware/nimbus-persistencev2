package com.nimbus.persistence.exception;

public class NimbusPersistenceException extends org.hibernate.HibernateException {

    public NimbusPersistenceException(String msg) {
        super(msg);
    }

    public NimbusPersistenceException(String msg, Throwable cause) {
        super(buildMessage(msg, cause), cause);
    }

    /**
     * Builds a message that includes the full cause chain separated by " → ".
     * Makes the root cause visible directly in detailMessage without expanding
     * the cause chain in the debugger.
     *
     * Example:
     *   Query.list() failed: SELECT * FROM AM3D101.TE_USER_WS WHERE ...
     *   → Failed to load @ManyToMany collection: rolesWS
     *   → Table "AM3D101.TR_ROLE_USER_WS" not found
     */
    private static String buildMessage(String msg, Throwable cause) {
        if (cause == null) return msg;
        StringBuilder sb = new StringBuilder(msg);
        Throwable c = cause;
        while (c != null) {
            String causeMsg = c.getMessage();
            if (causeMsg != null && !causeMsg.isEmpty() && !sb.toString().contains(causeMsg)) {
                sb.append("\n  → ").append(causeMsg);
            }
            c = c.getCause();
        }
        return sb.toString();
    }
}
