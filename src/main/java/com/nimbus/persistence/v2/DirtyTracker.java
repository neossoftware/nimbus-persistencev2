package com.nimbus.persistence.v2;

import com.nimbus.persistence.mapping.EntityMetadata;
import com.nimbus.persistence.mapping.ColumnMetadata;

import java.util.*;

public class DirtyTracker {

    // key = identity hash del objeto → snapshot de sus valores al cargar
    // Nota: usamos HashMap (no IdentityHashMap) porque las claves son Integer boxeados;
    // IdentityHashMap usa == por referencia y fallaría con autoboxing de int.
    private final Map<Integer, Map<String, Object>> snapshots = new LinkedHashMap<Integer, Map<String, Object>>();

    /** Toma un snapshot del estado actual de la entidad. */
    public void snapshot(Object entity, EntityMetadata meta) {
        Map<String, Object> snap = new LinkedHashMap<String, Object>();
        for (ColumnMetadata col : meta.getColumns()) {
            snap.put(col.getColumnName(), col.getValue(entity));
        }
        snapshots.put(System.identityHashCode(entity), snap);
    }

    /** Retorna true si la entidad cambió respecto al snapshot. */
    public boolean isDirty(Object entity, EntityMetadata meta) {
        Map<String, Object> snap = snapshots.get(System.identityHashCode(entity));
        if (snap == null) return false; // no fue trackeada
        for (ColumnMetadata col : meta.getColumns()) {
            Object current = col.getValue(entity);
            Object original = snap.get(col.getColumnName());
            if (!Objects.equals(current, original)) return true;
        }
        return false;
    }

    /** Retorna todas las entidades dirty junto con su clase y metadata. */
    public List<Object> getDirtyEntities(Map<Object, EntityMetadata> managedEntities) {
        List<Object> dirty = new ArrayList<Object>();
        for (Map.Entry<Object, EntityMetadata> entry : managedEntities.entrySet()) {
            if (isDirty(entry.getKey(), entry.getValue())) {
                dirty.add(entry.getKey());
            }
        }
        return dirty;
    }

    public void clear() {
        snapshots.clear();
    }

    public void evict(Object entity) {
        snapshots.remove(System.identityHashCode(entity));
    }
}
