package io.ads.peelerlegacy;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 红果短剧 / 红果漫剧 —— 中插广告拦截（传统 Xposed API 100 版）。
 *
 * 与 Peeler 逻辑一致：hook Gson.fromJson，对解析出的广告配置对象做字段检查，
 * 把三个开关置 false：
 *   shortVideoAdEnable         普通中插（切下一集时）
 *   landscapeInsertAdEnable    横屏中插
 *   enableMultiSeriesFlowAd    短剧信息流总开关
 *
 * 只处理主进程，不依赖混淆类名，按字段存在性识别配置对象。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PeelerLegacy";

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        // 只 hook 红果短剧 / 红果漫剧
        if (!"com.phoenix.read".equals(lpparam.packageName)
                && !"com.kylin.read".equals(lpparam.packageName)) {
            return;
        }

        // 只处理主进程，跳过子进程
        final String processName = lpparam.processName;
        if (processName != null && processName.contains(":")) {
            return;
        }

        Log.i(TAG, "attach to " + lpparam.packageName + " (process=" + processName + ")");

        try {
            hookGson(lpparam.classLoader);
        } catch (Throwable t) {
            Log.w(TAG, "hook gson failed: " + t);
        }
    }

    private void hookGson(ClassLoader cl) {
        try {
            Class<?> gson = XposedHelpers.findClass("com.google.gson.Gson", cl);

            // 重载1: fromJson(String, Type)
            try {
                XposedHelpers.findAndHookMethod(gson, "fromJson",
                        String.class, Type.class,
                        new GsonHook());
                Log.i(TAG, "hooked fromJson(String, Type)");
            } catch (Throwable t) {
                Log.w(TAG, "hook fromJson(String,Type) failed: " + t);
            }

            // 重载2: fromJson(String, Class)
            try {
                XposedHelpers.findAndHookMethod(gson, "fromJson",
                        String.class, Class.class,
                        new GsonHook());
                Log.i(TAG, "hooked fromJson(String, Class)");
            } catch (Throwable t) {
                Log.w(TAG, "hook fromJson(String,Class) failed: " + t);
            }

        } catch (Throwable t) {
            Log.w(TAG, "findClass Gson failed: " + t);
        }
    }

    private class GsonHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object out = param.getResult();
                Object typeArg = param.args.length > 1 ? param.args[1] : null;
                if (out == null || typeArg == null) {
                    return;
                }
                Class<?> target = rawClass(typeArg);
                if (target == null || !looksLikeAdConfig(target)) {
                    return;
                }
                int n = disableAdConfig(out);
                if (n > 0) {
                    Log.i(TAG, "sdk settings (" + target.getSimpleName() + ") -> false (" + n + ")");
                }
            } catch (Throwable t) {
                Log.e(TAG, "after fromJson failed: " + t);
            }
        }
    }

    private static Class<?> rawClass(Object type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            Type raw = ((ParameterizedType) type).getRawType();
            if (raw instanceof Class) {
                return (Class<?>) raw;
            }
        }
        return null;
    }

    private static boolean looksLikeAdConfig(Class<?> c) {
        return hasField(c, "shortVideoAdEnable")
                || hasField(c, "shortSeriesAdConfig")
                || hasField(c, "adSdkConfig")
                || hasField(c, "enableMultiSeriesFlowAd")
                || hasField(c, "landscapeInsertAdEnable")
                || hasField(c, "shortSeriesLandscapeInsertAdConfig");
    }

    private static boolean hasField(Class<?> c, String name) {
        try {
            c.getField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private static int disableAdConfig(Object model) {
        int n = 0;

        // SDK SettingsModel：adSdkConfig.commonAdConfig.*
        Object adSdk = getOrCreate(model, "adSdkConfig");
        if (adSdk != null) {
            Object common = getOrCreate(adSdk, "commonAdConfig");
            n += setFalse(getOrCreate(common, "shortSeriesAdConfig"), "shortVideoAdEnable");
            n += setFalse(getOrCreate(common, "shortSeriesLandscapeInsertAdConfig"),
                    "landscapeInsertAdEnable");
            n += setFalse(getOrCreate(adSdk, "seriesAdConfig"), "enableMultiSeriesFlowAd");
        }

        // App 侧 CommonAdConfig：shortSeriesAdConfig.*
        Object shortSeries = getOrCreate(model, "shortSeriesAdConfig");
        n += setFalse(shortSeries, "shortVideoAdEnable");
        n += setFalse(getOrCreate(shortSeries, "shortSeriesLandscapeInsertAdConfig"),
                "landscapeInsertAdEnable");

        // SDK CommonAdConfig 的横屏配置是直接字段
        n += setFalse(getOrCreate(model, "shortSeriesLandscapeInsertAdConfig"),
                "landscapeInsertAdEnable");

        // 直接解析到具体配置类时的兜底
        n += setFalse(model, "shortVideoAdEnable");
        n += setFalse(model, "landscapeInsertAdEnable");
        n += setFalse(model, "enableMultiSeriesFlowAd");

        // 全量拦截：递归遍历对象树，把所有广告开关型 boolean 字段置 false
        n += disableAllAdSwitches(model, 0);
        return n;
    }

    /**
     * 递归遍历广告配置对象，把所有「广告开关型」boolean 字段置 false。
     * 判断规则：字段名包含 ad/Ad/enable/Enable/switch/Switch/open/Open/show/Show，
     * 且类型为 boolean。覆盖中插、横屏、暂停、推荐流、Banner、评论区、开屏等所有广告。
     */
    private static int disableAllAdSwitches(Object obj, int depth) {
        if (obj == null || depth > 5) return 0;
        int n = 0;
        Class<?> c = obj.getClass();
        // 只处理红果/穿山甲配置类，避免递归进系统类
        if (c.getName().startsWith("java.") || c.getName().startsWith("javax.")
                || c.getName().startsWith("android.") || c.getName().startsWith("kotlin.")) {
            return 0;
        }
        for (Field f : c.getFields()) {
            try {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && !java.lang.reflect.Modifier.isFinal(f.getModifiers())) {
                    f.setAccessible(true);
                    if (f.getType() == boolean.class) {
                        String name = f.getName();
                        if (isAdSwitch(name) && f.getBoolean(obj)) {
                            f.setBoolean(obj, false);
                            if (REPORTED.add(name)) {
                                Log.i(TAG, "ad-switch " + name + " -> false");
                            }
                            n++;
                        }
                    } else if (f.getType() == java.util.List.class
                            || f.getType().isArray()) {
                        // 列表/数组里的广告配置也递归处理
                        Object v = f.get(obj);
                        if (v instanceof java.util.List) {
                            for (Object item : (java.util.List<?>) v) {
                                n += disableAllAdSwitches(item, depth + 1);
                            }
                        }
                    } else if (!f.getType().isPrimitive()
                            && f.getType() != String.class
                            && f.getType() != Integer.class
                            && f.getType() != Long.class
                            && f.getType() != Boolean.class
                            && f.getType() != Double.class
                            && f.getType() != Float.class) {
                        // 嵌套配置对象递归
                        Object v = f.get(obj);
                        if (v != null && !(v instanceof java.lang.Enum)) {
                            n += disableAllAdSwitches(v, depth + 1);
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
        }
        return n;
    }

    private static boolean isAdSwitch(String name) {
        String lower = name.toLowerCase();
        // 广告配置类里的 boolean 字段，只要名字含 ad 就当成广告开关
        // （含 enable/switch/open/show 的也一并关，覆盖推荐流/暂停/Banner/开屏等）
        return lower.contains("ad")
                || lower.contains("enable")
                || lower.contains("switch")
                || lower.contains("open")
                || lower.contains("show");
    }

    private static Object getOrCreate(Object parent, String name) {
        if (parent == null) return null;
        try {
            Field f = parent.getClass().getField(name);
            f.setAccessible(true);
            Object value = f.get(parent);
            if (value == null) {
                value = f.getType().getDeclaredConstructor().newInstance();
                f.set(parent, value);
            }
            return value;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int setFalse(Object obj, String name) {
        if (obj == null) return 0;
        try {
            Field f = obj.getClass().getField(name);
            if (f.getType() != boolean.class) return 0;
            f.setAccessible(true);
            if (f.getBoolean(obj)) {
                f.setBoolean(obj, false);
                if (REPORTED.add(name)) {
                    Log.i(TAG, "sdk field " + name + " -> false");
                }
                return 1;
            }
        } catch (Throwable ignore) {
            // 单个字段失败不影响其他字段
        }
        return 0;
    }
}
