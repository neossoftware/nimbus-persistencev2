package com.nimbus.persistence.v2;

import com.nimbus.persistence.Query;
import com.nimbus.persistence.Session;
import com.nimbus.persistence.Transaction;
import com.nimbus.persistence.exception.NimbusPersistenceException;
import com.nimbus.persistence.mapping.EntityMetadata;

import java.io.Serializable;
import java.sql.Connection;
import java.util.*;

public class CachingSession extends Session {

    /** L1 cache: "ClassName#pk" → entity */
    private final Map<String, Object> identityMap = new LinkedHashMap<String, Object>();

    /** Mapa inverso: entity → metadata (para dirty tracking) */
    private final Map<Object, EntityMetadata> managedEntities = new IdentityHashMap<Object, EntityMetadata>();

    private final DirtyTracker dirtyTracker = new DirtyTracker();
    private final CacheStats stats = new CacheStats();
    private final Map<String, String> namedQueries;

    CachingSession(Connection conn,
                   Map<Class<?>, EntityMetadata> metadataMap,
                   Map<String, Class<?>> entityRegistry,
                   boolean showSql,
                   Map<String, String> namedQueries) {
        super(conn, metadataMap, entityRegistry, showSql);
        this.namedQueries = namedQueries;
    }

    // ── L1 Cache + Dirty Tracking ─────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type, Serializable id) {
        String cacheKey = type.getSimpleName() + "#" + id;

        T cached = (T) identityMap.get(cacheKey);
        if (cached != null) {
            stats.recordHit();
            if (showSql) log("[L1-CACHE HIT] " + cacheKey);
            return cached;
        }

        stats.recordMiss();
        T entity = super.get(type, id);
        if (entity != null) {
            identityMap.put(cacheKey, entity);
            EntityMetadata meta = metadataMap.get(type);
            if (meta != null) {
                managedEntities.put(entity, meta);
                dirtyTracker.snapshot(entity, meta);
            }
        }
        return entity;
    }

    @Override
    public <T> Serializable save(T entity) {
        Serializable id = super.save(entity);
        // Registrar en identity map y dirty tracker
        EntityMetadata meta = metadataMap.get(entity.getClass());
        if (meta != null && id != null) {
            String cacheKey = entity.getClass().getSimpleName() + "#" + id;
            identityMap.put(cacheKey, entity);
            managedEntities.put(entity, meta);
            dirtyTracker.snapshot(entity, meta);
        }
        return id;
    }

    @Override
    public void evict(Object entity) {
        EntityMetadata meta = metadataMap.get(entity.getClass());
        if (meta != null) {
            Object pk = meta.getPkColumn().getValue(entity);
            identityMap.remove(entity.getClass().getSimpleName() + "#" + pk);
            managedEntities.remove(entity);
            dirtyTracker.evict(entity);
        }
    }

    @Override
    public void clear() {
        identityMap.clear();
        managedEntities.clear();
        dirtyTracker.clear();
    }

    // ── Transaction con Dirty Tracking ────────────────────────────────────────

    /**
     * Retorna un DirtyTrackingTransaction que auto-flush entidades dirty antes de commit.
     * Nota: se llama super.beginTransaction() para invocar begin() (package-private en
     * com.nimbus.persistence) y luego se reemplaza currentTx por el wrapper.
     */
    @Override
    public Transaction beginTransaction() {
        Transaction base = super.beginTransaction(); // llama begin() internamente
        DirtyTrackingTransaction dtx = new DirtyTrackingTransaction(connection, this, base);
        currentTx = dtx;
        return dtx;
    }

    /**
     * Ejecuta UPDATE en todas las entidades dirty.
     * Llamado por DirtyTrackingTransaction antes del commit.
     */
    void flushDirtyEntities() {
        List<Object> dirty = dirtyTracker.getDirtyEntities(managedEntities);
        for (Object entity : dirty) {
            if (showSql) log("[DIRTY-TRACK] Auto-UPDATE: " + entity.getClass().getSimpleName());
            super.update(entity);
            // Renovar snapshot tras actualizar
            EntityMetadata meta = managedEntities.get(entity);
            if (meta != null) dirtyTracker.snapshot(entity, meta);
        }
        if (!dirty.isEmpty()) {
            log("[DIRTY-TRACK] " + dirty.size() + " entidad(es) auto-actualizadas");
        }
    }

    // ── Named Queries (v2) ────────────────────────────────────────────────────

    /**
     * Ejecuta un @NamedQuery registrado en CachingSessionFactory.
     */
    public <T> Query<T> createNamedQuery(String name) {
        String hql = namedQueries.get(name);
        if (hql == null) {
            throw new NimbusPersistenceException(
                    "@NamedQuery no encontrado: '" + name + "'");
        }
        return createQuery(hql);
    }

    /**
     * Ejecuta un @NamedQuery con tipo de retorno explícito.
     */
    public <T> Query<T> createNamedQuery(String name, Class<T> resultType) {
        String hql = namedQueries.get(name);
        if (hql == null) {
            throw new NimbusPersistenceException(
                    "@NamedQuery no encontrado: '" + name + "'");
        }
        return createQuery(hql, resultType);
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    public CacheStats getCacheStats() { return stats; }

    public int getCacheSize() { return identityMap.size(); }
}
