package com.nimbus.persistence.v2.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NamedQueries {
    NamedQuery[] value();
}
