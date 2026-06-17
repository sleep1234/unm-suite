package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.raincat.dolby_beta.helper.EAPIHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findClassIfExists;

/**
 * EAPI Hook — v88: Deep discovery version
 *
 * v87 findings:
 * - o72.a.p() returns songplay-related batch JSONObject (but NOT /eapi/song/enhance/player)
 * - interceptor.q.a() only fires for CDN URLs, never for API URLs
 * - x1/b1/c1 return {code,data,message,trp,xHeaderTraceId} (individual EAPI responses)
 * - o72.a constructors NOT hooked (used null classLoader — BUG)
 * - okhttp3.RealCall NOT found (used null classLoader — BUG)
 * - Player API request is completely absent from all hooks!
 *
 * v88 fixes:
 * 1. FIX: Use app classLoader for o72.a constructors and OkHttp classes
 * 2. Hook o72.a constructors → see ALL EAPI request URIs
 * 3. Deep inspect x1/b1/c1 data fields for player content
 * 4. Hook BEFORE+AFTER on key o72.a methods (w1, n1, v1 — request handlers?)
 * 5. Search for okhttp3.Call/RealCall with app classLoader
 */
public class EAPIHook {
    private static final String DEBUG_LOG_PATH = "/data/local/tmp/dolby_debug.log";
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss.SSS");

    private ClassLoader appClassLoader;

    private static void debugLog(String msg) {
        String fullMsg = "[dolby_beta] " + msg;
        XposedBridge.log(fullMsg);
        Log.d("dolby_beta", msg);
        try {
            FileWriter fw = new FileWriter(DEBUG_LOG_PATH, true);
            fw.write(SDF.format(new Date()) + " " + msg + "\n");
            fw.close();
        } catch (IOException ignored) {}
    }

    private static boolean isPlayerRelated(String s) {
        if (s == null) return false;
        return s.contains("player") || s.contains("song/enhance") ||
                s.contains("/eapi/song") || s.contains("/api/song/enhance");
    }

    private static String getUrlFromRequest(Object request) {
        if (request == null) return null;
        try {
            Object url = XposedHelpers.callMethod(request, "url");
            return XposedHelpers.callMethod(url, "toString").toString();
        } catch (Exception e1) {
            try { return request.toString(); } catch (Exception e2) { return null; }
        }
    }

    private static String getUrlFromCall(Object call) {
        if (call == null) return null;
        try {
            Object request = XposedHelpers.callMethod(call, "request");
            return getUrlFromRequest(request);
        } catch (Exception e) { return null; }
    }

    /** Extract URI from an o72.a instance by walking class hierarchy */
    private static String getUriFromEapi(Object eapi) {
        try {
            Class<?> cls = eapi.getClass();
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType() == Uri.class) {
                        f.setAccessible(true);
                        Uri uri = (Uri) f.get(eapi);
                        return uri != null ? uri.toString() : null;
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Exception e) {
            debugLog("[V88] URI extraction failed: " + e.getMessage());
        }
        return null;
    }

    public EAPIHook(Context context, int versionCode, ClassLoader cl) {
        this.appClassLoader = cl;
        debugLog("=== EAPIHook v88 DEEP DISCOVERY init, versionCode=" + versionCode + " ===");

        // 1. Hook o72.a constructors with CORRECT classLoader
        hookO72AConstructors();

        // 2. Deep inspect x1/b1/c1/w1/n1/v1 (before + after)
        hookO72AKeyMethods();

        // 3. Find and hook OkHttp Call with correct classLoader
        hookOkHttpCall();

        // 4. Also keep the interceptor.q intercept(1) hook for completeness
        hookInterceptorQIntercept();

        debugLog("=== EAPIHook v88 init complete ===");
    }

    // ========================================================================
    // 1. o72.a CONSTRUCTORS — see ALL EAPI request URIs
    // ========================================================================
    private void hookO72AConstructors() {
        debugLog("[V88] Hooking o72.a constructors with app classLoader...");
        try {
            Class<?> eapiClass = findClassIfExists("o72.a", appClassLoader);
            if (eapiClass == null) {
                debugLog("[V88] o72.a class not found!");
                return;
            }

            Constructor<?>[] ctors = eapiClass.getDeclaredConstructors();
            debugLog("[V88] o72.a has " + ctors.length + " constructors");

            for (Constructor<?> c : ctors) {
                final int paramCount = c.getParameterTypes().length;
                try {
                    XposedBridge.hookMethod(c, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            String uriStr = getUriFromEapi(param.thisObject);
                            if (uriStr != null) {
                                if (isPlayerRelated(uriStr)) {
                                    debugLog("[V88-PLAYER-FOUND] o72.a constructor(" + paramCount + ") URI=" +
                                            uriStr.substring(0, Math.min(200, uriStr.length())));
                                } else {
                                    debugLog("[V88] o72.a constructor(" + paramCount + ") URI=" +
                                            uriStr.substring(0, Math.min(150, uriStr.length())));
                                }
                            } else {
                                debugLog("[V88] o72.a constructor(" + paramCount + ") URI=null");
                            }
                        }
                    });
                    debugLog("[V88] Hooked o72.a constructor(" + paramCount + ")");
                } catch (Exception e) {
                    debugLog("[V88] Failed to hook o72.a constructor(" + paramCount + "): " + e.getMessage());
                }
            }
        } catch (Exception e) {
            debugLog("[V88] Error hooking o72.a constructors: " + e.getMessage());
        }
    }

    // ========================================================================
    // 2. Deep inspect key o72.a methods — BEFORE + AFTER
    // ========================================================================
    private void hookO72AKeyMethods() {
        debugLog("[V88] Hooking key o72.a methods with BEFORE+AFTER...");
        try {
            Class<?> eapiClass = findClassIfExists("o72.a", appClassLoader);
            if (eapiClass == null) return;

            // Key methods to inspect deeply:
            // w1(3)->void — could be a "send request" method
            // v1(2)->void — could be a "process request" method
            // n1(2)->Object — could be a "execute and get result" method
            // x1(2)->Object — returns individual EAPI response
            // b1(3)->Object — returns individual EAPI response
            // c1(2)->Object — returns individual EAPI response
            // i1(2)->List — returns list (batch config?)
            // t1(1)->void — void method
            // A1(1)->void — void method
            String[] deepMethods = {"w1", "v1", "n1", "x1", "b1", "c1", "i1", "t1", "A1", "s1", "q1", "z1", "u1"};

            for (Method m : eapiClass.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge()) continue;
                String name = m.getName();
                boolean isDeep = false;
                for (String dm : deepMethods) {
                    if (name.equals(dm)) { isDeep = true; break; }
                }
                if (!isDeep) continue;

                final String methodName = name;
                final int argCount = m.getParameterTypes().length;
                final String returnTypeName = m.getReturnType().getSimpleName();

                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            // Log arguments for deep methods
                            StringBuilder argDesc = new StringBuilder();
                            for (int i = 0; i < param.args.length; i++) {
                                Object arg = param.args[i];
                                if (arg == null) {
                                    argDesc.append("null");
                                } else if (arg instanceof JSONObject) {
                                    JSONObject jo = (JSONObject) arg;
                                    argDesc.append("JSONObject(").append(jo.length()).append(")");
                                } else if (arg instanceof String) {
                                    String s = (String) arg;
                                    if (isPlayerRelated(s)) {
                                        argDesc.append("String[PLAYER](").append(s.length()).append("):").append(s.substring(0, Math.min(100, s.length())));
                                    } else {
                                        argDesc.append("String(").append(s.length()).append("):").append(s.substring(0, Math.min(50, s.length())));
                                    }
                                } else if (arg instanceof Uri) {
                                    argDesc.append("Uri:").append(arg.toString().substring(0, Math.min(100, arg.toString().length())));
                                } else {
                                    argDesc.append(arg.getClass().getSimpleName());
                                }
                                if (i < param.args.length - 1) argDesc.append(", ");
                            }
                            debugLog("[V88-BEFORE] o72.a." + methodName + "(" + argCount + ") args=[" + argDesc + "]");
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object result = param.getResult();
                            String resultDesc;
                            if (result == null) {
                                resultDesc = "null";
                            } else if (result instanceof JSONObject) {
                                JSONObject jo = (JSONObject) result;
                                // Check for player data in all JSON values
                                boolean hasPlayerData = false;
                                Iterator<String> keys = jo.keys();
                                while (keys.hasNext()) {
                                    String key = keys.next();
                                    if (isPlayerRelated(key)) {
                                        hasPlayerData = true;
                                        try {
                                            Object val = jo.get(key);
                                            String valStr = val.toString();
                                            debugLog("[V88-PLAYER-FOUND] o72.a." + methodName + " key=" + key +
                                                    " value_len=" + valStr.length() +
                                                    " preview=" + valStr.substring(0, Math.min(300, valStr.length())));
                                        } catch (Exception e) {
                                            debugLog("[V88-PLAYER-FOUND] o72.a." + methodName + " key=" + key +
                                                    " error: " + e.getMessage());
                                        }
                                    }
                                }
                                // Also check if "data" field contains player data
                                if (jo.has("data") && !jo.isNull("data")) {
                                    try {
                                        Object data = jo.get("data");
                                        String dataStr = data.toString();
                                        if (isPlayerRelated(dataStr) || dataStr.contains("\"fee\"") || dataStr.contains("\"url\"")) {
                                            debugLog("[V88-PLAYER-DATA] o72.a." + methodName + " data field contains player data, len=" +
                                                    dataStr.length() + " preview=" + dataStr.substring(0, Math.min(300, dataStr.length())));
                                        }
                                    } catch (Exception ignored) {}
                                }
                                // List all keys
                                StringBuilder keyStr = new StringBuilder();
                                Iterator<String> k = jo.keys();
                                int cnt = 0;
                                while (k.hasNext() && cnt < 100) {
                                    if (cnt > 0) keyStr.append("|");
                                    keyStr.append(k.next());
                                    cnt++;
                                }
                                resultDesc = "JSONObject(" + cnt + " keys): " + keyStr.substring(0, Math.min(300, keyStr.length()));
                            } else if (result instanceof String) {
                                String s = (String) result;
                                if (isPlayerRelated(s)) {
                                    resultDesc = "String[PLAYER](" + s.length() + "): " + s.substring(0, Math.min(200, s.length()));
                                } else {
                                    resultDesc = "String(" + s.length() + "): " + s.substring(0, Math.min(80, s.length()));
                                }
                            } else if (result instanceof JSONArray) {
                                resultDesc = "JSONArray(" + ((JSONArray) result).length() + ")";
                            } else {
                                resultDesc = result.getClass().getSimpleName();
                            }
                            debugLog("[V88-AFTER] o72.a." + methodName + "(" + argCount + ")->" + returnTypeName + " result=" + resultDesc);
                        }
                    });
                    debugLog("[V88] Hooked o72.a." + methodName + "(" + argCount + ")->" + returnTypeName + " (BEFORE+AFTER)");
                } catch (Exception e) {
                    debugLog("[V88] Failed to hook o72.a." + methodName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            debugLog("[V88] Error hooking o72.a key methods: " + e.getMessage());
        }
    }

    // ========================================================================
    // 3. Hook OkHttp Call with correct classLoader
    // ========================================================================
    private void hookOkHttpCall() {
        debugLog("[V88] Searching for OkHttp Call classes...");

        // Try multiple approaches to find the Call class
        String[] callClassNames = {
            "okhttp3.RealCall",
            "okhttp3.internal.connection.RealCall",
            "okhttp3.internal.http.RealCall",
        };

        Class<?> callClass = null;
        String foundName = null;

        // Approach 1: Try standard names with app classLoader
        for (String name : callClassNames) {
            Class<?> cls = findClassIfExists(name, appClassLoader);
            if (cls != null) {
                callClass = cls;
                foundName = name;
                break;
            }
        }

        // Approach 2: Try null classLoader (boot classpath)
        if (callClass == null) {
            for (String name : callClassNames) {
                Class<?> cls = findClassIfExists(name, null);
                if (cls != null) {
                    callClass = cls;
                    foundName = name + " (null loader)";
                    break;
                }
            }
        }

        // Approach 3: Find Call interface and look for implementations
        if (callClass == null) {
            try {
                Class<?> callInterface = findClassIfExists("okhttp3.Call", appClassLoader);
                if (callInterface != null) {
                    debugLog("[V88] Found okhttp3.Call interface, looking for implementations...");
                    // Hook the interface method directly — Xposed can hook default methods on interfaces
                    for (Method m : callInterface.getDeclaredMethods()) {
                        debugLog("[V88] Call interface method: " + m.getName() + "(" +
                                m.getParameterTypes().length + ")->" + m.getReturnType().getSimpleName());
                    }
                    // Hook Call.execute() and Call.enqueue()
                    for (Method m : callInterface.getDeclaredMethods()) {
                        if (m.getName().equals("execute") && m.getParameterTypes().length == 0) {
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    String url = getUrlFromCall(param.thisObject);
                                    if (url != null) {
                                        if (isPlayerRelated(url)) {
                                            debugLog("[V88-PLAYER-FOUND] Call.execute() URL=" + url.substring(0, Math.min(200, url.length())));
                                        } else {
                                            debugLog("[V88] Call.execute() URL=" + url.substring(0, Math.min(100, url.length())));
                                        }
                                    }
                                }
                            });
                            debugLog("[V88] Hooked okhttp3.Call.execute() interface method");
                        }
                        if (m.getName().equals("enqueue") && m.getParameterTypes().length == 1) {
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    String url = getUrlFromCall(param.thisObject);
                                    if (url != null) {
                                        if (isPlayerRelated(url)) {
                                            debugLog("[V88-PLAYER-FOUND] Call.enqueue() URL=" + url.substring(0, Math.min(200, url.length())));
                                        } else {
                                            debugLog("[V88] Call.enqueue() URL=" + url.substring(0, Math.min(100, url.length())));
                                        }
                                    }
                                }
                            });
                            debugLog("[V88] Hooked okhttp3.Call.enqueue() interface method");
                        }
                    }
                } else {
                    debugLog("[V88] okhttp3.Call interface not found either!");
                }
            } catch (Exception e) {
                debugLog("[V88] Error finding Call interface: " + e.getMessage());
            }
            return; // Don't try RealCall-specific hooks
        }

        debugLog("[V88] Found Call class: " + foundName);

        // Hook RealCall.execute()
        try {
            for (Method m : callClass.getDeclaredMethods()) {
                if (m.getName().equals("execute") && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String url = getUrlFromCall(param.thisObject);
                            if (url != null) {
                                if (isPlayerRelated(url)) {
                                    debugLog("[V88-PLAYER-FOUND] RealCall.execute() URL=" + url.substring(0, Math.min(200, url.length())));
                                } else {
                                    debugLog("[V88] RealCall.execute() URL=" + url.substring(0, Math.min(100, url.length())));
                                }
                            }
                        }
                    });
                    debugLog("[V88] Hooked " + foundName + ".execute()");
                    break;
                }
            }
        } catch (Exception e) {
            debugLog("[V88] Failed to hook RealCall.execute(): " + e.getMessage());
        }

        // Hook getResponseWithInterceptorChain()
        try {
            for (Method m : callClass.getDeclaredMethods()) {
                if (m.getName().equals("getResponseWithInterceptorChain")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String url = getUrlFromCall(param.thisObject);
                            if (url != null && isPlayerRelated(url)) {
                                debugLog("[V88-PLAYER-FOUND] getResponseWithInterceptorChain() URL=" +
                                        url.substring(0, Math.min(200, url.length())));
                            }
                        }
                    });
                    debugLog("[V88] Hooked " + foundName + ".getResponseWithInterceptorChain()");
                    break;
                }
            }
        } catch (Exception e) {
            debugLog("[V88] Failed to hook getResponseWithInterceptorChain(): " + e.getMessage());
        }
    }

    // ========================================================================
    // 4. Hook interceptor.q.intercept(Chain) — the standard OkHttp entry point
    // ========================================================================
    private void hookInterceptorQIntercept() {
        debugLog("[V88] Hooking interceptor.q.intercept(Chain)...");
        try {
            Class<?> interceptorClass = findClassIfExists("com.netease.cloudmusic.network.interceptor.q", appClassLoader);
            if (interceptorClass == null) {
                debugLog("[V88] interceptor.q not found");
                return;
            }

            for (Method m : interceptorClass.getDeclaredMethods()) {
                if (m.getName().equals("intercept") && m.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            // Chain has request() method
                            if (param.args[0] != null) {
                                try {
                                    Object request = XposedHelpers.callMethod(param.args[0], "request");
                                    String url = getUrlFromRequest(request);
                                    if (url != null) {
                                        if (isPlayerRelated(url)) {
                                            debugLog("[V88-PLAYER-FOUND] interceptor.q.intercept(Chain) URL=" +
                                                    url.substring(0, Math.min(200, url.length())));
                                        } else {
                                            debugLog("[V88] interceptor.q.intercept(Chain) URL=" +
                                                    url.substring(0, Math.min(100, url.length())));
                                        }
                                    }
                                } catch (Exception e) {
                                    debugLog("[V88] interceptor.q.intercept(Chain) URL extraction failed: " + e.getMessage());
                                }
                            }
                        }
                    });
                    debugLog("[V88] Hooked interceptor.q.intercept(Chain)");
                    break;
                }
            }
        } catch (Exception e) {
            debugLog("[V88] Failed to hook interceptor.q.intercept: " + e.getMessage());
        }
    }
}
