package com.example.cryptoapp.Activities;

import android.annotation.SuppressLint;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.util.Base64;
import android.util.Base64InputStream;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;

import com.example.cryptoapp.Base.BaseActivity;
import com.example.cryptoapp.Browser.BrowserDownloadService;
import com.example.cryptoapp.Browser.BrowserDownloadResolver;
import com.example.cryptoapp.Browser.BrowserFavoriteStore;
import com.example.cryptoapp.Browser.BrowserHistoryStore;
import com.example.cryptoapp.Browser.BrowserPreferences;
import com.example.cryptoapp.Browser.MediaResourceAggregator;
import com.example.cryptoapp.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.net.URI;
import java.net.URLDecoder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** 轻量多标签浏览器：标签管理、历史、UA、页内查找、分享和隐私清理。 */
public class SearchActivity extends BaseActivity {
    private static final String BROWSER_STATE = "browser_state";
    private static final String KEY_UA_MODE = "user_agent_mode";
    private static final String KEY_CUSTOM_UA = "custom_user_agent";
    private static final String UA_DEFAULT = "default";
    private static final String UA_MOBILE = "mobile";
    private static final String UA_DESKTOP = "desktop";
    private static final String UA_CUSTOM = "custom";
    /** 大型 data URI 只在 WebView 页面内保存，Java 层通过短标识分块提取。 */
    private static final String PAGE_DATA_PREFIX = "toolbox-page-data:";

    private final List<BrowserTab> tabs = new ArrayList<>();
    private FrameLayout webContainer;
    private EditText address;
    private ImageView favicon;
    private LinearProgressIndicator progress;
    private MaterialButton back;
    private MaterialButton forward;
    private MaterialButton tabButton;
    private TextView tabCount;
    private BrowserTab current;
    private SharedPreferences browserState;
    private SharedPreferences config;
    private BrowserHistoryStore historyStore;
    private boolean mediaTimelineProbeRunning;
    private boolean browserResumed;
    private BrowserFavoriteStore favoriteStore;
    private PendingDownload pendingDownload;
    private final Map<WebView, float[]> lastWebTouchPoints = new WeakHashMap<>();
    private final ActivityResultLauncher<String> storagePermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                PendingDownload download = pendingDownload;
                if (granted && download != null) startDownloadService(download);
                else if (!granted) {
                    discardPendingTemporary(download);
                    pendingDownload = null;
                    Toast.makeText(this, "未授予存储权限，无法保存下载文件", Toast.LENGTH_LONG).show();
                }
            });
    private final ActivityResultLauncher<String> notificationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                PendingDownload download = pendingDownload;
                if (granted && download != null) requestLegacyStorageOrStart(download);
                else if (!granted) {
                    discardPendingTemporary(download);
                    pendingDownload = null;
                    Toast.makeText(this, "需要通知权限才能显示下载进度及暂停、继续、取消按钮", Toast.LENGTH_LONG).show();
                }
            });
    private final ActivityResultLauncher<Intent> historyLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String url = result.getData().getStringExtra(BrowserHistoryActivity.EXTRA_SELECTED_URL);
                    if (url != null && !url.isEmpty()) navigate(url);
                }
            });

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        browserState = getSharedPreferences(BROWSER_STATE, MODE_PRIVATE);
        config = getSharedPreferences(BrowserPreferences.CONFIG, MODE_PRIVATE);
        historyStore = new BrowserHistoryStore(this);
        favoriteStore = new BrowserFavoriteStore(this);
        webContainer = findViewById(R.id.webContainer);
        address = findViewById(R.id.textUrl);
        favicon = findViewById(R.id.webIcon);
        progress = findViewById(R.id.progressBar);
        back = findViewById(R.id.goBack);
        forward = findViewById(R.id.goForward);
        tabButton = findViewById(R.id.navTabs);
        tabCount = findViewById(R.id.tabCount);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (current != null && current.webView.canGoBack()) current.webView.goBack();
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
        address.setOnEditorActionListener((v, actionId, event) -> {
            if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) {
                navigate(address.getText().toString());
                address.clearFocus();
                return true;
            }
            return false;
        });
        findViewById(R.id.btnStart).setOnClickListener(v -> {
            if (address.hasFocus()) navigate(address.getText().toString());
            else if (current != null) current.webView.reload();
        });
        findViewById(R.id.browserExit).setOnClickListener(v -> finish());
        bindAnimatedClick(back, () -> { if (current != null && current.webView.canGoBack()) current.webView.goBack(); });
        bindAnimatedClick(forward, () -> { if (current != null && current.webView.canGoForward()) current.webView.goForward(); });
        bindAnimatedClick(findViewById(R.id.goHome), () -> navigate(homeUrl()));
        bindAnimatedClick(tabButton, this::showTabs);
        bindAnimatedClick(findViewById(R.id.navSet), this::showMenu);

        if (savedInstanceState != null) {
            ArrayList<Bundle> restored = savedInstanceState.getParcelableArrayList("tab_states");
            if (restored != null) for (Bundle state : restored) restoreTab(state);
            if (!tabs.isEmpty()) switchTo(tabs.get(Math.min(
                    savedInstanceState.getInt("current_tab", tabs.size() - 1), tabs.size() - 1)));
        }
        if (tabs.isEmpty()) {
            String initial = getIntent().getStringExtra("web_address");
            newTab(initial == null || initial.trim().isEmpty() ? homeUrl() : initial);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView() {
        WebView view = new WebView(this);
        view.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WebSettings settings = view.getSettings();
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        applySettings(view);
        view.setWebViewClient(new BrowserClient());
        view.setWebChromeClient(new BrowserChromeClient());
        view.setDownloadListener(this::prepareDownload);
        view.setOnTouchListener((ignored, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                float scale = Math.max(0.01f, view.getScale());
                lastWebTouchPoints.put(view, new float[]{event.getX() / scale, event.getY() / scale});
            }
            return false;
        });
        view.setOnLongClickListener(ignored -> showWebElementActions(view));
        return view;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void applySettings(WebView view) {
        view.getSettings().setJavaScriptEnabled(config.getBoolean(BrowserPreferences.JAVASCRIPT_ENABLED, true));
        CookieManager.getInstance().setAcceptThirdPartyCookies(view,
                !config.getBoolean(BrowserPreferences.BLOCK_THIRD_PARTY_COOKIES, true));
        String mode = browserState.getString(KEY_UA_MODE, UA_DEFAULT);
        String ua = null;
        if (UA_DESKTOP.equals(mode) || (UA_DEFAULT.equals(mode)
                && config.getBoolean(BrowserPreferences.DESKTOP_MODE_DEFAULT, false))) ua = desktopUserAgent();
        else if (UA_CUSTOM.equals(mode)) ua = browserState.getString(KEY_CUSTOM_UA, "");
        view.getSettings().setUserAgentString(ua == null || ua.isEmpty() ? null : ua);
    }

    private void newTab(String input) {
        BrowserTab tab = new BrowserTab(createWebView());
        tabs.add(tab);
        switchTo(tab);
        navigate(input);
    }

    private void restoreTab(Bundle state) {
        BrowserTab tab = new BrowserTab(createWebView());
        tab.url = state.getString("toolbox_url", homeUrl());
        tab.title = state.getString("toolbox_title", "新标签页");
        tabs.add(tab);
        if (tab.webView.restoreState(state) == null) tab.webView.loadUrl(tab.url);
    }

    private void switchTo(BrowserTab tab) {
        if (current != null) {
            current.webView.onPause();
            webContainer.removeView(current.webView);
        }
        current = tab;
        webContainer.addView(tab.webView, 0);
        if (browserResumed) tab.webView.onResume();
        else tab.webView.onPause();
        address.setText(tab.webView.getUrl() == null ? tab.url : tab.webView.getUrl());
        updateNavigation();
    }

    private void closeTab(BrowserTab tab) {
        int index = tabs.indexOf(tab);
        if (index < 0) return;
        boolean wasCurrent = tab == current;
        if (wasCurrent) webContainer.removeView(tab.webView);
        tab.webView.stopLoading();
        tab.webView.destroy();
        tabs.remove(tab);
        if (tabs.isEmpty()) newTab(homeUrl());
        else if (wasCurrent) switchTo(tabs.get(Math.min(index, tabs.size() - 1)));
        updateNavigation();
    }

    private void navigate(String input) {
        if (current == null) return;
        String url = normalizeUrl(input);
        current.url = url;
        current.webView.loadUrl(url);
    }

    private String normalizeUrl(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) return homeUrl();
        if (value.matches("^https?://.*")) return value;
        if (value.matches("^[^\\s]+\\.[^\\s]+.*")) return "https://" + value;
        return config.getString(BrowserPreferences.SEARCH_METHOD, "https://cn.bing.com/search?q=") + Uri.encode(value);
    }

    private String homeUrl() { return config.getString(BrowserPreferences.HOME_URL, "https://cn.bing.com"); }

    /** 每个标签均提供独立关闭按钮，最后一个关闭后自动创建主页标签。 */
    private void showTabs() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(8);
        list.setPadding(padding, padding, padding, padding);
        scroll.addView(list);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("标签页（" + tabs.size() + "）").setView(scroll)
                .setPositiveButton("新建标签", (d, which) -> newTab(homeUrl()))
                .setNegativeButton("完成", null).create();
        for (BrowserTab tab : new ArrayList<>(tabs)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView text = new TextView(this);
            text.setText((tab == current ? "● " : "") + safeTitle(tab) + "\n" + tab.url);
            text.setMaxLines(2);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.setPadding(dp(12), dp(10), dp(8), dp(10));
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            MaterialButton close = new MaterialButton(this);
            close.setText("");
            close.setIconResource(R.drawable.ic_close_24);
            close.setIconPadding(0);
            close.setContentDescription("关闭标签 " + safeTitle(tab));
            close.setMinWidth(0);
            row.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52)));
            text.setOnClickListener(v -> { switchTo(tab); dialog.dismiss(); });
            close.setOnClickListener(v -> { closeTab(tab); dialog.dismiss(); showTabs(); });
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        dialog.show();
    }

    private String safeTitle(BrowserTab tab) {
        return tab.title == null || tab.title.trim().isEmpty() ? "新标签页" : tab.title;
    }

    private void showMenu() {
        BrowserMenuAction[] actions = {
                new BrowserMenuAction("新建标签", R.drawable.ic_add_24, () -> newTab(homeUrl())),
                new BrowserMenuAction("关闭标签", R.drawable.ic_close_24, () -> { if (current != null) closeTab(current); }),
                new BrowserMenuAction("历史记录", R.drawable.ic_history_24, () -> historyLauncher.launch(new Intent(this, BrowserHistoryActivity.class))),
                new BrowserMenuAction("添加收藏", R.drawable.ic_bookmark_add_24, this::addCurrentFavorite),
                new BrowserMenuAction("收藏夹", R.drawable.ic_bookmark_24, () -> historyLauncher.launch(new Intent(this, BrowserFavoritesActivity.class))),
                new BrowserMenuAction("刷新网页", R.drawable.ic_refresh_24, () -> { if (current != null) current.webView.reload(); }),
                new BrowserMenuAction("页内查找", R.drawable.ic_find_in_page_24, this::showFindInPage),
                new BrowserMenuAction("分享网页", R.drawable.ic_share_24, this::shareCurrentPage),
                new BrowserMenuAction("复制链接", R.drawable.ic_link_24, this::copyCurrentUrl),
                new BrowserMenuAction("资源嗅探", R.drawable.ic_video_search_24, this::showSniffedResources),
                new BrowserMenuAction("下载管理", R.drawable.ic_download_24, () -> startActivity(new Intent(this, BrowserDownloadsActivity.class))),
                new BrowserMenuAction("User-Agent", R.drawable.ic_language_24, this::showUserAgentSettings),
                new BrowserMenuAction("网页信息", R.drawable.ic_info_24, this::showPageInfo),
                new BrowserMenuAction("清理数据", R.drawable.ic_cleaning_24, this::confirmClearBrowserData),
                new BrowserMenuAction("浏览器设置", R.drawable.ic_settings_24, () -> startActivity(new Intent(this, SettingsActivity.class))),
                new BrowserMenuAction("外部打开", R.drawable.ic_open_in_new_24, () -> { if (current != null) openExternal(current.url); })
        };
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(12), dp(8), dp(12), dp(24));

        // Material 3 底部抽屉把手：弱化容器边界，让面板和网页的层级更清楚。
        View handle = new View(this);
        GradientDrawable handleBackground = new GradientDrawable();
        handleBackground.setColor(themeColor(sheet,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                R.color.browser_on_surface_variant));
        handleBackground.setCornerRadius(dp(2));
        handle.setBackground(handleBackground);
        handle.setAlpha(0.38f);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(32), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(14);
        sheet.addView(handle, handleParams);

        TextView title = new TextView(this);
        title.setText("浏览器功能");
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        title.setPadding(dp(8), 0, dp(8), dp(2));
        sheet.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("快捷操作与网页工具");
        subtitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        subtitle.setTextColor(themeColor(subtitle,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                R.color.browser_on_surface_variant));
        subtitle.setPadding(dp(8), 0, dp(8), dp(12));
        sheet.addView(subtitle);

        // 紧凑宽度使用 4 列；横屏、平板和 HD 大屏自动扩展为 6 列。
        int menuColumns = getResources().getConfiguration().screenWidthDp >= 600 ? 6 : 4;
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int menuRows = (actions.length + menuColumns - 1) / menuColumns;
        for (int rowIndex = 0; rowIndex < menuRows; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(94)));
            for (int columnIndex = 0; columnIndex < menuColumns; columnIndex++) {
                int actionIndex = rowIndex * menuColumns + columnIndex;
                View tile;
                if (actionIndex < actions.length) {
                    BrowserMenuAction action = actions[actionIndex];
                    tile = createBrowserMenuTile(action);
                    tile.setOnClickListener(view -> {
                        // 先展示触控反馈，再打开下一个页面或对话框。
                        view.postDelayed(() -> {
                            if (!isFinishing()) dialog.dismiss();
                            action.command.run();
                        }, 90L);
                    });
                } else {
                    // 最后一行补空白占位，保证所有实际项目仍与前几行严格等宽。
                    tile = new View(this);
                }
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, dp(88), 1f);
                tileParams.setMargins(dp(2), dp(3), dp(2), dp(3));
                row.addView(tile, tileParams);
            }
        }
        dialog.setContentView(sheet);
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.setOnShowListener(ignored -> {
            // 平板横屏的自动 peekHeight 通常只够显示一行，功能面板必须完整展开。
            dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            sheet.setTranslationY(dp(20));
            sheet.setAlpha(0f);
            sheet.animate().translationY(0f).alpha(1f).setDuration(220L).start();
        });
        dialog.show();
    }

    /**
     * 创建统一的 Material 3 快捷操作单元。48dp 色调容器保证视觉一致，
     * 但整块 88dp 单元都可点击，兼顾美观和无障碍触控面积。
     */
    private View createBrowserMenuTile(BrowserMenuAction action) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription(action.label);

        TypedValue selectable = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true)) {
            tile.setBackgroundResource(selectable.resourceId);
        }

        MaterialCardView iconContainer = new MaterialCardView(this);
        iconContainer.setRadius(dp(16));
        iconContainer.setCardElevation(0f);
        iconContainer.setStrokeWidth(0);
        iconContainer.setCardBackgroundColor(themeColor(iconContainer,
                com.google.android.material.R.attr.colorSecondaryContainer,
                R.color.browser_secondary_container));
        iconContainer.setClickable(false);

        AppCompatImageView icon = new AppCompatImageView(this);
        icon.setImageResource(action.icon);
        icon.setImageTintList(ColorStateList.valueOf(themeColor(icon,
                com.google.android.material.R.attr.colorOnSecondaryContainer,
                R.color.browser_on_secondary_container)));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER);
        iconContainer.addView(icon, iconParams);
        tile.addView(iconContainer, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView label = new TextView(this);
        label.setText(action.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(themeColor(label,
                com.google.android.material.R.attr.colorOnSurface,
                R.color.browser_on_surface));
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(6);
        labelParams.leftMargin = dp(2);
        labelParams.rightMargin = dp(2);
        tile.addView(label, labelParams);
        return tile;
    }

    /**
     * 安全读取主题颜色。部分厂商 ROM 会叠加主题，旧 Material Components 主题也可能
     * 缺少 Material 3 令牌；使用显式回退色可避免仅因视觉属性缺失导致页面崩溃。
     */
    private int themeColor(View view, int attribute, int fallbackColor) {
        return MaterialColors.getColor(view.getContext(), attribute,
                ContextCompat.getColor(view.getContext(), fallbackColor));
    }

    private void addCurrentFavorite() {
        if (current == null) return;
        boolean added = favoriteStore.add(safeTitle(current), current.url);
        Toast.makeText(this, added ? "已加入收藏夹" : "已更新收藏", Toast.LENGTH_SHORT).show();
    }

    private void showSniffedResources() {
        if (current == null) return;
        if (mediaTimelineProbeRunning) {
            Toast.makeText(this, "正在模拟全时间轴请求，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        final BrowserTab target = current;
        mediaTimelineProbeRunning = true;
        Toast.makeText(this, "正在探测网页媒体请求，约需 2 秒…", Toast.LENGTH_LONG).show();
        target.webView.evaluateJavascript(mediaTimelineProbeScript(), started -> {
            long delay = "true".equals(started) ? 2_200L : 0L;
            new Handler(Looper.getMainLooper()).postDelayed(() -> collectSniffedResources(target), delay);
        });
    }

    /**
     * 仅通过带凭据的小范围网络请求补充捕获候选清单，不创建 MediaCodec，也不改变网页播放器。
     * 硬件解码器实例数远小于设备内存容量；用隐藏 video 反复 seek/play 会直接耗尽解码器。
     */
    private String mediaTimelineProbeScript() {
        return "(()=>{"
                + "if(window.__toolboxMediaProbe)return false;"
                + "const f=[];const add=u=>{try{u=new URL(String(u||''),location.href).href;"
                + "if(/^https?:/i.test(u)&&/\\.(?:m3u8|mpd|mp4|webm|mkv|flv|mp3|m4a|aac|flac|ogg)(?:[/?#]|$)/i.test(u))f.push(u)}catch(_){}};"
                + "document.querySelectorAll('video,audio,source,[data-src],[data-url]').forEach(e=>"
                + "['src','data-src','data-url'].forEach(a=>add(e.getAttribute&&e.getAttribute(a))));"
                + "performance.getEntriesByType('resource').forEach(e=>add(e.name));"
                + "const urls=Array.from(new Set(f)).slice(-12);if(!urls.length)return false;"
                + "window.__toolboxMediaProbe=true;Promise.allSettled(urls.map(u=>{const c=new AbortController();"
                + "const timer=setTimeout(()=>c.abort(),1800);return fetch(u,{credentials:'include',cache:'no-store',"
                + "headers:{Range:'bytes=0-65535'},signal:c.signal}).then(r=>r.body?r.body.cancel():null)"
                + ".catch(()=>null).finally(()=>clearTimeout(timer))})).finally(()=>{window.__toolboxMediaProbe=false});"
                + "return true})()";
    }

    private void collectSniffedResources(BrowserTab target) {
        if (isFinishing() || isDestroyed() || !tabs.contains(target) || current != target) {
            mediaTimelineProbeRunning = false;
            return;
        }
        target.webView.evaluateJavascript(
                "(()=>{const found=[];"
                        + "document.querySelectorAll('video,audio,source').forEach(e=>{found.push(e.src,e.currentSrc)});"
                        + "document.querySelectorAll('[src],[href],[data-src],[data-url]').forEach(e=>"
                        + "['src','href','data-src','data-url'].forEach(a=>found.push(e.getAttribute(a))));"
                        + "performance.getEntriesByType('resource').forEach(e=>found.push(e.name));"
                        + "const html=document.documentElement.innerHTML;"
                        + "(html.match(/https?:[^\\\"'<>\\s]+?\\.m3u8(?:\\?[^\\\"'<>\\s]*)?/gi)||[]).forEach(u=>found.push(u));"
                        + "return Array.from(new Set(found.filter(Boolean).map(u=>{try{return new URL(u,location.href).href}catch(e){return ''}}).filter(Boolean)))} )()",
                value -> {
                    mediaTimelineProbeRunning = false;
                    if (current != target) return;
                    try {
                        JSONArray array = new JSONArray(value);
                        for (int index = 0; index < array.length(); index++) {
                            recordResource(target.webView, array.optString(index));
                        }
                    } catch (Exception ignored) { }
                    showResourceList();
                });
    }

    private void showResourceList() {
        if (current == null || current.resources.isEmpty()) {
            Toast.makeText(this, "暂未嗅探到音视频资源，可先播放网页媒体后重试", Toast.LENGTH_LONG).show();
            return;
        }
        List<MediaResourceAggregator.Resource> resources;
        synchronized (current.resources) {
            resources = MediaResourceAggregator.aggregate(current.resources);
        }
        String[] labels = new String[resources.size()];
        for (int i = 0; i < resources.size(); i++) {
            MediaResourceAggregator.Resource resource = resources.get(i);
            String value = resource.isReady() ? resource.url : "尚未捕获 m3u8 播放列表";
            if (value.length() > 82) value = value.substring(0, 82) + "…";
            if (resource.hls) {
                String details = resource.segmentCount > 0 ? " · 已识别视频分片" : "";
                labels[i] = resource.typeLabel + details + "\n" + value;
            } else {
                labels[i] = resource.typeLabel + "\n" + value;
            }
        }
        new MaterialAlertDialogBuilder(this).setTitle("嗅探到的资源（" + resources.size() + "）")
                .setItems(labels, (dialog, index) -> {
                    MediaResourceAggregator.Resource resource = resources.get(index);
                    if (resource.isReady()) {
                        showResourceActions(resource.url);
                    } else {
                        Toast.makeText(this, "已将分片合并显示；请继续播放几秒，捕获 m3u8 清单后即可完整下载并转为 MP4",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("关闭", null).show();
    }

    private void showResourceActions(String url) {
        boolean audio = isAudioResource(url);
        String format = config.getString(audio ? BrowserPreferences.SNIFF_AUDIO_FORMAT : BrowserPreferences.SNIFF_VIDEO_FORMAT,
                audio ? "mp3" : "mp4");
        String[] actions = {"下载并自动转换为 " + format.toUpperCase(java.util.Locale.ROOT), "手动选择 FFmpeg 格式", "复制资源地址"};
        new MaterialAlertDialogBuilder(this).setTitle("资源操作").setItems(actions, (dialog, which) -> {
            if (which == 0) requestMediaDownload(url, format);
            else if (which == 1) startActivity(new Intent(this, MediaTranscodeActivity.class)
                    .putExtra(MediaTranscodeActivity.EXTRA_SOURCE_URL, url)
                    .putExtra(MediaTranscodeActivity.EXTRA_REFERER, current.url));
            else {
                ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("网页资源", url));
                Toast.makeText(this, "资源地址已复制", Toast.LENGTH_SHORT).show();
            }
        }).show();
    }

    private boolean isAudioResource(String url) {
        String clean = url.toLowerCase(java.util.Locale.ROOT).split("\\?")[0];
        return clean.endsWith(".mp3") || clean.endsWith(".m4a") || clean.endsWith(".aac")
                || clean.endsWith(".flac") || clean.endsWith(".ogg") || clean.endsWith(".wav");
    }

    private void requestMediaDownload(String url, String format) {
        if (current == null || current.webView == null) {
            Toast.makeText(this, "当前网页已关闭，请重新嗅探", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = "web_media_" + System.currentTimeMillis() + "." + format;
        String mime = (format.equals("mp4") ? "video/mp4" : format.equals("mkv") ? "video/x-matroska"
                : format.equals("mp3") ? "audio/mpeg" : format.equals("m4a") ? "audio/mp4" : "audio/flac");
        PendingDownload download = new PendingDownload(url, current.webView.getSettings().getUserAgentString(),
                "", "", name, mime, true, format, current.url, "", capturedHeaders(url));
        new MaterialAlertDialogBuilder(this).setTitle("下载并自动转换？")
                .setMessage("输出：" + name + "\n格式：" + format.toUpperCase(java.util.Locale.ROOT)
                        + "\n位置：Download/Toolbox\n\n任务会显示在应用下载管理和通知中。")
                .setNegativeButton("取消", null).setPositiveButton("开始", (dialog, which) -> requestDownload(download)).show();
    }

    private void recordResource(WebView view, String url) {
        if (url == null || url.isEmpty()) return;
        if (!isMediaResourceUrl(url)) return;
        BrowserTab tab = tabFor(view);
        if (tab != null) {
            synchronized (tab.resources) {
                String streamKey = MediaResourceAggregator.streamKey(url);
                if (MediaResourceAggregator.isHlsManifest(url)) {
                    // 同一视频只保存最后捕获的清单，CDN 刷新签名不会无限增加记录。
                    tab.resources.removeIf(existing -> MediaResourceAggregator.isHlsManifest(existing)
                            && streamKey.equals(MediaResourceAggregator.streamKey(existing)));
                } else if (MediaResourceAggregator.isHlsSegment(url)) {
                    // 分片只用于判断哪一组是正在播放的主视频，不需要保存成百上千个 URL。
                    int samples = 0;
                    Iterator<String> iterator = tab.resources.iterator();
                    while (iterator.hasNext()) {
                        String existing = iterator.next();
                        if (MediaResourceAggregator.isHlsSegment(existing)
                                && streamKey.equals(MediaResourceAggregator.streamKey(existing))) {
                            samples++;
                            if (samples >= 6) { iterator.remove(); break; }
                        }
                    }
                }
                tab.resources.add(url);
                while (tab.resources.size() > 240) {
                    Iterator<String> iterator = tab.resources.iterator();
                    boolean removed = false;
                    while (iterator.hasNext()) {
                        String existing = iterator.next();
                        if (!MediaResourceAggregator.isHlsManifest(existing)) {
                            iterator.remove(); removed = true; break;
                        }
                    }
                    if (!removed && !tab.resources.isEmpty()) tab.resources.remove(tab.resources.iterator().next());
                }
            }
        }
    }

    private boolean isMediaResourceUrl(String url) {
        String clean = url.toLowerCase(java.util.Locale.ROOT).split("\\?")[0];
        return clean.endsWith(".m3u8") || clean.endsWith(".mpd") || clean.endsWith(".mp4")
                || clean.endsWith(".mkv") || clean.endsWith(".webm") || clean.endsWith(".flv")
                || clean.endsWith(".mp3") || clean.endsWith(".m4a") || clean.endsWith(".aac")
                || clean.endsWith(".flac") || clean.endsWith(".ogg") || clean.endsWith(".ts")
                || clean.endsWith(".m4s") || clean.endsWith(".m2ts") || clean.endsWith(".cmfv")
                || clean.endsWith(".cmfa");
    }

    private void bindAnimatedClick(View view, Runnable action) {
        view.setOnClickListener(clicked -> { animateTap(clicked); action.run(); });
    }

    private void animateTap(View view) {
        view.animate().cancel();
        view.animate().scaleX(0.84f).scaleY(0.84f).setDuration(70).withEndAction(() ->
                view.animate().scaleX(1f).scaleY(1f).setDuration(190)
                        .setInterpolator(new OvershootInterpolator(1.8f)).start()).start();
    }

    private void showFindInPage() {
        if (current == null) return;
        EditText editor = dialogEditor("");
        editor.setHint("输入网页内文字");
        new MaterialAlertDialogBuilder(this).setTitle("在网页中查找").setView(editor)
                .setNegativeButton("取消", null)
                .setPositiveButton("查找", (d, w) -> current.webView.findAllAsync(editor.getText().toString()))
                .show();
    }

    private void shareCurrentPage() {
        if (current == null) return;
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, safeTitle(current)).putExtra(Intent.EXTRA_TEXT, current.url);
        startActivity(Intent.createChooser(share, "分享网页"));
    }

    private void copyCurrentUrl() {
        if (current == null) return;
        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("网页链接", current.url));
        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
    }

    private void showPageInfo() {
        if (current == null) return;
        new MaterialAlertDialogBuilder(this).setTitle("网页信息")
                .setMessage("标题：" + safeTitle(current) + "\n\n地址：" + current.url
                        + "\n\n连接：" + (current.url.startsWith("https://") ? "HTTPS" : "非 HTTPS"))
                .setPositiveButton("关闭", null).show();
    }

    private void confirmClearBrowserData() {
        new MaterialAlertDialogBuilder(this).setTitle("清理浏览数据？")
                .setMessage("将清除 Cookie、缓存和网页本地存储，不删除历史记录。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理", (d, w) -> {
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                    WebStorage.getInstance().deleteAllData();
                    for (BrowserTab tab : tabs) tab.webView.clearCache(true);
                    Toast.makeText(this, "浏览数据已清理", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showUserAgentSettings() {
        String[] choices = {"跟随软件设置", "移动端", "桌面端", "自定义"};
        new MaterialAlertDialogBuilder(this).setTitle("User-Agent").setItems(choices, (dialog, which) -> {
            if (which == 0) applyUserAgentMode(UA_DEFAULT, null);
            else if (which == 1) applyUserAgentMode(UA_MOBILE, null);
            else if (which == 2) applyUserAgentMode(UA_DESKTOP, null);
            else showCustomUserAgent();
        }).show();
    }

    private void showCustomUserAgent() {
        EditText editor = dialogEditor(browserState.getString(KEY_CUSTOM_UA, ""));
        new MaterialAlertDialogBuilder(this).setTitle("自定义 User-Agent").setView(editor)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", (dialog, which) -> applyUserAgentMode(UA_CUSTOM, editor.getText().toString().trim()))
                .show();
    }

    private EditText dialogEditor(String value) {
        EditText editor = new EditText(this);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setText(value);
        int padding = dp(24);
        editor.setPadding(padding, padding / 2, padding, 0);
        return editor;
    }

    private void applyUserAgentMode(String mode, @Nullable String custom) {
        SharedPreferences.Editor edit = browserState.edit().putString(KEY_UA_MODE, mode);
        if (custom != null) edit.putString(KEY_CUSTOM_UA, custom);
        edit.apply();
        for (BrowserTab tab : tabs) { applySettings(tab.webView); tab.webView.reload(); }
    }

    private String desktopUserAgent() {
        return WebSettings.getDefaultUserAgent(this).replace("; wv", "")
                .replace(" Mobile ", " ").replace("Version/4.0 ", "");
    }

    private void updateNavigation() {
        boolean hasCurrent = current != null;
        back.setEnabled(hasCurrent && current.webView.canGoBack());
        forward.setEnabled(hasCurrent && current.webView.canGoForward());
        tabCount.setText(String.valueOf(tabs.size()));
        tabButton.setContentDescription("标签页，当前 " + tabs.size() + " 个");
    }

    private void openExternal(String url) {
        if (url == null || url.isEmpty()) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException error) { Toast.makeText(this, "没有应用可以打开此链接", Toast.LENGTH_SHORT).show(); }
    }

    /** 下载前展示解析结果，让用户确认文件名、文件类型和固定保存目录。 */
    private void prepareDownload(String url, String userAgent, String disposition, String mimeType, long length) {
        url = resolveDownloadUrl(url);
        if (url != null && url.startsWith(PAGE_DATA_PREFIX)) {
            if (current == null) {
                Toast.makeText(this, "网页已关闭，无法读取内嵌图片", Toast.LENGTH_LONG).show();
            } else {
                extractPageDataImage(current.webView, url.substring(PAGE_DATA_PREFIX.length()), userAgent);
            }
            return;
        }
        if (url != null && url.startsWith("data:")) {
            extractDataImage(url, userAgent);
            return;
        }
        if (url != null && url.startsWith("blob:")) {
            if (current == null) {
                Toast.makeText(this, "网页已关闭，无法读取临时图片", Toast.LENGTH_LONG).show();
            } else {
                extractBlobImage(current.webView, url, userAgent);
            }
            return;
        }
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            Toast.makeText(this, "暂不支持此类型的下载链接", Toast.LENGTH_LONG).show();
            return;
        }
        BrowserDownloadResolver.DownloadInfo info = BrowserDownloadResolver.resolve(url, disposition, mimeType);
        String referer = current == null || current.webView.getUrl() == null ? "" : current.webView.getUrl();
        PendingDownload download = new PendingDownload(url, userAgent, disposition, mimeType,
                info.getFileName(), info.getMimeType(), false, "", referer, "", capturedHeaders(url));
        String size = length > 0 ? readableSize(length) : "未知";
        new MaterialAlertDialogBuilder(this)
                .setTitle("下载文件？")
                .setMessage("文件：" + download.fileName + "\n类型：" + download.mimeType
                        + "\n大小：" + size + "\n位置：Download/Toolbox")
                .setNegativeButton("取消", null)
                .setPositiveButton("下载", (dialog, which) -> requestDownload(download))
                .show();
    }

    private void extractDataImage(String dataUrl, String userAgent) {
        Toast.makeText(this, "正在提取网页图片…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File temporary = null;
            try {
                int comma = dataUrl.indexOf(',');
                if (comma < 0) throw new IllegalArgumentException("无效的 data 图片");
                String metadata = dataUrl.substring(5, comma);
                String mime = metadata.split(";", 2)[0];
                if (mime.isEmpty()) mime = "image/png";
                String payload = dataUrl.substring(comma + 1);
                temporary = File.createTempFile("toolbox-web-image-", ".part", getCacheDir());
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    if (metadata.toLowerCase(java.util.Locale.ROOT).contains(";base64")) {
                        // 不再同时持有 Base64 字符串、ASCII 副本和完整解码字节，避免大图瞬时占用过高。
                        try (InputStream decoded = new Base64InputStream(
                                new StringAsciiInputStream(payload), Base64.DEFAULT)) {
                            byte[] buffer = new byte[64 * 1024];
                            int count;
                            while ((count = decoded.read(buffer)) != -1) output.write(buffer, 0, count);
                        }
                    } else {
                        output.write(URLDecoder.decode(payload, "UTF-8")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                File ready = temporary;
                String finalMime = mime;
                runOnUiThread(() -> queueExtractedImage(ready, finalMime, userAgent, dataUrl));
            } catch (Exception error) {
                if (temporary != null) temporary.delete();
                String message = error.getLocalizedMessage();
                runOnUiThread(() -> Toast.makeText(this,
                        "图片提取失败：" + (message == null ? "数据格式不受支持" : message), Toast.LENGTH_LONG).show());
            }
        }, "web-data-image").start();
    }

    /** 将保存在页面 JavaScript 状态中的大型 data URI 转成 blob，再沿用可靠的分块读取通道。 */
    private void extractPageDataImage(WebView view, String state, String userAgent) {
        Toast.makeText(this, "正在提取网页内嵌图片…", Toast.LENGTH_SHORT).show();
        String script = "(()=>{const k=" + JSONObject.quote(state) + ";const u=window[k];"
                + "const s={ready:false,error:'',mime:'',data:''};window[k]=s;"
                + "if(typeof u!=='string'||!u.startsWith('data:')){s.error='内嵌图片状态已失效';return false;}"
                + "fetch(u).then(r=>r.blob()).then(b=>{s.mime=b.type||'image/png';const f=new FileReader();"
                + "f.onload=()=>{const v=String(f.result||'');s.data=v.slice(v.indexOf(',')+1);s.ready=true};"
                + "f.onerror=()=>{s.error='读取图片失败'};f.readAsDataURL(b)}).catch(e=>{s.error=String(e&&e.message||e)});"
                + "return true})()";
        view.evaluateJavascript(script,
                ignored -> pollBlobImage(view, state, "toolbox-image://embedded", userAgent, 0));
    }

    private void extractBlobImage(WebView view, String blobUrl, String userAgent) {
        Toast.makeText(this, "正在从网页提取临时图片…", Toast.LENGTH_SHORT).show();
        String state = "__toolboxImageExtract" + System.nanoTime();
        String script = "(()=>{const k=" + JSONObject.quote(state) + ";const s={ready:false,error:'',mime:'',data:''};"
                + "window[k]=s;fetch(" + JSONObject.quote(blobUrl) + ").then(r=>{if(!r.ok)throw Error('HTTP '+r.status);return r.blob()})"
                + ".then(b=>{s.mime=b.type||'image/png';const f=new FileReader();f.onload=()=>{const v=String(f.result||'');"
                + "s.data=v.slice(v.indexOf(',')+1);s.ready=true};f.onerror=()=>{s.error='读取图片失败'};f.readAsDataURL(b)})"
                + ".catch(e=>{s.error=String(e&&e.message||e)});return true})()";
        view.evaluateJavascript(script, ignored -> pollBlobImage(view, state, blobUrl, userAgent, 0));
    }

    private void pollBlobImage(WebView view, String state, String sourceUrl, String userAgent, int attempt) {
        if (isFinishing() || isDestroyed()) return;
        String script = "(()=>{const s=window[" + JSONObject.quote(state) + "];return s?"
                + "{ready:s.ready,error:s.error,mime:s.mime,length:s.data.length}:{error:'提取状态已丢失'}})()";
        view.evaluateJavascript(script, value -> {
            try {
                JSONObject status = new JSONObject(value);
                String error = status.optString("error");
                if (!error.isEmpty()) throw new IllegalStateException(error);
                if (status.optBoolean("ready")) {
                    File temporary = File.createTempFile("toolbox-web-image-", ".part", getCacheDir());
                    FileOutputStream output = new FileOutputStream(temporary);
                    readBlobChunk(view, state, sourceUrl, userAgent, status.optString("mime", "image/png"),
                            temporary, output, 0, status.optInt("length"));
                    return;
                }
                if (attempt >= 150) throw new IllegalStateException("网页提取超时");
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> pollBlobImage(view, state, sourceUrl, userAgent, attempt + 1), 200);
            } catch (Exception error) {
                view.evaluateJavascript("delete window[" + JSONObject.quote(state) + "]", null);
                Toast.makeText(this, "图片提取失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void readBlobChunk(WebView view, String state, String sourceUrl, String userAgent, String mime,
                               File temporary, FileOutputStream output, int offset, int total) {
        if (offset >= total) {
            try { output.close(); }
            catch (Exception ignored) { temporary.delete(); return; }
            view.evaluateJavascript("delete window[" + JSONObject.quote(state) + "]", null);
            queueExtractedImage(temporary, mime, userAgent, sourceUrl);
            return;
        }
        int end = Math.min(total, offset + 256 * 1024);
        String script = "window[" + JSONObject.quote(state) + "].data.slice(" + offset + "," + end + ")";
        view.evaluateJavascript(script, value -> {
            try {
                String chunk = new JSONArray("[" + value + "]").optString(0, "");
                if (chunk.isEmpty()) throw new IllegalStateException("网页返回了空图片分块");
                output.write(Base64.decode(chunk, Base64.DEFAULT));
                readBlobChunk(view, state, sourceUrl, userAgent, mime, temporary, output, end, total);
            } catch (Exception error) {
                try { output.close(); } catch (Exception ignored) { }
                temporary.delete();
                view.evaluateJavascript("delete window[" + JSONObject.quote(state) + "]", null);
                Toast.makeText(this, "图片提取失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void queueExtractedImage(File temporary, String mime, String userAgent, String sourceUrl) {
        if (isFinishing() || isDestroyed()) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            return;
        }
        BrowserDownloadResolver.DownloadInfo info = BrowserDownloadResolver.resolve(
                "https://local/image_" + System.currentTimeMillis(), "", mime);
        String referer = current == null || current.webView.getUrl() == null ? "" : current.webView.getUrl();
        // 绝不能把完整 data URI 放进 Service Intent 或下载记录；大图会突破 Binder 事务上限。
        String compactSource = sourceUrl != null && (sourceUrl.startsWith("data:")
                || sourceUrl.startsWith(PAGE_DATA_PREFIX)) ? "toolbox-image://embedded" : sourceUrl;
        PendingDownload download = new PendingDownload(compactSource, userAgent, "", mime,
                info.getFileName(), info.getMimeType(), false, "", referer, temporary.getAbsolutePath());
        requestDownload(download);
    }

    private void discardPendingTemporary(PendingDownload download) {
        if (download == null || download.localSourcePath.isEmpty()) return;
        try {
            File file = new File(download.localSourcePath).getCanonicalFile();
            if (file.getParentFile().equals(getCacheDir().getCanonicalFile())
                    && file.getName().startsWith("toolbox-web-image-")) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Exception ignored) { }
    }

    /** 将协议相对、页面相对的懒加载图片地址解析成下载服务可访问的绝对 URL。 */
    private String resolveDownloadUrl(String value) {
        if (value == null) return null;
        String clean = value.trim();
        if (clean.isEmpty() || clean.startsWith("blob:") || clean.startsWith("data:")) return clean;
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean;
        String base = current == null ? null : current.webView.getUrl();
        if (base == null || base.isEmpty()) return clean;
        try {
            return new URI(base).resolve(clean).toString();
        } catch (Exception ignored) {
            return clean;
        }
    }

    private String capturedHeaders(String url) {
        if (current == null || url == null) return "";
        synchronized (current.requestHeaders) {
            String headers = current.requestHeaders.get(url);
            if (headers != null) return headers;
        }
        synchronized (current.mediaRequestHeaders) {
            String headers = current.mediaRequestHeaders.get(MediaResourceAggregator.streamKey(url));
            return headers == null ? "" : headers;
        }
    }

    private void requestDownload(PendingDownload download) {
        pendingDownload = download;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        requestLegacyStorageOrStart(download);
    }

    private void requestLegacyStorageOrStart(PendingDownload download) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        startDownloadService(download);
    }

    private void startDownloadService(PendingDownload download) {
        pendingDownload = null;
        String cookies = CookieManager.getInstance().getCookie(download.url);
        long downloadId = System.currentTimeMillis();
        Intent service = new Intent(this, BrowserDownloadService.class)
                .setAction(BrowserDownloadService.ACTION_START)
                .putExtra(BrowserDownloadService.EXTRA_DOWNLOAD_ID, downloadId)
                .putExtra(BrowserDownloadService.EXTRA_URL, download.url)
                .putExtra(BrowserDownloadService.EXTRA_USER_AGENT, download.userAgent)
                .putExtra(BrowserDownloadService.EXTRA_COOKIES, cookies)
                .putExtra(BrowserDownloadService.EXTRA_DISPOSITION, download.contentDisposition)
                .putExtra(BrowserDownloadService.EXTRA_MIME, download.originalMimeType)
                .putExtra(BrowserDownloadService.EXTRA_FILE_NAME, download.fileName)
                .putExtra(BrowserDownloadService.EXTRA_RESOLVED_MIME, download.mimeType)
                .putExtra(BrowserDownloadService.EXTRA_AUTO_CONVERT, download.autoConvert)
                .putExtra(BrowserDownloadService.EXTRA_OUTPUT_FORMAT, download.outputFormat)
                .putExtra(BrowserDownloadService.EXTRA_REFERER, download.referer);
        service.putExtra(BrowserDownloadService.EXTRA_LOCAL_SOURCE_PATH, download.localSourcePath);
        service.putExtra(BrowserDownloadService.EXTRA_REQUEST_HEADERS, download.requestHeaders);
        ContextCompat.startForegroundService(this, service);
        Toast.makeText(this, "已开始下载，可在通知中控制", Toast.LENGTH_SHORT).show();
    }

    /** 根据 WebView 命中类型，为图片和普通链接提供统一的下载入口。 */
    private boolean showWebElementActions(WebView view) {
        WebView.HitTestResult hit = view.getHitTestResult();
        if (hit == null) return false;
        int type = hit.getType();
        String direct = hit.getExtra();
        if (type == WebView.HitTestResult.IMAGE_TYPE) {
            resolveTouchedImageUrl(view, direct, resolved -> showElementMenu(view, null, resolved));
            return true;
        }
        if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
            showElementMenu(view, direct, null);
            return true;
        }
        if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            Handler handler = new Handler(Looper.getMainLooper(), message -> {
                Bundle data = message.getData();
                resolveTouchedImageUrl(view, data.getString("src", direct),
                        resolved -> showElementMenu(view, data.getString("url"), resolved));
                return true;
            });
            Message message = handler.obtainMessage();
            view.requestFocusNodeHref(message);
            return true;
        }
        if (type == WebView.HitTestResult.UNKNOWN_TYPE) {
            // CSS background-image 不会被 WebView 标记为 IMAGE_TYPE，仍应提供图片下载菜单。
            resolveTouchedImageUrl(view, null, resolved -> {
                if (resolved != null && !resolved.isEmpty()) showElementMenu(view, null, resolved);
            });
            return true;
        }
        return false;
    }

    /**
     * HitTestResult 经常只返回透明占位图；优先读取长按节点的 currentSrc、data-src、srcset
     * 和 CSS background-image，才能拿到懒加载图片当前真正显示的地址。
     */
    private void resolveTouchedImageUrl(WebView view, String fallback, Consumer<String> callback) {
        // WebView 已经给出可访问的 HTTP(S) 图片时直接使用，避免坐标换算误选到相邻节点。
        if (fallback != null && (fallback.startsWith("http://") || fallback.startsWith("https://"))) {
            callback.accept(fallback);
            return;
        }
        float[] point = lastWebTouchPoints.get(view);
        if (point == null || !view.getSettings().getJavaScriptEnabled()) {
            callback.accept(fallback);
            return;
        }
        String dataState = "__toolboxTouchedData" + System.nanoTime();
        String script = "(()=>{const k=" + JSONObject.quote(dataState)
                + ";let e=document.elementFromPoint(" + point[0] + "," + point[1] + ");"
                + "if(!e)return '';let n=e.closest?e.closest('img,picture,source,[data-src],[data-original],[data-lazy-src]'):e;"
                + "if(!n)n=e;const c=[];const add=v=>{if(v)c.push(String(v).trim())};"
                + "const attrs=x=>{if(!x)return;['data-original','data-src','data-lazy-src','data-url','src'].forEach(a=>add(x.getAttribute&&x.getAttribute(a)));"
                + "add(x.currentSrc);let s=x.getAttribute&&x.getAttribute('srcset');if(s)add(s.split(',').pop().trim().split(/\\s+/)[0]);};"
                + "attrs(n);attrs(n.querySelector&&n.querySelector('img'));attrs(n.querySelector&&n.querySelector('source'));attrs(e);"
                + "for(let x=e;x&&x!==document;x=x.parentElement){let b=getComputedStyle(x).backgroundImage;"
                + "let m=b&&b.match(/url\\([\"']?(.*?)[\"']?\\)/);if(m)add(m[1]);}"
                + "let u=c.find(v=>/^(https?:)?\\/\\//i.test(v));if(!u)u=c.find(v=>v&&!/^data:|^blob:/i.test(v));"
                + "if(!u)u=c.find(v=>/^data:|^blob:/i.test(v));"
                + "try{u=u?new URL(u,location.href).href:''}catch(_){}"
                + "if(/^data:/i.test(u)){window[k]=u;return '" + PAGE_DATA_PREFIX + "'+k}return u||''})()";
        view.evaluateJavascript(script, value -> {
            String resolved = "";
            try { resolved = new JSONArray("[" + value + "]").optString(0, ""); }
            catch (Exception ignored) { }
            callback.accept(resolved.isEmpty() ? fallback : resolved);
        });
    }

    private void showElementMenu(WebView view, String linkUrl, String imageUrl) {
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        String userAgent = view.getSettings().getUserAgentString();
        if (imageUrl != null && !imageUrl.isEmpty()) {
            labels.add("下载图片");
            actions.add(() -> prepareDownload(imageUrl, userAgent, "", "", -1));
            labels.add("复制图片地址");
            actions.add(() -> copyAddress(imageUrl, "图片地址已复制"));
        }
        if (linkUrl != null && !linkUrl.isEmpty()) {
            labels.add("下载链接文件");
            actions.add(() -> prepareDownload(linkUrl, userAgent, "", "", -1));
            labels.add("复制链接地址");
            actions.add(() -> copyAddress(linkUrl, "链接地址已复制"));
        }
        if (labels.isEmpty()) return;
        new MaterialAlertDialogBuilder(this).setTitle("网页元素")
                .setItems(labels.toArray(new String[0]), (dialog, index) -> actions.get(index).run())
                .setNegativeButton("取消", null).show();
    }

    private void copyAddress(String value, String message) {
        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("网页地址", value));
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String readableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unit]);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** 直接从 String 提供 ASCII 字节，避免为了 Base64InputStream 再复制一份完整字符串。 */
    private static final class StringAsciiInputStream extends InputStream {
        private final String value;
        private int position;

        StringAsciiInputStream(String value) { this.value = value; }

        @Override public int read() {
            return position < value.length() ? value.charAt(position++) & 0xff : -1;
        }

        @Override public int read(@NonNull byte[] buffer, int offset, int length) {
            if (position >= value.length()) return -1;
            int count = Math.min(length, value.length() - position);
            for (int index = 0; index < count; index++) {
                buffer[offset + index] = (byte) value.charAt(position + index);
            }
            position += count;
            return count;
        }
    }

    private static final class PendingDownload {
        final String url;
        final String userAgent;
        final String contentDisposition;
        final String originalMimeType;
        final String fileName;
        final String mimeType;
        final boolean autoConvert;
        final String outputFormat;
        final String referer;
        final String localSourcePath;
        final String requestHeaders;

        PendingDownload(String url, String userAgent, String contentDisposition, String originalMimeType,
                        String fileName, String mimeType) {
            this(url, userAgent, contentDisposition, originalMimeType, fileName, mimeType, false, "", "");
        }

        PendingDownload(String url, String userAgent, String contentDisposition, String originalMimeType,
                        String fileName, String mimeType, boolean autoConvert, String outputFormat, String referer) {
            this(url, userAgent, contentDisposition, originalMimeType, fileName, mimeType,
                    autoConvert, outputFormat, referer, "");
        }

        PendingDownload(String url, String userAgent, String contentDisposition, String originalMimeType,
                        String fileName, String mimeType, boolean autoConvert, String outputFormat,
                        String referer, String localSourcePath) {
            this(url, userAgent, contentDisposition, originalMimeType, fileName, mimeType,
                    autoConvert, outputFormat, referer, localSourcePath, "");
        }

        PendingDownload(String url, String userAgent, String contentDisposition, String originalMimeType,
                        String fileName, String mimeType, boolean autoConvert, String outputFormat,
                        String referer, String localSourcePath, String requestHeaders) {
            this.url = url;
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.originalMimeType = originalMimeType;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.autoConvert = autoConvert;
            this.outputFormat = outputFormat;
            this.referer = referer;
            this.localSourcePath = localSourcePath;
            this.requestHeaders = requestHeaders;
        }
    }

    private static final class BrowserMenuAction {
        final String label;
        final int icon;
        final Runnable command;
        BrowserMenuAction(String label, int icon, Runnable command) {
            this.label = label; this.icon = icon; this.command = command;
        }
    }

    private class BrowserTab {
        final WebView webView;
        String title = "新标签页";
        String url = "";
        final LinkedHashSet<String> resources = new LinkedHashSet<>();
        final LinkedHashMap<String, String> requestHeaders = new LinkedHashMap<>();
        final LinkedHashMap<String, String> mediaRequestHeaders = new LinkedHashMap<>();
        BrowserTab(WebView webView) { this.webView = webView; }
    }

    private final class BrowserClient extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            recordResource(view, url);
            BrowserTab tab = tabFor(view);
            if (tab != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                String serialized = new JSONObject(request.getRequestHeaders()).toString();
                synchronized (tab.requestHeaders) {
                    tab.requestHeaders.put(url, serialized);
                    while (tab.requestHeaders.size() > 200) {
                        tab.requestHeaders.remove(tab.requestHeaders.keySet().iterator().next());
                    }
                }
                if (isMediaResourceUrl(url)) {
                    synchronized (tab.mediaRequestHeaders) {
                        tab.mediaRequestHeaders.put(MediaResourceAggregator.streamKey(url), serialized);
                        while (tab.mediaRequestHeaders.size() > 32) {
                            tab.mediaRequestHeaders.remove(tab.mediaRequestHeaders.keySet().iterator().next());
                        }
                    }
                }
            }
            return super.shouldInterceptRequest(view, request);
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")) return false;
            openExternal(url); return true;
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")) return false;
            openExternal(url); return true;
        }
        @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
            BrowserTab tab = tabFor(view);
            if (tab != null) {
                tab.url = url;
                synchronized (tab.resources) { tab.resources.clear(); }
                synchronized (tab.requestHeaders) { tab.requestHeaders.clear(); }
                synchronized (tab.mediaRequestHeaders) { tab.mediaRequestHeaders.clear(); }
            }
            if (tab == current) {
                address.setText(url); progress.setVisibility(View.VISIBLE);
                if (icon != null) favicon.setImageBitmap(icon);
                updateNavigation();
            }
        }
        @Override public void onPageFinished(WebView view, String url) {
            BrowserTab tab = tabFor(view);
            if (tab != null) { tab.url = url; tab.title = view.getTitle(); }
            historyStore.add(view.getTitle(), url);
            if (tab == current) updateNavigation();
        }
    }

    private final class BrowserChromeClient extends WebChromeClient {
        @Override public void onProgressChanged(WebView view, int value) {
            if (current != null && current.webView == view) {
                progress.setProgressCompat(value, true);
                progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
            }
        }
        @Override public void onReceivedTitle(WebView view, String title) {
            BrowserTab tab = tabFor(view); if (tab != null) tab.title = title;
        }
        @Override public void onReceivedIcon(WebView view, Bitmap icon) {
            if (current != null && current.webView == view && icon != null) favicon.setImageBitmap(icon);
        }
    }

    private BrowserTab tabFor(WebView view) {
        for (BrowserTab tab : tabs) if (tab.webView == view) return tab;
        return null;
    }

    @Override protected void onResume() {
        super.onResume();
        browserResumed = true;
        for (BrowserTab tab : tabs) {
            applySettings(tab.webView);
            if (tab != current) tab.webView.onPause();
        }
        if (current != null) current.webView.onResume();
    }
    @Override protected void onPause() {
        browserResumed = false;
        for (BrowserTab tab : tabs) tab.webView.onPause();
        super.onPause();
    }
    @Override protected void onSaveInstanceState(@NonNull Bundle outState) {
        ArrayList<Bundle> states = new ArrayList<>();
        for (BrowserTab tab : tabs) {
            Bundle state = new Bundle();
            tab.webView.saveState(state);
            state.putString("toolbox_url", tab.url == null || tab.url.isEmpty() ? homeUrl() : tab.url);
            state.putString("toolbox_title", tab.title);
            states.add(state);
        }
        outState.putParcelableArrayList("tab_states", states);
        outState.putInt("current_tab", Math.max(0, tabs.indexOf(current)));
        super.onSaveInstanceState(outState);
    }
    @Override protected void onDestroy() {
        for (BrowserTab tab : new ArrayList<>(tabs)) { tab.webView.stopLoading(); tab.webView.destroy(); }
        tabs.clear();
        super.onDestroy();
    }
}
