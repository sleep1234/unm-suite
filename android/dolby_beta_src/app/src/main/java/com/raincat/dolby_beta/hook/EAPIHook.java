package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.util.Log;

import com.raincat.dolby_beta.helper.EAPIHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findClassIfExists;

/**
 * EAPI Hook — v91: Full privilege modification with enhanced debug + ResponseBody fix
 *
 * v90 findings:
 * - privilege hook successfully modifies 28 songs but "REPLACED OK" never prints
 * - rebuildResponse may silently fail at ResponseBody.create() or newBuilder().body()
 * - location/info body only has 47 bytes (geo check, not song data)
 *
 * v91 approach:
 * 1. Add detailed step-by-step debug logs in rebuildResponse
 * 2. Add step-by-step logs in afterHookedMethod around setResult
 * 3. Use try-catch around each individual step in rebuildResponse
 * 4. Same privilege modification as v90: fee=0, flag=0, payed=1, pl=320000, etc.
 * 5. Also intercept /eapi/song/enhance/location/info for play URL data
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

    /** Rebuild an OkHttp Response with a new body string — v91 enhanced debug */
    private static Object rebuildResponse(Object originalResponse, String newBodyString, ClassLoader cl) {
        try {
            debugLog("[V91-RB] step1: start rebuild, bodyLen=" + newBodyString.length());

            Class<?> mediaTypeClass = findClassIfExists("okhttp3.MediaType", cl);
            Class<?> responseBodyClass = findClassIfExists("okhttp3.ResponseBody", cl);

            if (responseBodyClass == null) {
                debugLog("[V91-RB] FAILED: ResponseBody class not found!");
                return null;
            }
            debugLog("[V91-RB] step2: found ResponseBody class: " + responseBodyClass.getName());

            Object newBody = null;

            // Try create(mediaType, string) first
            if (mediaTypeClass != null) {
                debugLog("[V91-RB] step3: trying MediaType.parse...");
                try {
                    Object mediaType = XposedHelpers.callStaticMethod(mediaTypeClass, "parse", "application/json;charset=utf-8");
                    debugLog("[V91-RB] step3a: mediaType=" + mediaType);
                    if (mediaType != null) {
                        // Try all method signatures
                        Method[] createMethods = responseBodyClass.getDeclaredMethods();
                        for (Method cm : createMethods) {
                            if (cm.getName().equals("create") && java.lang.reflect.Modifier.isStatic(cm.getModifiers())) {
                                Class<?>[] ptypes = cm.getParameterTypes();
                                debugLog("[V91-RB] step3b: create method: " + cm + " params=" + java.util.Arrays.toString(ptypes));
                            }
                        }
                        try {
                            newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create", mediaType, newBodyString);
                            debugLog("[V91-RB] step3c: create(mediaType, string) succeeded");
                        } catch (Exception e1) {
                            debugLog("[V91-RB] step3c-fail: create(mediaType, string) error: " + e1.getMessage());
                            try {
                                newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create", newBodyString, mediaType);
                                debugLog("[V91-RB] step3d: create(string, mediaType) succeeded");
                            } catch (Exception e2) {
                                debugLog("[V91-RB] step3d-fail: create(string, mediaType) error: " + e2.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    debugLog("[V91-RB] step3-fail: MediaType.parse error: " + e.getMessage());
                }
            } else {
                debugLog("[V91-RB] step3-skip: MediaType class not found");
            }

            if (newBody == null) {
                debugLog("[V91-RB] step4: trying create(string) without mediaType...");
                try {
                    newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create", newBodyString);
                    debugLog("[V91-RB] step4: create(string) succeeded");
                } catch (Exception e) {
                    debugLog("[V91-RB] step4-fail: create(string) error: " + e.getMessage());
                    return null;
                }
            }

            debugLog("[V91-RB] step5: calling newBuilder()...");
            Object builder = XposedHelpers.callMethod(originalResponse, "newBuilder");
            debugLog("[V91-RB] step5a: newBuilder OK, calling body(newBody)...");
            XposedHelpers.callMethod(builder, "body", newBody);
            debugLog("[V91-RB] step5b: body(newBody) OK");

            try {
                Object headersBuilder = XposedHelpers.callMethod(
                    XposedHelpers.callMethod(builder, "headers"), "newBuilder");
                XposedHelpers.callMethod(headersBuilder, "removeAll", "Content-Length");
                Object newHeaders = XposedHelpers.callMethod(headersBuilder, "build");
                XposedHelpers.callMethod(builder, "headers", newHeaders);
                debugLog("[V91-RB] step6: headers cleaned");
            } catch (Exception e) {
                debugLog("[V91-RB] step6-skip: header cleanup skipped: " + e.getMessage());
            }

            debugLog("[V91-RB] step7: calling build()...");
            Object result = XposedHelpers.callMethod(builder, "build");
            debugLog("[V91-RB] step7: build() OK, response rebuilt!");
            return result;

        } catch (Exception e) {
            debugLog("[V91-RB] FAILED: " + e.getClass().getName() + ": " + e.getMessage());
            // Print stack trace elements
            StackTraceElement[] stack = e.getStackTrace();
            if (stack.length > 0) {
                debugLog("[V91-RB] at " + stack[0]);
            }
            return null;
        }
    }

    /**
     * Modify a single song privilege object: clear ALL restrictions
     * @return true if the song was modified
     */
    private static boolean modifySingleSongPrivilege(JSONObject song) {
        try {
            int fee = song.optInt("fee", 0);
            int flag = song.optInt("flag", 0);
            int payed = song.optInt("payed", 0);
            int pl = song.optInt("pl", 0);
            int dl = song.optInt("dl", 0);
            int fl = song.optInt("fl", 0);
            int sp = song.optInt("sp", 0);
            boolean cs = song.optBoolean("cs", false);
            boolean hasFreeTrial = song.has("freeTrialInfo") && !song.isNull("freeTrialInfo");

            // Skip cloud disk songs (flag & 0x8 != 0)
            if ((flag & 0x8) != 0) return false;

            // Only modify if the song has restrictions
            if (fee == 0 && pl > 0 && dl > 0 && !hasFreeTrial) return false;

            long songId = song.optLong("id", 0);

            debugLog("[V91-PRI] id=" + songId + " fee=" + fee + " flag=" + flag +
                    " payed=" + payed + " pl=" + pl + " dl=" + dl + " fl=" + fl +
                    " sp=" + sp + " cs=" + cs + " hasFreeTrial=" + hasFreeTrial);

            // Clear ALL restrictions
            song.put("fee", 0);
            song.put("flag", 0);
            song.put("payed", 1);      // Mark as paid
            song.put("pl", 320000);     // Play level: 320kbps (standard)
            song.put("dl", 320000);     // Download level: 320kbps
            song.put("fl", 999000);     // Free level: highest
            song.put("sp", 7);          // Keep sp as-is or set to 7
            song.put("cs", true);       // Can stream
            song.put("st", 0);          // No special treatment
            song.put("subp", 1);        // Sub privilege
            song.put("toast", false);   // No VIP purchase prompt
            song.put("cp", 1);          // Can play
            song.put("preSell", false); // Not pre-sell

            // Ensure maxbr is high
            if (song.has("maxbr")) {
                song.put("maxbr", 999000);
            }
            if (song.has("playMaxbr")) {
                song.put("playMaxbr", 999000);
            }
            if (song.has("downloadMaxbr")) {
                song.put("downloadMaxbr", 999000);
            }

            // Clear freeTrialInfo
            song.put("freeTrialInfo", JSONObject.NULL);

            // For trial songs, set URL via GD API
            if (hasFreeTrial && songId > 0) {
                debugLog("[V91-PRI] id=" + songId + " hadFreeTrial, fetching GD API...");
                String gdUrl = EAPIHelper.fetchUrlFromGD(songId, 999);
                if (gdUrl != null) {
                    song.put("url", gdUrl);
                    debugLog("[V91-PRI] id=" + songId + " GD API URL set");
                } else {
                    debugLog("[V91-PRI] id=" + songId + " GD API returned NULL");
                }
            }

            return true;
        } catch (Exception e) {
            debugLog("[V91-PRI] modifySong error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Modify the /eapi/song/enhance/privilege response body
     */
    private static String modifyPrivilegeResponse(String bodyString) {
        try {
            JSONObject root = new JSONObject(bodyString);
            if (root.optInt("code") != 200) {
                debugLog("[V91-PRI] response code=" + root.optInt("code") + ", skipping");
                return null;
            }

            Object dataObj = root.opt("data");
            if (dataObj == null) {
                debugLog("[V91-PRI] no data field");
                return null;
            }

            int modifiedCount = 0;

            if (dataObj instanceof JSONArray) {
                JSONArray arr = (JSONArray) dataObj;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject song = arr.getJSONObject(i);
                    if (modifySingleSongPrivilege(song)) {
                        modifiedCount++;
                    }
                }
            } else if (dataObj instanceof JSONObject) {
                JSONObject data = (JSONObject) dataObj;
                if (modifySingleSongPrivilege(data)) {
                    modifiedCount++;
                }
                Iterator<String> keys = data.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object val = data.opt(key);
                    if (val instanceof JSONArray) {
                        JSONArray arr = (JSONArray) val;
                        for (int i = 0; i < arr.length(); i++) {
                            try {
                                if (modifySingleSongPrivilege(arr.getJSONObject(i))) {
                                    modifiedCount++;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            if (modifiedCount > 0) {
                debugLog("[V91-PRI] Modified " + modifiedCount + " songs, serializing...");
                String result = root.toString();
                debugLog("[V91-PRI] Serialized OK, len=" + result.length());
                return result;
            } else {
                debugLog("[V91-PRI] No songs needed modification");
                return null;
            }
        } catch (Exception e) {
            debugLog("[V91-PRI] modifyPrivilegeResponse error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Modify the /eapi/song/enhance/location/info response body
     * This endpoint returns the actual play URL for a song
     */
    private static String modifyLocationInfoResponse(String bodyString) {
        try {
            JSONObject root = new JSONObject(bodyString);
            if (root.optInt("code") != 200) {
                debugLog("[V91-LOC] response code=" + root.optInt("code") + ", skipping");
                return null;
            }

            Object dataObj = root.opt("data");
            if (dataObj == null) {
                debugLog("[V91-LOC] no data field");
                return null;
            }

            int modifiedCount = 0;

            // data can be a single object or array
            if (dataObj instanceof JSONArray) {
                JSONArray arr = (JSONArray) dataObj;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject song = arr.getJSONObject(i);
                    if (modifyLocationSong(song)) {
                        modifiedCount++;
                    }
                }
            } else if (dataObj instanceof JSONObject) {
                JSONObject data = (JSONObject) dataObj;
                if (modifyLocationSong(data)) {
                    modifiedCount++;
                }
            }

            if (modifiedCount > 0) {
                debugLog("[V91-LOC] Modified " + modifiedCount + " songs in location/info");
                return root.toString();
            } else {
                debugLog("[V91-LOC] No songs needed modification in location/info");
                return null;
            }
        } catch (Exception e) {
            debugLog("[V91-LOC] modifyLocationInfoResponse error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Modify a single song in location/info response
     */
    private static boolean modifyLocationSong(JSONObject song) {
        try {
            int fee = song.optInt("fee", 0);
            int flag = song.optInt("flag", 0);
            long songId = song.optLong("id", 0);
            String url = song.optString("url", null);
            boolean hasFreeTrial = song.has("freeTrialInfo") && !song.isNull("freeTrialInfo");

            debugLog("[V91-LOC] id=" + songId + " fee=" + fee + " flag=" + flag +
                    " hasFreeTrial=" + hasFreeTrial +
                    " url=" + (url != null ? url.substring(0, Math.min(80, url.length())) : "null"));

            // Skip cloud disk songs
            if ((flag & 0x8) != 0) return false;

            boolean modified = false;

            // If fee != 0 or has freeTrial, modify
            if (fee != 0 || hasFreeTrial || (url == null && songId > 0)) {
                song.put("fee", 0);
                song.put("flag", 0);
                song.put("payed", 1);
                song.put("freeTrialInfo", JSONObject.NULL);
                song.put("pl", 320000);
                song.put("dl", 320000);
                song.put("fl", 999000);
                song.put("cs", true);
                song.put("st", 0);
                song.put("toast", false);

                if (song.has("code")) {
                    song.put("code", 200);
                }

                // If URL is null or restricted, try GD API
                if ((url == null || hasFreeTrial) && songId > 0) {
                    debugLog("[V91-LOC] id=" + songId + " fetching GD API for URL...");
                    String gdUrl = EAPIHelper.fetchUrlFromGD(songId, 999);
                    if (gdUrl != null) {
                        song.put("url", gdUrl);
                        debugLog("[V91-LOC] id=" + songId + " GD API URL set OK");
                    } else {
                        debugLog("[V91-LOC] id=" + songId + " GD API returned NULL");
                    }
                }
                modified = true;
            }

            return modified;
        } catch (Exception e) {
            debugLog("[V91-LOC] modifyLocationSong error: " + e.getMessage());
            return false;
        }
    }

    public EAPIHook(Context context, int versionCode, ClassLoader cl) {
        this.appClassLoader = cl;
        debugLog("=== EAPIHook v91 FULL PRIVILEGE + ENHANCED DEBUG init, versionCode=" + versionCode + " ===");

        hookRealCallExecute();

        debugLog("=== EAPIHook v91 init complete ===");
    }

    private void hookRealCallExecute() {
        debugLog("[V91] Hooking RealCall.execute()...");

        String[] callClassNames = {
            "okhttp3.internal.connection.RealCall",
            "okhttp3.internal.http.RealCall",
            "okhttp3.RealCall",
        };

        Class<?> callClass = null;
        String foundName = null;

        for (String name : callClassNames) {
            Class<?> cls = findClassIfExists(name, appClassLoader);
            if (cls != null) {
                callClass = cls;
                foundName = name;
                break;
            }
        }

        if (callClass == null) {
            callClass = findClassIfExists("okhttp3.Call", appClassLoader);
            foundName = "okhttp3.Call (interface)";
        }

        if (callClass == null) {
            debugLog("[V91] No Call class found!");
            return;
        }

        debugLog("[V91] Found: " + foundName);

        for (Method m : callClass.getDeclaredMethods()) {
            if (m.getName().equals("execute") && m.getParameterTypes().length == 0) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                String url = getUrlFromCall(param.thisObject);
                                if (url == null) return;

                                // Intercept privilege endpoint
                                if (url.contains("song/enhance/privilege")) {
                                    debugLog("[V91] >>> privilege caught");
                                    Object response = param.getResult();
                                    if (response == null) {
                                        debugLog("[V91] privilege response=null, skip");
                                        return;
                                    }

                                    Object body = XposedHelpers.callMethod(response, "body");
                                    if (body == null) {
                                        debugLog("[V91] privilege body=null, skip");
                                        return;
                                    }

                                    String bodyString = (String) XposedHelpers.callMethod(body, "string");
                                    if (bodyString == null || bodyString.isEmpty()) {
                                        debugLog("[V91] privilege bodyString empty, skip");
                                        return;
                                    }

                                    debugLog("[V91] privilege body len=" + bodyString.length());

                                    String modifiedBody = modifyPrivilegeResponse(bodyString);
                                    debugLog("[V91] modifyPrivilegeResponse returned: " + (modifiedBody != null ? "len=" + modifiedBody.length() : "null"));
                                    if (modifiedBody != null) {
                                        debugLog("[V91] calling rebuildResponse...");
                                        Object newResponse = rebuildResponse(response, modifiedBody, appClassLoader);
                                        debugLog("[V91] rebuildResponse returned: " + (newResponse != null ? "OK" : "null"));
                                        if (newResponse != null) {
                                            debugLog("[V91] calling setResult...");
                                            param.setResult(newResponse);
                                            debugLog("[V91] >>> privilege REPLACED OK");
                                        } else {
                                            debugLog("[V91] rebuildResponse failed for privilege");
                                        }
                                    }
                                }
                                // Intercept location/info endpoint
                                else if (url.contains("song/enhance/location")) {
                                    debugLog("[V91] >>> location/info caught");
                                    Object response = param.getResult();
                                    if (response == null) return;

                                    Object body = XposedHelpers.callMethod(response, "body");
                                    if (body == null) return;

                                    String bodyString = (String) XposedHelpers.callMethod(body, "string");
                                    if (bodyString == null || bodyString.isEmpty()) return;

                                    debugLog("[V91] location/info body len=" + bodyString.length() +
                                            " preview=" + bodyString.substring(0, Math.min(300, bodyString.length())));

                                    String modifiedBody = modifyLocationInfoResponse(bodyString);
                                    if (modifiedBody != null) {
                                        Object newResponse = rebuildResponse(response, modifiedBody, appClassLoader);
                                        if (newResponse != null) {
                                            param.setResult(newResponse);
                                            debugLog("[V91] >>> location/info REPLACED OK");
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                debugLog("[V91] execute hook error: " + e.getClass().getName() + ": " + e.getMessage());
                                StackTraceElement[] stack = e.getStackTrace();
                                if (stack.length > 0) {
                                    debugLog("[V91] at " + stack[0]);
                                }
                            }
                        }
                    });
                    debugLog("[V91] Hooked " + foundName + ".execute()");
                } catch (Exception e) {
                    debugLog("[V91] Failed to hook execute(): " + e.getMessage());
                }
                break;
            }
        }

        // Also hook enqueue() for async requests
        for (Method m : callClass.getDeclaredMethods()) {
            if (m.getName().equals("enqueue") && m.getParameterTypes().length == 1) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args[0] == null) return;
                            String url = getUrlFromCall(param.thisObject);
                            if (url == null) return;

                            boolean isTarget = url.contains("song/enhance/privilege") ||
                                               url.contains("song/enhance/location");
                            if (!isTarget) return;

                            final Object originalCallback = param.args[0];
                            Class<?> callbackClass = originalCallback.getClass();

                            Method onResponseMethod = null;
                            for (Method cm : callbackClass.getDeclaredMethods()) {
                                if (cm.getName().equals("onResponse") && cm.getParameterTypes().length == 2) {
                                    onResponseMethod = cm;
                                    break;
                                }
                            }
                            if (onResponseMethod == null) {
                                Class<?> callbackInterface = findClassIfExists("okhttp3.Callback", appClassLoader);
                                if (callbackInterface != null) {
                                    for (Method im : callbackInterface.getDeclaredMethods()) {
                                        if (im.getName().equals("onResponse")) {
                                            onResponseMethod = im;
                                            break;
                                        }
                                    }
                                }
                            }

                            if (onResponseMethod == null) {
                                debugLog("[V91] enqueue: onResponse not found");
                                return;
                            }

                            final boolean isPrivilege = url.contains("song/enhance/privilege");
                            final Method finalOnResponse = onResponseMethod;
                            XposedBridge.hookMethod(finalOnResponse, new XC_MethodHook() {
                                private boolean used = false;
                                @Override
                                protected void beforeHookedMethod(MethodHookParam hp) throws Throwable {
                                    if (used) return;
                                    used = true;
                                    try {
                                        Object response = hp.args[1];
                                        if (response == null) return;
                                        Object body = XposedHelpers.callMethod(response, "body");
                                        if (body == null) return;
                                        String bodyString = (String) XposedHelpers.callMethod(body, "string");
                                        if (bodyString == null || bodyString.isEmpty()) return;

                                        String modifiedBody;
                                        if (isPrivilege) {
                                            debugLog("[V91] >>> async privilege caught, len=" + bodyString.length());
                                            modifiedBody = modifyPrivilegeResponse(bodyString);
                                        } else {
                                            debugLog("[V91] >>> async location/info caught, len=" + bodyString.length());
                                            modifiedBody = modifyLocationInfoResponse(bodyString);
                                        }

                                        if (modifiedBody != null) {
                                            Object newResponse = rebuildResponse(response, modifiedBody, appClassLoader);
                                            if (newResponse != null) {
                                                hp.args[1] = newResponse;
                                                debugLog("[V91] >>> async REPLACED OK");
                                            }
                                        }
                                    } catch (Exception e) {
                                        debugLog("[V91] async hook error: " + e.getMessage());
                                    }
                                }
                            });
                        }
                    });
                    debugLog("[V91] Hooked " + foundName + ".enqueue()");
                } catch (Exception e) {
                    debugLog("[V91] Failed to hook enqueue(): " + e.getMessage());
                }
                break;
            }
        }
    }
}
