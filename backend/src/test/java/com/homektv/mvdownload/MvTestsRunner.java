package com.homektv.mvdownload;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 独立单测运行器：在离线/无 surefire 插件环境下直接反射触发 JUnit 5 测试用例
 */
public class MvTestsRunner {
    public static void main(String[] args) {
        int totalPassed = 0;
        int totalFailed = 0;

        Class<?>[] testClasses = {
                BilibiliWbiTest.class,
                BilibiliMvProviderTest.class,
                NeteaseMvProviderTest.class,
                MvDownloadSettingsTest.class
        };

        for (Class<?> clazz : testClasses) {
            System.out.println("=== 正在运行单测套件: " + clazz.getSimpleName() + " ===");
            int passed = 0;
            int failed = 0;
            try {
                Constructor<?> ctor = clazz.getDeclaredConstructor();
                ctor.setAccessible(true);
                Object instance = ctor.newInstance();
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Test.class)) {
                        try {
                            method.setAccessible(true);
                            method.invoke(instance);
                            System.out.println("  [PASS] " + method.getName());
                            passed++;
                        } catch (Throwable t) {
                            Throwable cause = t.getCause() != null ? t.getCause() : t;
                            System.err.println("  [FAIL] " + method.getName() + " -> " + cause.getMessage());
                            cause.printStackTrace();
                            failed++;
                        }
                    }
                }
            } catch (Throwable e) {
                System.out.println("Init failed: " + e.getClass().getName() + " -> " + e.getCause());
                e.printStackTrace(System.out);
                failed++;
            }
            System.out.println("结果: " + passed + " 通过, " + failed + " 失败\n");
            totalPassed += passed;
            totalFailed += failed;
        }

        System.out.println("==================================================");
        System.out.println("全部测试汇总: " + totalPassed + " 通过, " + totalFailed + " 失败");
        System.out.println("==================================================");

        if (totalFailed > 0) {
            System.exit(1);
        }
    }
}
