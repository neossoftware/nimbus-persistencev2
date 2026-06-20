package com.nimbus.persistence.util;

import com.nimbus.persistence.annotation.MappedSuperclass;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class ReflectionUtils {

    private ReflectionUtils() {
    }

    /**
     * Converts camelCase to snake_case.
     * Example: "nombreCompleto" -> "nombre_completo"
     */
    public static String camelToSnake(String camel) {
        if (camel == null || camel.isEmpty()) {
            return camel;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                result.append('_');
            }
            result.append(Character.toLowerCase(c));
        }
        return result.toString();
    }

    /**
     * Gets all fields from a class and its superclasses
     * (walking up through @MappedSuperclass annotated classes).
     */
    public static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<Field>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            Field[] declared = current.getDeclaredFields();
            // Add declared fields at the beginning to give priority to subclass fields
            List<Field> currentFields = new ArrayList<Field>();
            for (Field f : declared) {
                currentFields.add(f);
            }
            // Prepend current class fields (so subclass overrides are processed first)
            fields.addAll(0, currentFields);
            Class<?> superClass = current.getSuperclass();
            if (superClass != null && superClass != Object.class
                    && (superClass.isAnnotationPresent(MappedSuperclass.class)
                        || superClass.isAnnotationPresent(com.nimbus.persistence.annotation.Entity.class))) {
                current = superClass;
            } else {
                // Also walk up if superclass has MappedSuperclass
                if (superClass != null && superClass != Object.class) {
                    current = superClass;
                } else {
                    break;
                }
            }
        }
        return fields;
    }

    /**
     * Gets the value of a field from an object instance.
     */
    public static Object getFieldValue(Field field, Object obj) {
        try {
            field.setAccessible(true);
            return field.get(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot access field: " + field.getName(), e);
        }
    }

    /**
     * Sets the value of a field on an object instance.
     */
    public static void setFieldValue(Field field, Object obj, Object value) {
        try {
            field.setAccessible(true);
            field.set(obj, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot set field: " + field.getName(), e);
        }
    }

    /**
     * Converts a value from a ResultSet to the correct Java type.
     */
    public static Object convertToJavaType(Object rsValue, Class<?> targetType) {
        if (rsValue == null) {
            return null;
        }
        if (targetType.isInstance(rsValue)) {
            return rsValue;
        }
        String strVal = rsValue.toString();
        if (targetType == String.class) {
            return strVal;
        }
        if (targetType == Integer.class || targetType == int.class) {
            if (rsValue instanceof Number) {
                return ((Number) rsValue).intValue();
            }
            return Integer.parseInt(strVal);
        }
        if (targetType == Long.class || targetType == long.class) {
            if (rsValue instanceof Number) {
                return ((Number) rsValue).longValue();
            }
            return Long.parseLong(strVal);
        }
        if (targetType == Double.class || targetType == double.class) {
            if (rsValue instanceof Number) {
                return ((Number) rsValue).doubleValue();
            }
            return Double.parseDouble(strVal);
        }
        if (targetType == Float.class || targetType == float.class) {
            if (rsValue instanceof Number) {
                return ((Number) rsValue).floatValue();
            }
            return Float.parseFloat(strVal);
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            if (rsValue instanceof Boolean) {
                return rsValue;
            }
            if (rsValue instanceof Number) {
                return ((Number) rsValue).intValue() != 0;
            }
            return Boolean.parseBoolean(strVal);
        }
        if (targetType == BigDecimal.class) {
            if (rsValue instanceof BigDecimal) {
                return rsValue;
            }
            return new BigDecimal(strVal);
        }
        if (targetType == java.sql.Date.class) {
            if (rsValue instanceof java.sql.Date) {
                return rsValue;
            }
            if (rsValue instanceof Date) {
                return new java.sql.Date(((Date) rsValue).getTime());
            }
        }
        if (targetType == Timestamp.class) {
            if (rsValue instanceof Timestamp) {
                return rsValue;
            }
            if (rsValue instanceof Date) {
                return new Timestamp(((Date) rsValue).getTime());
            }
        }
        if (targetType == Date.class) {
            if (rsValue instanceof Date) {
                return rsValue;
            }
            if (rsValue instanceof java.sql.Date) {
                return new Date(((java.sql.Date) rsValue).getTime());
            }
            if (rsValue instanceof Timestamp) {
                return new Date(((Timestamp) rsValue).getTime());
            }
        }
        // Default: return as-is
        return rsValue;
    }
}
