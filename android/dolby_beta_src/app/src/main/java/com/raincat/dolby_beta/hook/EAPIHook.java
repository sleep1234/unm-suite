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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findClassIfExists;


/**
 * EAPI Hook — v9.5.30+ uses callback class hook instead of HttpResponse.getResultMethod()
 *
 * v9.5.30 architecture:
 * - o72/a (EAPI request) holds URL, params, and result
 * - o72/a$d.b(String)->JSONObject is the result callback for JSONObject responses
 * - o72/a$e.b(String)->String is the result callback for String responses
 * - Both callbacks have field 'a' referencing the enclosing o72/a instance
 * - o72/a inherits from o72/f which has field 'k' (android.net.Uri)
 */

public class EAPIHook {
    private static final int VERSION_V9_5_30 = 9005030;

    public EAPIHook(final Context context) {
        int versionCode = 0;
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo("com.netease.cloudmusic", 0);
            versionCode = pi.versionCode;
        } catch (Exception e) {
            // fallback
        }

        if (versionCode >= VERSION_V9_5_30) {
            initV2(context, versionCode);
        } else {
            initLegacy(context);
        }
    }

    /**
     * v9.5.30+ hook: intercept EAPI results via callback inner classes
     *
     * o72/a$d.b(String)->JSONObject — called when EAPI response is parsed to JSONObject
     * o72/a$e.b(String)->String — called when EAPI response is returned as String
     *
     * Both inner classes have field 'a' = enclosing o72/a instance.
     * o72/a (via o72/f) has field 'k' = android.net.Uri (the request URI).
     */
    private void initV2(final Context context, int versionCode) {
        ClassLoader cl = context.getClassLoader();
        boolean hooked = false;

        // Hook o72/a$d.b(String)->JSONObject
        Class<?> callbackJsonClass = findClassIfExists("o72.a$d", cl);
        if (callbackJsonClass != null) {
            try {
                Method bMethod = XposedHelpers.findMethodExact(callbackJsonClass, "b", String.class);
                XposedBridge.hookMethod(bMethod, new EapiCallbackHook(context, true));
                XposedBridge.log("[dolby_beta] EAPIHook v2: hooked o72.a$d.b(String)->JSONObject");
                hooked = true;
            } catch (Exception e) {
                XposedBridge.log("[dolby_beta] EAPIHook v2: failed to hook o72.a$d: " + e.getMessage());
                // Try alternative: search for method returning JSONObject
                try {
                    for (Method m : callbackJsonClass.getDeclaredMethods()) {
                        if (m.getName().equals("b") && m.getReturnType() == JSONObject.class) {
                            XposedBridge.hookMethod(m, new EapiCallbackHook(context, true));
                            XposedBridge.log("[dolby_beta] EAPIHook v2: hooked o72.a$d.b via reflection");
                            hooked = true;
                            break;
                        }
                    }
                } catch (Exception e2) {
                    XposedBridge.log("[dolby_beta] EAPIHook v2: reflection fallback also failed: " + e2.getMessage());
                }
            }
        } else {
            XposedBridge.log("[dolby_beta] EAPIHook v2: o72.a$d class not found");
        }

        // Hook o72/a$e.b(String)->String
        Class<?> callbackStringClass = findClassIfExists("o72.a$e", cl);
        if (callbackStringClass != null) {
            try {
                Method bMethod = XposedHelpers.findMethodExact(callbackStringClass, "b", String.class);
                XposedBridge.hookMethod(bMethod, new EapiCallbackHook(context, false));
                XposedBridge.log("[dolby_beta] EAPIHook v2: hooked o72.a$e.b(String)->String");
                hooked = true;
            } catch (Exception e) {
                XposedBridge.log("[dolby_beta] EAPIHook v2: failed to hook o72.a$e: " + e.getMessage());
                try {
                    for (Method m : callbackStringClass.getDeclaredMethods()) {
                        if (m.getName().equals("b") && m.getReturnType() == String.class) {
                            XposedBridge.hookMethod(m, new EapiCallbackHook(context, false));
                            XposedBridge.log("[dolby_beta] EAPIHook v2: hooked o72.a$e.b via reflection");
                            hooked = true;
                            break;
                        }
                    }
                } catch (Exception e2) {
                    XposedBridge.log("[dolby_beta] EAPIHook v2: reflection fallback also failed: " + e2.getMessage());
                }
            }
        } else {
            XposedBridge.log("[dolby_beta] EAPIHook v2: o72.a$e class not found");
        }

        if (!hooked) {
            XposedBridge.log("[dolby_beta] EAPIHook v2: no callbacks hooked, falling back to legacy");
            initLegacy(context);
        }
    }

    /**
     * Inner class for EAPI callback hooks (v9.5.30+)
     */
    private class EapiCallbackHook extends XC_MethodHook {
        private final Context context;
        private final boolean isJsonCallback;
        private Field enclosingField; // field 'a' on callback class -> enclosing o72/a
        private Field uriField;       // field 'k' on o72/f -> android.net.Uri
        private boolean fieldsResolved = false;

        EapiCallbackHook(Context context, boolean isJsonCallback) {
            this.context = context;
            this.isJsonCallback = isJsonCallback;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            // Check if proxy or black VIP is enabled
            if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)
                    && !SettingHelper.getInstance().isEnable(SettingHelper.proxy_master_key))
                return;

            // Check result type
            Object result = param.getResult();
            if (result == null) return;

            // Resolve fields lazily (only once)
            if (!fieldsResolved) {
                resolveFields(param.thisObject.getClass());
                fieldsResolved = true;
            }

            // Get the URI from the enclosing o72/a (via o72/f field 'k')
            Uri uri = getUriFromCallback(param.thisObject);

            // DEBUG: log every callback invocation
            String resultType = result.getClass().getSimpleName();
            String uriStr = (uri != null) ? uri.toString() : "null";
            XposedBridge.log("[dolby_beta] EAPI callback fired: resultType=" + resultType
                + " isJson=" + isJsonCallback + " uri=" + uriStr);
            if (uri == null || uri.getPath() == null) {
                return;
            }
            if (!uri.getPath().contains("/eapi/"))
                return;

            String path = uri.getPath();
            String original = result.toString();
            if (TextUtils.isEmpty(original)) return;

            try {
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
                    LinkedHashMap<String, String> paramsMap = getParamsFromUri(uri);
                    String paramsStr = paramsMap.get("params");
                    if (paramsStr == null) paramsStr = "";
                    String songid = EAPIHelper.decrypt(paramsStr).getString("songid");
                    EAPIHelper.uploadCloud(songid);
                    original = CloudDao.getInstance(context).getSong(Integer.parseInt(songid));
                }

                // Set modified result
                if (isJsonCallback && result instanceof JSONObject) {
                    param.setResult(new JSONObject(original));
                } else {
                    param.setResult(original);
                }
            } catch (Exception e) {
                XposedBridge.log("[dolby_beta] EAPIHook v2: error processing " + path + ": " + e.getMessage());
            }
        }

        /**
         * Resolve the field references: callback.a -> enclosing o72/a, and o72/f.k -> Uri
         */
        private void resolveFields(Class<?> callbackClass) {
            // Find field referencing o72/a on the callback class
            // The field is named 'a' or 'b' and is of type o72/a (synthetic)
            for (Field f : callbackClass.getDeclaredFields()) {
                Class<?> fType = f.getType();
                String typeName = fType.getName();
                // The enclosing field's class name is like "o72.a" 
                if (typeName.startsWith("o72.") && !typeName.contains("$")) {
                    enclosingField = f;
                    enclosingField.setAccessible(true);
                    XposedBridge.log("[dolby_beta] EAPIHook v2: enclosing field = " + f.getName() + " type=" + typeName);
                    break;
                }
            }
            if (enclosingField == null) {
                // Try all fields, look for one whose type has a field named 'k' of type Uri
                for (Field f : callbackClass.getDeclaredFields()) {
                    try {
                        Class<?> fType = f.getType();
                        for (Field ff : fType.getDeclaredFields()) {
                            if (ff.getType() == android.net.Uri.class) {
                                enclosingField = f;
                                enclosingField.setAccessible(true);
                                XposedBridge.log("[dolby_beta] EAPIHook v2: enclosing field (heuristic) = " + f.getName() + " type=" + fType.getName());
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                    if (enclosingField != null) break;
                }
            }

            // Find Uri field on the o72/a (or o72/f) class
            if (enclosingField != null) {
                Class<?> requestClass = enclosingField.getType();
                // Walk up the class hierarchy to find field 'k' of type Uri
                Class<?> current = requestClass;
                while (current != null && current != Object.class) {
                    try {
                        for (Field f : current.getDeclaredFields()) {
                            if (f.getType() == android.net.Uri.class) {
                                uriField = f;
                                uriField.setAccessible(true);
                                XposedBridge.log("[dolby_beta] EAPIHook v2: Uri field = " + f.getName() + " on " + current.getName());
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                    if (uriField != null) break;
                    current = current.getSuperclass();
                }
            }

            if (enclosingField == null) {
                XposedBridge.log("[dolby_beta] EAPIHook v2: WARNING: could not resolve enclosing field");
            }
            if (uriField == null) {
                XposedBridge.log("[dolby_beta] EAPIHook v2: WARNING: could not resolve Uri field");
            }
        }

        /**
         * Get the request URI from the callback's enclosing o72/a instance
         */
        private Uri getUriFromCallback(Object callbackInstance) {
            try {
                if (enclosingField == null || uriField == null) return null;
                Object requestObj = enclosingField.get(callbackInstance);
                if (requestObj == null) return null;
                return (Uri) uriField.get(requestObj);
            } catch (Exception e) {
                XposedBridge.log("[dolby_beta] EAPIHook v2: getUri failed: " + e.getMessage());
                return null;
            }
        }

        /**
         * Get request parameters from URI query string
         */
        private LinkedHashMap<String, String> getParamsFromUri(Uri uri) {
            LinkedHashMap<String, String> map = new LinkedHashMap<>();
            try {
                for (String name : uri.getQueryParameterNames()) {
                    String val = uri.getQueryParameter(name);
                    map.put(name, val != null ? val : "");
                }
            } catch (Exception e) {
                XposedBridge.log("[dolby_beta] EAPIHook v2: getParamsFromUri failed: " + e.getMessage());
            }
            return map;
        }
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
