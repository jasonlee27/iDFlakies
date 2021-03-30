package edu.illinois.cs.dt.tools.asm;

import org.junit.Assert;

import java.util.HashMap;
import java.util.Map;

public class StaticFieldsCountManager {

    public static final int MAX_TOTAL_INSTANCE_VARIABLES = 31;
    public final Map<String, Integer> nameToDeclaredCount = new HashMap<>();
    public final Map<String, String> nameToSuperClassName = new HashMap<>();

    public void addCount(String className, String superClassName, int count) {
        this.nameToDeclaredCount.putIfAbsent(className, count);
        this.nameToSuperClassName.putIfAbsent(className, superClassName);
//        // java7 (jdk1.7) does not have map.putIfAbsent method
//        if(this.nameToDeclaredCount.containsKey(className)) {
//            this.nameToDeclaredCount.put(className, count);
//        }
//        if(this.nameToSuperClassName.containsKey(className)){
//            this.nameToSuperClassName.put(className, superClassName);
//        }
    }

    public void verifyAllCounts() {
        Map<String, Integer> cache = new HashMap<>();
        for (String className : this.nameToDeclaredCount.keySet()) {
            int thisSize = populateAndCacheSize(cache, className);
            if (thisSize > MAX_TOTAL_INSTANCE_VARIABLES) {
                System.err.print("Class exceeds instance variable limit: " + className);
            } else {
                // Verify that the other method cached this.
                Assert.assertTrue(cache.containsKey(className));
            }
        }
    }

    private int populateAndCacheSize(Map<String, Integer> cache, String className) {
        int size = 0;
        // Terminate when we get outside of the user code (we will reference the type from the child but it won't be in the map).
        String superClassName = this.nameToSuperClassName.get(className);
        if (null != superClassName) {
            if (cache.containsKey(className)) {
                size = cache.get(className);
            } else {
                int parentSize = populateAndCacheSize(cache, superClassName);
                size = parentSize + this.nameToDeclaredCount.get(className);
                cache.put(className, size);
            }
        }
        return size;
    }
}
