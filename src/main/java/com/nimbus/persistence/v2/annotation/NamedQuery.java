package com.nimbus.persistence.v2.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(NamedQueries.class)
public @interface NamedQuery {
    String name();
    String query(); // HQL
}
