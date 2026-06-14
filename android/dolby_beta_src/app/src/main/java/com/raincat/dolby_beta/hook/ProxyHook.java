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

        if (!isPlayProcess)
            findAndHookMethod("com.netease.cloudmusic.activity.LoadingActivity", context.getClassLoader(), "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ExtraHelper.setExtraDate(ExtraHelper.SCRIPT_STATUS, "0");
                    if (SettingHelper.getInstance().getSetting(SettingHelper.proxy_master_key)) {
                        ScriptHelper.initScript(context, false);
                        if (SettingHelper.getInstance().getSetting(SettingHelper.proxy_server_key)) {
                            ScriptHelper.startHttpProxyMode(context);
                        } else {
                            ScriptHelper.startScript();
                        }
                    }
                }
            });
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
}