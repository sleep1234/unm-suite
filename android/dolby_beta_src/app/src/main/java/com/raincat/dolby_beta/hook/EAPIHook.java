package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.text.TextUtils;

import com.raincat.dolby_beta.db.CloudDao;
import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.EAPIHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findClassIfExists;


/**
 * EAPI Hook — v3: Comprehensive debug + multi-level hooking
 *
 * v9.5.30 architecture (confirmed):
 * - o72/a (EAPI request) holds URL, params, and result
 * - o72/a extends o72/p -> o72/e -> o72/f (which has Uri field 'k')
 * - o72/a$d was believed to be JSON callback, o72/a$e String callback
 *   BUT: these hook-registration succeeds, afterHookedMethod NEVER fires
 *
 * v3 strategy:
 * 1. Hook ALL methods on o72.a$d / o72.a$e (not just b(String))
 * 2. Hook ALL methods on o72.a that return JSONObject or String
 * 3. Search and hook lambda classes in o72 package
 * 4. Comprehensive debug logging at every level
 */

public class EAPIHook {
    private static final int VERSION_V9_5_30 = 9005030;
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

    public EAPIHook(final Context context) {
        int versionCode = 0;
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo("com.netease.cloudmusic", 0);
            versionCode = pi.versionCode;
        } catch (Exception e) {
            // fallback
        }
        debugLog("=== EAPIHook init, versionCode=" + versionCode + " ===");

        if (versionCode >= VERSION_V9_5_30) {
            initV3(context, versionCode);
        } else {
            initLegacy(context);
        }
    }

    /**
     * v3 hook: comprehensive multi-level debugging + interception
     */
    private void initV3(final Context context, int versionCode) {
        ClassLoader cl = context.getClassLoader();

        // ========== LEVEL 1: Hook ALL methods on o72.a$d (not just b(String)) ==========
        Class<?> callbackJsonClass = findClassIfExists("o72.a$d", cl);
        if (callbackJsonClass != null) {
            debugLog("[V3-LEVEL1] o72.a$d found, hooking ALL methods...");
            int hookCount = 0;
            for (Method m : callbackJsonClass.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers())) continue;
                try {
                    final String methodName = m.getName();
                    final String methodDesc = m.getReturnType().getSimpleName() + " " + methodName +
                            "(" + paramTypesStr(m) + ")";
                    debugLog("[V3-LEVEL1] o72.a$d." + methodDesc);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            debugLog("[V3-LEVEL1-CALL] o72.a$d." + methodName + " CALLED! args=" + param.args.length);
                            // If this is b(String) returning JSONObject, try full EAPI processing
                            if (isJsonCallbackMethod(param)) {
                                processEapiCallback(param, context);
                            }
                        }
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object result = param.getResult();
                            String resultStr = result != null ?
                                    result.getClass().getSimpleName() + "(len=" + result.toString().length() + ")" : "null";
                            debugLog("[V3-LEVEL1-AFTER] o72.a$d." + methodName + " result=" + resultStr);
                        }
                    });
                    hookCount++;
                } catch (Exception e) {
                    debugLog("[V3-LEVEL1] failed to hook o72.a$d." + m.getName() + ": " + e.getMessage());
                }
            }
            debugLog("[V3-LEVEL1] o72.a$d: hooked " + hookCount + " methods");
        } else {
            debugLog("[V3-LEVEL1] o72.a$d class NOT found");
        }

        // ========== LEVEL 2: Hook ALL methods on o72.a$e (not just b(String)) ==========
        Class<?> callbackStringClass = findClassIfExists("o72.a$e", cl);
        if (callbackStringClass != null) {
            debugLog("[V3-LEVEL2] o72.a$e found, hooking ALL methods...");
            int hookCount = 0;
            for (Method m : callbackStringClass.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers())) continue;
                try {
                    final String methodName = m.getName();
                    final String methodDesc = m.getReturnType().getSimpleName() + " " + methodName +
                            "(" + paramTypesStr(m) + ")";
                    debugLog("[V3-LEVEL2] o72.a$e." + methodDesc);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            debugLog("[V3-LEVEL2-CALL] o72.a$e." + methodName + " CALLED! args=" + param.args.length);
                            if (isStringCallbackMethod(param)) {
                                processEapiCallback(param, context);
                            }
                        }
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object result = param.getResult();
                            String resultStr = result != null ?
                                    result.getClass().getSimpleName() + "(len=" + result.toString().length() + ")" : "null";
                            debugLog("[V3-LEVEL2-AFTER] o72.a$e." + methodName + " result=" + resultStr);
                        }
                    });
                    hookCount++;
                } catch (Exception e) {
                    debugLog("[V3-LEVEL2] failed to hook o72.a$e." + m.getName() + ": " + e.getMessage());
                }
            }
            debugLog("[V3-LEVEL2] o72.a$e: hooked " + hookCount + " methods");
        } else {
            debugLog("[V3-LEVEL2] o72.a$e class NOT found");
        }

        // ========== LEVEL 3: Hook ALL methods on o72.a that return JSONObject or String ==========
        Class<?> eapiClass = findClassIfExists("o72.a", cl);
        if (eapiClass != null) {
            debugLog("[V3-LEVEL3] o72.a found, scanning methods...");
            int hookCount = 0;
            for (Method m : eapiClass.getDeclaredMethods()) {
                Class<?> retType = m.getReturnType();
                if ((retType == JSONObject.class || retType == String.class)
                        && !Modifier.isAbstract(m.getModifiers())) {
                    try {
                        final String methodName = m.getName();
                        final String methodDesc = retType.getSimpleName() + " " + methodName +
                                "(" + paramTypesStr(m) + ")";
                        debugLog("[V3-LEVEL3] o72.a." + methodDesc);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Object result = param.getResult();
                                String resultPreview = "";
                                if (result != null) {
                                    String s = result.toString();
                                    resultPreview = "len=" + s.length() +
                                            " preview=" + s.substring(0, Math.min(200, s.length()));
                                }
                                debugLog("[V3-LEVEL3-CALL] o72.a." + methodName + " result=" + resultPreview);
                            }
                        });
                        hookCount++;
                    } catch (Exception e) {
                        debugLog("[V3-LEVEL3] failed to hook o72.a." + m.getName() + ": " + e.getMessage());
                    }
                }
            }
            debugLog("[V3-LEVEL3] o72.a: hooked " + hookCount + " JSONObject/String-returning methods");
        } else {
            debugLog("[V3-LEVEL3] o72.a class NOT found");
        }

        // ========== LEVEL 4: Search for lambda classes in o72 package ==========
        debugLog("[V3-LEVEL4] Searching for lambda classes in o72 package...");
        int lambdaCount = 0;
        for (int i = 0; i <= 20; i++) {
            String lambdaName = "o72.a$$ExternalSyntheticLambda" + i;
            Class<?> lambdaClass = findClassIfExists(lambdaName, cl);
            if (lambdaClass == null) {
                // Also try without $$
                lambdaName = "o72.a$ExternalSyntheticLambda" + i;
                lambdaClass = findClassIfExists(lambdaName, cl);
            }
            if (lambdaClass != null) {
                final String finalLambdaName = lambdaName;
                debugLog("[V3-LEVEL4] Found lambda: " + finalLambdaName);
                for (Method m : lambdaClass.getDeclaredMethods()) {
                    if (Modifier.isAbstract(m.getModifiers())) continue;
                    try {
                        final String methodName = m.getName();
                        final String methodDesc = m.getReturnType().getSimpleName() + " " + methodName +
                                "(" + paramTypesStr(m) + ")";
                        debugLog("[V3-LEVEL4] " + finalLambdaName + "." + methodDesc);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                debugLog("[V3-LEVEL4-CALL] " + finalLambdaName + "." + methodName + " CALLED! args=" + param.args.length);
                            }
                        });
                        lambdaCount++;
                    } catch (Exception e) {
                        debugLog("[V3-LEVEL4] failed to hook " + finalLambdaName + "." + m.getName() + ": " + e.getMessage());
                    }
                }
            }
        }
        // Also check for other o72 inner classes that might be callbacks
        for (char c = 'a'; c <= 'z'; c++) {
            for (char c2 = 'a'; c2 <= 'z'; c2++) {
                String innerName = "o72.a$" + c + c2;
                Class<?> innerClass = findClassIfExists(innerName, cl);
                if (innerClass != null && innerClass != callbackJsonClass && innerClass != callbackStringClass) {
                    // Check if this class has methods with suitable parameter types
                    boolean hasBMethod = false;
                    for (Method m : innerClass.getDeclaredMethods()) {
                        if (m.getName().equals("b") && m.getParameterTypes().length == 1
                                && (m.getParameterTypes()[0] == String.class || m.getParameterTypes()[0] == JSONObject.class)) {
                            hasBMethod = true;
                            break;
                        }
                    }
                    if (hasBMethod) {
                        debugLog("[V3-LEVEL4] Found alternative callback: " + innerName);
                        for (Method m : innerClass.getDeclaredMethods()) {
                            if (Modifier.isAbstract(m.getModifiers())) continue;
                            try {
                                final String methodName = m.getName();
                                final String innerName_ = innerName;
                                XposedBridge.hookMethod(m, new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                        debugLog("[V3-LEVEL4-CALL] " + innerName_ + "." + methodName + " CALLED!");
                                    }
                                });
                                lambdaCount++;
                            } catch (Exception e) {
                                debugLog("[V3-LEVEL4] failed to hook " + innerName + "." + m.getName());
                            }
                        }
                    }
                }
            }
        }
        debugLog("[V3-LEVEL4] Lambda/alt callback hooks: " + lambdaCount);

        // ========== LEVEL 5: Interceptor.q method calls ==========
        // This is handled by CdnHook which we'll add debug logging to separately
        // But also add our own debug hooks here
        Class<?> interceptorClass = findClassIfExists("com.netease.cloudmusic.network.interceptor.q", cl);
        if (interceptorClass != null) {
            debugLog("[V3-LEVEL5] interceptor.q found, hooking ALL methods for debug...");
            for (Method m : interceptorClass.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers())) continue;
                try {
                    final String methodName = m.getName();
                    final String methodDesc = m.getReturnType().getSimpleName() + " " + methodName +
                            "(" + paramTypesStr(m) + ")";
                    debugLog("[V3-LEVEL5] interceptor.q." + methodDesc);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            debugLog("[V3-LEVEL5-CALL] interceptor.q." + methodName + " CALLED! args=" + param.args.length);
                        }
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object result = param.getResult();
                            String resultStr = result != null ? result.getClass().getSimpleName() : "null";
                            debugLog("[V3-LEVEL5-AFTER] interceptor.q." + methodName + " result=" + resultStr);
                        }
                    });
                } catch (Exception e) {
                    debugLog("[V3-LEVEL5] failed to hook interceptor.q." + m.getName() + ": " + e.getMessage());
                }
            }
        } else {
            debugLog("[V3-LEVEL5] interceptor.q class NOT found");
        }
    }

    /**
     * Check if this looks like the JSON callback method (b(String)->JSONObject)
     */
    private boolean isJsonCallbackMethod(XC_MethodHook.MethodHookParam param) {
        return param.args.length >= 1 && param.args[0] instanceof String;
    }

    /**
     * Check if this looks like the String callback method (b(String)->String)
     */
    private boolean isStringCallbackMethod(XC_MethodHook.MethodHookParam param) {
        return param.args.length >= 1 && param.args[0] instanceof String;
    }

    /**
     * Process EAPI callback result — same logic as v2 but with debug logging
     */
    private void processEapiCallback(XC_MethodHook.MethodHookParam param, Context context) {
        boolean blackEnabled = SettingHelper.getInstance().isEnable(SettingHelper.black_key);
        boolean proxyEnabled = SettingHelper.getInstance().isEnable(SettingHelper.proxy_master_key);
        debugLog("[V3-PROCESS] black=" + blackEnabled + " proxy=" + proxyEnabled);
        if (!blackEnabled && !proxyEnabled) {
            debugLog("[V3-PROCESS] Both disabled, skipping");
            return;
        }

        // Try to get URI from the callback's enclosing instance
        Uri uri = null;
        try {
            Object thisObj = param.thisObject;
            // Walk through fields to find o72.a enclosing instance
            for (Field f : thisObj.getClass().getDeclaredFields()) {
                Class<?> fType = f.getType();
                String typeName = fType.getName();
                if (typeName.equals("o72.a") || (typeName.startsWith("o72.") && !typeName.contains("$"))) {
                    f.setAccessible(true);
                    Object enclosing = f.get(thisObj);
                    if (enclosing != null) {
                        // Search for Uri field in the enclosing's class hierarchy
                        Class<?> current = enclosing.getClass();
                        while (current != null && current != Object.class) {
                            for (Field uf : current.getDeclaredFields()) {
                                if (uf.getType() == Uri.class) {
                                    uf.setAccessible(true);
                                    uri = (Uri) uf.get(enclosing);
                                    break;
                                }
                            }
                            if (uri != null) break;
                            current = current.getSuperclass();
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            debugLog("[V3-PROCESS] URI extraction failed: " + e.getMessage());
        }

        String uriStr = (uri != null) ? uri.toString() : "null";
        debugLog("[V3-PROCESS] uri=" + uriStr);

        if (uri == null || uri.getPath() == null || !uri.getPath().contains("/eapi/")) {
            if (uri != null && uri.getPath() != null) {
                debugLog("[V3-PROCESS] Not EAPI path: " + uri.getPath());
            }
            return;
        }

        String path = uri.getPath();
        // Get the current result (what the original method would return)
        Object currentResult = param.getResult();
        if (currentResult == null) {
            debugLog("[V3-PROCESS] result is null");
            return;
        }
        String original = currentResult.toString();
        if (TextUtils.isEmpty(original)) return;

        debugLog("[V3-PROCESS] EAPI path=" + path + " result_len=" + original.length());

        try {
            if (path.contains("song/enhance/player/url")) {
                debugLog("[V3-PROCESS] MATCH: player/url, calling modifyPlayer");
                original = EAPIHelper.modifyPlayer(original);
                debugLog("[V3-PROCESS] modifyPlayer done, new_len=" + original.length());
            } else if (path.contains("song/enhance/download/url")) {
                JSONObject jsonObject = new JSONObject(original);
                JSONObject object = jsonObject.getJSONObject("data");
                JSONArray array = new JSONArray();
                array.put(object);
                jsonObject.put("data", array);
                original = EAPIHelper.modifyPlayer(jsonObject.toString())
                        .replace("[", "").replace("]", "");
            } else if (path.contains("v1/playlist/manipulate/tracks")) {
                LinkedHashMap<String, String> params = getParamsFromUri(uri);
                original = EAPIHelper.modifyManipulate(params, original);
            } else if (path.contains("song/like")) {
                LinkedHashMap<String, String> params = getParamsFromUri(uri);
                original = EAPIHelper.modifyLike(params, original);
            } else if (path.contains("sound/mobile") || path.contains("page=audio_effect")) {
                original = EAPIHelper.modifyEffect(original);
            } else if (path.contains("batch")) {
                if (original.contains("comment\\/banner\\/get")) {
                    JSONObject jsonObject = new JSONObject(original);
                    if (!jsonObject.isNull("/api/content/exposure/comment/banner/get")) {
                        JSONObject object = new JSONObject();
                        object.put("code", 200);
                        object.put("data", new JSONObject());
                        jsonObject.put("/api/content/exposure/comment/banner/get", object);
                    }
                    if (!jsonObject.isNull("/api/v1/content/exposure/comment/banner/get")) {
                        JSONObject object = jsonObject.getJSONObject("/api/v1/content/exposure/comment/banner/get");
                        JSONObject data = object.getJSONObject("data");
                        data.put("count", 0);
                        data.put("offset", 999999999);
                        data.put("records", new JSONArray());
                        data.put("message", "");
                        object.put("data", data);
                        jsonObject.put("/api/v1/content/exposure/comment/banner/get", object);
                    }
                    original = jsonObject.toString();
                }
            } else if (path.contains("upload/cloud/info/v2")) {
                JSONObject jsonObject = new JSONObject(original);
                jsonObject = jsonObject.getJSONObject("privateCloud");
                jsonObject = jsonObject.getJSONObject("simpleSong");
                original = original.replace("\"waitTime\":60,", "\"waitTime\":5,");
                CloudDao.getInstance(context).saveSong(Integer.parseInt(jsonObject.getString("id")), original);
            } else if (path.contains("cloud/pub/v2")) {
                LinkedHashMap<String, String> paramsMap = getParamsFromUri(uri);
                String paramsStr = paramsMap.get("params");
                if (paramsStr == null) paramsStr = "";
                String songid = EAPIHelper.decrypt(paramsStr).getString("songid");
                EAPIHelper.uploadCloud(songid);
                original = CloudDao.getInstance(context).getSong(Integer.parseInt(songid));
            }

            // Set modified result
            if (currentResult instanceof JSONObject) {
                param.setResult(new JSONObject(original));
            } else {
                param.setResult(original);
            }
        } catch (Exception e) {
            debugLog("[V3-PROCESS] error: " + e.getMessage());
        }
    }

    private LinkedHashMap<String, String> getParamsFromUri(Uri uri) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        try {
            for (String name : uri.getQueryParameterNames()) {
                String val = uri.getQueryParameter(name);
                map.put(name, val != null ? val : "");
            }
        } catch (Exception e) {
            debugLog("[V3] getParamsFromUri failed: " + e.getMessage());
        }
        return map;
    }

    private String paramTypesStr(Method m) {
        Class<?>[] pts = m.getParameterTypes();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(pts[i].getSimpleName());
        }
        return sb.toString();
    }

    /**
     * Legacy hook: for versions before v9.5.30
     */
    private void initLegacy(final Context context) {
        Method resultMethod = ClassHelper.OKHttp3Response.getResultMethod(context);
        if (resultMethod == null) {
            XposedBridge.log("[dolby_beta] EAPIHook: core class not found, skipping EAPI hook");
            return;
        }
        XposedBridge.hookMethod(resultMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)
                        && !SettingHelper.getInstance().isEnable(SettingHelper.proxy_master_key))
                    return;
                if ((!(param.getResult() instanceof String) && !(param.getResult() instanceof JSONObject)))
                    return;
                String original = param.getResult().toString();
                if (TextUtils.isEmpty(original)) {
                    return;
                }
                ClassHelper.OKHttp3Response httpResponse = new ClassHelper.OKHttp3Response(param.thisObject);
                Object eapi = httpResponse.getEapi(context);
                Uri uri = ClassHelper.HttpUrl.getUri(context, eapi);
                if (!uri.getPath().contains("/eapi/"))
                    return;
                String path = uri.getPath();

                if (path.contains("song/enhance/player/url")) {
                    original = EAPIHelper.modifyPlayer(original);
                } else if (path.contains("song/enhance/download/url")) {
                    JSONObject jsonObject = new JSONObject(original);
                    JSONObject object = jsonObject.getJSONObject("data");
                    JSONArray array = new JSONArray();
                    array.put(object);
                    jsonObject.put("data", array);
                    original = EAPIHelper.modifyPlayer(jsonObject.toString())
                            .replace("[", "").replace("]", "");
                } else if (path.contains("v1/playlist/manipulate/tracks")) {
                    original = EAPIHelper.modifyManipulate(ClassHelper.HttpParams.getParams(context, eapi), original);
                } else if (path.contains("song/like")) {
                    original = EAPIHelper.modifyLike(ClassHelper.HttpParams.getParams(context, eapi), original);
                } else if (path.contains("sound/mobile") || path.contains("page=audio_effect")) {
                    original = EAPIHelper.modifyEffect(original);
                } else if (path.contains("batch")) {
                    if (original.contains("comment\\/banner\\/get")) {
                        JSONObject jsonObject = new JSONObject(original);
                        if (!jsonObject.isNull("/api/content/exposure/comment/banner/get")) {
                            JSONObject object = new JSONObject();
                            object.put("code", 200);
                            object.put("data", new JSONObject());
                            jsonObject.put("/api/content/exposure/comment/banner/get", object);
                        }
                        if (!jsonObject.isNull("/api/v1/content/exposure/comment/banner/get")) {
                            JSONObject object = jsonObject.getJSONObject("/api/v1/content/exposure/comment/banner/get");
                            JSONObject data = object.getJSONObject("data");
                            data.put("count", 0);
                            data.put("offset", 999999999);
                            data.put("records", new JSONArray());
                            data.put("message", "");
                            object.put("data", data);
                            jsonObject.put("/api/v1/content/exposure/comment/banner/get", object);
                        }
                        original = jsonObject.toString();
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.fix_comment_key) &&
                            original.contains("\\/api\\/resource\\/comment\\/musiciansaid\\/authors")) {
                        JSONObject jsonObject = new JSONObject(original);
                        JSONObject object = jsonObject.getJSONObject("/api/resource/comment/musiciansaid/authors");
                        JSONObject data = object.getJSONObject("data");
                        JSONArray team = data.getJSONArray("team");
                        for (int i = 0; i < team.length(); i++) {
                            JSONObject o = team.getJSONObject(i);
                            String s = o.optString("authorTypeText");
                            if (s != null && s.equals("作者")) {
                                long uid = o.optLong("uid");
                                long artistId = o.optLong("artistId");
                                if (uid > 2147483647) {
                                    JSONObject artistJSONObject = jsonObject.getJSONObject("/api/auth/artist");
                                    JSONObject authJSONObject = artistJSONObject.getJSONObject("auth");
                                    while (uid > 2147483647)
                                        uid = uid / 10;
                                    authJSONObject.put(artistId + "", uid);
                                    artistJSONObject.put("auth", authJSONObject);
                                    jsonObject.put("/api/auth/artist", artistJSONObject);
                                    original = jsonObject.toString();
                                }
                            }
                        }
                    }
                } else if (path.contains("upload/cloud/info/v2")) {
                    JSONObject jsonObject = new JSONObject(original);
                    jsonObject = jsonObject.getJSONObject("privateCloud");
                    jsonObject = jsonObject.getJSONObject("simpleSong");
                    original = original.replace("\"waitTime\":60,", "\"waitTime\":5,");
                    CloudDao.getInstance(context).saveSong(Integer.parseInt(jsonObject.getString("id")), original);
                } else if (path.contains("cloud/pub/v2")) {
                    LinkedHashMap<String, String> paramsMap = ClassHelper.HttpParams.getParams(context, eapi);
                    String paramsStr = paramsMap != null ? paramsMap.get("params") : null;
                    if (paramsStr == null) paramsStr = "";
                    String songid = EAPIHelper.decrypt(paramsStr).getString("songid");
                    EAPIHelper.uploadCloud(songid);
                    original = CloudDao.getInstance(context).getSong(Integer.parseInt(songid));
                }

                param.setResult(param.getResult() instanceof JSONObject ? new JSONObject(original) : original);
            }
        });
    }
}
