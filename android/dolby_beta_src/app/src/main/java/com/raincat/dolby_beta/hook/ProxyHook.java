package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.os.Bundle;

import com.raincat.dolby_beta.helper.ExtraHelper;
import com.raincat.dolby_beta.helper.ScriptHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLSocketFactory;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedBridge.hookAllConstructors;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/09/08
 *     desc   : 代理
 *     version: 1.0
 * </pre>
 */

public class ProxyHook {
    private static SSLSocketFactory socketFactory;
    private static Object objectProxy;
    private static Object objectSSLSocketFactory;

    private String fieldSSLSocketFactory;
    private String fieldHttpUrl = "url";
    private String fieldProxy = "proxy";

    private final List<String> whiteUrlList = Arrays.asList("song/enhance/player/url", "song/enhance/download/url");

    public ProxyHook(Context context, boolean isPlayProcess) {
        Class<?> realCallClass = findClassIfExists("okhttp3.internal.connection.RealCall", context.getClassLoader());
        if (realCallClass != null) {
            fieldSSLSocketFactory = "sslSocketFactoryOrNull";
        } else {
            realCallClass = findClassIfExists("okhttp3.RealCall", context.getClassLoader());
            if (realCallClass != null)
                fieldSSLSocketFactory = "sslSocketFactory";
            else {
                realCallClass = findClassIfExists("okhttp3.z", context.getClassLoader());
                fieldSSLSocketFactory = "o";
                fieldHttpUrl = "a";
                fieldProxy = "d";
            }
        }

        // 9.x+ 兜底：通过 dex 扫描查找 RealCall
        if (realCallClass == null) {
            XposedBridge.log("[DolbyBeta] Standard RealCall not found, trying dex scan fallback");
            try {
                // 尝试通过类加载器查找所有 okhttp3 下的类
                for (String candidate : new String[]{
                        "okhttp3.internal.http.RealCall",
                        "okhttp3.internal.connection.RealCall",
                        "okhttp3.a",
                        "okhttp3.b",
                        "okhttp3.c",
                        "okhttp3.d",
                        "okhttp3.e",
                        "okhttp3.f",
                        "okhttp3.h",
                        "okhttp3.i",
                        "okhttp3.j",
                        "okhttp3.k",
                        "okhttp3.l",
                        "okhttp3.m",
                        "okhttp3.n",
                        "okhttp3.p",
                        "okhttp3.q",
                        "okhttp3.r",
                        "okhttp3.s",
                        "okhttp3.t",
                        "okhttp3.u",
                        "okhttp3.v",
                        "okhttp3.w",
                        "okhttp3.x",
                        "okhttp3.y"
                }) {
                    Class<?> candidateClass = findClassIfExists(candidate, context.getClassLoader());
                    if (candidateClass != null) {
                        // 检查是否有3参数构造函数 (OkHttpClient, Request, boolean)
                        try {
                            candidateClass.getDeclaredConstructor(candidateClass.getClassLoader().loadClass("okhttp3.OkHttpClient"),
                                    candidateClass.getClassLoader().loadClass("okhttp3.Request"), boolean.class);
                            realCallClass = candidateClass;
                            fieldSSLSocketFactory = "sslSocketFactoryOrNull";
                            fieldProxy = "proxy";
                            fieldHttpUrl = "url";
                            XposedBridge.log("[DolbyBeta] Found RealCall fallback: " + candidate);
                            break;
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                XposedBridge.log("[DolbyBeta] Dex scan fallback failed: " + e.getMessage());
            }
        }

        if (realCallClass == null) {
            XposedBridge.log("[DolbyBeta] RealCall class not found, proxy hook skipped entirely");
            return;
        }

        hookAllConstructors(realCallClass, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args.length == 3) {
                    Object client = param.args[0];
                    Object request = param.args[1];

                    Field urlField = request.getClass().getDeclaredField(fieldHttpUrl);
                    urlField.setAccessible(true);
                    Object urlObj = urlField.get(request);
                    for (String url : whiteUrlList) {
                        if (urlObj.toString().contains(url)) {
                            setProxy(context, client);
                            break;
                        }
                    }
                }
            }
        });

        Class<?> okHttpClientBuilderClass = XposedHelpers.findClassIfExists("okhttp3.OkHttpClient$Builder", context.getClassLoader());
        if (okHttpClientBuilderClass != null) {
            XposedBridge.hookAllMethods(okHttpClientBuilderClass, "addInterceptor", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    String className = param.args[0].getClass().getName();
                    // 屏蔽 cronet 拦截器（新版类名可能变化，用 contains 匹配）
                    if (className.contains("cronet") || className.contains("Cronet"))
                        param.setResult(param.thisObject);
//                        XposedBridge.hookAllMethods(param.args[0].getClass(), "intercept", new XC_MethodHook() {
//                            @Override
//                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
//                                super.beforeHookedMethod(param);
//                                Object object = param.args[0];
//                                if (object != null && object.getClass().getName().contains("Chain")) {
//                                    Object request = XposedHelpers.callMethod(object, "request");
//                                    if (request.toString().contains("song/enhance/player/url") || request.toString().contains("song/enhance/download/url")) {
//                                        Object response = XposedHelpers.callMethod(object, "proceed", request);
//                                        param.setResult(response);
//                                    }
//                                }
//                            }
//                        });
                }
            });
        }

        if (!isPlayProcess) {
            // Script startup hook: start UNM proxy when the app launches.
            // IMPORTANT: In v9.5.30+, LoadingActivity still exists as a class in the DEX but is
            // NOT registered in AndroidManifest, so its onCreate is never called by the system.
            // We must try MainActivity FIRST (the actual launcher activity), and only fall back
            // to LoadingActivity for very old versions where it was still the entry point.
            boolean hooked = false;

            Class<?> mainClass = findClassIfExists("com.netease.cloudmusic.activity.MainActivity", context.getClassLoader());
            if (mainClass != null) {
                XposedBridge.log("[dolby_beta] ProxyHook: hooking MainActivity for script startup");
                // Use a flag to ensure script init runs only once per process lifetime,
                // since MainActivity.onCreate may be called multiple times (e.g. task restore).
                findAndHookMethod(mainClass, "onCreate", Bundle.class, new XC_MethodHook() {
                    private boolean scriptStarted = false;
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (scriptStarted) return;
                        scriptStarted = true;
                        startProxyScript(context);
                    }
                });
                hooked = true;
            }

            if (!hooked) {
                Class<?> loadingClass = findClassIfExists("com.netease.cloudmusic.activity.LoadingActivity", context.getClassLoader());
                if (loadingClass != null) {
                    XposedBridge.log("[dolby_beta] ProxyHook: MainActivity not found, hooking LoadingActivity for script startup (old version)");
                    findAndHookMethod(loadingClass, "onCreate", Bundle.class, scriptStartupHook(context));
                    hooked = true;
                }
            }

            if (!hooked) {
                XposedBridge.log("[dolby_beta] ProxyHook: neither MainActivity nor LoadingActivity found, script will not auto-start");
            }
        }
    }

    /**
     * 设置代理
     */
    private void setProxy(Context context, Object client) throws Exception {
        //保存正常的代理与SSL
        Field sslSocketFactoryField = client.getClass().getDeclaredField(fieldSSLSocketFactory);
        sslSocketFactoryField.setAccessible(true);
        Field proxyField = client.getClass().getDeclaredField(fieldProxy);
        proxyField.setAccessible(true);
        if (objectProxy == null)
            objectProxy = proxyField.get(client);
        if (objectSSLSocketFactory == null)
            objectSSLSocketFactory = sslSocketFactoryField.get(client);

        if (ExtraHelper.getExtraDate(ExtraHelper.SCRIPT_STATUS).equals("1")) {
            String httpUrlHost = SettingHelper.getInstance().getSetting(SettingHelper.proxy_server_key) ?
                    SettingHelper.getInstance().getHttpProxy() : "127.0.0.1";
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(httpUrlHost, SettingHelper.getInstance().getProxyPort()));
            proxyField.set(client, proxy);
            if (socketFactory == null)
                socketFactory = ScriptHelper.getSSLSocketFactory(context);
            if (socketFactory != null)
                sslSocketFactoryField.set(client, socketFactory);
        } else {
            proxyField.set(client, objectProxy);
            sslSocketFactoryField.set(client, objectSSLSocketFactory);
        }
    }

    /**
     * Create an XC_MethodHook that starts the UNM proxy script.
     */
    private XC_MethodHook scriptStartupHook(final Context context) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                startProxyScript(context);
            }
        };
    }

    /**
     * Start the UnblockNeteaseMusic proxy script.
     *
     * Strategy for v9.5.30+:
     * - Server proxy mode (proxy_server_key=true): Directly set SCRIPT_STATUS="1" and route
     *   traffic to the remote UNM server (e.g. NAS). No local libnode.so needed.
     * - Local mode (proxy_server_key=false): Try launching libnode.so, but if it fails to
     *   output "HTTP Server running" within 5 seconds, automatically fall back to server
     *   proxy mode. This handles v9.5.30+ where libnode.so execution silently fails.
     */
    private static void startProxyScript(Context context) {
        ExtraHelper.setExtraDate(ExtraHelper.SCRIPT_STATUS, "0");
        if (SettingHelper.getInstance().getSetting(SettingHelper.proxy_master_key)) {
            XposedBridge.log("[dolby_beta] ProxyHook: proxy_master_key is ON, starting script");
            ScriptHelper.initScript(context, false);
            if (SettingHelper.getInstance().getSetting(SettingHelper.proxy_server_key)) {
                // Server proxy mode — just mark as running, route traffic to remote server
                XposedBridge.log("[dolby_beta] ProxyHook: using server proxy mode");
                ScriptHelper.startHttpProxyMode(context);
            } else {
                // Local mode — try libnode.so, fallback to server proxy if it fails
                XposedBridge.log("[dolby_beta] ProxyHook: trying local script mode, will fallback to server proxy if it fails");
                ScriptHelper.startScript();

                // Schedule a fallback: if local script doesn't set SCRIPT_STATUS="1" within
                // 5 seconds, switch to server proxy mode automatically.
                final Context appContext = context.getApplicationContext();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (ExtraHelper.getExtraDate(ExtraHelper.SCRIPT_STATUS).equals("0")) {
                        XposedBridge.log("[dolby_beta] ProxyHook: local script failed to start within 5s, falling back to server proxy mode");
                        ScriptHelper.stopScript();
                        ScriptHelper.startHttpProxyMode(appContext);
                    } else {
                        XposedBridge.log("[dolby_beta] ProxyHook: local script started successfully, no fallback needed");
                    }
                }, 5000);
            }
        } else {
            XposedBridge.log("[dolby_beta] ProxyHook: proxy_master_key is OFF, script not started");
        }
    }
}