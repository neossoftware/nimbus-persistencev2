# Nimbus Persistence v2

ORM ligero para Java 8 con API 100% compatible con Hibernate 5.  
Un único JAR de **106 KB** sin dependencias externas en runtime.

---

## Por qué existe este framework

### El problema real: findings críticos en Nexus IQ / Sonatype

En entornos regulados (banca, gobierno, salud) los artefactos de terceros pasan por
escáneres de vulnerabilidades como **Nexus IQ Server** o **Fortify** antes de llegar
a producción. **Hibernate 4 y Hibernate 5 acumulan findings críticos** en esos
escáneres — no solo en el JAR principal sino en toda su cadena de dependencias
transitivas:

| Componente | CVEs conocidos (ejemplos) | Severidad |
|---|---|---|
| `hibernate-core` | CVE-2019-14900, CVE-2020-25638 | Critical / High |
| `dom4j` (transitivo) | CVE-2018-1000632, CVE-2020-10683 | Critical |
| `javassist` (transitivo) | CVE-2022-41853 | High |
| `antlr` (transitivo) | Múltiples advisories | Medium / High |
| `byte-buddy` (transitivo) | Advisories activos | Medium |

Un solo finding crítico en Nexus IQ puede **bloquear el despliegue a producción**
y detonar una auditoría formal de seguridad. Con Hibernate, ese riesgo es permanente
porque los CVEs se acumulan en versiones que ya no tienen soporte activo (Hibernate 4)
o que no se pueden actualizar (Hibernate 5 → 6 requiere Java 11, imposible en WAS z/OS).

### El segundo problema: no hay ruta de salida

Hibernate 6 resuelve muchos CVEs pero requiere **Java 11 como mínimo**. WAS en
z/OS mainframe corre **Java 8** — la versión que IBM soporta en esa plataforma.
No hay hoja de ruta a corto plazo para subir de versión en ese entorno.

Esto atrapa a los equipos en un ciclo sin salida:

| Opción | Problema |
|---|---|
| Hibernate 4 / 5 | Findings críticos en Nexus IQ — riesgo de auditoría |
| Hibernate 6 | Requiere Java 11 — **imposible en WAS z/OS** |
| JPA puro (EclipseLink) | Dependencias propias con findings similares |
| JDBC puro | Sin ORM, todo SQL a mano, regresión de productividad |

**Nimbus Persistence v2 corta el nudo**: cero dependencias de terceros en runtime
significa **cero findings de terceros en Nexus IQ**. El único artefacto que el
escáner analiza es el JAR propio del equipo — 106 KB de código que el equipo
mismo escribió y puede auditar.

---

## El mismo API, sin el equipaje

El shim `org.hibernate.*` incluido en el JAR replica las interfaces de Hibernate 5.
El código de negocio existente **no cambia**:

```java
// Este código funciona igual con Hibernate 5 y con Nimbus — sin modificar nada
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

SessionFactory sf = new Configuration().configure().buildSessionFactory();
Session s = sf.openSession();
s.beginTransaction();
MyEntity e = s.get(MyEntity.class, 1L);
s.getTransaction().commit();
s.close();
```

No hay refactor. No hay regresiones. El switch es transparente para los equipos.

---

## Comparativa vs Hibernate 5 en producción

| | Hibernate 5 | Nimbus Persistence v2 |
|---|---|---|
| Java mínimo requerido | 8 | **8** |
| Peso del JAR | ~7 MB | **106 KB** |
| JARs transitivos | ~18 (Antlr, ByteBuddy, Javassist, ...) | **0** |
| CVEs a auditar (Fortify / Nexus IQ) | Toda la cadena transitiva | **Solo el JAR propio** |
| Conflictos classloader en WAS | Frecuentes (JPA bundleado vs Hibernate) | **Ninguno** |
| Soporte JNDI DataSource nativo | Requiere configuración extra | **Nativo** |
| DB2 `FETCH FIRST n ROWS ONLY` | Configuración de dialecto Hibernate | **Nativo** |
| Líneas de código auditables | ~500,000+ | **~5,100** |
| Depurable por el equipo en producción | No en la práctica | **Sí** |

> **Para entornos regulados (banca, gobierno, salud)**: pasar 18 JARs por Fortify/Nexus IQ
> puede tomar semanas. Nimbus pasa en horas — solo hay un artefacto que revisar.

---

## Contenido

- [Características](#características)
- [Instalación](#instalación)
- [Configuración](#configuración)
  - [Modo DriverManager (desarrollo)](#modo-drivermanager-desarrollo)
  - [Modo JNDI DataSource (WAS / JEE)](#modo-jndi-datasource-was--jee)
- [Mapeo de entidades](#mapeo-de-entidades)
  - [Anotaciones JPA](#anotaciones-jpa)
  - [HBM XML](#hbm-xml)
- [Operaciones CRUD](#operaciones-crud)
- [Queries](#queries)
  - [HQL](#hql)
  - [SQL nativo](#sql-nativo)
  - [Criteria API](#criteria-api)
- [Relaciones](#relaciones)
- [PKs compuestas (@IdClass)](#pks-compuestas-idclass)
- [Sesión con caché L1 (v2)](#sesión-con-caché-l1-v2)
- [Dialectos soportados](#dialectos-soportados)
- [Estructura del proyecto](#estructura-del-proyecto)

---

## Características

| Característica | Soporte |
|---|---|
| Mapeo por anotaciones JPA (`@Entity`, `@Table`, `@Id`, `@Column`, ...) | ✓ |
| Mapeo por HBM XML (`<hibernate-mapping>`) | ✓ |
| CRUD: `save`, `get`, `update`, `delete`, `saveOrUpdate` | ✓ |
| PK simple (asignada o `@GeneratedValue IDENTITY`) | ✓ |
| PK compuesta (`@IdClass`) | ✓ |
| Relaciones `@ManyToOne` (EAGER/LAZY), `@OneToMany` | ✓ |
| Shared PK/FK (`@JoinColumn(insertable=false)`) | ✓ |
| HQL básico (`FROM`, `WHERE`, `ORDER BY`, `COUNT(*)`, join paths) | ✓ |
| SQL nativo (`createSQLQuery`) | ✓ |
| Criteria API (`createCriteria`, `Restrictions`, `Order`) | ✓ |
| DDL automático (`hbm2ddl.auto`: `create`, `update`, `validate`) | ✓ |
| Conexión vía `DriverManager` (desarrollo / H2 / PostgreSQL) | ✓ |
| Conexión vía JNDI `DataSource` (WAS / JEE) | ✓ |
| Caché L1 por sesión + Dirty Tracking (v2) | ✓ |
| `@NamedQuery` (v2) | ✓ |
| `schema.default_schema` prefijo de esquema | ✓ |
| Java 8+ | ✓ |

---

## Instalación

### Maven

```xml
<dependency>
    <groupId>com.nimbus</groupId>
    <artifactId>nimbus-persistencev2</artifactId>
    <version>2.0.0</version>
</dependency>
```

La única dependencia en `provided` es la API JPA 2.2 — en WAS ya viene incluida en el servidor.

```xml
<dependency>
    <groupId>javax.persistence</groupId>
    <artifactId>javax.persistence-api</artifactId>
    <version>2.2</version>
    <scope>provided</scope>
</dependency>
```

---

## Configuración

### Modo DriverManager (desarrollo)

`hibernate.cfg.xml` en el classpath:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE hibernate-configuration PUBLIC
    "-//Hibernate/Hibernate Configuration DTD 3.0//EN"
    "http://www.hibernate.org/dtd/hibernate-configuration-3.0.dtd">
<hibernate-configuration>
  <session-factory>
    <property name="hibernate.connection.driver_class">org.h2.Driver</property>
    <property name="hibernate.connection.url">jdbc:h2:mem:mydb;DB_CLOSE_DELAY=-1</property>
    <property name="hibernate.connection.username">sa</property>
    <property name="hibernate.connection.password"></property>
    <property name="hibernate.dialect">org.hibernate.dialect.H2Dialect</property>
    <property name="hibernate.show_sql">true</property>

    <mapping class="com.example.MyEntity"/>
    <mapping resource="hbm/my_entity.hbm.xml"/>
  </session-factory>
</hibernate-configuration>
```

### Modo JNDI DataSource (WAS / JEE)

```xml
<hibernate-configuration>
  <session-factory>
    <property name="hibernate.connection.datasource">java:comp/env/jdbc/MyDataSource</property>
    <property name="hibernate.dialect">org.hibernate.dialect.DB2390Dialect</property>
    <property name="hibernate.show_sql">false</property>

    <mapping class="com.example.MyEntity"/>
  </session-factory>
</hibernate-configuration>
```

> El DataSource se resuelve vía JNDI **una sola vez** al construir el `SessionFactory`.  
> Cada `openSession()` solo invoca `dataSource.getConnection()`.  
> El resource reference `jdbc/MyDataSource` debe estar declarado en `web.xml` e `ibm-web-bnd.xml`.

### Inicialización en código

```java
SessionFactory sf = new Configuration()
        .configure("hibernate.cfg.xml")   // o ruta alternativa
        .buildSessionFactory();
```

También se puede configurar sin XML:

```java
SessionFactory sf = new Configuration()
        .setProperty("hibernate.connection.url", "jdbc:h2:mem:test")
        .setProperty("hibernate.dialect", "H2")
        .addAnnotatedClass(MyEntity.class)
        .buildSessionFactory();
```

---

## Mapeo de entidades

### Anotaciones JPA

```java
import javax.persistence.*;

@Entity
@Table(name = "TC_CUSTOMER")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_CUSTOMER")
    private Long id;

    @Column(name = "DES_NAME", nullable = false, length = 100)
    private String name;

    @ManyToOne
    @JoinColumn(name = "CVE_SEGMENT")
    private Segment segment;

    // getters / setters
}
```

Anotaciones propias de Nimbus también soportadas:  
`com.nimbus.persistence.annotation.{Entity, Table, Id, Column, GeneratedValue, ManyToOne, OneToMany, JoinColumn, Transient, MappedSuperclass}`

### HBM XML

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE hibernate-mapping PUBLIC
    "-//Hibernate/Hibernate Mapping DTD 3.0//EN"
    "http://www.hibernate.org/dtd/hibernate-mapping-3.0.dtd">
<hibernate-mapping>
  <class name="com.example.Customer" table="TC_CUSTOMER">
    <id name="id" column="ID_CUSTOMER" type="long">
      <generator class="identity"/>
    </id>
    <property name="name" column="DES_NAME" type="string" length="100" not-null="true"/>
    <many-to-one name="segment" column="CVE_SEGMENT" class="com.example.Segment"/>
  </class>
</hibernate-mapping>
```

Se puede mezclar: algunas entidades en HBM XML y otras con anotaciones en el mismo `SessionFactory`.

---

## Operaciones CRUD

```java
Session s = sf.openSession();
Transaction tx = s.beginTransaction();

// INSERT
Long id = (Long) s.save(new Customer("Juan", segment));

// SELECT por PK
Customer c = s.get(Customer.class, id);

// UPDATE
c.setName("Juan Actualizado");
s.update(c);

// INSERT o UPDATE según si la PK está asignada
s.saveOrUpdate(c);

// DELETE
s.delete(c);

tx.commit();
s.close();
```

---

## Queries

### HQL

```java
// Lista completa
List<Customer> all = s.createQuery("FROM Customer").list();

// Filtro con parámetro nombrado
List<Customer> result = s.createQuery(
        "FROM Customer WHERE name = :name")
        .setParameter("name", "Juan")
        .list();

// Resultado único
Customer one = (Customer) s.createQuery(
        "FROM Customer WHERE id = :id")
        .setParameter("id", 1L)
        .uniqueResult();

// Count
Long total = (Long) s.createQuery("SELECT COUNT(*) FROM Customer").uniqueResult();

// Join path (navega relación)
List<?> list = s.createQuery(
        "FROM Customer WHERE segment.cveSegmento = :seg")
        .setParameter("seg", 1).list();

// Order by
List<Customer> ordered = s.createQuery(
        "FROM Customer ORDER BY name ASC").list();
```

> **Nota**: usar `COUNT(*)` en lugar de `COUNT(alias)` — Nimbus no resuelve alias de entidad en funciones de agregación.

### SQL nativo

```java
// SELECT
Number count = (Number) s.createSQLQuery(
        "SELECT COUNT(*) FROM TC_CUSTOMER").uniqueResult();

// JOIN nativo
List<?> rows = s.createSQLQuery(
        "SELECT c.ID_CUSTOMER, c.DES_NAME, s.DESC_SEGMENTO " +
        "FROM TC_CUSTOMER c JOIN TC_SEGMENT s ON c.CVE_SEGMENT = s.CVE_SEGMENT")
        .list();

// UPDATE nativo (requiere transacción activa)
int affected = s.createSQLQuery(
        "UPDATE TC_CUSTOMER SET DES_NAME = 'Nuevo' WHERE ID_CUSTOMER = 1")
        .executeUpdate();

// DELETE nativo
s.createSQLQuery("DELETE FROM TC_CUSTOMER WHERE ID_CUSTOMER = 99")
        .executeUpdate();
```

### Criteria API

```java
import com.nimbus.persistence.Criteria;
import com.nimbus.persistence.Restrictions;
import com.nimbus.persistence.Order;

List<Customer> list = s.createCriteria(Customer.class)
        .add(Restrictions.eq("name", "Juan"))
        .add(Restrictions.like("name", "Ju%"))
        .add(Restrictions.gt("id", 0L))
        .add(Restrictions.isNotNull("segment"))
        .add(Restrictions.between("id", 1L, 100L))
        .add(Restrictions.in("id", Arrays.asList(1L, 2L, 3L)))
        .addOrder(Order.asc("name"))
        .setMaxResults(10)
        .list();
```

---

## Relaciones

### `@ManyToOne` EAGER (default)

```java
@ManyToOne
@JoinColumn(name = "CVE_SEGMENT")
private Segment segment;
```

Al hacer `get()`, la entidad relacionada se carga automáticamente con un SELECT adicional.

### `@ManyToOne` LAZY

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "CVE_SEGMENT")
private Segment segment;
```

### `@OneToMany`

```java
@OneToMany(mappedBy = "customer")
private List<Order> orders;
```

### Shared PK/FK (PK = FK)

Cuando la FK es la misma columna que la PK de la entidad hija:

```java
@Entity
@Table(name = "TC_ENTITY_DETAIL")
public class EntityDetail {

    @Id
    @Column(name = "ID_PARENT")
    private Long idParent;             // misma columna que la FK

    @ManyToOne
    @JoinColumn(name = "ID_PARENT", insertable = false, updatable = false)
    private ParentEntity parent;       // insertable=false evita columna duplicada en INSERT/DDL
}
```

---

## PKs compuestas (@IdClass)

### 1. Clase PK

```java
public class OrderItemPK implements Serializable {
    private Long orderId;
    private Integer itemSeq;

    // equals() y hashCode() obligatorios
    @Override public boolean equals(Object o) { ... }
    @Override public int hashCode() { ... }
}
```

### 2. Entidad con `@IdClass`

```java
@Entity
@Table(name = "TC_ORDER_ITEM")
@IdClass(OrderItemPK.class)
public class OrderItem {

    @Id
    @Column(name = "ORDER_ID")
    private Long orderId;

    @Id
    @Column(name = "ITEM_SEQ")
    private Integer itemSeq;

    @Column(name = "DES_PRODUCT")
    private String product;
}
```

### 3. Uso

```java
// save
s.save(new OrderItem(100L, 1, "Laptop"));

// get por PK compuesta
OrderItemPK pk = new OrderItemPK();
pk.setOrderId(100L);
pk.setItemSeq(1);
OrderItem item = s.get(OrderItem.class, pk);
```

---

## Sesión con caché L1 (v2)

```java
import com.nimbus.persistence.v2.CachingSessionFactory;

// Construir con CachingSessionFactory
CachingSessionFactory csf = new com.nimbus.persistence.v2.CachingSessionFactory(
        properties, metadataMap, entityRegistry, namedQueries);

com.nimbus.persistence.v2.CachingSession cs =
        (com.nimbus.persistence.v2.CachingSession) csf.openSession();

Transaction tx = cs.beginTransaction();

// Primer get → va a DB
Customer c = cs.get(Customer.class, 1L);

// Segundo get → L1 cache hit (no va a DB)
Customer c2 = cs.get(Customer.class, 1L);  // [L1-CACHE HIT]

// Modificar la entidad — el dirty tracker detecta el cambio
c.setName("Modificado");

// commit → DirtyTrackingTransaction hace UPDATE automático de entidades dirty
tx.commit();

// Stats de caché
CacheStats stats = cs.getCacheStats();
System.out.println("Hits: " + stats.getHits() + " / Misses: " + stats.getMisses());

// Evict manual
cs.evict(c);
cs.clear();  // limpia toda la sesión

cs.close();
```

### Named Queries (v2)

```java
// En la entidad
@NamedQuery(name = "Customer.byName",
            query = "FROM Customer WHERE name = :name")
@Entity
public class Customer { ... }

// En código
Query<Customer> q = cs.createNamedQuery("Customer.byName");
q.setParameter("name", "Juan");
List<Customer> result = q.list();
```

---

## Dialectos soportados

| Propiedad `hibernate.dialect` | Comportamiento de paginación |
|---|---|
| `org.hibernate.dialect.H2Dialect` | `LIMIT n` |
| `org.hibernate.dialect.PostgreSQLDialect` | `LIMIT n` |
| `org.hibernate.dialect.DB2Dialect` | `FETCH FIRST n ROWS ONLY` |
| `org.hibernate.dialect.DB2390Dialect` | `FETCH FIRST n ROWS ONLY` |
| Cualquier otro valor | `LIMIT n` (default) |

---

## Estructura del proyecto

```
nimbus-persistencev2/
├── pom.xml
└── src/main/java/
    ├── com/nimbus/persistence/
    │   ├── Configuration.java          ← carga XML/programática, buildSessionFactory()
    │   ├── SessionFactory.java         ← DriverManager o JNDI DataSource, SchemaExport
    │   ├── Session.java                ← save/get/update/delete/saveOrUpdate/createQuery/...
    │   ├── Transaction.java            ← begin/commit/rollback
    │   ├── Query.java                  ← HQL y SQL nativo, parámetros, list()/uniqueResult()
    │   ├── Criteria.java               ← Criteria API, Restrictions, Order
    │   ├── Restrictions.java           ← eq/ne/gt/lt/like/in/between/isNull/...
    │   ├── Order.java                  ← asc/desc
    │   ├── SchemaExport.java           ← DDL create/update/validate/drop
    │   ├── annotation/                 ← @Entity @Table @Id @Column @GeneratedValue ...
    │   ├── mapping/
    │   │   ├── EntityMetadata.java     ← introspección de clase → tabla/columnas/relaciones
    │   │   ├── ColumnMetadata.java     ← field → columna SQL, tipos, @Lob, @Temporal
    │   │   └── RelationMetadata.java   ← @ManyToOne/@OneToMany, insertable, fetchType
    │   ├── hql/
    │   │   ├── HqlParser.java          ← HQL → SQL (FROM/WHERE/ORDER/COUNT/join path)
    │   │   └── HbmXmlParser.java       ← parseo de archivos .hbm.xml
    │   ├── dialect/
    │   │   └── NimbusDialect.java      ← H2 / PostgreSQL / DB2390
    │   ├── exception/
    │   │   ├── NimbusPersistenceException.java
    │   │   └── NonUniqueResultException.java
    │   ├── util/
    │   │   └── ReflectionUtils.java    ← camelToSnake, getAllFields
    │   └── v2/
    │       ├── CachingSessionFactory.java
    │       ├── CachingSession.java     ← L1 cache + dirty tracking + @NamedQuery
    │       ├── DirtyTracker.java       ← snapshot de campos para detectar cambios
    │       ├── DirtyTrackingTransaction.java
    │       ├── CacheStats.java
    │       └── annotation/
    │           ├── NamedQuery.java
    │           └── NamedQueries.java
    └── org/hibernate/                  ← shim de compatibilidad con Hibernate API
        ├── Session.java
        ├── SessionFactory.java
        ├── Transaction.java
        ├── Query.java
        ├── Criteria.java
        ├── HibernateException.java
        ├── NonUniqueResultException.java
        ├── cfg/Configuration.java
        └── criterion/
            ├── Restrictions.java
            └── Order.java
```

---

## Argumento para arquitectura / liderazgo técnico

Si el equipo necesita justificar la adopción ante un comité técnico o un arquitecto,
estos son los puntos de decisión clave:

### 1. No es una preferencia — es seguridad y restricción de plataforma

Hibernate 4 y 5 tienen findings críticos en Nexus IQ que bloquean despliegues.
Hibernate 6 los resuelve pero requiere Java 11 — WAS z/OS corre Java 8 y no tiene
hoja de ruta para subir. No hay decisión técnica que tomar: la plataforma y el
proceso de seguridad juntos cierran todas las puertas excepto una.

### 2. Cero findings de terceros en Nexus IQ

Hibernate 4 y 5 tienen CVEs críticos documentados en el JAR principal y en sus
~18 dependencias transitivas. Cada CVE puede bloquear un despliegue o generar una
auditoría formal. Nimbus tiene **cero dependencias en runtime** — el escáner solo
analiza el código que el equipo escribió. No hay CVEs heredados de terceros que
gestionar.

### 3. El equipo puede depurarlo

Si hay un problema en producción a las 2am, el equipo puede abrir el código fuente de
Nimbus y leerlo completo en minutos — son 5,100 líneas. Nadie puede hacer eso con
Hibernate. La capacidad de depurar el ORM directamente reduce el tiempo de resolución
de incidentes.

### 4. Cero riesgo de regresión en el código existente

El shim `org.hibernate.*` replica el API de Hibernate 5 con los mismos nombres de clase,
mismos métodos, misma firma. Cambiar el JAR en el `pom.xml` es la única modificación
necesaria. No se toca el código de negocio.

### 5. Diseñado para el entorno, no adaptado

JNDI DataSource (`java:comp/env/jdbc/...`) y la sintaxis `FETCH FIRST n ROWS ONLY` de
DB2 for z/OS son ciudadanos de primera clase en Nimbus — no son workarounds ni
configuraciones especiales. Hibernate fue diseñado para ambientes JEE genéricos y
adaptado a WAS; Nimbus fue construido para ese contexto desde el inicio.

---

## Limitaciones conocidas

- `COUNT(alias)` en HQL no resuelve el alias → usar `COUNT(*)`.
- No soporta `@OneToOne` bidireccional ni `@ManyToMany` con anotaciones (solo HBM XML).
- Sin pool de conexiones propio — usar el pool del servidor (WAS) o añadir HikariCP/c3p0 como `DataSource`.
- `hbm2ddl.auto` genera DDL en sintaxis H2/PostgreSQL; no apto para DB2 en producción (usar `none`).
- HQL no soporta subconsultas ni funciones de agregación complejas (`GROUP BY`, `HAVING`).

---

## Compatibilidad

| Entorno | Versión mínima |
|---|---|
| Java | 8 |
| WAS | 8.5.5 |
| H2 | 2.x |
| PostgreSQL | 12+ |
| DB2 for z/OS | 12+ |
