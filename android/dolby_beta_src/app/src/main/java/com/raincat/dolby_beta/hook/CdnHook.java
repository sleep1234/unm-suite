package com.raincat.dolby_beta.hook;

import android.content.Context;

import com.raincat.dolby_beta.helper.ClassHelper;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/09/13
 *     desc   : 绕过CDN责任链拦截器检测
 *     version: 1.0
 * </pre>
 */

public class CdnHook {
    private static final String DEBUG_LOG_PATH = "/data/local/tmp/dolby_debug.log";
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss.SSS");

    private static void debugLog(String msg) {
        XposedBridge.log("[dolby_beta] " + msg);
        try {
            FileWriter fw = new FileWriter(DEBUG_LOG_PATH, true);
            fw.write(SDF.format(new Date()) + " " + msg + "\n");
            fw.close();
        } catch (IOException ignored) {}
    }

    public CdnHook(Context context, int versionCode) {
        if (versionCode < 138)
            return;
        List<Method> methods = ClassHelper.HttpInterceptor.getMethodList(context);
        if (methods == null || methods.isEmpty()) {
            debugLog("[CdnHook] interceptor methods not found, skipping");
            return;
        }
        debugLog("[CdnHook] hooking " + methods.size() + " interceptor methods");
        for (Method m : methods) {
            final String methodSig = m.getDeclaringClass().getSimpleName() + "." + m.getName() +
                    "(args=" + m.getParameterTypes().length + ")->" + m.getReturnType().getSimpleName();
            debugLog("[CdnHook] hooking " + methodSig);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    debugLog("[CdnHook-CALL] " + methodSig + " FIRED! args=" + param.args.length +
                            " argTypes=" + argTypesStr(param.args));
                    // Log args[2] type (the replacement value)
                    if (param.args.length > 2 && param.args[2] != null) {
                        debugLog("[CdnHook-CALL] args[2] type=" + param.args[2].getClass().getName());
                    }
                    param.setResult(param.args[2]);
                    debugLog("[CdnHook-CALL] " + methodSig + " setResult to args[2]");
                }
            });
        }
    }

    private String argTypesStr(Object[] args) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(args[i] != null ? args[i].getClass().getSimpleName() : "null");
        }
        sb.append("]");
        return sb.toString();
    }
}
