package com.nimbus.persistence.hql;

import com.nimbus.persistence.exception.NimbusPersistenceException;
import com.nimbus.persistence.mapping.EntityMetadata;
import com.nimbus.persistence.mapping.RelationMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simplified HQL parser that converts HQL queries to SQL.
 * Supports common HQL patterns used with Hibernate 5.
 */
public class HqlParser {

    private final Map<Class<?>, EntityMetadata> metadataMap;
    private final Map<String, Class<?>> entityRegistry;

    private String sql;
    private Class<?> resultType;
    private boolean isCount;
    private boolean isNativeCount;
    private boolean isDelete;
    private boolean isUpdate;
    private boolean isProjection;
    private String currentAlias = ""; // alias del FROM principal (ej: "bit")
    private final List<String> joinClauses = new ArrayList<String>(); // JOINs generados por path expressions
    private final Map<String, List<Integer>> paramPositions = new LinkedHashMap<String, List<Integer>>();

    public HqlParser(Map<Class<?>, EntityMetadata> metadataMap, Map<String, Class<?>> entityRegistry) {
        this.metadataMap = metadataMap;
        this.entityRegistry = entityRegistry;
    }

    public HqlParser parse(String hql) {
        if (hql == null || hql.trim().isEmpty()) {
            throw new NimbusPersistenceException("HQL query cannot be null or empty");
        }

        String trimmed = hql.trim();
        String upper = trimmed.toUpperCase();

        if (upper.startsWith("DELETE")) {
            parseDelete(trimmed);
        } else if (upper.startsWith("UPDATE")) {
            parseUpdate(trimmed);
        } else if (upper.startsWith("SELECT COUNT")) {
            parseSelectCount(trimmed);
        } else {
            parseSelectOrFrom(trimmed);
        }

        return this;
    }

    private void parseDelete(String hql) {
        isDelete = true;
        // DELETE [FROM] EntityName [alias] [WHERE ...]  — FROM es opcional en HQL
        Pattern p = Pattern.compile(
                "(?i)DELETE(?:\\s+FROM)?\\s+(\\w+)(?:\\s+(\\w+))?(?:\\s+WHERE\\s+(.+))?",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(hql.trim());
        if (!m.matches()) {
            throw new NimbusPersistenceException("Cannot parse DELETE HQL: " + hql);
        }
        String entityName = m.group(1);
        // group(2) = optional alias — not needed for DELETE SQL
        String whereClause = m.group(3);

        EntityMetadata meta = resolveEntity(entityName);
        resultType = meta.getEntityClass();

        StringBuilder sb = new StringBuilder("DELETE FROM ");
        sb.append(meta.getTableName());

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sb.append(" WHERE ");
            sb.append(translateCondition(whereClause.trim(), meta));
        }

        sql = sb.toString();
    }

    private void parseUpdate(String hql) {
        isUpdate = true;
        // UPDATE EntityName SET field = :val WHERE ...
        Pattern p = Pattern.compile(
                "(?i)UPDATE\\s+(\\w+)(?:\\s+\\w+)?\\s+SET\\s+(.+?)(?:\\s+WHERE\\s+(.+))?$",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(hql.trim());
        if (!m.matches()) {
            throw new NimbusPersistenceException("Cannot parse UPDATE HQL: " + hql);
        }
        String entityName = m.group(1);
        String setClause = m.group(2);
        String whereClause = m.group(3);

        EntityMetadata meta = resolveEntity(entityName);
        resultType = meta.getEntityClass();

        StringBuilder sb = new StringBuilder("UPDATE ");
        sb.append(meta.getTableName());
        sb.append(" SET ");
        sb.append(translateSetClause(setClause.trim(), meta));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sb.append(" WHERE ");
            sb.append(translateCondition(whereClause.trim(), meta));
        }

        sql = sb.toString();
    }

    private void parseSelectCount(String hql) {
        isCount = true;
        // SELECT COUNT(*) FROM EntityName [WHERE ...]
        // SELECT COUNT(field) FROM EntityName [WHERE ...]
        Pattern p = Pattern.compile(
                "(?i)SELECT\\s+COUNT\\s*\\(\\s*(\\*|\\w+)\\s*\\)\\s+FROM\\s+(\\w+)(?:\\s+(?:\\w+))?(?:\\s+WHERE\\s+(.+))?(?:\\s+ORDER\\s+BY\\s+(.+))?",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(hql.trim());
        if (!m.matches()) {
            // Try alternate: might be a native count query
            isCount = false;
            isNativeCount = true;
            sql = hql;
            return;
        }
        String countField = m.group(1);
        String entityName = m.group(2);
        String whereClause = m.group(3);

        EntityMetadata meta = resolveEntity(entityName);
        resultType = Long.class;

        String countExpr;
        if ("*".equals(countField)) {
            countExpr = "COUNT(*)";
        } else {
            String colName = meta.resolveColumnName(countField);
            countExpr = "COUNT(" + colName + ")";
        }

        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(countExpr);
        sb.append(" FROM ");
        sb.append(meta.getTableName());

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sb.append(" WHERE ");
            sb.append(translateCondition(whereClause.trim(), meta));
        }

        sql = sb.toString();
    }

    private void parseSelectOrFrom(String hql) {
        String working = hql.trim();

        if (working.toUpperCase().startsWith("SELECT")) {
            // Localiza el FROM principal (nivel 0 de paréntesis)
            int fromIdx = findMainFromIndex(working);
            if (fromIdx < 0) {
                throw new NimbusPersistenceException("FROM no encontrado en HQL: " + hql);
            }

            String selectClause = working.substring("SELECT".length(), fromIdx).trim();
            String fromAndRest  = working.substring(fromIdx).trim();

            // SELECT simple: una sola palabra (alias) → SELECT e FROM Entity
            // SELECT DISTINCT alias → también simple
            String effectiveClause = selectClause.toUpperCase().startsWith("DISTINCT")
                    ? selectClause.substring("DISTINCT".length()).trim()
                    : selectClause;

            if (effectiveClause.matches("\\w+")) {
                // Simple alias — descartar y parsear solo el FROM
                working = fromAndRest;
            } else {
                // Proyección compleja: CASE WHEN, agregados, multi-campo
                parseComplexSelect(selectClause, fromAndRest);
                return;
            }
        }

        // Parsear FROM clause
        Pattern fromPat = Pattern.compile(
                "(?i)FROM\\s+(\\w+)(?:\\s+(\\w+))?(?:\\s+WHERE\\s+(.+?))?(?:\\s+ORDER\\s+BY\\s+(.+))?$",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = fromPat.matcher(working.trim());
        if (!m.matches()) {
            throw new NimbusPersistenceException("No se puede parsear HQL: " + hql);
        }

        String entityName    = m.group(1);
        String alias         = m.group(2);
        String whereClause   = m.group(3);
        String orderByClause = m.group(4);

        EntityMetadata meta = resolveEntity(entityName);
        resultType = meta.getEntityClass();
        currentAlias = alias != null ? alias.trim() : "";

        // Traducir WHERE/ORDER BY PRIMERO para que resolvePathExpression()
        // pueble joinClauses antes de ensamblar el SQL.
        String whereSQL = null;
        if (whereClause != null && !whereClause.trim().isEmpty()) {
            whereSQL = translateCondition(whereClause.trim(), meta);
        }
        String orderBySQL = null;
        if (orderByClause != null && !orderByClause.trim().isEmpty()) {
            orderBySQL = translateOrderBy(orderByClause.trim(), meta);
        }

        StringBuilder sb = new StringBuilder("SELECT * FROM ");
        sb.append(meta.getTableName());
        if (currentAlias != null && !currentAlias.isEmpty()) {
            sb.append(" ").append(currentAlias);
        }
        for (String join : joinClauses) {
            sb.append(" ").append(join);
        }
        if (whereSQL != null) {
            sb.append(" WHERE ").append(whereSQL);
        }
        if (orderBySQL != null) {
            sb.append(" ORDER BY ").append(orderBySQL);
        }

        sql = sb.toString();
    }

    /**
     * Parsea SELECT con proyección compleja: CASE WHEN, agregados, múltiples campos.
     * El resultado es un escalar u Object[], no una entidad mapeada.
     */
    private void parseComplexSelect(String selectClause, String fromAndRest) {
        Pattern fromPat = Pattern.compile(
                "(?i)FROM\\s+(\\w+)(?:\\s+(\\w+))?(?:\\s+WHERE\\s+(.+?))?(?:\\s+GROUP\\s+BY\\s+(.+?))?(?:\\s+HAVING\\s+(.+?))?(?:\\s+ORDER\\s+BY\\s+(.+))?$",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = fromPat.matcher(fromAndRest.trim());
        if (!m.matches()) {
            throw new NimbusPersistenceException("No se puede parsear FROM en proyección compleja: " + fromAndRest);
        }

        String entityName    = m.group(1);
        String alias         = m.group(2);
        String whereClause   = m.group(3);
        String groupByClause = m.group(4);
        String havingClause  = m.group(5);
        String orderByClause = m.group(6);

        EntityMetadata meta = resolveEntity(entityName);
        isProjection = true;
        resultType = null;

        // Registrar alias para que removeAliasPrefix solo quite un nivel
        currentAlias = alias != null ? alias.trim() : "";

        // SELECT se traduce primero (puede generar JOINs por path expressions)
        String translatedSelect = translateSelectProjection(selectClause, alias, meta);

        // Luego traducir las demás cláusulas para capturar JOINs adicionales del WHERE
        String whereSQL    = whereClause    != null && !whereClause.trim().isEmpty()
                ? translateCondition(whereClause.trim(), meta) : null;
        String groupBySQL  = groupByClause  != null && !groupByClause.trim().isEmpty()
                ? translateGroupBy(groupByClause.trim(), alias, meta) : null;
        String havingSQL   = havingClause   != null && !havingClause.trim().isEmpty()
                ? translateCondition(havingClause.trim(), meta) : null;
        String orderBySQL  = orderByClause  != null && !orderByClause.trim().isEmpty()
                ? translateOrderBy(orderByClause.trim(), meta) : null;

        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(translatedSelect);
        sb.append(" FROM ").append(meta.getTableName());
        if (currentAlias != null && !currentAlias.isEmpty()) {
            sb.append(" ").append(currentAlias);
        }
        for (String join : joinClauses) {
            sb.append(" ").append(join);
        }
        if (whereSQL   != null) sb.append(" WHERE ").append(whereSQL);
        if (groupBySQL != null) sb.append(" GROUP BY ").append(groupBySQL);
        if (havingSQL  != null) sb.append(" HAVING ").append(havingSQL);
        if (orderBySQL != null) sb.append(" ORDER BY ").append(orderBySQL);

        sql = sb.toString();
    }

    /**
     * Traduce una proyección SELECT compleja: elimina el alias y resuelve paths
     * simples y multi-nivel (alias.rel.campo → JOIN + tabla_join.columna).
     */
    private String translateSelectProjection(String projection, String alias, EntityMetadata meta) {
        String result = projection;
        if (alias != null && !alias.trim().isEmpty()) {
            String a = alias.trim();
            // Captura alias.path donde path puede ser multi-nivel: alias.rel.campo
            Pattern pathPat = Pattern.compile(
                    "(?i)\\b" + Pattern.quote(a) + "\\.(\\w+(?:\\.\\w+)*)");
            StringBuffer sb = new StringBuffer();
            Matcher m = pathPat.matcher(result);
            while (m.find()) {
                String path     = m.group(1); // "campo" o "relacion.campo"
                String resolved = resolvePathExpression(path, meta, null);
                m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
            }
            m.appendTail(sb);
            result = sb.toString();
        }
        return result;
    }

    /**
     * Resuelve un campo simple o path multi-nivel generando JOINs cuando es necesario.
     * campo        → columna
     * rel.campo    → JOIN tabla_rel jrel ON ... / jrel.columna
     * rel.rel2.campo → JOIN anidados
     */
    private String resolveField(String fieldOrPath, EntityMetadata meta) {
        if (!fieldOrPath.contains(".")) {
            return meta.resolveColumnName(fieldOrPath);
        }
        return resolvePathExpression(fieldOrPath, meta, null);
    }

    private String resolvePathExpression(String dotPath, EntityMetadata meta, String tablePrefix) {
        int dot = dotPath.indexOf('.');
        if (dot < 0) {
            String col = meta.resolveColumnName(dotPath);
            return tablePrefix != null ? tablePrefix + "." + col : col;
        }

        String relationField = dotPath.substring(0, dot);
        String remaining     = dotPath.substring(dot + 1);

        RelationMetadata rel = findRelationByField(meta, relationField);
        if (rel == null) {
            // No es una relación conocida — convertir todo a snake_case como fallback
            String col = meta.resolveColumnName(dotPath.replace('.', '_'));
            return tablePrefix != null ? tablePrefix + "." + col : col;
        }

        Class<?> relClass = rel.getField().getType();
        EntityMetadata relMeta = metadataMap.get(relClass);
        if (relMeta == null) {
            String col = meta.resolveColumnName(dotPath.replace('.', '_'));
            return tablePrefix != null ? tablePrefix + "." + col : col;
        }

        String joinAlias  = "j_" + relationField.toLowerCase();
        String ownerTable;
        if (tablePrefix != null) {
            ownerTable = tablePrefix;
        } else if (currentAlias != null && !currentAlias.isEmpty()) {
            ownerTable = currentAlias;
        } else {
            ownerTable = meta.getTableName();
        }
        String joinSql    = "JOIN " + relMeta.getTableName() + " " + joinAlias
                + " ON " + joinAlias + "." + relMeta.getPkColumn().getColumnName()
                + " = " + ownerTable + "." + rel.getJoinColumnName();
        if (!joinClauses.contains(joinSql)) {
            joinClauses.add(joinSql);
        }

        return resolvePathExpression(remaining, relMeta, joinAlias);
    }

    private RelationMetadata findRelationByField(EntityMetadata meta, String fieldName) {
        for (RelationMetadata rel : meta.getRelations()) {
            if (rel.getField().getName().equalsIgnoreCase(fieldName)) {
                return rel;
            }
        }
        return null;
    }

    /**
     * Traduce GROUP BY eliminando prefijos de alias y convirtiendo a nombres de columna.
     */
    private String translateGroupBy(String groupBy, String alias, EntityMetadata meta) {
        String cleaned = alias != null ? removeSpecificAliasPrefix(groupBy, alias) : groupBy;
        String[] parts = cleaned.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(meta.resolveColumnName(parts[i].trim()));
        }
        return sb.toString();
    }

    private String removeSpecificAliasPrefix(String expr, String alias) {
        return expr.replaceAll("(?i)\\b" + Pattern.quote(alias) + "\\.(\\w+)", "$1");
    }

    /**
     * Encuentra el índice del FROM principal (nivel 0 de paréntesis) en la query.
     * Ignora FROM que aparezcan dentro de paréntesis (subqueries, funciones).
     */
    private int findMainFromIndex(String hql) {
        String upper = hql.toUpperCase();
        int depth = 0;
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && i + 5 <= upper.length()) {
                // Busca " FROM " o inicio de FROM con espacio/tab antes
                String chunk = upper.substring(i, Math.min(i + 6, upper.length()));
                if (chunk.matches("\\sFROM\\s.*") || (i == 0 && chunk.startsWith("FROM "))) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Translates an HQL WHERE condition to SQL.
     * Handles: field = :p, field > :p, field LIKE :p, field IS NULL, etc.
     */
    private String translateCondition(String hqlCondition, EntityMetadata meta) {
        // Remove alias prefix if present (e.g. "e.fieldName" -> "fieldName")
        String condition = removeAliasPrefix(hqlCondition);

        // Split by AND/OR preserving them
        StringBuilder result = new StringBuilder();
        // Simple token-based approach
        String[] andParts = condition.split("(?i)\\s+AND\\s+");
        for (int i = 0; i < andParts.length; i++) {
            if (i > 0) {
                result.append(" AND ");
            }
            String[] orParts = andParts[i].split("(?i)\\s+OR\\s+");
            if (orParts.length > 1) {
                result.append("(");
                for (int j = 0; j < orParts.length; j++) {
                    if (j > 0) {
                        result.append(" OR ");
                    }
                    result.append(translateSingleCondition(orParts[j].trim(), meta));
                }
                result.append(")");
            } else {
                result.append(translateSingleCondition(andParts[i].trim(), meta));
            }
        }
        return result.toString();
    }

    private String removeAliasPrefix(String expr) {
        if (currentAlias != null && !currentAlias.isEmpty()) {
            // Elimina solo el alias específico (un nivel): bit.campo → campo, bit.rel.campo → rel.campo
            return expr.replaceAll("(?i)\\b" + Pattern.quote(currentAlias) + "\\.(\\w+)", "$1");
        }
        // Sin alias: no tocar nada — los paths (segmento.cveSeg) son relaciones reales
        // y los resuelve resolvePathExpression() en translateSingleCondition().
        return expr;
    }

    private String translateSingleCondition(String condition, EntityMetadata meta) {
        condition = condition.trim();

        // Patrón reutilizable: campo simple O path multi-nivel (rel.campo, rel.rel2.campo)
        final String PATH = "([\\w]+(?:\\.[\\w]+)*)";
        final String OPS  = "(=|!=|<>|>=|<=|>|<)";

        // IS NOT NULL
        Pattern isNotNull = Pattern.compile("(?i)" + PATH + "\\s+IS\\s+NOT\\s+NULL");
        Matcher m = isNotNull.matcher(condition);
        if (m.matches()) {
            return resolveField(m.group(1), meta) + " IS NOT NULL";
        }

        // IS NULL
        Pattern isNull = Pattern.compile("(?i)" + PATH + "\\s+IS\\s+NULL");
        m = isNull.matcher(condition);
        if (m.matches()) {
            return resolveField(m.group(1), meta) + " IS NULL";
        }

        // field LIKE :param or field NOT LIKE :param
        Pattern likePattern = Pattern.compile("(?i)" + PATH + "\\s+(NOT\\s+)?LIKE\\s+:(\\w+)");
        m = likePattern.matcher(condition);
        if (m.matches()) {
            String col  = resolveField(m.group(1), meta);
            String not  = m.group(2) != null ? "NOT " : "";
            String paramName = m.group(3);
            registerParam(paramName);
            return col + " " + not + "LIKE ?";
        }

        // field IN (:param)
        Pattern inPattern = Pattern.compile("(?i)" + PATH + "\\s+IN\\s*\\(\\s*:(\\w+)\\s*\\)");
        m = inPattern.matcher(condition);
        if (m.matches()) {
            String col = resolveField(m.group(1), meta);
            String paramName = m.group(2);
            registerParam(paramName);
            return col + " IN (?)";
        }

        // function(field) operator :param  — trim(campo) = :p, lower(campo) = :p
        Pattern funcOpParam = Pattern.compile(
                "(?i)(\\w+)\\s*\\(\\s*(\\w+)\\s*\\)\\s*" + OPS + "\\s*:(\\w+)");
        m = funcOpParam.matcher(condition);
        if (m.matches()) {
            String func = m.group(1).toUpperCase();
            String col  = meta.resolveColumnName(m.group(2));
            String op   = m.group(3);
            String paramName = m.group(4);
            registerParam(paramName);
            return func + "(" + col + ") " + op + " ?";
        }

        // function(field) operator literal
        Pattern funcOpLiteral = Pattern.compile(
                "(?i)(\\w+)\\s*\\(\\s*(\\w+)\\s*\\)\\s*" + OPS + "\\s*(.+)");
        m = funcOpLiteral.matcher(condition);
        if (m.matches()) {
            String func    = m.group(1).toUpperCase();
            String col     = meta.resolveColumnName(m.group(2));
            String op      = m.group(3);
            String literal = m.group(4).trim();
            return func + "(" + col + ") " + op + " " + literal;
        }

        // path operator :param — cubre: campo=:p  Y  rel.campo=:p  Y  rel.rel2.campo=:p
        Pattern opPattern = Pattern.compile("(?i)" + PATH + "\\s*" + OPS + "\\s*:(\\w+)");
        m = opPattern.matcher(condition);
        if (m.matches()) {
            String col = resolveField(m.group(1), meta);
            String op  = m.group(2);
            String paramName = m.group(3);
            registerParam(paramName);
            return col + " " + op + " ?";
        }

        // path operator literal
        Pattern literalPattern = Pattern.compile("(?i)" + PATH + "\\s*" + OPS + "\\s*(.+)");
        m = literalPattern.matcher(condition);
        if (m.matches()) {
            String col     = resolveField(m.group(1), meta);
            String op      = m.group(2);
            String literal = m.group(3).trim();
            return col + " " + op + " " + literal;
        }

        return condition;
    }

    private String translateSetClause(String setClause, EntityMetadata meta) {
        String[] parts = setClause.split(",");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                result.append(", ");
            }
            String part = parts[i].trim();
            Pattern p = Pattern.compile("(?i)(\\w+)\\s*=\\s*:(\\w+)");
            Matcher m = p.matcher(part);
            if (m.matches()) {
                String col = meta.resolveColumnName(m.group(1));
                String paramName = m.group(2);
                registerParam(paramName);
                result.append(col).append(" = ?");
            } else {
                result.append(part);
            }
        }
        return result.toString();
    }

    private String translateOrderBy(String orderByClause, EntityMetadata meta) {
        String[] parts = orderByClause.split(",");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                result.append(", ");
            }
            String part = parts[i].trim();
            // Remove alias prefix
            part = removeAliasPrefix(part);
            Pattern p = Pattern.compile("(?i)(\\w+)(?:\\s+(ASC|DESC))?");
            Matcher m = p.matcher(part);
            if (m.matches()) {
                String col = meta.resolveColumnName(m.group(1));
                String dir = m.group(2) != null ? " " + m.group(2).toUpperCase() : " ASC";
                result.append(col).append(dir);
            } else {
                result.append(part);
            }
        }
        return result.toString();
    }

    private void registerParam(String paramName) {
        int nextPos = countCurrentParams() + 1;
        List<Integer> positions = paramPositions.get(paramName);
        if (positions == null) {
            positions = new ArrayList<Integer>();
            paramPositions.put(paramName, positions);
        }
        positions.add(nextPos);
    }

    private int countCurrentParams() {
        int count = 0;
        for (List<Integer> positions : paramPositions.values()) {
            count += positions.size();
        }
        return count;
    }

    private EntityMetadata resolveEntity(String entityName) {
        // 1. Exact match en el registro
        Class<?> clazz = entityRegistry.get(entityName);
        if (clazz != null) {
            EntityMetadata meta = metadataMap.get(clazz);
            if (meta != null) return meta;
        }

        // 2. Exact match por simple class name
        for (Map.Entry<Class<?>, EntityMetadata> entry : metadataMap.entrySet()) {
            if (entry.getKey().getSimpleName().equals(entityName)) {
                return entry.getValue();
            }
        }

        // 3. Case-insensitive — cubre código legado con mayúsculas inconsistentes (FOlio, folio, FOLIO)
        String entityNameLower = entityName.toLowerCase();
        for (Map.Entry<String, Class<?>> entry : entityRegistry.entrySet()) {
            if (entry.getKey().toLowerCase().equals(entityNameLower)) {
                EntityMetadata meta = metadataMap.get(entry.getValue());
                if (meta != null) return meta;
            }
        }
        for (Map.Entry<Class<?>, EntityMetadata> entry : metadataMap.entrySet()) {
            if (entry.getKey().getSimpleName().toLowerCase().equals(entityNameLower)) {
                return entry.getValue();
            }
        }

        throw new NimbusPersistenceException(
                "Entity not found in registry: '" + entityName + "'. "
                + "Entities registradas: " + entityRegistry.keySet());
    }

    public String getSql() {
        return sql;
    }

    public Class<?> getResultType() {
        return resultType;
    }

    public boolean isCount() {
        return isCount;
    }

    public boolean isNativeCount() {
        return isNativeCount;
    }

    public boolean isDelete() {
        return isDelete;
    }

    public boolean isUpdate() {
        return isUpdate;
    }

    public boolean isProjection() {
        return isProjection;
    }

    public Map<String, List<Integer>> getParamPositions() {
        return paramPositions;
    }
}
