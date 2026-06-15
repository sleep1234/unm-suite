package com.raincat.dolby_beta;

import android.content.Context;
import android.content.SharedPreferences;

import com.annimon.stream.Stream;
import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.ExtraHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import java.io.Closeable;
import java.io.File;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook {
    private final static String PACKAGE_NAME = "com.netease.cloudmusic";

    public Hook(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.netease.cloudmusic.NeteaseMusicApplication", lpparam.classLoader),
                "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                final Context context = (Context) param.thisObject;
                final int versionCode = context.getPackageManager().getPackageInfo(PACKAGE_NAME, 0).versionCode;
                XposedBridge.log("[dolby_diag] versionCode = " + versionCode);
                
                ExtraHelper.init(context);
                SettingHelper.init(context);

                // Dump SettingActivity existence
                Class<?> settingNew = XposedHelpers.findClassIfExists("com.netease.cloudmusic.music.biz.setting.activity.SettingActivity", context.getClassLoader());
                Class<?> settingOld = XposedHelpers.findClassIfExists("com.netease.cloudmusic.activity.SettingActivity", context.getClassLoader());
                XposedBridge.log("[dolby_diag] SettingActivity new=" + (settingNew != null) + " old=" + (settingOld != null));

                // Run dex scan to get all classes, then dump network package
                ClassHelper.getCacheClassList(context, versionCode, () -> {
                    XposedBridge.log("[dolby_diag] === DEX SCAN COMPLETE ===");
                    
                    // Dump all classes in network.interceptor package
                    Pattern interceptorPattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\.interceptor\\..+$");
                    List<String> interceptorClasses = ClassHelper.getFilteredClasses(interceptorPattern, null);
                    XposedBridge.log("[dolby_diag] network.interceptor classes count: " + interceptorClasses.size());
                    for (String className : interceptorClasses) {
                        dumpClass(context, className);
                    }

                    // Dump all classes in network.cookie package  
                    Pattern cookiePattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\.cookie\\..+$");
                    List<String> cookieClasses = ClassHelper.getFilteredClasses(cookiePattern, null);
                    XposedBridge.log("[dolby_diag] network.cookie classes count: " + cookieClasses.size());
                    for (String className : cookieClasses) {
                        dumpClass(context, className);
                    }

                    // Dump all classes in network.model package
                    Pattern modelPattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\.model\\..+$");
                    List<String> modelClasses = ClassHelper.getFilteredClasses(modelPattern, null);
                    XposedBridge.log("[dolby_diag] network.model classes count: " + modelClasses.size());
                    for (String className : modelClasses) {
                        dumpClass(context, className);
                    }

                    // Find classes with ConcurrentHashMap + SharedPreferences (Cookie candidates)
                    Pattern broadPattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\..+$");
                    List<String> allNetworkClasses = ClassHelper.getFilteredClasses(broadPattern, null);
                    XposedBridge.log("[dolby_diag] total network.* classes count: " + allNetworkClasses.size());

                    int cookieCandidates = 0;
                    int httpResponseCandidates = 0;
                    int httpUrlCandidates = 0;
                    int httpParamsCandidates = 0;
                    int interceptorImplCandidates = 0;
                    
                    for (String className : allNetworkClasses) {
                        try {
                            Class<?> c = XposedHelpers.findClassIfExists(className, context.getClassLoader());
                            if (c == null) continue;
                            
                            boolean hasConcurrentHashMap = Stream.of(c.getDeclaredFields()).anyMatch(f -> f.getType() == ConcurrentHashMap.class);
                            boolean hasSharedPreferences = Stream.of(c.getDeclaredFields()).anyMatch(f -> f.getType() == SharedPreferences.class);
                            boolean hasLong = Stream.of(c.getDeclaredFields()).anyMatch(f -> f.getType() == long.class);
                            boolean hasLinkedHashMap = Stream.of(c.getDeclaredFields()).anyMatch(f -> f.getType() == LinkedHashMap.class);
                            boolean hasUri = Stream.of(c.getDeclaredFields()).anyMatch(f -> f.getType() == android.net.Uri.class);
                            boolean hasOkhttp3Field = Stream.of(c.getDeclaredFields()).anyMatch(f -> f.getType().getName().startsWith("okhttp3"));
                            boolean isAbstract = Modifier.isAbstract(c.getModifiers());
                            boolean isPublic = Modifier.isPublic(c.getModifiers());
                            boolean isFinal = Modifier.isFinal(c.getModifiers());
                            boolean hasSerializable = Stream.of(c.getInterfaces()).anyMatch(i -> i == Serializable.class);
                            boolean hasCloseable = Stream.of(c.getInterfaces()).anyMatch(i -> i == Closeable.class);
                            boolean hasInterceptor = Stream.of(c.getInterfaces()).anyMatch(i -> i.getName().contains("Interceptor"));
                            
                            // Cookie candidate
                            if (hasConcurrentHashMap && hasSharedPreferences) {
                                cookieCandidates++;
                                XposedBridge.log("[dolby_diag] CookieCandidate: " + className + 
                                    " abstract=" + isAbstract + " public=" + isPublic + " final=" + isFinal +
                                    " hasLong=" + hasLong + " superclass=" + (c.getSuperclass() != null ? c.getSuperclass().getName() : "null"));
                            }
                            
                            // HttpResponse candidate: not abstract, has okhttp3.Response-like field
                            if (!isAbstract && hasOkhttp3Field && isPublic) {
                                httpResponseCandidates++;
                                XposedBridge.log("[dolby_diag] HttpResponseCandidate: " + className + 
                                    " final=" + isFinal + " superclass=" + (c.getSuperclass() != null ? c.getSuperclass().getName() : "null") +
                                    " implements Closeable=" + hasCloseable);
                            }
                            
                            // HttpUrl candidate: abstract, has okhttp3 field
                            if (isAbstract && hasOkhttp3Field && isPublic) {
                                httpUrlCandidates++;
                                XposedBridge.log("[dolby_diag] HttpUrlCandidate: " + className + 
                                    " hasUri=" + hasUri + " superclass=" + (c.getSuperclass() != null ? c.getSuperclass().getName() : "null"));
                            }
                            
                            // HttpParams candidate: has LinkedHashMap
                            if (!isAbstract && hasLinkedHashMap && isPublic) {
                                httpParamsCandidates++;
                                XposedBridge.log("[dolby_diag] HttpParamsCandidate: " + className + 
                                    " hasSerializable=" + hasSerializable + " superclass=" + (c.getSuperclass() != null ? c.getSuperclass().getName() : "null"));
                            }
                            
                            // Interceptor implementation
                            if (hasInterceptor && !isAbstract && isPublic) {
                                interceptorImplCandidates++;
                                dumpClass(context, className);
                            }
                        } catch (Exception e) {
                            // skip
                        }
                    }
                    
                    XposedBridge.log("[dolby_diag] === SUMMARY ===");
                    XposedBridge.log("[dolby_diag] CookieCandidates: " + cookieCandidates);
                    XposedBridge.log("[dolby_diag] HttpResponseCandidates: " + httpResponseCandidates);
                    XposedBridge.log("[dolby_diag] HttpUrlCandidates: " + httpUrlCandidates);
                    XposedBridge.log("[dolby_diag] HttpParamsCandidates: " + httpParamsCandidates);
                    XposedBridge.log("[dolby_diag] InterceptorImplementations: " + interceptorImplCandidates);
                });
            }
        });
    }

    private void dumpClass(Context context, String className) {
        try {
            Class<?> c = XposedHelpers.findClassIfExists(className, context.getClassLoader());
            if (c == null) {
                XposedBridge.log("[dolby_diag] " + className + " = NULL");
                return;
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("[dolby_diag] CLASS: ").append(className);
            sb.append(" modifiers=").append(Modifier.toString(c.getModifiers()));
            sb.append(" superclass=").append(c.getSuperclass() != null ? c.getSuperclass().getSimpleName() : "null");
            
            Class<?>[] interfaces = c.getInterfaces();
            sb.append(" interfaces=[");
            for (int i = 0; i < interfaces.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(interfaces[i].getSimpleName());
            }
            sb.append("]");
            
            XposedBridge.log(sb.toString());
            
            // Dump fields
            for (Field f : c.getDeclaredFields()) {
                XposedBridge.log("[dolby_diag]   FIELD: " + f.getType().getSimpleName() + " " + f.getName() + 
                    " (" + Modifier.toString(f.getModifiers()) + ")");
            }
            
            // Dump methods (abbreviated)
            for (Method m : c.getDeclaredMethods()) {
                StringBuilder ms = new StringBuilder();
                ms.append("[dolby_diag]   METHOD: ");
                ms.append(m.getReturnType().getSimpleName()).append(" ").append(m.getName()).append("(");
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) ms.append(",");
                    ms.append(params[i].getSimpleName());
                }
                ms.append(") throws ");
                Class<?>[] exc = m.getExceptionTypes();
                for (int i = 0; i < exc.length; i++) {
                    if (i > 0) ms.append(",");
                    ms.append(exc[i].getSimpleName());
                }
                XposedBridge.log(ms.toString());
            }
        } catch (Exception e) {
            XposedBridge.log("[dolby_diag] " + className + " ERROR: " + e.getMessage());
        }
    }
}
