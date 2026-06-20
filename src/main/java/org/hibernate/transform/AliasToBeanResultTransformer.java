package org.hibernate.transform;

import com.nimbus.persistence.exception.NimbusPersistenceException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public class AliasToBeanResultTransformer implements ResultTransformer {

    private final Class<?> beanClass;

    public AliasToBeanResultTransformer(Class<?> beanClass) {
        this.beanClass = beanClass;
    }

    @Override
    public Object transformTuple(Object[] tuple, String[] aliases) {
        try {
            Object bean = beanClass.newInstance();
            for (int i = 0; i < aliases.length; i++) {
                if (aliases[i] == null || tuple[i] == null) continue;
                String alias = aliases[i].trim();
                Object value = tuple[i];
                if (!trySetterMethod(bean, alias, value)) {
                    tryFieldDirect(bean, alias, value);
                }
            }
            return bean;
        } catch (InstantiationException | IllegalAccessException e) {
            throw new NimbusPersistenceException(
                    "AliasToBeanResultTransformer: cannot instantiate " + beanClass.getName()
                    + " — needs public no-arg constructor", e);
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List transformList(List collection) {
        return collection;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private boolean trySetterMethod(Object bean, String alias, Object value) {
        // Try setAlias(value) and setALIAS(value) — also converts snake_case to camelCase
        String[] candidates = {
            "set" + capitalize(alias),
            "set" + capitalize(snakeToCamel(alias)),
            "set" + alias.toUpperCase()
        };
        for (String methodName : candidates) {
            for (Method m : beanClass.getMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == 1) {
                    try {
                        Object converted = convertValue(value, m.getParameterTypes()[0]);
                        m.invoke(bean, converted);
                        return true;
                    } catch (Exception ignore) {}
                }
            }
        }
        return false;
    }

    private void tryFieldDirect(Object bean, String alias, Object value) {
        // Try exact name, then camelCase of snake_case, case-insensitively
        String[] candidates = { alias, snakeToCamel(alias) };
        Class<?> cls = beanClass;
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                for (String candidate : candidates) {
                    if (f.getName().equalsIgnoreCase(candidate)) {
                        try {
                            f.setAccessible(true);
                            f.set(bean, convertValue(value, f.getType()));
                        } catch (Exception ignore) {}
                        return;
                    }
                }
            }
            cls = cls.getSuperclass();
        }
    }

    private static Object convertValue(Object value, Class<?> targetType) {
        if (value == null) return null;
        if (targetType.isAssignableFrom(value.getClass())) return value;
        if (targetType == String.class)  return value.toString();
        if (targetType == Integer.class || targetType == int.class)
            return value instanceof Number ? ((Number) value).intValue()
                                           : Integer.parseInt(value.toString());
        if (targetType == Long.class || targetType == long.class)
            return value instanceof Number ? ((Number) value).longValue()
                                           : Long.parseLong(value.toString());
        if (targetType == Double.class || targetType == double.class)
            return value instanceof Number ? ((Number) value).doubleValue()
                                           : Double.parseDouble(value.toString());
        if (targetType == Float.class || targetType == float.class)
            return value instanceof Number ? ((Number) value).floatValue()
                                           : Float.parseFloat(value.toString());
        if (targetType == Boolean.class || targetType == boolean.class) {
            if (value instanceof Boolean) return value;
            String s = value.toString().trim().toUpperCase();
            return "1".equals(s) || "Y".equals(s) || "T".equals(s) || "TRUE".equals(s);
        }
        if (targetType == java.math.BigDecimal.class)
            return value instanceof java.math.BigDecimal ? value
                                                         : new java.math.BigDecimal(value.toString());
        if (targetType == java.util.Date.class || targetType == java.sql.Timestamp.class)
            return value instanceof java.util.Date ? value : value;
        return value;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String snakeToCamel(String s) {
        if (!s.contains("_")) return s;
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (char c : s.toCharArray()) {
            if (c == '_') { nextUpper = true; }
            else if (nextUpper) { sb.append(Character.toUpperCase(c)); nextUpper = false; }
            else { sb.append(Character.toLowerCase(c)); }
        }
        return sb.toString();
    }
}
