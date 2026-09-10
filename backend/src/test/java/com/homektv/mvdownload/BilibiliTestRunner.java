package com.homektv.mvdownload;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

/**
 * 独立单元测试启动器：直接在 JVM 中调用 JUnit 测试类，规避外部 Maven 插件依赖下载问题
 */
public class BilibiliTestRunner {
    public static void main(String[] args) {
        int passed = 0;
        int failed = 0;
        Class<?>[] testClasses = new Class<?>[] {
            BilibiliWbiTest.class,
            BilibiliAuthServiceTest.class,
            BilibiliMvProviderTest.class
        };

        for (Class<?> clazz : testClasses) {
            System.out.println("Running " + clazz.getSimpleName() + "...");
            try {
                Object instance = clazz.getDeclaredConstructor().newInstance();
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.isAnnotationPresent(Test.class)) {
                        m.setAccessible(true);
                        try {
                            m.invoke(instance);
                            System.out.println("  [PASS] " + m.getName());
                            passed++;
                        } catch (Exception e) {
                            System.err.println("  [FAIL] " + m.getName() + ": " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                            failed++;
                        }
                    }
                }
            } catch (Exception e) {
                Throwable root = e.getCause() != null ? e.getCause() : e;
                System.err.println("Failed to instantiate " + clazz.getName() + ": " + root);
                root.printStackTrace(System.err);
            }
        }

        System.out.println("----------------------------------------");
        System.out.println("TOTAL PASSED: " + passed + ", FAILED: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
