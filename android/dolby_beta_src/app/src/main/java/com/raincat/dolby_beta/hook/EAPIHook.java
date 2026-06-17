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
 * EAPI Hook — v89: Production version
 *
 * Strategy: Intercept at OkHttp Response level for /eapi/song/enhance/privilege
 *
 * v88 discovered:
 * - The real player URL endpoint is /eapi/song/enhance/privilege (NOT player/url/v1)
 * - Player API requests go through RealCall.execute(), NOT through interceptor.q
 * - The privilege response body contains song data with fee/flag/payed/freeTrialInfo/url
 *
 * v89 approach:
 * 1. Hook RealCall.execute() AFTER to get the Response
 * 2. If URL contains "song/enhance/privilege", read body string
 * 3. Parse JSON, find songs with fee!=0 or freeTrialInfo, modify them
 * 4. Rebuild Response with modified body
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

    /** Rebuild an OkHttp Response with a new body string */
    private static Object rebuildResponse(Object originalResponse, String newBodyString, ClassLoader cl) {
        try {
            // 1. Create a new ResponseBody from the string
            // okhttp3.ResponseBody.create(MediaType, String)
            Class<?> mediaTypeClass = findClassIfExists("okhttp3.MediaType", cl);
            Class<?> responseBodyClass = findClassIfExists("okhttp3.ResponseBody", cl);

            if (responseBodyClass == null) {
                debugLog("[V89] ResponseBody class not found!");
                return null;
            }

            // Try the 2-arg create(mediaType, string) first, then 1-arg create(string)
            Object newBody = null;
            if (mediaTypeClass != null) {
                try {
                    // Try create(MediaType, String) — OkHttp 3.x
                    Object mediaType = XposedHelpers.callStaticMethod(mediaTypeClass, "parse", "application/json;charset=utf-8");
                    if (mediaType != null) {
                        try {
                            newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create", mediaType, newBodyString);
                        } catch (Exception e1) {
                            // OkHttp 4.x changed signature: create(String, MediaType)
                            try {
                                newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create", newBodyString, mediaType);
                            } catch (Exception e2) {
                                // Try create(MediaType, ByteString) — unlikely but try
                                debugLog("[V89] ResponseBody.create with MediaType failed: " + e2.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    debugLog("[V89] MediaType.parse failed: " + e.getMessage());
                }
            }
            if (newBody == null) {
                // Fallback: create(String) — OkHttp 4.x shorthand
                try {
                    newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create", newBodyString);
                } catch (Exception e) {
                    debugLog("[V89] ResponseBody.create(string) failed: " + e.getMessage());
                    return null;
                }
            }

            // 2. Build new Response using newBuilder().body(body).build()
            Object builder = XposedHelpers.callMethod(originalResponse, "newBuilder");
            XposedHelpers.callMethod(builder, "body", newBody);
            // Also remove Content-Length header since body changed
            try {
                Object headersBuilder = XposedHelpers.callMethod(
                    XposedHelpers.callMethod(builder, "headers"), "newBuilder");
                XposedHelpers.callMethod(headersBuilder, "removeAll", "Content-Length");
                Object newHeaders = XposedHelpers.callMethod(headersBuilder, "build");
                XposedHelpers.callMethod(builder, "headers", newHeaders);
            } catch (Exception e) {
                debugLog("[V89] Header cleanup skipped: " + e.getMessage());
            }
            return XposedHelpers.callMethod(builder, "build");

        } catch (Exception e) {
            debugLog("[V89] rebuildResponse failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Modify the /eapi/song/enhance/privilege response body.
     * The response format is: {"code":200, "data": [...songs...]}
     * Each song has: id, url, fee, flag, payed, freeTrialInfo, br, size, md5, etc.
     */
    private static String modifyPrivilegeResponse(String bodyString) {
        try {
            JSONObject root = new JSONObject(bodyString);
            if (root.optInt("code") != 200) {
                debugLog("[V89] privilege response code=" + root.optInt("code") + ", skipping");
                return null;
            }

            // The data can be a JSONArray of song objects
            Object dataObj = root.opt("data");
            if (dataObj == null) {
                debugLog("[V89] privilege response has no data field");
                return null;
            }

            int modifiedCount = 0;

            if (dataObj instanceof JSONArray) {
                JSONArray arr = (JSONArray) dataObj;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject song = arr.getJSONObject(i);
                    if (modifySingleSong(song)) {
                        modifiedCount++;
                    }
                }
            } else if (dataObj instanceof JSONObject) {
                // Sometimes the data is a single object or nested structure
                // Try to find arrays inside
                JSONObject data = (JSONObject) dataObj;
                // Check direct song data
                if (modifySingleSong(data)) {
                    modifiedCount++;
                }
                // Also look for nested arrays
                Iterator<String> keys = data.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object val = data.opt(key);
                    if (val instanceof JSONArray) {
                        JSONArray arr = (JSONArray) val;
                        for (int i = 0; i < arr.length(); i++) {
                            try {
                                JSONObject song = arr.getJSONObject(i);
                                if (modifySingleSong(song)) {
                                    modifiedCount++;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            if (modifiedCount > 0) {
                debugLog("[V89] Modified " + modifiedCount + " songs in privilege response");
                return root.toString();
            } else {
                debugLog("[V89] No songs needed modification in privilege response");
                return null; // null = don't modify
            }
        } catch (Exception e) {
            debugLog("[V89] modifyPrivilegeResponse error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Modify a single song object: clear fee/flag/payed/freeTrialInfo, replace URL if needed.
     * @return true if the song was modified
     */
    private static boolean modifySingleSong(JSONObject song) {
        try {
            int fee = song.optInt("fee", 0);
            int flag = song.optInt("flag", 0);
            int payed = song.optInt("payed", 0);
            boolean hasFreeTrial = song.has("freeTrialInfo") && !song.isNull("freeTrialInfo");

            // Skip cloud disk songs (flag & 0x8 != 0)
            if ((flag & 0x8) != 0) return false;

            // Only modify if the song has restrictions
            if (fee == 0 && !hasFreeTrial && payed != 0) return false;

            long songId = song.optLong("id", 0);
            String currentUrl = song.optString("url", null);

            debugLog("[V89] song id=" + songId + " fee=" + fee + " flag=" + flag +
                    " payed=" + payed + " hasFreeTrial=" + hasFreeTrial +
                    " url=" + (currentUrl != null ? currentUrl.substring(0, Math.min(80, currentUrl.length())) : "null"));

            // Clear restrictions
            song.put("fee", 0);
            song.put("flag", 0);
            song.put("payed", 0);
            song.put("freeTrialInfo", JSONObject.NULL);

            // Ensure code is 200
            if (song.has("code")) {
                song.put("code", 200);
            }

            // Replace URL for trial songs via GD API
            if (hasFreeTrial && songId > 0) {
                debugLog("[V89] song " + songId + " has freeTrial, fetching from GD API...");
                String gdUrl = EAPIHelper.fetchUrlFromGD(songId, 999);
                if (gdUrl != null) {
                    song.put("url", gdUrl);
                    debugLog("[V89] song " + songId + " GD API URL replaced OK");
                } else {
                    debugLog("[V89] song " + songId + " GD API returned NULL, keeping original URL");
                }
            } else if (currentUrl != null && currentUrl.contains("?")) {
                // Remove query string from URL (some URLs have auth params)
                song.put("url", currentUrl.substring(0, currentUrl.indexOf("?")));
            }

            return true;
        } catch (Exception e) {
            debugLog("[V89] modifySingleSong error: " + e.getMessage());
            return false;
        }
    }

    public EAPIHook(Context context, int versionCode, ClassLoader cl) {
        this.appClassLoader = cl;
        debugLog("=== EAPIHook v89 PRODUCTION init, versionCode=" + versionCode + " ===");

        // Core hook: RealCall.execute() AFTER → intercept /eapi/song/enhance/privilege Response
        hookRealCallExecute();

        debugLog("=== EAPIHook v89 init complete ===");
    }

    // ========================================================================
    // Core Hook: RealCall.execute() AFTER → modify privilege response
    // ========================================================================
    private void hookRealCallExecute() {
        debugLog("[V89] Hooking RealCall.execute() for privilege interception...");

        // Find the RealCall class
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

        // Fallback: try Call interface
        if (callClass == null) {
            debugLog("[V89] RealCall class not found, trying okhttp3.Call interface...");
            callClass = findClassIfExists("okhttp3.Call", appClassLoader);
            foundName = "okhttp3.Call (interface)";
        }

        if (callClass == null) {
            debugLog("[V89] No Call/RealCall class found! Hook failed.");
            return;
        }

        debugLog("[V89] Found Call class: " + foundName);

        // Hook execute()
        for (Method m : callClass.getDeclaredMethods()) {
            if (m.getName().equals("execute") && m.getParameterTypes().length == 0) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                // Get the request URL
                                String url = getUrlFromCall(param.thisObject);
                                if (url == null) return;

                                // Only process privilege endpoint
                                if (!url.contains("song/enhance/privilege")) return;

                                debugLog("[V89] >>> privilege response caught for URL: " +
                                        url.substring(0, Math.min(150, url.length())));

                                Object response = param.getResult();
                                if (response == null) {
                                    debugLog("[V89] privilege response is null");
                                    return;
                                }

                                // Read response body — can only be read once!
                                Object body = XposedHelpers.callMethod(response, "body");
                                if (body == null) {
                                    debugLog("[V89] privilege response body is null");
                                    return;
                                }

                                String bodyString = (String) XposedHelpers.callMethod(body, "string");
                                if (bodyString == null || bodyString.isEmpty()) {
                                    debugLog("[V89] privilege response body is empty");
                                    return;
                                }

                                debugLog("[V89] privilege body length=" + bodyString.length() +
                                        " preview=" + bodyString.substring(0, Math.min(200, bodyString.length())));

                                // Try to modify the response
                                String modifiedBody = modifyPrivilegeResponse(bodyString);
                                if (modifiedBody != null) {
                                    // Rebuild the Response with modified body
                                    Object newResponse = rebuildResponse(response, modifiedBody, appClassLoader);
                                    if (newResponse != null) {
                                        param.setResult(newResponse);
                                        debugLog("[V89] >>> privilege response REPLACED successfully");
                                    } else {
                                        debugLog("[V89] rebuildResponse failed, original response preserved");
                                    }
                                }
                            } catch (Exception e) {
                                debugLog("[V89] privilege hook error: " + e.getMessage());
                            }
                        }
                    });
                    debugLog("[V89] Hooked " + foundName + ".execute() — privilege interceptor active");
                } catch (Exception e) {
                    debugLog("[V89] Failed to hook execute(): " + e.getMessage());
                }
                break;
            }
        }

        // Also hook enqueue() for async requests (some API calls may be async)
        for (Method m : callClass.getDeclaredMethods()) {
            if (m.getName().equals("enqueue") && m.getParameterTypes().length == 1) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            // Wrap the Callback to intercept the onResponse
                            if (param.args[0] == null) return;
                            String url = getUrlFromCall(param.thisObject);
                            if (url == null || !url.contains("song/enhance/privilege")) return;

                            final Object originalCallback = param.args[0];
                            Class<?> callbackClass = originalCallback.getClass();

                            // Find the onResponse method on the callback
                            Method onResponseMethod = null;
                            for (Method cm : callbackClass.getDeclaredMethods()) {
                                if (cm.getName().equals("onResponse") && cm.getParameterTypes().length == 2) {
                                    onResponseMethod = cm;
                                    break;
                                }
                            }
                            if (onResponseMethod == null) {
                                // Try the interface method
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
                                debugLog("[V89] enqueue: could not find onResponse method on callback");
                                return;
                            }

                            // Hook the onResponse for this specific callback instance
                            final Method finalOnResponse = onResponseMethod;
                            XposedBridge.hookMethod(finalOnResponse, new XC_MethodHook() {
                                private boolean used = false; // prevent multiple invocations
                                @Override
                                protected void beforeHookedMethod(MethodHookParam hp) throws Throwable {
                                    if (used) return;
                                    used = true;
                                    try {
                                        Object response = hp.args[1]; // onResponse(Call, Response)
                                        if (response == null) return;
                                        Object body = XposedHelpers.callMethod(response, "body");
                                        if (body == null) return;
                                        String bodyString = (String) XposedHelpers.callMethod(body, "string");
                                        if (bodyString == null || bodyString.isEmpty()) return;

                                        debugLog("[V89] >>> async privilege response caught, body len=" + bodyString.length());
                                        String modifiedBody = modifyPrivilegeResponse(bodyString);
                                        if (modifiedBody != null) {
                                            Object newResponse = rebuildResponse(response, modifiedBody, appClassLoader);
                                            if (newResponse != null) {
                                                hp.args[1] = newResponse;
                                                debugLog("[V89] >>> async privilege response REPLACED successfully");
                                            }
                                        }
                                    } catch (Exception e) {
                                        debugLog("[V89] async privilege hook error: " + e.getMessage());
                                    }
                                }
                            });

                            debugLog("[V89] async privilege callback wrapped");
                        }
                    });
                    debugLog("[V89] Hooked " + foundName + ".enqueue() — async privilege interceptor active");
                } catch (Exception e) {
                    debugLog("[V89] Failed to hook enqueue(): " + e.getMessage());
                }
                break;
            }
        }
    }
}
