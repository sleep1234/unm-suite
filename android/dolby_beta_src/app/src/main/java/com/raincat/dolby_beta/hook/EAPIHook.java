package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.util.Log;

import com.raincat.dolby_beta.helper.EAPIHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileWriter;
import java.io.IOException;
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
 * EAPI Hook — v87: DISCOVERY version
 *
 * Goal: Find WHERE the player API request actually flows.
 * v86 showed that neither PATH A (e1() startup config) nor PATH B (interceptor.q.a() CDN)
 * captures /eapi/song/enhance/player requests.
 *
 * v87 strategy — THREE LAYER discovery:
 * LAYER 1: Hook ALL methods of interceptor.q (a, i, h, c) — which method carries player?
 * LAYER 2: Hook ALL methods of o72.a (e1, p1, o1, s, p, etc) — which returns player data?
 * LAYER 3: Hook OkHttp RealCall.execute() + AsyncCall — catch ALL network requests
 *
 * For ANY hook: if URL/key contains "player" or "song/enhance", log with [PLAYER-FOUND] prefix.
 * NO modification — this is purely a discovery build.
 */
public class EAPIHook {
    private static final String DEBUG_LOG_PATH = "/data/local/tmp/dolby_debug.log";
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss.SSS");
    private static int logCounter = 0;

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

    /** Check if a string contains player-related keywords */
    private static boolean isPlayerRelated(String s) {
        if (s == null) return false;
        return s.contains("player") || s.contains("song/enhance") || s.contains("/eapi/song") || s.contains("/api/song");
    }

    /** Extract URL from an OkHttp Request object */
    private static String getUrlFromRequest(Object request) {
        if (request == null) return null;
        try {
            Object url = XposedHelpers.callMethod(request, "url");
            return XposedHelpers.callMethod(url, "toString").toString();
        } catch (Exception e1) {
            try {
                return request.toString();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /** Extract URL from an OkHttp Request object inside a RealCall */
    private static String getUrlFromCall(Object call) {
        if (call == null) return null;
        try {
            Object request = XposedHelpers.callMethod(call, "request");
            return getUrlFromRequest(request);
        } catch (Exception e) {
            return null;
        }
    }

    public EAPIHook(Context context, int versionCode, ClassLoader cl) {
        debugLog("=== EAPIHook v87 DISCOVERY init, versionCode=" + versionCode + " ===");
        logCounter = 0;

        // ===== LAYER 1: Hook ALL methods of interceptor.q =====
        hookInterceptorQAllMethods(cl);

        // ===== LAYER 2: Hook ALL methods of o72.a =====
        hookO72AAllMethods(cl);

        // ===== LAYER 3: Hook OkHttp RealCall =====
        hookOkHttpRealCall();

        debugLog("=== EAPIHook v87 DISCOVERY init complete ===");
    }

    // ========================================================================
    // LAYER 1: Hook ALL methods of interceptor.q
    // ========================================================================
    private void hookInterceptorQAllMethods(ClassLoader cl) {
        debugLog("[L1] Attempting to hook ALL methods of interceptor.q...");
        try {
            Class<?> interceptorClass = findClassIfExists("com.netease.cloudmusic.network.interceptor.q", cl);
            if (interceptorClass == null) {
                debugLog("[L1] interceptor.q class not found");
                return;
            }

            Method[] methods = interceptorClass.getDeclaredMethods();
            debugLog("[L1] interceptor.q has " + methods.length + " declared methods");

            for (Method m : methods) {
                // Skip synthetic/bridge methods
                if (m.isSynthetic() || m.isBridge()) continue;

                final String methodName = m.getName();
                final int argCount = m.getParameterTypes().length;
                final String returnTypeName = m.getReturnType().getSimpleName();

                // Skip very generic methods (toString, hashCode, equals)
                if (methodName.equals("toString") || methodName.equals("hashCode") || methodName.equals("equals"))
                    continue;

                // We especially care about methods that return Response or have 4+ args
                // But for discovery, let's hook ALL non-trivial methods
                if (argCount == 0 && returnTypeName.equals("void")) continue; // skip trivial setters

                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            logCounter++;
                            // Only log every call for high-value methods (4+ args, or Response return)
                            boolean isHighValue = argCount >= 4 || returnTypeName.contains("Response") || returnTypeName.contains("Pair");

                            // Try to extract URL from Request arg
                            String urlStr = null;
                            for (Object arg : param.args) {
                                if (arg != null && arg.getClass().getName().contains("Request")) {
                                    urlStr = getUrlFromRequest(arg);
                                    break;
                                }
                            }

                            if (urlStr != null && isPlayerRelated(urlStr)) {
                                debugLog("[L1-PLAYER-FOUND] interceptor.q." + methodName + "(" + argCount + ")->" + returnTypeName +
                                        " URL=" + urlStr.substring(0, Math.min(200, urlStr.length())));
                            } else if (isHighValue) {
                                // Log all high-value method calls with URL (truncated)
                                if (urlStr != null) {
                                    debugLog("[L1] interceptor.q." + methodName + "(" + argCount + ")->" + returnTypeName +
                                            " URL=" + urlStr.substring(0, Math.min(120, urlStr.length())));
                                } else if (logCounter % 50 == 0) {
                                    // Periodic: log without URL to show method is firing
                                    debugLog("[L1] interceptor.q." + methodName + "(" + argCount + ")->" + returnTypeName +
                                            " (no Request arg, #" + logCounter + ")");
                                }
                            }
                        }
                    });
                    debugLog("[L1] Hooked interceptor.q." + methodName + "(" + argCount + ")->" + returnTypeName);
                } catch (Exception e) {
                    debugLog("[L1] Failed to hook interceptor.q." + methodName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            debugLog("[L1] Error hooking interceptor.q: " + e.getMessage());
        }
    }

    // ========================================================================
    // LAYER 2: Hook ALL methods of o72.a
    // ========================================================================
    private void hookO72AAllMethods(ClassLoader cl) {
        debugLog("[L2] Attempting to hook ALL methods of o72.a...");
        try {
            Class<?> eapiClass = findClassIfExists("o72.a", cl);
            if (eapiClass == null) {
                debugLog("[L2] o72.a class not found");
                return;
            }

            Method[] methods = eapiClass.getDeclaredMethods();
            debugLog("[L2] o72.a has " + methods.length + " declared methods");

            for (Method m : methods) {
                if (m.isSynthetic() || m.isBridge()) continue;

                final String methodName = m.getName();
                final int argCount = m.getParameterTypes().length;
                final String returnTypeName = m.getReturnType().getSimpleName();

                // Skip trivial methods
                if (methodName.equals("toString") || methodName.equals("hashCode") || methodName.equals("equals"))
                    continue;

                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object result = param.getResult();
                            String resultType = result != null ? result.getClass().getSimpleName() : "null";

                            // For JSONObject results: log ALL keys (no 20-key limit like v86)
                            if (result instanceof JSONObject) {
                                JSONObject json = (JSONObject) result;
                                StringBuilder allKeys = new StringBuilder();
                                Iterator<String> keys = json.keys();
                                int count = 0;
                                boolean hasPlayerKey = false;
                                while (keys.hasNext()) {
                                    String key = keys.next();
                                    if (isPlayerRelated(key)) hasPlayerKey = true;
                                    if (count > 0) allKeys.append("|");
                                    allKeys.append(key);
                                    count++;
                                    // Safety: cap at 100 keys to avoid insane log size
                                    if (count >= 100) {
                                        allKeys.append("...(truncated)");
                                        break;
                                    }
                                }

                                if (hasPlayerKey) {
                                    debugLog("[L2-PLAYER-FOUND] o72.a." + methodName + "(" + argCount + ")->" + returnTypeName +
                                            " JSONObject(" + json.length() + " keys) ALL_KEYS=" + allKeys.toString());
                                    // Log the player data specifically
                                    Iterator<String> keys2 = json.keys();
                                    while (keys2.hasNext()) {
                                        String key = keys2.next();
                                        if (isPlayerRelated(key)) {
                                            try {
                                                Object val = json.get(key);
                                                String valStr = val.toString();
                                                debugLog("[L2-PLAYER-DATA] key=" + key + " value_len=" + valStr.length() +
                                                        " preview=" + valStr.substring(0, Math.min(300, valStr.length())));
                                            } catch (Exception e) {
                                                debugLog("[L2-PLAYER-DATA] key=" + key + " error reading value: " + e.getMessage());
                                            }
                                        }
                                    }
                                } else {
                                    // Log key summary for all JSONObject returns (but less verbose)
                                    debugLog("[L2] o72.a." + methodName + "(" + argCount + ")->" + returnTypeName +
                                            " JSONObject(" + count + " keys): " + allKeys.substring(0, Math.min(300, allKeys.length())));
                                }
                            } else if (result instanceof String) {
                                String str = (String) result;
                                if (isPlayerRelated(str)) {
                                    debugLog("[L2-PLAYER-FOUND] o72.a." + methodName + "(" + argCount + ")->" + returnTypeName +
                                            " String(len=" + str.length() + "): " + str.substring(0, Math.min(200, str.length())));
                                } else {
                                    debugLog("[L2] o72.a." + methodName + "(" + argCount + ")->" + returnTypeName +
                                            " String(len=" + str.length() + ")");
                                }
                            } else if (result instanceof JSONArray) {
                                JSONArray arr = (JSONArray) result;
                                if (isPlayerRelated(arr.toString())) {
                                    debugLog("[L2-PLAYER-FOUND] o72.a." + methodName + "(" + argCount + ")->" + returnTypeName +
                                            " JSONArray(len=" + arr.length() + "): " + arr.toString().substring(0, Math.min(300, arr.toString().length())));
                                } else {
                                    debugLog("[L2] o72.a." + methodName + "(" + argCount + ")->" + returnTypeName +
                                            " JSONArray(len=" + arr.length() + ")");
                                }
                            } else {
                                // Non-JSON result: just log type
                                // Only log once per method name to avoid spam
                                debugLog("[L2] o72.a." + methodName + "(" + argCount + ")->" + returnTypeName +
                                        " result=" + resultType);
                            }
                        }
                    });
                    debugLog("[L2] Hooked o72.a." + methodName + "(" + argCount + ")->" + returnTypeName);
                } catch (Exception e) {
                    debugLog("[L2] Failed to hook o72.a." + methodName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            debugLog("[L2] Error hooking o72.a: " + e.getMessage());
        }
    }

    // ========================================================================
    // LAYER 3: Hook OkHttp RealCall — catch ALL network requests
    // ========================================================================
    private void hookOkHttpRealCall() {
        debugLog("[L3] Attempting to hook OkHttp RealCall...");

        // Hook RealCall.execute() — synchronous requests
        try {
            Class<?> realCallClass = findClassIfExists("okhttp3.RealCall", null);
            if (realCallClass != null) {
                for (Method m : realCallClass.getDeclaredMethods()) {
                    if (m.getName().equals("execute") && m.getParameterTypes().length == 0) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                String url = getUrlFromCall(param.thisObject);
                                if (url != null) {
                                    if (isPlayerRelated(url)) {
                                        debugLog("[L3-PLAYER-FOUND] RealCall.execute() URL=" + url.substring(0, Math.min(200, url.length())));
                                    } else {
                                        // Log all execute() URLs (less verbose — only first 80 chars)
                                        debugLog("[L3] RealCall.execute() URL=" + url.substring(0, Math.min(80, url.length())));
                                    }
                                }
                            }
                        });
                        debugLog("[L3] Hooked RealCall.execute()");
                        break;
                    }
                }
            } else {
                debugLog("[L3] okhttp3.RealCall not found (unexpected — OkHttp should be present)");
            }
        } catch (Exception e) {
            debugLog("[L3] Failed to hook RealCall.execute(): " + e.getMessage());
        }

        // Hook the inner AsyncCall.execute() — async requests (most API calls are async)
        // RealCall$AsyncCall extends NamedRunnable, its execute() method is called for async requests
        try {
            Class<?> asyncCallClass = findClassIfExists("okhttp3.RealCall$AsyncCall", null);
            if (asyncCallClass != null) {
                for (Method m : asyncCallClass.getDeclaredMethods()) {
                    // AsyncCall.execute() or the method that calls getResponseWithInterceptorChain()
                    if (m.getName().equals("execute") || m.getName().equals("run")) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                // Try to access the enclosing RealCall to get the request
                                try {
                                    // AsyncCall has a reference to the outer RealCall
                                    // In OkHttp 3.x: field is 'this$0' or accessed via accessor
                                    Object outerCall = null;
                                    try {
                                        outerCall = XposedHelpers.getObjectField(param.thisObject, "this$0");
                                    } catch (Exception e1) {
                                        // Try alternate field names
                                        try {
                                            outerCall = XposedHelpers.getObjectField(param.thisObject, "val$call");
                                        } catch (Exception e2) {
                                            // Try getting from any field that is a RealCall
                                            for (java.lang.reflect.Field f : param.thisObject.getClass().getDeclaredFields()) {
                                                if (f.getType().getName().contains("RealCall")) {
                                                    f.setAccessible(true);
                                                    outerCall = f.get(param.thisObject);
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                    if (outerCall != null) {
                                        String url = getUrlFromCall(outerCall);
                                        if (url != null) {
                                            if (isPlayerRelated(url)) {
                                                debugLog("[L3-PLAYER-FOUND] AsyncCall." + m.getName() + "() URL=" +
                                                        url.substring(0, Math.min(200, url.length())));
                                            } else {
                                                debugLog("[L3] AsyncCall." + m.getName() + "() URL=" +
                                                        url.substring(0, Math.min(80, url.length())));
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    // Can't access outer call — just log that async call fired
                                    debugLog("[L3] AsyncCall." + m.getName() + "() fired (can't get URL: " + e.getMessage() + ")");
                                }
                            }
                        });
                        debugLog("[L3] Hooked AsyncCall." + m.getName() + "()");
                    }
                }
            } else {
                debugLog("[L3] okhttp3.RealCall$AsyncCall not found");
            }
        } catch (Exception e) {
            debugLog("[L3] Failed to hook AsyncCall: " + e.getMessage());
        }

        // Also hook getResponseWithInterceptorChain() on RealCall — this is the method that
        // actually executes the interceptor chain. It's called by both execute() and AsyncCall.
        try {
            Class<?> realCallClass = findClassIfExists("okhttp3.RealCall", null);
            if (realCallClass != null) {
                for (Method m : realCallClass.getDeclaredMethods()) {
                    if (m.getName().equals("getResponseWithInterceptorChain") && m.getParameterTypes().length == 0) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                String url = getUrlFromCall(param.thisObject);
                                if (url != null) {
                                    if (isPlayerRelated(url)) {
                                        debugLog("[L3-PLAYER-FOUND] getResponseWithInterceptorChain() URL=" +
                                                url.substring(0, Math.min(200, url.length())));
                                    }
                                    // Don't log all URLs here — too noisy, RealCall.execute() already covers it
                                }
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                // Check if the response contains player data
                                Object result = param.getResult();
                                if (result != null && result.getClass().getName().contains("Response")) {
                                    String url = getUrlFromCall(param.thisObject);
                                    if (url != null && isPlayerRelated(url)) {
                                        try {
                                            Object body = XposedHelpers.callMethod(result, "body");
                                            if (body != null) {
                                                // Peek at body without consuming it
                                                Object source = XposedHelpers.callMethod(body, "source");
                                                if (source != null) {
                                                    // Try to peek
                                                    try {
                                                        Method peekMethod = body.getClass().getMethod("peekString");
                                                        // Not standard — try reading the buffer
                                                    } catch (Exception ignored) {}

                                                    // Just log body size
                                                    long contentLength = -1;
                                                    try {
                                                        Object clObj = XposedHelpers.callMethod(body, "contentLength");
                                                        if (clObj instanceof Long) contentLength = (Long) clObj;
                                                        else if (clObj instanceof Integer) contentLength = (Integer) clObj;
                                                    } catch (Exception ignored) {}
                                                    debugLog("[L3-PLAYER-FOUND] Response for player URL: body_size=" + contentLength);
                                                }
                                            }
                                        } catch (Exception e) {
                                            debugLog("[L3-PLAYER-FOUND] Response body read failed: " + e.getMessage());
                                        }
                                    }
                                }
                            }
                        });
                        debugLog("[L3] Hooked RealCall.getResponseWithInterceptorChain()");
                        break;
                    }
                }
            }
        } catch (Exception e) {
            debugLog("[L3] Failed to hook getResponseWithInterceptorChain(): " + e.getMessage());
        }

        // Additional: Hook the EAPI request construction
        // In v9.5.30, EAPI requests are built via o72.a constructor — hook it to see URI
        try {
            Class<?> eapiClass = findClassIfExists("o72.a", null);
            if (eapiClass != null) {
                for (java.lang.reflect.Constructor<?> c : eapiClass.getDeclaredConstructors()) {
                    final int paramCount = c.getParameterTypes().length;
                    XposedBridge.hookMethod(c, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            // Try to get URI from the constructed object
                            try {
                                java.lang.reflect.Field uriField = null;
                                Class<?> cls = param.thisObject.getClass();
                                // Walk up the class hierarchy to find Uri field
                                while (cls != null && cls != Object.class) {
                                    for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                                        if (f.getType() == android.net.Uri.class) {
                                            uriField = f;
                                            break;
                                        }
                                    }
                                    if (uriField != null) break;
                                    cls = cls.getSuperclass();
                                }
                                if (uriField != null) {
                                    uriField.setAccessible(true);
                                    android.net.Uri uri = (android.net.Uri) uriField.get(param.thisObject);
                                    if (uri != null) {
                                        String uriStr = uri.toString();
                                        if (isPlayerRelated(uriStr)) {
                                            debugLog("[L3-PLAYER-FOUND] o72.a constructor URI=" +
                                                    uriStr.substring(0, Math.min(200, uriStr.length())));
                                        } else {
                                            debugLog("[L3] o72.a constructor(" + paramCount + ") URI=" +
                                                    uriStr.substring(0, Math.min(120, uriStr.length())));
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                debugLog("[L3] o72.a constructor(" + paramCount + ") URI extraction failed: " + e.getMessage());
                            }
                        }
                    });
                    debugLog("[L3] Hooked o72.a constructor(" + paramCount + ")");
                }
            }
        } catch (Exception e) {
            debugLog("[L3] Failed to hook o72.a constructors: " + e.getMessage());
        }
    }
}
