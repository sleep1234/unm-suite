package com.raincat.dolby_beta.hook;

import android.content.Context;

import com.raincat.dolby_beta.helper.EAPIHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findClassIfExists;

/**
 * EAPI Hook — v4: Production version based on debug findings
 *
 * v9.5.30 architecture (CONFIRMED by v3 debug):
 * - o72.a$d / o72.a$e: HOOK registers but NEVER fires (dead code in v9.5.30)
 * - o72.a.e1(): FIRES! Returns decrypted EAPI batch JSONObject (457KB+)
 *   Keys are URL paths: {"/api/about/config": {...}, "/api/song/.../player/...": {...}}
 * - interceptor.q.a(Chain,Request,Response,d,int)->Response: FIRES! OkHttp interceptor
 *
 * v4 strategy — DUAL PATH:
 * PATH A (Primary): Hook o72.a.e1() — modify the decrypted EAPI batch JSON in-place
 *   - Look for keys containing "player" in the batch response
 *   - For player data, clear fee/flag/payed/freeTrialInfo, replace URL via GD API
 * PATH B (Fallback): Hook interceptor.q.a() — modify at OkHttp Response level
 *   - Check Request URL for player endpoints
 *   - Modify Response body if it contains player JSON
 */
public class EAPIHook {
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

    public EAPIHook(Context context, int versionCode, ClassLoader cl) {
        debugLog("=== EAPIHook v4 init, versionCode=" + versionCode + " ===");

        boolean pathASuccess = false;
        boolean pathBSuccess = false;

        // ===== PATH A: Hook o72.a.e1() for decrypted EAPI batch JSON =====
        debugLog("[V4-PATH-A] Attempting to hook o72.a.e1()...");
        try {
            Class<?> eapiClass = findClassIfExists("o72.a", cl);
            if (eapiClass != null) {
                // e1() returns JSONObject — the decrypted EAPI batch response
                Method e1Method = null;
                for (Method m : eapiClass.getDeclaredMethods()) {
                    if (m.getName().equals("e1") && m.getParameterTypes().length == 0) {
                        e1Method = m;
                        break;
                    }
                }

                if (e1Method != null) {
                    XposedBridge.hookMethod(e1Method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object result = param.getResult();
                            if (result == null) return;

                            if (result instanceof JSONObject) {
                                JSONObject batchJson = (JSONObject) result;
                                JSONObject modified = processBatchResponse(batchJson);
                                if (modified != null) {
                                    param.setResult(modified);
                                }
                            } else if (result instanceof String) {
                                // e1() might return String in some versions
                                String jsonStr = (String) result;
                                if (jsonStr.contains("player") || jsonStr.contains("\"fee\"")) {
                                    debugLog("[V4-PATH-A] e1() returned String containing player data, len=" + jsonStr.length());
                                    // Try to parse and modify
                                    try {
                                        JSONObject batchJson = new JSONObject(jsonStr);
                                        JSONObject modified = processBatchResponse(batchJson);
                                        if (modified != null) {
                                            param.setResult(modified.toString());
                                        }
                                    } catch (Exception e) {
                                        debugLog("[V4-PATH-A] Failed to parse e1() String as JSON: " + e.getMessage());
                                    }
                                }
                            }
                        }
                    });
                    pathASuccess = true;
                    debugLog("[V4-PATH-A] Successfully hooked o72.a.e1()");
                } else {
                    debugLog("[V4-PATH-A] e1() method not found on o72.a");
                }
            } else {
                debugLog("[V4-PATH-A] o72.a class not found");
            }
        } catch (Exception e) {
            debugLog("[V4-PATH-A] Failed to hook e1(): " + e.getMessage());
        }

        // ===== PATH B: Hook interceptor.q.a() for OkHttp Response modification =====
        debugLog("[V4-PATH-B] Attempting to hook interceptor.q.a()...");
        try {
            // v9.5.30+: interceptor class name is known from APK analysis
            String interceptorClassName = "com.netease.cloudmusic.network.interceptor.q";
            Class<?> interceptorClass = findClassIfExists(interceptorClassName, cl);
            if (interceptorClass != null) {
                // Find the a() method with 5 args that returns Response
                Method interceptMethod = null;
                for (Method m : interceptorClass.getDeclaredMethods()) {
                    if (m.getName().equals("a") && m.getParameterTypes().length == 5) {
                        String returnType = m.getReturnType().getName();
                        if (returnType.contains("Response")) {
                            interceptMethod = m;
                            break;
                        }
                    }
                }

                if (interceptMethod != null) {
                    XposedBridge.hookMethod(interceptMethod, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            // args: Chain, Request, Response, d, Integer
                            if (param.args.length < 2) return;

                            Object request = param.args[1]; // OkHttp Request
                            Object response = param.getResult(); // OkHttp Response

                            if (request == null || response == null) return;

                            // Get Request URL
                            String urlStr = null;
                            try {
                                Object url = XposedHelpers.callMethod(request, "url");
                                urlStr = XposedHelpers.callMethod(url, "toString").toString();
                            } catch (Exception e) {
                                // Try alternative
                                try {
                                    urlStr = request.toString();
                                } catch (Exception ignored) {}
                            }

                            if (urlStr == null) return;

                            // Check if this is a player-related request
                            // EAPI requests go to /eapi/... with the actual API path in the body
                            // But the URL might also contain "player" directly
                            boolean isPlayerRequest = urlStr.contains("player") ||
                                    urlStr.contains("/eapi/song") ||
                                    urlStr.contains("/api/song");

                            if (!isPlayerRequest) return;

                            debugLog("[V4-PATH-B] Player-related request detected: " +
                                    urlStr.substring(0, Math.min(120, urlStr.length())));

                            // Try to get and modify the Response body
                            try {
                                Object responseBody = XposedHelpers.callMethod(response, "body");
                                if (responseBody == null) return;

                                String bodyStr = XposedHelpers.callMethod(responseBody, "string").toString();

                                // Check if body contains player data
                                if (!bodyStr.contains("fee") && !bodyStr.contains("player")) {
                                    // Not player data, but we already consumed the body — need to rebuild Response
                                    rebuildResponse(param, response, bodyStr);
                                    return;
                                }

                                debugLog("[V4-PATH-B] Response body contains player data, len=" + bodyStr.length());

                                // Try to modify the player data
                                String modifiedBody = tryModifyPlayerBody(bodyStr);
                                if (modifiedBody != null) {
                                    debugLog("[V4-PATH-B] Player data modified, rebuilding Response");
                                    rebuildResponse(param, response, modifiedBody);
                                } else {
                                    // Body was consumed, need to rebuild even if not modified
                                    rebuildResponse(param, response, bodyStr);
                                }
                            } catch (Exception e) {
                                debugLog("[V4-PATH-B] Error processing Response: " + e.getMessage());
                            }
                        }
                    });
                    pathBSuccess = true;
                    debugLog("[V4-PATH-B] Successfully hooked interceptor.q.a()");
                } else {
                    debugLog("[V4-PATH-B] interceptor.q.a(5args) method not found");
                }
            } else {
                debugLog("[V4-PATH-B] interceptor class not found");
            }
        } catch (Exception e) {
            debugLog("[V4-PATH-B] Failed to hook interceptor: " + e.getMessage());
        }

        debugLog("=== EAPIHook v4 init complete: PATH_A=" + pathASuccess + " PATH_B=" + pathBSuccess + " ===");
    }

    /**
     * Process the decrypted EAPI batch JSON response.
     * The batch format: {"/api/path1": {response1}, "/api/path2": {response2}, ...}
     * We look for keys containing "player" and modify the player data.
     *
     * @return modified JSONObject if changes were made, null if no changes needed
     */
    private JSONObject processBatchResponse(JSONObject batchJson) {
        try {
            boolean modified = false;
            Iterator<String> keys = batchJson.keys();

            while (keys.hasNext()) {
                String key = keys.next();
                // Check if this key is a player-related API path
                if (key.contains("player") || key.contains("/api/song/enhance")) {
                    debugLog("[V4-PATH-A] Found player key in batch: " + key);

                    Object value = batchJson.get(key);
                    if (value instanceof JSONObject) {
                        JSONObject playerResponse = (JSONObject) value;
                        JSONObject modifiedPlayer = modifyPlayerResponse(playerResponse);
                        if (modifiedPlayer != null) {
                            batchJson.put(key, modifiedPlayer);
                            modified = true;
                            debugLog("[V4-PATH-A] Player data modified for key: " + key);
                        }
                    }
                }
            }

            return modified ? batchJson : null;
        } catch (Exception e) {
            debugLog("[V4-PATH-A] processBatchResponse error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Modify a player API response JSONObject.
     * Format: {"code":200, "data":[{song1},{song2},...]} or {"code":200, "data":{"url":"...", "fee":1, ...}}
     *
     * @return modified JSONObject if changes were made, null if no changes needed
     */
    private JSONObject modifyPlayerResponse(JSONObject playerResponse) {
        try {
            if (!playerResponse.has("data")) return null;

            Object data = playerResponse.get("data");
            boolean modified = false;

            if (data instanceof JSONArray) {
                // Multiple songs: data is an array
                JSONArray songs = (JSONArray) data;
                for (int i = 0; i < songs.length(); i++) {
                    JSONObject song = songs.getJSONObject(i);
                    if (modifySongData(song)) {
                        modified = true;
                    }
                }
            } else if (data instanceof JSONObject) {
                // Single song: data is an object
                modified = modifySongData((JSONObject) data);
            }

            return modified ? playerResponse : null;
        } catch (Exception e) {
            debugLog("[V4] modifyPlayerResponse error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Modify a single song's data in the player response.
     * Clear fee/flag/payed/freeTrialInfo and replace URL for trial songs.
     *
     * @return true if modifications were made
     */
    private boolean modifySongData(JSONObject song) {
        try {
            int fee = song.optInt("fee", 0);
            int flag = song.optInt("flag", 0);
            int payed = song.optInt("payed", 0);
            boolean hasFreeTrial = song.has("freeTrialInfo") && !song.isNull("freeTrialInfo");
            long songId = song.optLong("id", 0);
            String url = song.optString("url", "");

            // Only modify if the song needs unlocking
            if (fee == 0 && flag == 0 && payed != 0 && !hasFreeTrial) {
                return false; // Already free
            }

            debugLog("[V4] Modifying song id=" + songId + " fee=" + fee + " flag=" + flag +
                    " payed=" + payed + " hasFreeTrial=" + hasFreeTrial +
                    " url=" + url.substring(0, Math.min(80, url.length())));

            // Clear VIP restrictions
            song.put("fee", 0);
            song.put("flag", 0);
            song.put("payed", 0);
            song.put("freeTrialInfo", JSONObject.NULL);

            // For songs that had free trial (30s preview), replace URL via GD API
            if (hasFreeTrial && songId > 0) {
                debugLog("[V4] Song " + songId + " had freeTrial, fetching from GD API...");
                String gdUrl = EAPIHelper.fetchUrlFromGD(songId, 999);
                if (gdUrl != null) {
                    song.put("url", gdUrl);
                    debugLog("[V4] Song " + songId + " URL replaced via GD API");
                } else {
                    debugLog("[V4] Song " + songId + " GD API failed, keeping original URL");
                }
            }

            return true;
        } catch (Exception e) {
            debugLog("[V4] modifySongData error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Try to modify player data in a Response body string.
     * Handles both EAPI batch format and direct player response format.
     *
     * @return modified body string if changes were made, null if no changes needed
     */
    private String tryModifyPlayerBody(String bodyStr) {
        try {
            JSONObject json = new JSONObject(bodyStr);

            // Check if it's a batch response (keys are URL paths)
            boolean isBatch = false;
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.startsWith("/api/") || key.startsWith("/eapi/")) {
                    isBatch = true;
                    break;
                }
            }

            if (isBatch) {
                JSONObject modified = processBatchResponse(json);
                return modified != null ? modified.toString() : null;
            } else if (json.has("data") && (json.has("fee") || bodyStr.contains("player"))) {
                // Direct player response
                JSONObject modified = modifyPlayerResponse(json);
                return modified != null ? modified.toString() : null;
            }

            return null;
        } catch (Exception e) {
            debugLog("[V4] tryModifyPlayerBody error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Rebuild an OkHttp Response with a new body string.
     * This is necessary because calling response.body().string() consumes the body.
     */
    private void rebuildResponse(XC_MethodHook.MethodHookParam param, Object response, String newBodyStr) {
        try {
            // Create a new ResponseBody from the string
            Object mediaType = null;
            try {
                Object oldBody = XposedHelpers.callMethod(response, "body");
                mediaType = XposedHelpers.callMethod(oldBody, "contentType");
            } catch (Exception ignored) {}

            // okhttp3.ResponseBody.create(contentType, content)
            Class<?> responseBodyClass = XposedHelpers.findClass("okhttp3.ResponseBody", null);
            Object newBody;
            if (mediaType != null) {
                newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create",
                        mediaType, newBodyStr);
            } else {
                newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create",
                        newBodyStr);
            }

            // response.newBuilder().body(newBody).build()
            Object builder = XposedHelpers.callMethod(response, "newBuilder");
            Object builderWithBody = XposedHelpers.callMethod(builder, "body", newBody);
            Object newResponse = XposedHelpers.callMethod(builderWithBody, "build");

            param.setResult(newResponse);
        } catch (Exception e) {
            debugLog("[V4] rebuildResponse error: " + e.getMessage());
        }
    }
}
