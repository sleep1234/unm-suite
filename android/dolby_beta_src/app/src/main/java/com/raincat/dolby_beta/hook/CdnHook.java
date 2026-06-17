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
 * CDN责任链拦截器检测绕过
 * Hooks interceptor.q methods to bypass CDN response validation.
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
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    // Set result to args[2] (the Response/pre-computed result)
                    if (param.args.length > 2 && param.args[2] != null) {
                        param.setResult(param.args[2]);
                    }
                }
            });
        }
    }
}
