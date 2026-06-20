package org.hibernate.transform;

import java.util.List;

public interface ResultTransformer {
    Object transformTuple(Object[] tuple, String[] aliases);
    List transformList(List collection);
}
