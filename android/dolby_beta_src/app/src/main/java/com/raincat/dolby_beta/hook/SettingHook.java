package com.raincat.dolby_beta.hook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
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
    private String settingActivityName;
    private String rnActivityName = "com.netease.cloudmusic.music.biz.rn.activity.MainProcessRNActivity";
    private TextView titleView, subView;
    private LinearLayout dialogRoot, dialogProxyRoot, dialogBeautyRoot, dialogSidebarRoot;

    private BroadcastReceiver broadcastReceiver;

    /** Unique tag for our settings row, so we can detect if it already exists in the view hierarchy */
    private static final String SETTINGS_ROW_TAG = "dolby_beta_settings_row";

    public SettingHook(Context context, int versionCode) {
        // Determine the legacy native SettingActivity class name
        if (versionCode >= 8007000) {
            settingActivityName = "com.netease.cloudmusic.music.biz.setting.activity.SettingActivity";
        } else {
            settingActivityName = "com.netease.cloudmusic.activity.SettingActivity";
        }

        // Hook 1: The RN-based settings page (MainProcessRNActivity) — this is the one users
        // actually see when entering Settings from the sidebar in v9.5.30+.
        Class<?> rnActivityClass = findClassIfExists(rnActivityName, context.getClassLoader());
        if (rnActivityClass != null) {
            XposedBridge.log("[dolby_beta] SettingHook: hooking RN activity: " + rnActivityName);
            hookActivity(rnActivityClass);
        } else {
            XposedBridge.log("[dolby_beta] SettingHook: RN activity not found: " + rnActivityName);
        }

        // Hook 2: The legacy native SettingActivity — still exists but users rarely enter it directly.
        Class<?> settingActivityClass = findClassIfExists(settingActivityName, context.getClassLoader());
        if (settingActivityClass != null) {
            XposedBridge.log("[dolby_beta] SettingHook: hooking native activity: " + settingActivityName);
            hookActivity(settingActivityClass);
        } else {
            XposedBridge.log("[dolby_beta] SettingHook: native SettingActivity not found: " + settingActivityName);
        }
    }

    /**
     * Hook onResume and onDestroy for the given Activity class.
     * On resume: ensure our settings row is present in the view hierarchy.
     * On destroy: clean up broadcast receiver.
     */
    private void hookActivity(Class<?> activityClass) {
        findAndHookMethod(activityClass, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                Activity activity = (Activity) param.thisObject;
                ensureSettingsRow(activity);
            }
        });

        findAndHookMethod(activityClass, "onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                if (broadcastReceiver != null) {
                    try {
                        ((Context) param.thisObject).unregisterReceiver(broadcastReceiver);
                    } catch (Exception ignored) {}
                    broadcastReceiver = null;
                }
            }
        });
    }

    /**
     * Ensure the settings row exists in the view hierarchy.
     * If it already exists (found by tag), just refresh the subtitle text.
     * If not, find a suitable container and insert it.
     *
     * RN pages are rendered asynchronously — views may not be ready when onResume fires.
     * We handle this by posting a delayed retry when we detect the settings page
     * but can't find the content yet.
     */
    private void ensureSettingsRow(Activity activity) {
        try {
            // First: check if our row already exists anywhere in the activity's view tree
            View contentView = activity.findViewById(android.R.id.content);
            if (contentView != null) {
                View existingRow = contentView.findViewWithTag(SETTINGS_ROW_TAG);
                if (existingRow != null) {
                    // Row already present — just refresh the subtitle
                    refresh();
                    // Also ensure broadcast receiver is registered
                    registerBroadcastReceiverOnce(activity);
                    XposedBridge.log("[dolby_beta] SettingHook: row already present, refreshed");
                    return;
                }
            }

            // Register broadcast first
            registerBroadcastReceiverOnce(activity);

            // Try to insert into the RN settings page layout
            if (tryInsertIntoRNSettings(activity)) {
                return;
            }

            // Fallback: try the native settings page approach
            if (tryInsertViaContentRoot(activity)) {
                return;
            }

            // If we got here, all strategies failed. This could be because:
            // 1. The RN page hasn't finished rendering yet (most common)
            // 2. We're on a different RN page entirely
            // Schedule a delayed retry to handle the async rendering case.
            XposedBridge.log("[dolby_beta] SettingHook: all strategies failed, scheduling delayed retry");
            activity.getWindow().getDecorView().postDelayed(() -> {
                try {
                    // Check again if row was already inserted (by a previous retry)
                    View cv = activity.findViewById(android.R.id.content);
                    if (cv != null && cv.findViewWithTag(SETTINGS_ROW_TAG) != null) {
                        XposedBridge.log("[dolby_beta] SettingHook: delayed retry found row already present");
                        return;
                    }
                    if (tryInsertIntoRNSettings(activity)) {
                        XposedBridge.log("[dolby_beta] SettingHook: delayed retry succeeded (RN)");
                        return;
                    }
                    if (tryInsertViaContentRoot(activity)) {
                        XposedBridge.log("[dolby_beta] SettingHook: delayed retry succeeded (native)");
                        return;
                    }
                    XposedBridge.log("[dolby_beta] SettingHook: delayed retry also failed");
                } catch (Exception e) {
                    XposedBridge.log("[dolby_beta] SettingHook: delayed retry exception: " + e.getMessage());
                }
            }, 500); // 500ms delay for RN content to render
        } catch (Exception e) {
            XposedBridge.log("[dolby_beta] SettingHook: ensureSettingsRow failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Strategy for RN settings page (MainProcessRNActivity):
     * The RN layout is: contentContainer → musicContainer → RN root → ... →
     * FrameLayout[scrollable] → ViewGroup (list of setting rows) → ViewGroup rows
     * We need to find the scrollable FrameLayout's first child ViewGroup (the list container)
     * and insert our row before the first existing row.
     *
     * IMPORTANT: MainProcessRNActivity is a generic RN container used for many pages.
     * We must verify this is actually the Settings page by checking for "设置" title text.
     */
    private boolean tryInsertIntoRNSettings(Activity activity) {
        try {
            View contentView = activity.findViewById(android.R.id.content);
            if (contentView == null) return false;

            // First, verify this is the Settings page by finding a "设置" TextView
            if (!findTextInView(contentView, "设置")) {
                XposedBridge.log("[dolby_beta] SettingHook: RN: not a settings page (no '设置' title found)");
                return false;
            }

            // Find the scrollable FrameLayout inside the RN view tree
            ViewGroup scrollContainer = findScrollableFrameLayout(contentView);
            if (scrollContainer == null) {
                XposedBridge.log("[dolby_beta] SettingHook: RN: no scrollable FrameLayout found");
                return false;
            }

            // The scrollable FrameLayout's first child should be the ViewGroup that
            // contains all the setting rows
            if (scrollContainer.getChildCount() == 0) {
                XposedBridge.log("[dolby_beta] SettingHook: RN: scrollable container has no children");
                return false;
            }

            View listChild = scrollContainer.getChildAt(0);
            if (!(listChild instanceof ViewGroup)) {
                XposedBridge.log("[dolby_beta] SettingHook: RN: scrollable child is not a ViewGroup: " + listChild.getClass().getName());
                return false;
            }

            ViewGroup rowListContainer = (ViewGroup) listChild;
            XposedBridge.log("[dolby_beta] SettingHook: RN: found row list container: " + rowListContainer.getClass().getName() + ", children = " + rowListContainer.getChildCount());

            // Find an existing row to copy style from
            View styleRow = findRNSettingsRow(rowListContainer);
            LinearLayout ourRow = createRNSettingsRow(activity, styleRow);
            // Insert at position 0 (before "账号与安全")
            rowListContainer.addView(ourRow, 0);
            XposedBridge.log("[dolby_beta] SettingHook: RN: settings row inserted at position 0");
            return true;
        } catch (Exception e) {
            XposedBridge.log("[dolby_beta] SettingHook: RN insert failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if a specific text string exists in the view hierarchy.
     * Limited depth to avoid performance issues when scanning large RN view trees.
     */
    private boolean findTextInView(View root, String target) {
        return findTextInViewInternal(root, target, 0, 15);
    }

    private boolean findTextInViewInternal(View root, String target, int depth, int maxDepth) {
        if (depth > maxDepth) return false;
        if (root instanceof TextView) {
            CharSequence text = ((TextView) root).getText();
            if (text != null && text.toString().equals(target)) return true;
        }
        if (root instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                if (findTextInViewInternal(((ViewGroup) root).getChildAt(i), target, depth + 1, maxDepth)) return true;
            }
        }
        return false;
    }

    /**
     * Find the first scrollable FrameLayout in the view hierarchy.
     * In RN settings, this is the main scroll container (a FrameLayout that acts as ScrollView).
     * We identify it by: FrameLayout instance + isScrollContainer() == true + has children with setting rows.
     */
    private ViewGroup findScrollableFrameLayout(View root) {
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;

        // Check if this is a scrollable FrameLayout (the RN scroll container)
        // isScrollContainer() returns true for views that can scroll their content
        if (group instanceof android.widget.FrameLayout) {
            // Check if this looks like the RN scroll container by verifying:
            // 1. It's scrollable
            // 2. It has a child ViewGroup that contains multiple rows with TextViews
            if (group.isScrollContainer() && group.getChildCount() > 0) {
                View firstChild = group.getChildAt(0);
                if (firstChild instanceof ViewGroup) {
                    ViewGroup childGroup = (ViewGroup) firstChild;
                    // Check if it has multiple children that look like setting rows
                    int rowLikeCount = 0;
                    for (int i = 0; i < childGroup.getChildCount(); i++) {
                        View row = childGroup.getChildAt(i);
                        if (row instanceof ViewGroup && hasTextView((ViewGroup) row)) {
                            rowLikeCount++;
                        }
                    }
                    if (rowLikeCount >= 5) {
                        XposedBridge.log("[dolby_beta] SettingHook: RN: found scrollable FrameLayout with " + rowLikeCount + " row-like children");
                        return group;
                    }
                }
            }
        }

        // Recursively search children
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            ViewGroup result = findScrollableFrameLayout(child);
            if (result != null) return result;
        }

        return null;
    }

    /**
     * Find the first ViewGroup child that looks like an RN settings row
     * (has a clickable child that contains a TextView).
     */
    private View findRNSettingsRow(ViewGroup container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof ViewGroup) {
                // Check if this row contains a clickable ViewGroup with a TextView
                if (hasClickableWithText((ViewGroup) child)) {
                    return child;
                }
            }
        }
        return null;
    }

    /**
     * Check if a ViewGroup has a clickable child that contains a TextView.
     */
    private boolean hasClickableWithText(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.isClickable() && child instanceof ViewGroup) {
                if (hasTextView((ViewGroup) child)) return true;
            }
        }
        return false;
    }

    /**
     * Check if a ViewGroup contains a TextView with non-empty text.
     */
    private boolean hasTextView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && !((TextView) child).getText().toString().isEmpty()) {
                return true;
            }
            if (child instanceof ViewGroup && hasTextView((ViewGroup) child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create a settings row that mimics the RN settings page style.
     * RN rows are: ViewGroup(padding=0, horizontal) → ViewGroup[clickable] →
     *   ViewGroup(padding) → [TextView(title) + optional ImageView(arrow)]
     *
     * We create a native LinearLayout with vertical orientation that looks similar:
     * title text on top, subtitle below, matching the RN row dimensions.
     */
    private LinearLayout createRNSettingsRow(Context context, View styleRow) {
        LinearLayout outerWrapper = new LinearLayout(context);
        outerWrapper.setTag(SETTINGS_ROW_TAG);
        outerWrapper.setOrientation(LinearLayout.VERTICAL);

        // Match RN row dimensions: each row is about 150dp tall, full width with 50dp horizontal padding
        int padH = Tools.dp2px(context, 50);
        int padV = Tools.dp2px(context, 20);
        outerWrapper.setPadding(padH, padV, padH, padV);

        // Try to copy text style from existing RN row
        float existingTextSize = 0;
        int existingTextColor = 0;
        if (styleRow instanceof ViewGroup) {
            TextView existingTv = findFirstTextView((ViewGroup) styleRow);
            if (existingTv != null) {
                existingTextSize = existingTv.getTextSize();
                existingTextColor = existingTv.getCurrentTextColor();
                // Copy background from the clickable inner ViewGroup
                View clickableChild = findClickableChild((ViewGroup) styleRow);
                if (clickableChild != null && clickableChild.getBackground() != null) {
                    Drawable bg = clickableChild.getBackground();
                    outerWrapper.setBackground(bg.getConstantState() != null ? bg.getConstantState().newDrawable() : bg);
                }
            }
        }

        // Reuse the style from the native path if RN style didn't yield results
        // Title text
        titleView = new TextView(context);
        if (existingTextSize > 0) {
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, existingTextSize);
            titleView.setTextColor(existingTextColor);
        } else {
            titleView.setTextSize(16);
        }
        outerWrapper.addView(titleView);

        // Subtitle text
        subView = new TextView(context);
        if (existingTextSize > 0) {
            subView.setTextSize(TypedValue.COMPLEX_UNIT_PX, existingTextSize * 0.8f);
            subView.setTextColor(existingTextColor);
        } else {
            subView.setTextSize(12);
        }
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Tools.dp2px(context, 4);
        subView.setLayoutParams(subLp);
        outerWrapper.addView(subView);

        refresh();
        outerWrapper.setOnClickListener(view -> showSettingDialog(context));
        return outerWrapper;
    }

    /**
     * Find the first clickable child in a ViewGroup (for extracting RN row background).
     */
    private View findClickableChild(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.isClickable()) return child;
            if (child instanceof ViewGroup) {
                View result = findClickableChild((ViewGroup) child);
                if (result != null) return result;
            }
        }
        return null;
    }

    /**
     * Register broadcast receiver only once per Activity lifecycle.
     */
    private void registerBroadcastReceiverOnce(Context context) {
        if (broadcastReceiver != null) return;
        registerBroadcastReceiver(context);
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

            // Try to find an existing row to copy style from
            View existingRow = findFirstSettingsRow(targetContainer);
            LinearLayout linearLayout = createSettingsRow(context, existingRow);
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
        if (group instanceof ScrollView && group.getChildCount() > 0) {
            View child = group.getChildAt(0);
            if (child instanceof LinearLayout && ((LinearLayout) child).getOrientation() == LinearLayout.VERTICAL) {
                XposedBridge.log("[dolby_beta] SettingHook: found ScrollView > LinearLayout (preferenceRoot)");
                return (ViewGroup) child;
            }
        }

        // Second priority: look for resource-id "preferenceRoot"
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

        // Fallback: any vertical LinearLayout with multiple children (>= 5 to skip the outer contentContainer with only 3)
        if (group.getChildCount() >= 5) {
            if (group instanceof LinearLayout && ((LinearLayout) group).getOrientation() == LinearLayout.VERTICAL) {
                XposedBridge.log("[dolby_beta] SettingHook: fallback to vertical LinearLayout with " + group.getChildCount() + " children");
                return group;
            }
        }

        return null;
    }

    /**
     * Find the first settings row (a ViewGroup child of the container that contains a TextView)
     * to use as a style reference.
     */
    private View findFirstSettingsRow(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof ViewGroup && findFirstTextView((ViewGroup) child) != null) {
                return child;
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
     * This version creates a row that matches the app's native preference item style:
     * - Vertical LinearLayout with title on top and subtitle below
     * - Proper padding and text sizes
     * - Click ripple background copied from an existing row
     * - Tagged with SETTINGS_ROW_TAG for reliable re-detection
     */
    private LinearLayout createSettingsRow(Context context, View styleParent) {
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setTag(SETTINGS_ROW_TAG);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        // Copy layout params from an existing row if available
        if (styleParent != null) {
            ViewGroup.LayoutParams lp = styleParent.getLayoutParams();
            if (lp != null) {
                linearLayout.setLayoutParams(new ViewGroup.LayoutParams(lp.width, lp.height));
            }
            // Copy background (ripple/drawable) from existing row
            Drawable bg = styleParent.getBackground();
            if (bg != null) {
                linearLayout.setBackground(bg.getConstantState() != null ? bg.getConstantState().newDrawable() : bg);
            }
        }

        // Default padding if no style parent
        int PadH = Tools.dp2px(context, 16);
        int PadV = Tools.dp2px(context, 12);
        linearLayout.setPadding(PadH, PadV, PadH, PadV);

        // Try to get text style from an existing row
        float titleTextSize = 0;
        int titleTextColor = 0;
        float subTextSize = 0;
        int subTextColor = 0;
        int titlePadLeft = 0;

        if (styleParent instanceof ViewGroup) {
            TextView existingTitle = findFirstTextView((ViewGroup) styleParent);
            if (existingTitle != null) {
                titleTextSize = existingTitle.getTextSize();
                titleTextColor = existingTitle.getCurrentTextColor();
                titlePadLeft = existingTitle.getPaddingLeft();
                subTextSize = titleTextSize * 0.8f;
                subTextColor = titleTextColor;
            }
        }

        titleView = new TextView(context);
        if (titleTextSize > 0) {
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, titleTextSize);
            titleView.setTextColor(titleTextColor);
            titleView.setPadding(titlePadLeft > 0 ? titlePadLeft : 0, 0, 0, 0);
        } else {
            titleView.setTextSize(16);
            titleView.setPadding(0, 0, 0, 0);
        }
        linearLayout.addView(titleView);

        subView = new TextView(context);
        if (subTextSize > 0) {
            subView.setTextSize(TypedValue.COMPLEX_UNIT_PX, subTextSize);
            subView.setTextColor(subTextColor);
            subView.setPadding(titlePadLeft > 0 ? titlePadLeft : 0, 0, 0, 0);
        } else {
            subView.setTextSize(12);
        }
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Tools.dp2px(context, 2);
        subView.setLayoutParams(subLp);
        linearLayout.addView(subView);

        refresh();
        linearLayout.setOnClickListener(view -> showSettingDialog(context));
        return linearLayout;
    }

    @SuppressLint("SetTextI18n")
    private void refresh() {
        if (titleView == null || subView == null) return;
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
