package com.raincat.dolby_beta.hook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.raincat.dolby_beta.helper.ExtraHelper;
import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.model.SidebarEnum;
import com.raincat.dolby_beta.utils.Tools;
import com.raincat.dolby_beta.view.BaseDialogInputItem;
import com.raincat.dolby_beta.view.BaseDialogItem;
import com.raincat.dolby_beta.view.beauty.BeautyBannerHideView;
import com.raincat.dolby_beta.view.beauty.BeautyBlackHideView;
import com.raincat.dolby_beta.view.beauty.BeautyBubbleHideView;
import com.raincat.dolby_beta.view.beauty.BeautyCommentHotView;
import com.raincat.dolby_beta.view.beauty.BeautyKSongHideView;
import com.raincat.dolby_beta.view.beauty.BeautyNightModeView;
import com.raincat.dolby_beta.view.beauty.BeautyRotationView;
import com.raincat.dolby_beta.view.beauty.BeautySidebarHideItem;
import com.raincat.dolby_beta.view.beauty.BeautySidebarHideView;
import com.raincat.dolby_beta.view.beauty.BeautyTabHideView;
import com.raincat.dolby_beta.view.beauty.BeautyTitleView;
import com.raincat.dolby_beta.view.proxy.ProxyCoverView;
import com.raincat.dolby_beta.view.proxy.ProxyFlacView;
import com.raincat.dolby_beta.view.proxy.ProxyGrayView;
import com.raincat.dolby_beta.view.proxy.ProxyHttpView;
import com.raincat.dolby_beta.view.proxy.ProxyMasterView;
import com.raincat.dolby_beta.view.proxy.ProxyOriginalView;
import com.raincat.dolby_beta.view.proxy.ProxyPortView;
import com.raincat.dolby_beta.view.proxy.ProxyPriorityView;
import com.raincat.dolby_beta.view.proxy.ProxyServerView;
import com.raincat.dolby_beta.view.proxy.ProxyTitleView;
import com.raincat.dolby_beta.view.setting.AboutView;
import com.raincat.dolby_beta.view.setting.BeautyView;
import com.raincat.dolby_beta.view.setting.BlackView;
import com.raincat.dolby_beta.view.setting.DexView;
import com.raincat.dolby_beta.view.setting.FixCommentView;
import com.raincat.dolby_beta.view.setting.MasterView;
import com.raincat.dolby_beta.view.setting.ProxyView;
import com.raincat.dolby_beta.view.setting.SignSongDailyView;
import com.raincat.dolby_beta.view.setting.SignSongSelfView;
import com.raincat.dolby_beta.view.setting.SignView;
import com.raincat.dolby_beta.view.setting.TitleView;
import com.raincat.dolby_beta.view.setting.UpdateView;
import com.raincat.dolby_beta.view.setting.ListenView;
import com.raincat.dolby_beta.view.setting.WarnView;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;

/**
 * <pre>
 *     author : RainCat
 *     time   : 2019/10/26
 *     desc   : 设置
 *     version: 1.0
 * </pre>
 */
public class SettingHook {
    private String SettingActivity;
    private String switchViewName = "";
    private TextView titleView, subView;
    private LinearLayout dialogRoot, dialogProxyRoot, dialogBeautyRoot, dialogSidebarRoot;

    private BroadcastReceiver broadcastReceiver;
    private boolean viewInitialized = false;

    public SettingHook(Context context,int versionCode) {
        //一切的前提，没这个页面连设置都进不去
        if(versionCode>=8007000)
        {
            SettingActivity="com.netease.cloudmusic.music.biz.setting.activity.SettingActivity";
        }else
        {
            SettingActivity="com.netease.cloudmusic.activity.SettingActivity";
        }
        Class<?> settingActivityClass = findClassIfExists(SettingActivity, context.getClassLoader());
        if (settingActivityClass == null) {
            XposedBridge.log("[dolby_beta] SettingHook: SettingActivity class not found!");
            return;
        }
        Field[] allFields = settingActivityClass.getDeclaredFields();
        XposedBridge.log("[dolby_beta] SettingHook: SettingActivity has " + allFields.length + " fields");
        // Find a Switch-like view field for anchoring our settings UI
        // Try multiple type name patterns: Switch, SwitchCompat, MaterialSwitch, CompoundButton
        String[] switchPatterns = {"Switch", "switch", "CompoundButton", "compoundbutton", "Toggle", "toggle"};
        for (Field field : allFields) {
            String typeName = field.getType().getName();
            for (String pattern : switchPatterns) {
                if (typeName.contains(pattern)) {
                    switchViewName = field.getName();
                    break;
                }
            }
            if (!switchViewName.isEmpty()) break;
        }
        // If no Switch-like field found, try any View field as anchor
        if (switchViewName.isEmpty()) {
            XposedBridge.log("[dolby_beta] SettingHook: no Switch field found, trying View field");
            for (Field field : allFields) {
                if (field.getType().getName().contains("View") && !Modifier.isStatic(field.getModifiers())) {
                    switchViewName = field.getName();
                    XposedBridge.log("[dolby_beta] SettingHook: using View field as anchor: " + switchViewName);
                    break;
                }
            }
        }
        XposedBridge.log("[dolby_beta] SettingHook: anchor field = " + switchViewName);

        // Hook onResume instead of onCreate - views are guaranteed to be initialized by then
        findAndHookMethod(settingActivityClass, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                if (viewInitialized) return;
                Context c = (Context) param.thisObject;
                //注册广播
                registerBroadcastReceiver(c);
                //初始化控件
                initView(c);
            }
        });

        findAndHookMethod(settingActivityClass, "onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                viewInitialized = false;
                if (broadcastReceiver != null)
                    ((Context) param.thisObject).unregisterReceiver(broadcastReceiver);
            }
        });
    }

    private void initView(final Context context) {
        try {
            // Strategy 1: Try the SwitchCompat field approach
            if (!switchViewName.isEmpty()) {
                Object fieldObj = XposedHelpers.getObjectField(context, switchViewName);
                XposedBridge.log("[dolby_beta] SettingHook: field '" + switchViewName + "' = " + fieldObj);
                if (fieldObj instanceof View) {
                    View switchView = (View) fieldObj;
                    if (tryInsertViaAnchor(context, switchView)) {
                        viewInitialized = true;
                        return;
                    }
                }
            }

            // Strategy 2: Traverse from content root to find a suitable container
            XposedBridge.log("[dolby_beta] SettingHook: anchor field approach failed, trying content root traversal");
            if (tryInsertViaContentRoot(context)) {
                viewInitialized = true;
                return;
            }

            XposedBridge.log("[dolby_beta] SettingHook: all strategies failed, settings UI will not appear");
        } catch (Exception e) {
            XposedBridge.log("[dolby_beta] SettingHook: initView failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Strategy 1: Insert settings row via a known anchor View (e.g., SwitchCompat field)
     * Gets the anchor's parent and grandparent, inserts a new row alongside existing settings rows.
     */
    private boolean tryInsertViaAnchor(Context context, View anchorView) {
        try {
            ViewGroup parent = (ViewGroup) anchorView.getParent();
            if (parent == null) {
                XposedBridge.log("[dolby_beta] SettingHook: anchor view has no parent");
                return false;
            }
            XposedBridge.log("[dolby_beta] SettingHook: parent = " + parent.getClass().getName());

            ViewGroup grandparent = (ViewGroup) parent.getParent();
            if (grandparent == null) {
                XposedBridge.log("[dolby_beta] SettingHook: parent has no parent");
                return false;
            }
            XposedBridge.log("[dolby_beta] SettingHook: grandparent = " + grandparent.getClass().getName() + ", children = " + grandparent.getChildCount());

            LinearLayout linearLayout = createSettingsRow(context, parent);
            grandparent.addView(linearLayout, 0);
            XposedBridge.log("[dolby_beta] SettingHook: settings row inserted via anchor at position 0");
            return true;
        } catch (ClassCastException e) {
            XposedBridge.log("[dolby_beta] SettingHook: anchor approach ClassCastException: " + e.getMessage());
            return false;
        } catch (Exception e) {
            XposedBridge.log("[dolby_beta] SettingHook: anchor approach failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Strategy 2: Traverse from the activity's content root view to find a suitable
     * vertical container (LinearLayout, ScrollView child, RecyclerView, ListView, etc.)
     * and insert a settings row.
     */
    private boolean tryInsertViaContentRoot(Context context) {
        try {
            Activity activity = (Activity) context;
            View contentView = activity.findViewById(android.R.id.content);
            if (contentView == null) {
                XposedBridge.log("[dolby_beta] SettingHook: android.R.id.content not found");
                return false;
            }
            XposedBridge.log("[dolby_beta] SettingHook: content root = " + contentView.getClass().getName());

            // Find a deep vertical container by traversing the view tree
            ViewGroup targetContainer = findVerticalContainer(contentView);
            if (targetContainer == null) {
                XposedBridge.log("[dolby_beta] SettingHook: no suitable vertical container found");
                return false;
            }
            XposedBridge.log("[dolby_beta] SettingHook: found container = " + targetContainer.getClass().getName() + ", children = " + targetContainer.getChildCount());

            // Create a styled row matching the existing settings rows
            // Try to find an existing row to copy style from
            TextView styleSource = findFirstTextView(targetContainer);
            LinearLayout linearLayout = createSettingsRow(context, styleSource);
            targetContainer.addView(linearLayout, 0);
            XposedBridge.log("[dolby_beta] SettingHook: settings row inserted via content root at position 0");
            return true;
        } catch (Exception e) {
            XposedBridge.log("[dolby_beta] SettingHook: content root approach failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Find a suitable vertical container in the view hierarchy.
     * Priority: ScrollView > LinearLayout (the preferenceRoot inside a ScrollView).
     * We want the deepest suitable container that holds actual settings rows,
     * NOT the top-level contentContainer that holds statusbar + toolbar + scrollview.
     */
    private ViewGroup findVerticalContainer(View root) {
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;

        // Highest priority: ScrollView's child LinearLayout (preferenceRoot)
        // This is the actual settings list container
        if (group instanceof ScrollView && group.getChildCount() > 0) {
            View child = group.getChildAt(0);
            if (child instanceof LinearLayout && ((LinearLayout) child).getOrientation() == LinearLayout.VERTICAL) {
                XposedBridge.log("[dolby_beta] SettingHook: found ScrollView > LinearLayout (preferenceRoot)");
                return (ViewGroup) child;
            }
        }

        // Second priority: look for resource-id "preferenceRoot" or "preferenceScrollView"
        String resName = "";
        try { resName = group.getId() > 0 ? group.getResources().getResourceEntryName(group.getId()) : ""; } catch (Exception ignored) {}
        if (resName.equals("preferenceRoot")) {
            XposedBridge.log("[dolby_beta] SettingHook: found preferenceRoot by resource-id");
            return group;
        }

        // Recursively search children (depth-first to find deepest match)
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            ViewGroup result = findVerticalContainer(child);
            if (result != null) return result;
        }

        // Fallback: any vertical LinearLayout with multiple children (>= 3 to skip the contentContainer)
        if (group.getChildCount() >= 3) {
            if (group instanceof LinearLayout && ((LinearLayout) group).getOrientation() == LinearLayout.VERTICAL) {
                XposedBridge.log("[dolby_beta] SettingHook: fallback to vertical LinearLayout with " + group.getChildCount() + " children");
                return group;
            }
        }

        return null;
    }

    /**
     * Find the first TextView in the view hierarchy to copy text style from.
     */
    private TextView findFirstTextView(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof TextView) return (TextView) child;
            if (child instanceof ViewGroup) {
                TextView result = findFirstTextView((ViewGroup) child);
                if (result != null) return result;
            }
        }
        return null;
    }

    /**
     * Create the settings row LinearLayout with title and subtitle.
     */
    private LinearLayout createSettingsRow(Context context, ViewGroup styleParent) {
        LinearLayout linearLayout = new LinearLayout(context);
        if (styleParent != null) {
            ViewGroup.LayoutParams layoutParams = styleParent.getLayoutParams();
            if (layoutParams != null) linearLayout.setLayoutParams(layoutParams);
            linearLayout.setBackground(styleParent.getBackground());
        }
        linearLayout.setGravity(Gravity.CENTER_VERTICAL);
        linearLayout.setOrientation(LinearLayout.HORIZONTAL);

        titleView = new TextView(context);
        linearLayout.addView(titleView);
        subView = new TextView(context);
        linearLayout.addView(subView);
        refresh();
        linearLayout.setOnClickListener(view -> showSettingDialog(context));
        return linearLayout;
    }

    /**
     * Create the settings row with a TextView style source.
     */
    private LinearLayout createSettingsRow(Context context, TextView styleSource) {
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setGravity(Gravity.CENTER_VERTICAL);
        linearLayout.setOrientation(LinearLayout.HORIZONTAL);

        titleView = new TextView(context);
        linearLayout.addView(titleView);
        subView = new TextView(context);
        linearLayout.addView(subView);
        refresh();

        if (styleSource != null) {
            titleView.setTextColor(styleSource.getTextColors());
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, styleSource.getTextSize());
            titleView.setPadding(styleSource.getPaddingLeft() == 0 ? Tools.dp2px(context, 10) : styleSource.getPaddingLeft(), 0, 0, 0);
            subView.setTextColor(styleSource.getTextColors());
            subView.setTextSize(TypedValue.COMPLEX_UNIT_PX, (int) (styleSource.getTextSize() / 3.0 * 2.0));
        }

        linearLayout.setOnClickListener(view -> showSettingDialog(context));
        return linearLayout;
    }

    @SuppressLint("SetTextI18n")
    private void refresh() {
        titleView.setText("杜比大喇叭β");
        if (ExtraHelper.getExtraDate(ExtraHelper.USER_ID).equals("-1")) {
            subView.setText("（USERID获取失败）");
        } else if (!SettingHelper.getInstance().getSetting(SettingHelper.master_key))
            subView.setText("（已关闭）");
        else if (ExtraHelper.getExtraDate(ExtraHelper.SCRIPT_STATUS).equals("1"))
            subView.setText("（UnblockNeteaseMusic正在运行）");
        else
            subView.setText("（UnblockNeteaseMusic停止运行）");
    }

    private void registerBroadcastReceiver(final Context context) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(SettingHelper.refresh_setting);
        intentFilter.addAction(SettingHelper.proxy_setting);
        intentFilter.addAction(SettingHelper.beauty_setting);
        intentFilter.addAction(SettingHelper.sidebar_setting);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent.getAction().equals(SettingHelper.refresh_setting)) {
                    for (int i = 0; i < dialogRoot.getChildCount(); i++) {
                        if (dialogRoot.getChildAt(i) instanceof BaseDialogItem)
                            ((BaseDialogItem) dialogRoot.getChildAt(i)).refresh();
                    }
                    if (dialogProxyRoot != null)
                        for (int i = 0; i < dialogProxyRoot.getChildCount(); i++) {
                            if (dialogProxyRoot.getChildAt(i) instanceof BaseDialogItem)
                                ((BaseDialogItem) dialogProxyRoot.getChildAt(i)).refresh();
                            else if (dialogProxyRoot.getChildAt(i) instanceof BaseDialogInputItem)
                                ((BaseDialogInputItem) dialogProxyRoot.getChildAt(i)).refresh();
                        }
                    if (dialogBeautyRoot != null)
                        for (int i = 0; i < dialogBeautyRoot.getChildCount(); i++) {
                            if (dialogBeautyRoot.getChildAt(i) instanceof BaseDialogItem)
                                ((BaseDialogItem) dialogBeautyRoot.getChildAt(i)).refresh();
                        }
                } else if (intent.getAction().equals(SettingHelper.proxy_setting)) {
                    showProxyDialog(context);
                } else if (intent.getAction().equals(SettingHelper.beauty_setting)) {
                    showBeautyDialog(context);
                } else if (intent.getAction().equals(SettingHelper.sidebar_setting)) {
                    showSidebarDialog(context);
                }
            }
        };
        context.registerReceiver(broadcastReceiver, intentFilter);
    }

    private void showSettingDialog(final Context context) {
        dialogRoot = new BaseDialogItem(context);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(dialogRoot);

        MasterView masterView = new MasterView(context);
        DexView dexView = new DexView(context);
        dexView.setBaseOnView(masterView);
        WarnView warnView = new WarnView(context);
        warnView.setBaseOnView(masterView);
        BlackView blackView = new BlackView(context);
        blackView.setBaseOnView(masterView);
        ListenView listenView = new ListenView(context);
        listenView.setBaseOnView(masterView);
        FixCommentView fixCommentView = new FixCommentView(context);
        fixCommentView.setBaseOnView(masterView);
        UpdateView updateView = new UpdateView(context);
        updateView.setBaseOnView(masterView);
        SignView signView = new SignView(context);
        signView.setBaseOnView(masterView);
        SignSongDailyView signSongDailyView = new SignSongDailyView(context);
        signSongDailyView.setBaseOnView(masterView);
        SignSongSelfView signSongSelfView = new SignSongSelfView(context);
        signSongSelfView.setBaseOnView(masterView);
        ProxyView proxyView = new ProxyView(context);
        proxyView.setBaseOnView(masterView);
        BeautyView beautyView = new BeautyView(context);
        beautyView.setBaseOnView(masterView);

        dialogRoot.addView(new TitleView(context));
        dialogRoot.addView(masterView);
        dialogRoot.addView(dexView);
        dialogRoot.addView(warnView);
        dialogRoot.addView(blackView);
        dialogRoot.addView(listenView);
        dialogRoot.addView(fixCommentView);
        dialogRoot.addView(updateView);
        dialogRoot.addView(signView);
        dialogRoot.addView(signSongDailyView);
        dialogRoot.addView(signSongSelfView);
        dialogRoot.addView(proxyView);
        dialogRoot.addView(beautyView);
        dialogRoot.addView(new AboutView(context));
        new AlertDialog.Builder(context)
                .setView(scrollView)
                .setCancelable(false)
                .setPositiveButton("确定", (dialogInterface, i) -> refresh())
                .setNegativeButton("重启网易云", (dialogInterface, i) -> restartApplication(context)).show();
    }

    private void showProxyDialog(final Context context) {
        dialogProxyRoot = new BaseDialogItem(context);
        dialogProxyRoot.setOrientation(LinearLayout.VERTICAL);
        ProxyMasterView proxyMasterView = new ProxyMasterView(context);
        ProxyCoverView proxyCoverView = new ProxyCoverView(context);
        proxyCoverView.setBaseOnView(proxyMasterView);
        ProxyServerView ProxyServerView = new ProxyServerView(context);
        ProxyServerView.setBaseOnView(proxyMasterView);
        ProxyPriorityView proxyPriorityView = new ProxyPriorityView(context);
        proxyPriorityView.setBaseOnView(proxyMasterView);
        ProxyFlacView proxyFlacView = new ProxyFlacView(context);
        proxyFlacView.setBaseOnView(proxyMasterView);
        ProxyGrayView proxyGrayView = new ProxyGrayView(context);
        proxyGrayView.setBaseOnView(proxyMasterView);
        ProxyHttpView proxyHttpView = new ProxyHttpView(context);
        proxyHttpView.setBaseOnView(proxyMasterView);
        ProxyPortView proxyPortView = new ProxyPortView(context);
        proxyPortView.setBaseOnView(proxyMasterView);
        ProxyOriginalView proxyOriginalView = new ProxyOriginalView(context);
        proxyOriginalView.setBaseOnView(proxyMasterView);

        dialogProxyRoot.addView(new ProxyTitleView(context));
        dialogProxyRoot.addView(proxyMasterView);
        dialogProxyRoot.addView(proxyCoverView);
        dialogProxyRoot.addView(ProxyServerView);
        dialogProxyRoot.addView(proxyPriorityView);
        dialogProxyRoot.addView(proxyFlacView);
        dialogProxyRoot.addView(proxyGrayView);
        dialogProxyRoot.addView(proxyHttpView);
        dialogProxyRoot.addView(proxyPortView);
        dialogProxyRoot.addView(proxyOriginalView);
        new AlertDialog.Builder(context)
                .setView(dialogProxyRoot)
                .setCancelable(true)
                .setPositiveButton("仅保存", (dialogInterface, i) -> refresh())
                .setNegativeButton("保存并重启", (dialogInterface, i) -> restartApplication(context)).show();
    }

    private void showBeautyDialog(final Context context) {
        dialogBeautyRoot = new BaseDialogItem(context);
        dialogBeautyRoot.setOrientation(LinearLayout.VERTICAL);
        dialogBeautyRoot.addView(new BeautyTitleView(context));
        dialogBeautyRoot.addView(new BeautyNightModeView(context));
        dialogBeautyRoot.addView(new BeautyTabHideView(context));
        dialogBeautyRoot.addView(new BeautyBannerHideView(context));
        dialogBeautyRoot.addView(new BeautyBubbleHideView(context));
        dialogBeautyRoot.addView(new BeautyKSongHideView(context));
        dialogBeautyRoot.addView(new BeautyBlackHideView(context));
        dialogBeautyRoot.addView(new BeautyRotationView(context));
        dialogBeautyRoot.addView(new BeautyCommentHotView(context));
        dialogBeautyRoot.addView(new BeautySidebarHideView(context));
        new AlertDialog.Builder(context)
                .setView(dialogBeautyRoot)
                .setCancelable(true)
                .setPositiveButton("仅保存", (dialogInterface, i) -> refresh())
                .setNegativeButton("保存并重启", (dialogInterface, i) -> restartApplication(context)).show();
    }

    private void showSidebarDialog(final Context context) {
        dialogSidebarRoot = new BaseDialogItem(context);
        dialogSidebarRoot.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(dialogSidebarRoot);

        final LinkedHashMap<String, String> sidebarMap = SidebarEnum.getSidebarEnum();
        final HashMap<String, Boolean> sidebarSettingMap = SettingHelper.getInstance().getSidebarSetting(sidebarMap);
        for (Map.Entry<String, String> entry : sidebarMap.entrySet()) {
            BeautySidebarHideItem item = new BeautySidebarHideItem(context);
            item.initData(sidebarMap, sidebarSettingMap, entry.getKey());
            dialogSidebarRoot.addView(item);
        }

        new AlertDialog.Builder(context)
                .setView(scrollView)
                .setCancelable(true)
                .setPositiveButton("确定", (dialogInterface, i) -> refresh()).show();
    }

    private void restartApplication(Context context) {
        ExtraHelper.setExtraDate(ExtraHelper.SCRIPT_STATUS, "0");
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningAppProcessInfoListist = activityManager.getRunningAppProcesses();
        for (ActivityManager.RunningAppProcessInfo runningAppProcessInfo : runningAppProcessInfoListist) {
            if (runningAppProcessInfo.processName.contains(":play")) {
                android.os.Process.killProcess(runningAppProcessInfo.pid);
                break;
            }
        }
        final Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
}
