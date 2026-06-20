package com.nimbus.persistence.v2;

import com.nimbus.persistence.Configuration;
import com.nimbus.persistence.SessionFactory;
import com.nimbus.persistence.mapping.EntityMetadata;
import com.nimbus.persistence.v2.annotation.NamedQueries;
import com.nimbus.persistence.v2.annotation.NamedQuery;

import java.util.*;

/**
 * SessionFactory extendida que soporta:
 * - L1 cache (identity map) en cada CachingSession
 * - Dirty tracking automático en commit
 * - @NamedQuery registradas desde anotaciones
 */
public class CachingSessionFactory extends SessionFactory {

    /** Mapa de @NamedQuery: nombre → HQL */
    private final Map<String, String> namedQueries = new LinkedHashMap<String, String>();

    /**
     * Construye a partir de una Configuration.
     * Ejecuta DDL si hbm2ddl.auto lo indica (a través de buildSessionFactory),
     * luego reutiliza los mapas internos para no volver a ejecutar DDL.
     */
    public CachingSessionFactory(Configuration config) {
        // buildSessionFactory() ejecuta DDL según hbm2ddl.auto
        // Luego usamos sus mapas pero desactivamos DDL en el super() de esta clase
        this(config.buildSessionFactory(), config.getAnnotatedClasses());
    }

    /**
     * Constructor privado: reutiliza los mapas de un SessionFactory ya construido.
     * Pasa hbm2ddl.auto=none para evitar re-ejecución de DDL en este super().
     */
    private CachingSessionFactory(SessionFactory base, List<Class<?>> annotatedClasses) {
        super(withNoDdl(base.getProperties()), base.getMetadataMap(), base.getEntityRegistry());
        scanNamedQueries(annotatedClasses);
    }

    /**
     * Devuelve una copia del mapa de propiedades con hbm2ddl.auto=none.
     */
    private static Map<String, String> withNoDdl(Map<String, String> original) {
        Map<String, String> copy = new LinkedHashMap<String, String>(original);
        copy.put("hibernate.hbm2ddl.auto", "none");
        return copy;
    }

    /** Registra un @NamedQuery manualmente (útil para tests). */
    public CachingSessionFactory addNamedQuery(String name, String hql) {
        namedQueries.put(name, hql);
        return this;
    }

    @Override
    public CachingSession openSession() {
        return new CachingSession(openConnection(), metadataMap, entityRegistry, showSql, namedQueries);
    }

    private void scanNamedQueries(List<Class<?>> classes) {
        for (Class<?> clazz : classes) {
            // Nuestras anotaciones (com.nimbus.persistence.v2.annotation.*)
            NamedQuery single = clazz.getAnnotation(NamedQuery.class);
            if (single != null) {
                namedQueries.put(single.name(), single.query());
            }
            NamedQueries multi = clazz.getAnnotation(NamedQueries.class);
            if (multi != null) {
                for (NamedQuery nq : multi.value()) {
                    namedQueries.put(nq.name(), nq.query());
                }
            }

            // JPA estándar (javax.persistence.NamedQuery / NamedQueries)
            javax.persistence.NamedQuery jpaSingle =
                    clazz.getAnnotation(javax.persistence.NamedQuery.class);
            if (jpaSingle != null) {
                namedQueries.put(jpaSingle.name(), jpaSingle.query());
            }
            javax.persistence.NamedQueries jpaMulti =
                    clazz.getAnnotation(javax.persistence.NamedQueries.class);
            if (jpaMulti != null) {
                for (javax.persistence.NamedQuery nq : jpaMulti.value()) {
                    namedQueries.put(nq.name(), nq.query());
                }
            }
        }
    }

    public Map<String, String> getNamedQueries() {
        return Collections.unmodifiableMap(namedQueries);
    }
}
