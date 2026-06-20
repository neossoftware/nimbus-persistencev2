package org.hibernate.transform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Transformers {

    /** Maps each result row to an instance of beanClass using column aliases as field/setter names. */
    public static ResultTransformer aliasToBean(Class<?> beanClass) {
        return new AliasToBeanResultTransformer(beanClass);
    }

    /** Maps each result row to a Map<String, Object> keyed by column alias. */
    public static final ResultTransformer ALIAS_TO_ENTITY_MAP = new ResultTransformer() {
        @Override
        public Object transformTuple(Object[] tuple, String[] aliases) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            for (int i = 0; i < aliases.length; i++) {
                map.put(aliases[i], tuple[i]);
            }
            return map;
        }
        @Override
        public List transformList(List collection) { return collection; }
    };

    /** Returns each row as a plain Object[] (no-op transformer). */
    public static final ResultTransformer TO_LIST = new ResultTransformer() {
        @Override
        public Object transformTuple(Object[] tuple, String[] aliases) { return tuple; }
        @Override
        public List transformList(List collection) { return collection; }
    };

    private Transformers() {}
}
