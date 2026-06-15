package com.raincat.dolby_beta.helper;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import com.annimon.stream.Stream;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.dexbacked.DexBackedClassDef;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;
import static de.robv.android.xposed.XposedHelpers.findMethodsByExactParameters;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/04/14
 *     desc   : 类加载帮助
 *     version: 1.0
 * </pre>
 */

public class ClassHelper {
    //类加载器
    private static ClassLoader classLoader = null;
    //dex缓存
    private static List<String> classCacheList = null;
    //dex缓存路径
    private static String classCachePath = null;
    //网易云版本
    private static int versionCode = 0;

    public static synchronized void getCacheClassList(final Context context, final int version, final OnCacheClassListener listener) {
        if (classLoader == null) {
            classLoader = context.getClassLoader();
            versionCode = version;
            File cacheFile = Objects.requireNonNull(context.getExternalFilesDir(null));
            if (cacheFile.exists() || cacheFile.mkdirs())
                classCachePath = cacheFile.getPath();
        }
        if (classCacheList == null) {
            if (SettingHelper.getInstance().isEnable(SettingHelper.dex_key))
                classCacheList = FileHelper.readFileFromSD(classCachePath + File.separator + "class-" + version);
            else
                classCacheList = new ArrayList<>();
            if (classCacheList.size() == 0) {
                new Thread(() -> getCacheClassByZip(context, version, listener)).start();
            } else
                listener.onGet();
        } else
            listener.onGet();
    }

    private static synchronized void getCacheClassByZip(Context context, int version, OnCacheClassListener listener) {
        try {
            // 不用 ZipDexContainer 因为会验证zip里面的文件是不是dex，会慢一点
            File appInstallFile = new File(context.getPackageResourcePath());
            Enumeration<? extends ZipEntry> zip = new ZipFile(appInstallFile).entries();
            while (zip.hasMoreElements()) {
                ZipEntry dexInZip = zip.nextElement();
                if (dexInZip.getName().startsWith("classes") && dexInZip.getName().endsWith(".dex")) {
                    MultiDexContainer.DexEntry<? extends DexBackedDexFile> dexEntry = DexFileFactory.loadDexEntry(appInstallFile, dexInZip.getName(), true, null);
                    DexBackedDexFile dexFile = dexEntry.getDexFile();
                    for (DexBackedClassDef classDef : dexFile.getClasses()) {
                        String classType = classDef.getType();
                        if (classType.contains("com/netease/cloudmusic") || classType.contains("okhttp3")) {
                            classType = classType.substring(1, classType.length() - 1).replace("/", ".");
                            classCacheList.add(classType);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            FileHelper.writeFileFromSD(classCachePath + File.separator + "class-" + version, classCacheList);
            listener.onGet();
        }
    }

    public interface OnCacheClassListener {
        void onGet();
    }

    public static List<String> getFilteredClasses(Pattern pattern, Comparator<String> comparator) {
        List<String> list = Stream.of(classCacheList)
                .filter(s -> pattern.matcher(s).find())
                .toList();
        Collections.sort(list, comparator);
        return list;
    }

    private static Class<?> getClassByXposed(String className) {
        Class<?> clazz = findClassIfExists(className, classLoader);
        if (clazz == null)
            clazz = findClassIfExists("com.netease.cloudmusic.NeteaseMusicApplication", classLoader);
        return clazz;
    }

    public static class Cookie {
        private static Class<?> clazz, abstractClazz;

        public static String getCookie(Context context) {
            if (clazz == null) {
                if (versionCode >= 8007000) {
                    // v9.5.30+: direct class name lookup based on APK analysis
                    XposedBridge.log("[dolby_beta] Cookie: trying direct class names for v9.5.30+");
                    abstractClazz = findClassIfExists("com.netease.cloudmusic.network.cookie.store.AbsCookieStore", classLoader);
                    // Try known concrete subclass names from APK analysis
                    String[] concreteNames = {
                            "com.netease.cloudmusic.network.cookie.store.CloudMusicCookieStore",
                            "com.netease.cloudmusic.network.cookie.store.LookCookieStore",
                            "com.netease.cloudmusic.network.cookie.store.MusCookieStore",
                            "com.netease.cloudmusic.network.cookie.store.WatchCookieStore",
                            "com.netease.cloudmusic.network.cookie.store.CustomCookieStore"
                    };
                    for (String name : concreteNames) {
                        Class<?> candidate = findClassIfExists(name, classLoader);
                        if (candidate != null && !Modifier.isAbstract(candidate.getModifiers())) {
                            clazz = candidate;
                            XposedBridge.log("[dolby_beta] Cookie: found concrete class " + name);
                            break;
                        }
                    }
                    if (clazz == null && abstractClazz != null) {
                        // Fallback: scan for any non-abstract subclass of AbsCookieStore
                        XposedBridge.log("[dolby_beta] Cookie: direct names failed, scanning for subclass");
                        try {
                            Pattern broadPattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\.cookie\\..+$");
                            List<String> subList = getFilteredClasses(broadPattern, null);
                            clazz = Stream.of(subList)
                                    .map(ClassHelper::getClassByXposed)
                                    .filter(c -> c != null && c != abstractClazz)
                                    .filter(c -> Modifier.isPublic(c.getModifiers()))
                                    .filter(c -> !Modifier.isInterface(c.getModifiers()))
                                    .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                                    .filter(c -> c.getSuperclass() == abstractClazz || 
                                                 (c.getSuperclass() != null && c.getSuperclass().getSuperclass() == abstractClazz))
                                    .findFirst()
                                    .orElse(abstractClazz);
                        } catch (Exception e) {
                            XposedBridge.log("[dolby_beta] Cookie: subclass scan failed: " + e.getMessage());
                            clazz = abstractClazz;
                        }
                    }
                    if (abstractClazz == null && clazz == null) {
                        XposedBridge.log("[dolby_beta] Cookie: all lookup methods failed");
                        MessageHelper.sendNotification(context, MessageHelper.cookieClassNotFoundCode);
                    }
                } else if (versionCode >= 800) {
                    // Earlier v9.x: try AbsCookieStore + scan
                    XposedBridge.log("[dolby_beta] Cookie: trying AbsCookieStore scan");
                    abstractClazz = findClassIfExists("com.netease.cloudmusic.network.cookie.store.AbsCookieStore", classLoader);
                    if (abstractClazz != null) {
                        try {
                            Pattern broadPattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\.cookie\\..+$");
                            List<String> subList = getFilteredClasses(broadPattern, null);
                            clazz = Stream.of(subList)
                                    .map(ClassHelper::getClassByXposed)
                                    .filter(c -> c != null && c != abstractClazz)
                                    .filter(c -> Modifier.isPublic(c.getModifiers()))
                                    .filter(c -> !Modifier.isInterface(c.getModifiers()))
                                    .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                                    .filter(c -> c.getSuperclass() == abstractClazz)
                                    .findFirst()
                                    .orElse(abstractClazz);
                        } catch (Exception e) {
                            clazz = abstractClazz;
                        }
                    }
                    if (abstractClazz == null) {
                        XposedBridge.log("[dolby_beta] Cookie: AbsCookieStore not found");
                        MessageHelper.sendNotification(context, MessageHelper.cookieClassNotFoundCode);
                    }
                } else {
                    // Legacy versions
                    Pattern pattern;
                    if (versionCode < 154)
                        pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.[a-z]\\.[a-z]\\.[a-z]\\.[a-z]$");
                    else
                        pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\.[a-z]\\.[a-z]\\.[a-z]$");
                    List<String> list = getFilteredClasses(pattern, null);

                    try {
                        abstractClazz = Stream.of(list)
                                .map(ClassHelper::getClassByXposed)
                                .filter(c -> Modifier.isPublic(c.getModifiers()))
                                .filter(c -> c.getSuperclass() == Object.class)
                                .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == ConcurrentHashMap.class))
                                .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == SharedPreferences.class))
                                .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == long.class))
                                .findFirst()
                                .get();

                        if (versionCode >= 154) {
                            clazz = Stream.of(list)
                                    .map(ClassHelper::getClassByXposed)
                                    .filter(c -> Modifier.isPublic(c.getModifiers()))
                                    .filter(m -> !Modifier.isInterface(m.getModifiers()))
                                    .filter(c -> {
                                        try {
                                            return c.getSuperclass() == abstractClazz;
                                        } catch (Exception e) {
                                            return false;
                                        }
                                    })
                                    .findFirst()
                                    .orElse(abstractClazz);
                        } else {
                            clazz = abstractClazz;
                        }
                    } catch (NoSuchElementException e) {
                        MessageHelper.sendNotification(context, MessageHelper.cookieClassNotFoundCode);
                    }
                }
            }

            Object cookieString = null;
            if (clazz == null) {
                XposedBridge.log("[dolby_beta] Cookie.getCookie: Cookie class not found, returning empty cookie");
                return "";
            }
            if (versionCode >= 154) {
                //获取静态cookie方法
                try {
                    Method[] methods = XposedHelpers.findMethodsByExactParameters(clazz, clazz);
                    if (methods == null || methods.length == 0) return "";
                    Method cookieMethod = methods[0];
                    Object cookie = XposedHelpers.callStaticMethod(clazz, cookieMethod.getName());
                    if (abstractClazz != null) {
                        for (Method method : XposedHelpers.findMethodsByExactParameters(abstractClazz, String.class)) {
                            if (method.getTypeParameters().length == 0 && method.getModifiers() == Modifier.PUBLIC) {
                                cookieString = XposedHelpers.callMethod(cookie, method.getName());
                            }
                        }
                    }
                } catch (Exception e) {
                    XposedBridge.log("[dolby_beta] Cookie.getCookie exception: " + e.getMessage());
                    return "";
                }
            } else {
                try {
                    Method[] methods = XposedHelpers.findMethodsByExactParameters(clazz, String.class);
                    if (methods == null || methods.length == 0) return "";
                    cookieString = XposedHelpers.callStaticMethod(clazz, methods[0].getName());
                } catch (Exception e) {
                    XposedBridge.log("[dolby_beta] Cookie.getCookie exception: " + e.getMessage());
                    return "";
                }
            }

            return "MUSIC_U=" + cookieString;
        }
    }

    public static class DownloadTransfer {
        private static Method checkMd5Method;
        private static Method checkDownloadStatusMethod;

        //下载完后的MD5检查
        public static Method getCheckMd5Method(Context context) {
            if (checkMd5Method == null) {
                Pattern pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.module\\.transfer\\.download\\.[a-z0-9]{1,2}$");
                List<String> list = ClassHelper.getFilteredClasses(pattern, Collections.reverseOrder());

                try {
                    checkMd5Method = Stream.of(list)
                            .map(c -> getClassByXposed(c).getDeclaredMethods())
                            .flatMap(Stream::of)
                            .filter(m -> m.getParameterTypes().length == 4)
                            .filter(m -> m.getParameterTypes()[0] == File.class)
                            .filter(m -> m.getParameterTypes()[1] == File.class)
                            .findFirst()
                            .get();
                } catch (NoSuchElementException e) {
                    MessageHelper.sendNotification(context, MessageHelper.transferClassNotFoundCode);
                }
            }
            return checkMd5Method;
        }

        //下载之前下载状态检查
        public static Method getCheckDownloadStatusMethod(Context context) {
            if (checkDownloadStatusMethod == null) {
                Pattern pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.module\\.transfer\\.download\\.[a-z0-9]{1,2}$");
                List<String> list = ClassHelper.getFilteredClasses(pattern, Collections.reverseOrder());

                try {
                    checkDownloadStatusMethod = Stream.of(list)
                            .map(c -> getClassByXposed(c).getDeclaredMethods())
                            .flatMap(Stream::of)
                            .filter(m -> m.getReturnType() == long.class)
                            .filter(m -> m.getParameterTypes().length == 5)
                            .filter(m -> m.getParameterTypes()[1] == int.class)
                            .filter(m -> m.getParameterTypes()[3] == File.class)
                            .filter(m -> m.getParameterTypes()[4] == long.class)
                            .findFirst()
                            .get();
                } catch (NoSuchElementException e) {
                    MessageHelper.sendNotification(context, MessageHelper.transferClassNotFoundCode);
                }
            }
            return checkDownloadStatusMethod;
        }
    }

    public static class MainActivitySuperClass {
        private static Class<?> clazz;
        private static List<Method> methods;
        private static Method method;

        static void getClazz(Context context) {
            if (clazz == null) {
                Class<?> mainActivityClass = findClass("com.netease.cloudmusic.activity.MainActivity", context.getClassLoader());
                clazz = mainActivityClass.getSuperclass();
            }
        }

        public static List<Method> getTabItemStringMethods(Context context) {
            if (clazz == null)
                getClazz(context);
            if (methods == null && clazz != null) {
                List<Method> methodList = Arrays.asList(clazz.getDeclaredMethods());
                methods = Stream.of(methodList)
                        .filter(m -> m.getParameterTypes().length >= 1)
                        .filter(m -> m.getReturnType() == void.class)
                        .filter(m -> m.getParameterTypes()[0] == String[].class)
                        .filter(m -> Modifier.isPublic(m.getModifiers()))
                        .toList();
            }
            return methods;
        }

        public static Method getViewPagerInitMethod(Context context) {
            if (method == null) {
                try {
                    List<Method> methodList = Arrays.asList(findClass("com.netease.cloudmusic.activity.MainActivity", context.getClassLoader()).getDeclaredMethods());
                    method = Stream.of(methodList)
                            .filter(m -> m.getParameterTypes().length == 1)
                            .filter(m -> m.getReturnType() == void.class)
                            .filter(m -> m.getParameterTypes()[0] == Intent.class)
                            .filter(m -> Modifier.isPrivate(m.getModifiers()))
                            .findFirst()
                            .get();
                } catch (Exception e) {
                    MessageHelper.sendNotification(context, MessageHelper.tabClassNotFoundCode);
                }
            }
            return method;
        }
    }

    public static class BottomTabView {
        private static Class<?> clazz;
        private static Method initMethod, refreshMethod;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                try {
                    Pattern pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.module\\.[a-z0-9]{1,2}\\.[a-z]$");
                    Pattern pattern2 = Pattern.compile("^com\\.netease\\.cloudmusic\\.[a-z0-9]{1,2}\\.[a-z]\\.[a-z]$");
                    Pattern pattern3 = Pattern.compile("^com\\.netease\\.cloudmusic\\.module\\.main\\.[a-z]$");
                    List<String> list = ClassHelper.getFilteredClasses(pattern, Collections.reverseOrder());
                    list.addAll(ClassHelper.getFilteredClasses(pattern2, Collections.reverseOrder()));
                    list.addAll(ClassHelper.getFilteredClasses(pattern3, Collections.reverseOrder()));
                    clazz = Stream.of(list)
                            .map(ClassHelper::getClassByXposed)
                            .filter(c -> Modifier.isPublic(c.getModifiers()))
                            .filter(m -> Modifier.isFinal(m.getModifiers()))
                            .filter(m -> !Modifier.isInterface(m.getModifiers()))
                            .filter(m -> !Modifier.isStatic(m.getModifiers()))
                            .filter(m -> !Modifier.isAbstract(m.getModifiers()))
                            .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == String.class))
                            .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == ArrayList.class))
                            .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == boolean.class))
                            .filter(c -> Stream.of(c.getDeclaredMethods()).anyMatch(m -> m.getReturnType() == ArrayList.class && Modifier.isFinal(m.getModifiers()) && m.getParameterTypes().length == 0))
                            .filter(c -> Stream.of(c.getDeclaredMethods()).anyMatch(m -> m.getReturnType() == String[].class && Modifier.isFinal(m.getModifiers()) && m.getParameterTypes().length == 0))
                            .findFirst()
                            .get();
                } catch (NoSuchElementException e) {
                    MessageHelper.sendNotification(context, MessageHelper.tabClassNotFoundCode);
                }
            }
            return clazz;
        }

        public static Method getTabInitMethod(Context context) {
            if (initMethod == null) {
                Method[] methods = findMethodsByExactParameters(clazz, ArrayList.class);
                if (methods.length != 0)
                    initMethod = methods[0];
                else
                    MessageHelper.sendNotification(context, MessageHelper.tabClassNotFoundCode);
            }
            return initMethod;
        }

        public static Method getTabRefreshMethod(Context context) {
            if (refreshMethod == null) {
                Method[] methods = findMethodsByExactParameters(clazz, void.class, List.class);
                if (methods.length != 0)
                    refreshMethod = methods[0];
                else
                    MessageHelper.sendNotification(context, MessageHelper.tabClassNotFoundCode);
            }
            return refreshMethod;
        }
    }

    public static class SidebarItem {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                try {
                    Pattern pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.module\\.account\\.[a-z]$");
                    Pattern pattern2 = Pattern.compile("^com\\.netease\\.cloudmusic\\.music\\.biz\\.sidebar\\.account\\.[a-z0-9]{1,2}$");
                    List<String> list = ClassHelper.getFilteredClasses(pattern, Collections.reverseOrder());
                    list.addAll(ClassHelper.getFilteredClasses(pattern2, Collections.reverseOrder()));
                    clazz = Stream.of(list)
                            .map(ClassHelper::getClassByXposed)
                            .filter(c -> Modifier.isPublic(c.getModifiers()))
                            .filter(m -> Modifier.isFinal(m.getModifiers()))
                            .filter(m -> !Modifier.isInterface(m.getModifiers()))
                            .filter(m -> !Modifier.isStatic(m.getModifiers()))
                            .filter(m -> !Modifier.isAbstract(m.getModifiers()))
                            .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == int.class))
                            .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == List.class))
                            .filter(c -> Stream.of(c.getDeclaredMethods()).anyMatch(m -> m.getReturnType() == List.class))
                            .filter(c -> Stream.of(c.getDeclaredMethods()).anyMatch(m -> m.getReturnType() == Throwable.class))
                            .findFirst()
                            .get();
                } catch (NoSuchElementException e) {
                    MessageHelper.sendNotification(context, MessageHelper.sidebarClassNotFoundCode);
                }
            }
            return clazz;
        }
    }

    /**
     * 评论
     */
    public static class CommentDataClass {
        private static Class<?> clazz;

        public static Class<?> getClazz() {
            if (clazz == null) {
                try {
                    Pattern pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.module\\.comment2\\.[a-z]\\.[a-z]$");
                    Pattern pattern2 = Pattern.compile("^com\\.netease\\.cloudmusic\\.music\\.biz\\.comment\\.[a-z]\\.[a-z]$");
                    Pattern pattern3 = Pattern.compile("^com\\.netease\\.cloudmusic\\.music\\.biz\\.comment\\.viewmodel\\.[a-z]$");
                    List<String> list = ClassHelper.getFilteredClasses(pattern, Collections.reverseOrder());
                    list.addAll(ClassHelper.getFilteredClasses(pattern2, Collections.reverseOrder()));
                    list.addAll(ClassHelper.getFilteredClasses(pattern3, Collections.reverseOrder()));
                    clazz = Stream.of(list)
                            .map(ClassHelper::getClassByXposed)
                            .filter(c -> Modifier.isPublic(c.getModifiers()))
                            .filter(m -> !Modifier.isInterface(m.getModifiers()))
                            .filter(m -> !Modifier.isStatic(m.getModifiers()))
                            .filter(m -> !Modifier.isAbstract(m.getModifiers()))
                            .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == int.class))
                            .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == List.class))
                            .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == Intent.class))
                            .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == String.class))
                            .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == long.class))
                            .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == boolean.class))
                            .findFirst()
                            .get();
                } catch (NoSuchElementException e) {
                    e.printStackTrace();
                }
            }
            return clazz;
        }
    }

    /**
     * 广告
     */
    public static class Ad {
        private static Class<?> adClazz;
        private static Class<?> clazz;

        public static Class<?> getClazz() {
            if (clazz == null) {
                adClazz = getClassByXposed("com.netease.cloudmusic.meta.Ad");
                try {
                    Pattern pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.module\\.ad\\.[a-z]$");
                    List<String> list = ClassHelper.getFilteredClasses(pattern, Collections.reverseOrder());
                    clazz = Stream.of(list)
                            .map(ClassHelper::getClassByXposed)
                            .filter(c -> Modifier.isPublic(c.getModifiers()))
                            .filter(m -> !Modifier.isInterface(m.getModifiers()))
                            .filter(m -> !Modifier.isStatic(m.getModifiers()))
                            .filter(m -> !Modifier.isAbstract(m.getModifiers()))
                            .filter(c -> Stream.of(c.getDeclaredMethods()).anyMatch(m -> m.getReturnType().getName().contains("VideoAdInfo")))
                            .filter(c -> Stream.of(c.getDeclaredMethods()).anyMatch(m -> m.getReturnType() == adClazz))
                            .findFirst()
                            .get();
                } catch (NoSuchElementException e) {
                    e.printStackTrace();
                }
            }
            return clazz;
        }

        public static List<Method> getAdMethod() {
            try {
                List<Method> methodList = Arrays.asList(getClazz().getDeclaredMethods());
                List<Method> hookMethodList = Stream.of(methodList)
                        .filter(m -> m.getReturnType().getName().contains("com.netease.cloudmusic.meta"))
                        .filter(m -> Stream.of(m.getParameterTypes()).anyMatch(c -> c == JSONObject.class))
                        .toList();
                hookMethodList.addAll(Stream.of(methodList)
                        .filter(m -> Stream.of(m.getParameterTypes()).anyMatch(c -> c.getName().contains("com.netease.cloudmusic.meta")))
                        .filter(m -> Stream.of(m.getParameterTypes()).anyMatch(c -> c == JSONObject.class))
                        .toList());
                return hookMethodList;
            } catch (Exception e) {
                return null;
            }
        }
    }

    public static class OKHttp3Response {
        private static Class<?> clazz;
        private static Method getResultMethod;

        final Object okHttp3Response;

        public OKHttp3Response(Object okHttp3Response) {
            this.okHttp3Response = okHttp3Response;
        }

        static Class<?> getClazz(Context context) {
            if (clazz == null) {
                if (versionCode >= 8007000) {
                    // v9.5.30+: o72/a is the EAPI request class that replaces the old "HttpResponse"
                    // It extends o72/p -> o72/e -> o72/f, and o72/f has Uri field
                    // Note: EAPIHook v2 hooks o72/a$d and o72/a$e directly, so this class
                    // is mainly for backward compatibility with other code
                    XposedBridge.log("[dolby_beta] HttpResponse: trying o72.a for v9.5.30+");
                    clazz = findClassIfExists("o72.a", classLoader);
                    if (clazz != null) {
                        XposedBridge.log("[dolby_beta] HttpResponse: found o72.a");
                    } else {
                        XposedBridge.log("[dolby_beta] HttpResponse: o72.a not found, trying fallback scan");
                        // Fallback: scan network package for public class extending through o72/f
                        try {
                            Pattern broadPattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\..+$");
                            List<String> list = ClassHelper.getFilteredClasses(broadPattern, Collections.reverseOrder());
                            clazz = Stream.of(list)
                                    .map(ClassHelper::getClassByXposed)
                                    .filter(c -> c != null)
                                    .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                                    .filter(c -> Modifier.isPublic(c.getModifiers()))
                                    .filter(c -> Stream.of(c.getDeclaredMethods()).anyMatch(m -> m.getReturnType() == JSONObject.class || m.getReturnType() == String.class))
                                    .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(f -> f.getType().getName().contains("AntiSpam")))
                                    .findFirst()
                                    .orElse(null);
                            if (clazz != null) {
                                XposedBridge.log("[dolby_beta] HttpResponse: found " + clazz.getName() + " via scan");
                            }
                        } catch (Exception e) {
                            XposedBridge.log("[dolby_beta] HttpResponse: scan failed: " + e.getMessage());
                        }
                    }
                    if (clazz == null)
                        MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
                } else if (versionCode >= 800) {
                    // 9.x+: broad regex scan for HttpResponse
                    XposedBridge.log("[dolby_beta] HttpResponse: scanning with broad regex for v9.x+");
                    Pattern broadPattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\..+$");
                    List<String> list = ClassHelper.getFilteredClasses(broadPattern, Collections.reverseOrder());

                    try {
                        Class<?> okHttp3ResponseClazz = OKHttp3Response.getClazz(context);
                        if (okHttp3ResponseClazz != null) {
                            // Strategy 1: strict match (public + final + Object + has OKHttp3Response field)
                            clazz = Stream.of(list)
                                    .map(ClassHelper::getClassByXposed)
                                    .filter(c -> c != null)
                                    .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                                    .filter(c -> Modifier.isPublic(c.getModifiers()))
                                    .filter(c -> c.getSuperclass() == Object.class)
                                    .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == okHttp3ResponseClazz))
                                    .findFirst()
                                    .orElse(null);
                        }
                        if (clazz == null) {
                            // Strategy 2: relaxed match - not abstract, public, has field with type that implements Closeable
                            // and that field type has okhttp3 fields
                            XposedBridge.log("[dolby_beta] HttpResponse: strict match failed, trying relaxed match");
                            clazz = Stream.of(list)
                                    .map(ClassHelper::getClassByXposed)
                                    .filter(c -> c != null)
                                    .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                                    .filter(c -> Modifier.isPublic(c.getModifiers()))
                                    .filter(c -> c.getSuperclass() == Object.class)
                                    .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(f ->
                                            Stream.of(f.getType().getInterfaces()).anyMatch(i -> i == Closeable.class)
                                                    || f.getType().getName().startsWith("okhttp3")))
                                    .findFirst()
                                    .orElse(null);
                        }
                        if (clazz == null) {
                            // Strategy 3: find class with method that has 2 exception types (the EAPI result method)
                            XposedBridge.log("[dolby_beta] HttpResponse: relaxed match failed, trying method signature match");
                            clazz = Stream.of(list)
                                    .map(ClassHelper::getClassByXposed)
                                    .filter(c -> c != null)
                                    .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                                    .filter(c -> Modifier.isPublic(c.getModifiers()))
                                    .filter(c -> c.getSuperclass() == Object.class)
                                    .filter(c -> Stream.of(c.getDeclaredMethods()).anyMatch(m -> m.getExceptionTypes().length >= 2))
                                    .findFirst()
                                    .orElse(null);
                        }
                        if (clazz != null) {
                            XposedBridge.log("[dolby_beta] HttpResponse: found class " + clazz.getName());
                        } else {
                            XposedBridge.log("[dolby_beta] HttpResponse: no suitable class found");
                        }
                    } catch (Exception e) {
                        XposedBridge.log("[dolby_beta] HttpResponse: exception during scan: " + e.getMessage());
                    }
                    if (clazz == null)
                        MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
                } else {
                    // Legacy versions
                    Pattern pattern;
                    if (versionCode < 154)
                        pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.[a-z]\\.[a-z]\\.[a-z]\\.[a-z]$");
                    else
                        pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\.[a-z]\\.[a-z]\\.[a-z]$");
                    List<String> list = ClassHelper.getFilteredClasses(pattern, Collections.reverseOrder());

                    try {
                        clazz = Stream.of(list)
                                .map(ClassHelper::getClassByXposed)
                                .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                                .filter(c -> Modifier.isPublic(c.getModifiers()))
                                .filter(c -> Modifier.isFinal(c.getModifiers()))
                                .filter(c -> c.getSuperclass() == Object.class)
                                .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == OKHttp3Response.getClazz(context)))
                                .findFirst()
                                .get();
                    } catch (Exception e) {
                        MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
                    }
                }
            }
            return clazz;
        }

        public Object getResponseObject(Context context) throws IllegalAccessException, NullPointerException {
            Field[] fields = getClazz(context).getDeclaredFields();
            // Strategy 1: find field implementing Closeable with okhttp3 sub-fields
            Field dataField = null;
            try {
                dataField = Stream.of(fields)
                        .filter(f -> Stream.of(f.getType().getInterfaces()).anyMatch(i -> i == Closeable.class))
                        .filter(f -> Stream.of(f.getType().getDeclaredFields()).anyMatch(pf -> pf.getType().getName().startsWith("okhttp3")))
                        .findFirst().get();
            } catch (Exception e) {
                // Strategy 2: find field whose type name starts with okhttp3
                XposedBridge.log("[dolby_beta] HttpResponse.getResponseObject: Closeable match failed, trying okhttp3 field");
                dataField = Stream.of(fields)
                        .filter(f -> f.getType().getName().startsWith("okhttp3"))
                        .findFirst()
                        .orElse(null);
                if (dataField == null) {
                    // Strategy 3: find field implementing Closeable
                    dataField = Stream.of(fields)
                            .filter(f -> Stream.of(f.getType().getInterfaces()).anyMatch(i -> i == Closeable.class))
                            .findFirst()
                            .orElse(null);
                }
            }
            if (dataField == null) throw new NullPointerException("getResponseObject: no suitable field found");
            dataField.setAccessible(true);
            return dataField.get(okHttp3Response);
        }

        public Object getEapi(Context context) throws IllegalAccessException, NullPointerException {
            Field[] fields = getClazz(context).getDeclaredFields();
            // Strategy 1: abstract type with Object superclass and okhttp3 fields
            Field dataField = null;
            try {
                dataField = Stream.of(fields)
                        .filter(c -> Modifier.isAbstract(c.getType().getModifiers()))
                        .filter(c -> c.getType().getSuperclass() == Object.class)
                        .filter(c -> Stream.of(c.getType().getDeclaredFields()).anyMatch(m -> m.getType().getName().startsWith("okhttp3")))
                        .findFirst().get();
            } catch (Exception e) {
                // Strategy 2: any abstract field (the eapi/params object)
                XposedBridge.log("[dolby_beta] HttpResponse.getEapi: strict match failed, trying abstract field");
                dataField = Stream.of(fields)
                        .filter(c -> Modifier.isAbstract(c.getType().getModifiers()))
                        .findFirst()
                        .orElse(null);
            }
            if (dataField == null) {
                // Strategy 3: any field with Uri type or String fields typical of eapi
                XposedBridge.log("[dolby_beta] HttpResponse.getEapi: trying Uri field match");
                dataField = Stream.of(fields)
                        .filter(f -> Stream.of(f.getType().getDeclaredFields()).anyMatch(m -> m.getType() == android.net.Uri.class))
                        .findFirst()
                        .orElse(null);
            }
            if (dataField == null) throw new NullPointerException("getEapi: no suitable field found");
            dataField.setAccessible(true);
            return dataField.get(okHttp3Response);
        }

        public static Method getResultMethod(Context context) {
            if (getResultMethod == null) {
                try {
                    Class<?> cls = getClazz(context);
                    if (cls == null) {
                        MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
                        return null;
                    }
                    List<Method> methodList = Arrays.asList(cls.getDeclaredMethods());
                    // Strategy 1: find method with 2 exception types (original approach)
                    getResultMethod = Stream.of(methodList)
                            .filter(m -> m.getExceptionTypes().length == 2)
                            .findFirst()
                            .orElse(null);
                    if (getResultMethod == null) {
                        // Strategy 2: find method with >=1 exception types and non-void return
                        XposedBridge.log("[dolby_beta] HttpResponse.getResultMethod: 2-exception match failed, trying relaxed");
                        getResultMethod = Stream.of(methodList)
                                .filter(m -> m.getExceptionTypes().length >= 1)
                                .filter(m -> m.getReturnType() != void.class)
                                .filter(m -> Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers()))
                                .findFirst()
                                .orElse(null);
                    }
                    if (getResultMethod != null) {
                        XposedBridge.log("[dolby_beta] HttpResponse.getResultMethod: found " + getResultMethod.getName());
                    }
                } catch (Exception e) {
                    XposedBridge.log("[dolby_beta] HttpResponse.getResultMethod exception: " + e.getMessage());
                    MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
                }
            }
            return getResultMethod;
        }
    }

    /**
     * 获取请求URL
     */
    public static class HttpUrl {
        private static Class<?> clazz;

        static Class<?> getClazz(Context context) {
            if (clazz == null) {
                if (versionCode >= 8007000) {
                    // v9.5.30+: o72/f is the base request class with Uri field 'k'
                    XposedBridge.log("[dolby_beta] HttpUrl: trying o72.f for v9.5.30+");
                    clazz = findClassIfExists("o72.f", classLoader);
                    if (clazz != null) {
                        // Verify: should be abstract, have Uri field
                        boolean isAbstract = Modifier.isAbstract(clazz.getModifiers());
                        boolean hasUri = Stream.of(clazz.getDeclaredFields()).anyMatch(m -> m.getType() == android.net.Uri.class);
                        boolean hasRequest = Stream.of(clazz.getDeclaredFields()).anyMatch(m -> m.getType().getName().startsWith("okhttp3"));
                        if (isAbstract && hasUri && hasRequest) {
                            XposedBridge.log("[dolby_beta] HttpUrl: found o72.f (abstract, has Uri+Request)");
                        } else {
                            XposedBridge.log("[dolby_beta] HttpUrl: o72.f validation failed (abstract=" + isAbstract + " uri=" + hasUri + " req=" + hasRequest + "), falling back");
                            clazz = null;
                        }
                    }
                    if (clazz == null) {
                        // Fallback: scan for abstract class with Uri + okhttp3 fields
                        XposedBridge.log("[dolby_beta] HttpUrl: trying broad scan fallback");
                        try {
                            Pattern broadPattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\..+$");
                            List<String> list = ClassHelper.getFilteredClasses(broadPattern, Collections.reverseOrder());
                            clazz = Stream.of(list)
                                    .map(ClassHelper::getClassByXposed)
                                    .filter(c -> c != null)
                                    .filter(c -> Modifier.isAbstract(c.getModifiers()))
                                    .filter(c -> Modifier.isPublic(c.getModifiers()))
                                    .filter(c -> c.getSuperclass() == Object.class)
                                    .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == android.net.Uri.class))
                                    .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType().getName().startsWith("okhttp3")))
                                    .findFirst()
                                    .orElse(null);
                        } catch (Exception e) {
                            XposedBridge.log("[dolby_beta] HttpUrl: scan failed: " + e.getMessage());
                        }
                    }
                    if (clazz == null)
                        MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
                } else if (versionCode >= 800) {
                    // 9.x+: broad regex scan
                    XposedBridge.log("[dolby_beta] HttpUrl: scanning with broad regex for v9.x+");
                    Pattern broadPattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\..+$");
                    List<String> list = ClassHelper.getFilteredClasses(broadPattern, Collections.reverseOrder());

                    try {
                        // Strategy 1: abstract, public, Object superclass, has okhttp3 field, has Uri field
                        clazz = Stream.of(list)
                                .map(ClassHelper::getClassByXposed)
                                .filter(c -> c != null)
                                .filter(c -> Modifier.isAbstract(c.getModifiers()))
                                .filter(c -> Modifier.isPublic(c.getModifiers()))
                                .filter(c -> c.getSuperclass() == Object.class)
                                .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType().getName().startsWith("okhttp3")))
                                .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == android.net.Uri.class))
                                .findFirst()
                                .orElse(null);
                        if (clazz != null) {
                            XposedBridge.log("[dolby_beta] HttpUrl: found with Uri field: " + clazz.getName());
                        } else {
                            // Strategy 2: abstract, public, Object superclass, has okhttp3 field (Uri might be in subclass)
                            XposedBridge.log("[dolby_beta] HttpUrl: trying without Uri filter");
                            clazz = Stream.of(list)
                                    .map(ClassHelper::getClassByXposed)
                                    .filter(c -> c != null)
                                    .filter(c -> Modifier.isAbstract(c.getModifiers()))
                                    .filter(c -> Modifier.isPublic(c.getModifiers()))
                                    .filter(c -> c.getSuperclass() == Object.class)
                                    .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType().getName().startsWith("okhttp3")))
                                    .findFirst()
                                    .orElse(null);
                            if (clazz != null) {
                                XposedBridge.log("[dolby_beta] HttpUrl: found without Uri: " + clazz.getName());
                            }
                        }
                    } catch (Exception e) {
                        XposedBridge.log("[dolby_beta] HttpUrl: exception: " + e.getMessage());
                    }
                    if (clazz == null)
                        MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
                } else {
                    // Legacy versions
                    Pattern pattern;
                    if (versionCode < 154)
                        pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.[a-z]\\.[a-z]\\.[a-z]\\.[a-z]$");
                    else
                        pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\.[a-z]\\.[a-z]\\.[a-z]$");
                    List<String> list = ClassHelper.getFilteredClasses(pattern, Collections.reverseOrder());

                    try {
                        clazz = Stream.of(list)
                                .map(ClassHelper::getClassByXposed)
                                .filter(c -> Modifier.isAbstract(c.getModifiers()))
                                .filter(c -> Modifier.isPublic(c.getModifiers()))
                                .filter(c -> c.getSuperclass() == Object.class)
                                .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType().getName().startsWith("okhttp3")))
                                .findFirst()
                                .get();
                    } catch (Exception e) {
                        MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
                    }
                }
            }
            return clazz;
        }

        public static Uri getUri(Context context, Object eapi) throws IllegalAccessException, NullPointerException {
            Field uriField = XposedHelpers.findFirstFieldByExactType(getClazz(context), Uri.class);
            uriField.setAccessible(true);
            return (Uri) uriField.get(eapi);
        }
    }

    /**
     * 获取请求参数
     */
    public static class HttpParams {
        private static Class<?> clazz;
        private static Field paramsMap;

        static Class<?> getClazz(Context context) {
            if (clazz == null) {
                if (versionCode >= 8007000) {
                    // v9.5.30+: no separate HttpParams class — params come from URI query parameters.
                    // EAPIHook v2 uses getParamsFromUri() directly. This class path should
                    // not be reached for v9.5.30+, but we handle it gracefully by skipping
                    // the class scan and letting getParams() extract from URI instead.
                    XposedBridge.log("[dolby_beta] HttpParams: v9.5.30+ has no separate params class, using URI extraction");
                    return null;
                } else if (versionCode >= 800) {
                    // 9.x+ (before v9.5.30): broad regex scan
                    XposedBridge.log("[dolby_beta] HttpParams: scanning with broad regex for v9.x+");
                    Pattern broadPattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\..+$");
                    List<String> list = ClassHelper.getFilteredClasses(broadPattern, Collections.reverseOrder());

                    try {
                        // Strategy 1: Serializable + LinkedHashMap (original approach)
                        clazz = Stream.of(list)
                                .map(ClassHelper::getClassByXposed)
                                .filter(c -> c != null)
                                .filter(c -> Stream.of(c.getInterfaces()).anyMatch(i -> i == Serializable.class))
                                .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                                .filter(c -> Modifier.isPublic(c.getModifiers()))
                                .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == LinkedHashMap.class))
                                .findFirst()
                                .orElse(null);
                        if (clazz != null) {
                            XposedBridge.log("[dolby_beta] HttpParams: found with Serializable+LinkedHashMap: " + clazz.getName());
                        } else {
                            // Strategy 2: not abstract, public, has LinkedHashMap (might not implement Serializable in v9.x)
                            XposedBridge.log("[dolby_beta] HttpParams: trying without Serializable filter");
                            clazz = Stream.of(list)
                                    .map(ClassHelper::getClassByXposed)
                                    .filter(c -> c != null)
                                    .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                                    .filter(c -> Modifier.isPublic(c.getModifiers()))
                                    .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == LinkedHashMap.class))
                                    .filter(c -> c.getSuperclass() == Object.class)
                                    .findFirst()
                                    .orElse(null);
                            if (clazz != null) {
                                XposedBridge.log("[dolby_beta] HttpParams: found without Serializable: " + clazz.getName());
                            }
                        }
                        if (clazz == null)
                            MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
                    } catch (Exception e) {
                        XposedBridge.log("[dolby_beta] HttpParams: exception: " + e.getMessage());
                        MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
                    }
                } else {
                    // Legacy versions
                    Pattern pattern;
                    if (versionCode < 154)
                        pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.[a-z]\\.[a-z]\\.[a-z]\\.[a-z]$");
                    else
                        pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\.[a-z]\\.[a-z]\\.[a-z]$");
                    List<String> list = ClassHelper.getFilteredClasses(pattern, Collections.reverseOrder());

                    try {
                        clazz = Stream.of(list)
                                .map(ClassHelper::getClassByXposed)
                                .filter(c -> Stream.of(c.getInterfaces()).anyMatch(i -> i == Serializable.class))
                                .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                                .filter(c -> Modifier.isPublic(c.getModifiers()))
                                .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == LinkedHashMap.class))
                                .findFirst()
                                .get();
                    } catch (Exception e) {
                        MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
                    }
                }
            }
            return clazz;
        }

        static Field getParamsMapField(Context context) {
            if (paramsMap == null) {
                Field[] fields = getClazz(context).getDeclaredFields();
                paramsMap = Stream.of(fields)
                        .filter(c -> Stream.of(c.getType()).anyMatch(m -> m == LinkedHashMap.class))
                        .findFirst().get();
                paramsMap.setAccessible(true);
            }
            return paramsMap;
        }

        public static LinkedHashMap<String, String> getParams(Context context, Object eapi) throws IllegalAccessException, NullPointerException {
            // v9.5.30+: no separate params class, extract directly from URI
            if (versionCode >= 8007000 && getClazz(context) == null) {
                Uri uri = HttpUrl.getUri(context, eapi);
                LinkedHashMap<String, String> map = new LinkedHashMap<>();
                if (uri != null) {
                    for (String name : uri.getQueryParameterNames()) {
                        String val = uri.getQueryParameter(name);
                        map.put(name, val != null ? val : "");
                    }
                }
                return map;
            }
            // Legacy path
            List<Method> list = new ArrayList<>(Arrays.asList(findMethodsByExactParameters(eapi.getClass(), getClazz(context))));
            if (list != null && list.size() != 0) {
                Object params = XposedHelpers.callMethod(eapi, list.get(0).getName());
                LinkedHashMap<String, String> map = (LinkedHashMap<String, String>) getParamsMapField(context).get(params);
                Uri uri = HttpUrl.getUri(context, eapi);
                for (String name : uri.getQueryParameterNames()) {
                    String val = uri.getQueryParameter(name);
                    map.put(name, val != null ? val : "");
                }
                return (LinkedHashMap<String, String>) getParamsMapField(context).get(params);
            }
            return new LinkedHashMap<>();
        }
    }

    /**
     * 拦截器
     */
    public static class HttpInterceptor {
        private static Class<?> clazz;
        private static List<Method> methodList;

        static Class<?> getClazz(Context context) {
            if (clazz == null) {
                if (versionCode >= 8007000) {
                    // v9.5.30+: interceptor.q is the concrete EAPI interceptor
                    // APK analysis: interceptor.q (not interceptor.i!) is the interceptor with
                    // intercept(Chain), h(5)->Pair, a(5)->Response, i(5)->Response methods
                    XposedBridge.log("[dolby_beta] HttpInterceptor: trying interceptor.q for v9.5.30+");
                    clazz = findClassIfExists("com.netease.cloudmusic.network.interceptor.q", classLoader);
                    if (clazz != null) {
                        // Verify it implements Interceptor
                        boolean isInterceptor = Stream.of(clazz.getInterfaces())
                                .anyMatch(i -> i.getName().contains("Interceptor"));
                        boolean isConcrete = !Modifier.isAbstract(clazz.getModifiers());
                        if (isInterceptor && isConcrete) {
                            XposedBridge.log("[dolby_beta] HttpInterceptor: found interceptor.q");
                        } else {
                            XposedBridge.log("[dolby_beta] HttpInterceptor: interceptor.q failed validation (interceptor=" + isInterceptor + ", concrete=" + isConcrete + "), falling back");
                            clazz = null;
                        }
                    }
                    if (clazz == null) {
                        // Fallback: broad regex scan for concrete Interceptor with Pair-returning method
                        XposedBridge.log("[dolby_beta] HttpInterceptor: trying broad regex fallback");
                        Pattern broadPattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\..+$");
                        try {
                            List<String> list = ClassHelper.getFilteredClasses(broadPattern, Collections.reverseOrder());
                            clazz = Stream.of(list)
                                    .map(ClassHelper::getClassByXposed)
                                    .filter(c -> c != null)
                                    .filter(c -> Stream.of(c.getInterfaces()).anyMatch(i -> i.getName().contains("Interceptor")))
                                    .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                                    .filter(c -> Modifier.isPublic(c.getModifiers()))
                                    .filter(c -> Stream.of(c.getDeclaredMethods()).anyMatch(m -> m.getReturnType().getName().contains("Pair")))
                                    .findFirst()
                                    .orElse(null);
                            if (clazz != null) {
                                XposedBridge.log("[dolby_beta] HttpInterceptor: found " + clazz.getName() + " via scan");
                            }
                        } catch (Exception e) {
                            XposedBridge.log("[dolby_beta] HttpInterceptor: scan failed: " + e.getMessage());
                        }
                    }
                    if (clazz == null)
                        MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
                } else if (versionCode >= 800) {
                    // Earlier v9.x: try interceptor.i then q then broad scan
                    XposedBridge.log("[dolby_beta] HttpInterceptor: scanning for earlier v9.x");
                    // Try q first (the actual interceptor), then i (cookie listener)
                    String[] candidates = {"com.netease.cloudmusic.network.interceptor.q", "com.netease.cloudmusic.network.interceptor.i"};
                    for (String candidateName : candidates) {
                        clazz = findClassIfExists(candidateName, classLoader);
                        if (clazz != null) {
                            boolean isInterceptor = Stream.of(clazz.getInterfaces())
                                    .anyMatch(i -> i.getName().contains("Interceptor"));
                            if (isInterceptor) {
                                XposedBridge.log("[dolby_beta] HttpInterceptor: found " + candidateName);
                                break;
                            }
                            clazz = null;
                        }
                    }
                    if (clazz == null) {
                        Pattern broadPattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\..+$");
                        try {
                            List<String> list = ClassHelper.getFilteredClasses(broadPattern, Collections.reverseOrder());
                            clazz = Stream.of(list)
                                    .map(ClassHelper::getClassByXposed)
                                    .filter(c -> c != null)
                                    .filter(c -> Stream.of(c.getInterfaces()).anyMatch(i -> i.getName().contains("Interceptor")))
                                    .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                                    .filter(c -> Modifier.isPublic(c.getModifiers()))
                                    .filter(c -> Stream.of(c.getDeclaredMethods()).anyMatch(m -> m.getReturnType().getName().contains("Pair")))
                                    .findFirst()
                                    .orElse(null);
                            if (clazz == null) {
                                clazz = Stream.of(list)
                                        .map(ClassHelper::getClassByXposed)
                                        .filter(c -> c != null)
                                        .filter(c -> Stream.of(c.getInterfaces()).anyMatch(i -> i.getName().contains("Interceptor")))
                                        .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                                        .filter(c -> Modifier.isPublic(c.getModifiers()))
                                        .findFirst()
                                        .orElse(null);
                            }
                        } catch (Exception e) {
                            XposedBridge.log("[dolby_beta] HttpInterceptor: exception: " + e.getMessage());
                        }
                    }
                    if (clazz == null)
                        MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
                } else {
                    // Legacy versions
                    Pattern pattern;
                    if (versionCode < 154)
                        pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.[a-z]\\.[a-z]\\.[a-z]");
                    else
                        pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\.[a-z]");
                    try {
                        List<String> list = ClassHelper.getFilteredClasses(pattern, Collections.reverseOrder());
                        clazz = Stream.of(list)
                                .map(ClassHelper::getClassByXposed)
                                .filter(c -> c.getInterfaces().length == 1)
                                .filter(c -> Stream.of(c.getInterfaces()).anyMatch(i -> i.getName().contains("Interceptor")))
                                .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                                .filter(c -> Modifier.isPublic(c.getModifiers()))
                                .filter(c -> Stream.of(c.getDeclaredMethods()).anyMatch(m -> m.getReturnType().getName().contains("Pair")))
                                .findFirst()
                                .get();
                    } catch (Exception e) {
                        MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
                    }
                }
            }
            return clazz;
        }

        public static List<Method> getMethodList(Context context) {
            if (methodList == null) {
                methodList = new ArrayList<>();
                Class<?> cls = getClazz(context);
                if (cls != null) {
                    if (versionCode >= 8007000) {
                        // v9.5.30+: interceptor.q methods have 0 exception types (unlike old versions)
                        // Methods: a(5)->Response, i(5)->Response, h(5)->Pair, c(4)->Response
                        // For CdnHook we need the Response-returning methods with >=4 params
                        List<Method> filtered = Stream.of(cls.getDeclaredMethods())
                                .filter(m -> m.getParameterTypes().length >= 4)
                                .filter(m -> m.getReturnType().getName().contains("Response"))
                                .toList();
                        if (filtered.isEmpty()) {
                            // Fallback: any Response-returning method
                            XposedBridge.log("[dolby_beta] HttpInterceptor.getMethodList: 4+ param Response match failed, trying any Response");
                            filtered = Stream.of(cls.getDeclaredMethods())
                                    .filter(m -> m.getReturnType().getName().contains("Response"))
                                    .filter(m -> m.getParameterTypes().length >= 1)
                                    .toList();
                        }
                        methodList.addAll(filtered);
                    } else {
                        // Legacy: methods with 1 exception type, 5 params, return type with "Response"
                        List<Method> filtered = Stream.of(cls.getDeclaredMethods())
                                .filter(m -> m.getExceptionTypes().length == 1)
                                .filter(m -> m.getParameterTypes().length == 5)
                                .filter(m -> m.getReturnType().getName().contains("Response"))
                                .toList();
                        if (filtered.isEmpty()) {
                            // Strategy 2: try methods with 1 exception type and 5 params, any return type
                            XposedBridge.log("[dolby_beta] HttpInterceptor.getMethodList: Response match failed, trying relaxed");
                            filtered = Stream.of(cls.getDeclaredMethods())
                                    .filter(m -> m.getExceptionTypes().length == 1)
                                    .filter(m -> m.getParameterTypes().length >= 3)
                                    .filter(m -> m.getReturnType().getName().contains("Response") || m.getReturnType() == Object.class)
                                    .toList();
                        }
                        if (filtered.isEmpty()) {
                            // Strategy 3: try interceptor's intercept-like methods
                            XposedBridge.log("[dolby_beta] HttpInterceptor.getMethodList: trying intercept method pattern");
                            filtered = Stream.of(cls.getDeclaredMethods())
                                    .filter(m -> m.getReturnType().getName().contains("Response") || m.getReturnType() == Object.class)
                                    .filter(m -> m.getParameterTypes().length >= 1)
                                    .filter(m -> m.getExceptionTypes().length >= 1)
                                    .toList();
                        }
                        methodList.addAll(filtered);
                    }
                    XposedBridge.log("[dolby_beta] HttpInterceptor.getMethodList: found " + methodList.size() + " methods");
                }
            }
            return methodList;
        }
    }
}
