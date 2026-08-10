package com.example.cryptoapp.Activities;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.example.cryptoapp.Base.BaseActivity;
import com.example.cryptoapp.Browser.BrowserPreferences;
import com.example.cryptoapp.R;
import com.example.cryptoapp.Utils.SystemFileManagerLauncher;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.activity.CaptureActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends BaseActivity {
    private final OkHttpClient httpClient = new OkHttpClient();
    private DrawerLayout drawer;
    private ImageView bingImage;
    private SharedPreferences config;
    private Call wallpaperMetadataCall;
    private Call wallpaperImageCall;
    private volatile boolean wallpaperLoading;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        config = getSharedPreferences("config", MODE_PRIVATE);
        initializeDefaults();

        drawer = findViewById(R.id.drawerLayout);
        bingImage = findViewById(R.id.image_bing);
        Toolbar toolbar = findViewById(R.id.toolbar);
        NavigationView navigation = findViewById(R.id.navView);
        TextView searchText = findViewById(R.id.search_text_tv);
        ImageView searchButton = findViewById(R.id.search_button);

        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeAsUpIndicator(R.drawable.ic_menu);
        }

        searchText.setOnClickListener(v -> openSearch(searchText));
        searchButton.setOnClickListener(v -> openSearch(searchText));
        // 首帧绘制完成后再显示提示，避免启动阶段同时测量抽屉、卡片和 Snackbar。
        searchText.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                Snackbar.make(searchText, "当前搜索引擎：" + config.getString("method_name", "必应"),
                        Snackbar.LENGTH_SHORT).show();
            }
        }, 350L);

        navigation.setCheckedItem(R.id.home);
        navigation.setNavigationItemSelectedListener(item -> {
            handleNavigation(item.getItemId());
            drawer.closeDrawer(GravityCompat.START);
            return true;
        });
    }

    @Override protected void onStart() {
        super.onStart();
        if (config.getBoolean("bing_pic_check", true)) {
            // 从子页面返回时 ImageView 仍持有壁纸，不重复请求和解码同一张大图。
            if (bingImage.getDrawable() == null && !wallpaperLoading) loadBingWallpaper();
        } else {
            cancelWallpaperLoad();
            bingImage.setImageDrawable(null);
        }
    }

    private void openSearch(TextView source) {
        int[] location = new int[2];
        source.getLocationOnScreen(location);
        startActivity(new Intent(this, EnterSearchStringFragment.class)
                .putExtra("x", location[0]).putExtra("y", location[1]));
        overridePendingTransition(0, 0);
    }

    private void handleNavigation(int id) {
        if (id == R.id.info) startActivity(new Intent(this, AboutActivity.class));
        else if (id == R.id.settings) startActivity(new Intent(this, SettingsActivity.class));
        else if (id == R.id.navTextLayout) startActivity(new Intent(this, TextActivity.class));
        else if (id == R.id.navFileLayout) startActivity(new Intent(this, FileActivity.class));
        else if (id == R.id.navPicLayout) startActivity(new Intent(this, PicActivity.class));
        else if (id == R.id.fake_terminal) startActivity(new Intent(this, FakeTerminalActivity.class));
        else if (id == R.id.data_tools) startActivity(new Intent(this, DataToolsActivity.class));
        else if (id == R.id.unit_converter) startActivity(new Intent(this, UnitConverterActivity.class));
        else if (id == R.id.media_converter) startActivity(new Intent(this, MediaTranscodeActivity.class));
        else if (id == R.id.api_debug) startActivity(new Intent(this, ApiDebugActivity.class));
        else if (id == R.id.network_utils) startActivity(new Intent(this, NetworkUtilitiesActivity.class));
        else if (id == R.id.open_sys_file_mgr) SystemFileManagerLauncher.open(this);
        else if (id == R.id.qr_scan) openScannerWithPermission();
    }

    private void openScannerWithPermission() {
        // 扫码页在真正创建相机预览前申请权限；即使拒绝也能使用图库识别。
        startActivity(new Intent(this, CaptureActivity.class));
    }

    private void initializeDefaults() {
        if (!config.getBoolean("isFirstIn", true)) return;
        config.edit()
                .putBoolean("bing_pic_check", true)
                .putString("home_url", "https://cn.bing.com")
                .putString("search_method", "https://cn.bing.com/search?q=")
                .putString("method_name", "必应")
                .putInt("method_num", 0)
                .putBoolean(BrowserPreferences.SAVE_HISTORY, true)
                .putBoolean(BrowserPreferences.SHOW_SEARCH_HISTORY, true)
                .putBoolean(BrowserPreferences.JAVASCRIPT_ENABLED, true)
                .putBoolean(BrowserPreferences.BLOCK_THIRD_PARTY_COOKIES, true)
                .putBoolean(BrowserPreferences.DESKTOP_MODE_DEFAULT, false)
                .putBoolean("isFirstIn", false)
                .apply();
    }

    private void loadBingWallpaper() {
        wallpaperLoading = true;
        Request request = new Request.Builder()
                .url("https://cn.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1")
                .build();
        wallpaperMetadataCall = httpClient.newCall(request);
        wallpaperMetadataCall.enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                wallpaperLoading = false;
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        wallpaperLoading = false;
                        return;
                    }
                    JSONObject root = new JSONObject(response.body().string());
                    JSONArray images = root.getJSONArray("images");
                    if (images.length() == 0) {
                        wallpaperLoading = false;
                        return;
                    }
                    String path = images.getJSONObject(0).getString("url");
                    if (isScreenPortrait(MainActivity.this)) path = path.replace("1920x1080", "1080x1920");
                    String url = "https://cn.bing.com" + path;
                    loadWallpaperBitmap(url);
                } catch (Exception ignored) {
                    wallpaperLoading = false;
                }
            }
        });
    }

    /** 复用 OkHttp 加载壁纸，减少仅为一张图片引入完整图片框架的依赖。 */
    private void loadWallpaperBitmap(String url) {
        wallpaperImageCall = httpClient.newCall(new Request.Builder().url(url).build());
        wallpaperImageCall.enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException error) {
                wallpaperLoading = false;
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        wallpaperLoading = false;
                        return;
                    }
                    Bitmap bitmap = BitmapFactory.decodeStream(response.body().byteStream());
                    if (bitmap != null) {
                        runOnUiThread(() -> {
                            wallpaperLoading = false;
                            if (!isFinishing() && !isDestroyed()) {
                                bingImage.setImageBitmap(bitmap);
                            } else {
                                bitmap.recycle();
                            }
                        });
                    } else {
                        wallpaperLoading = false;
                    }
                } catch (Exception ignored) {
                    wallpaperLoading = false;
                }
            }
        });
    }

    private void cancelWallpaperLoad() {
        if (wallpaperMetadataCall != null) wallpaperMetadataCall.cancel();
        if (wallpaperImageCall != null) wallpaperImageCall.cancel();
        wallpaperMetadataCall = null;
        wallpaperImageCall = null;
        wallpaperLoading = false;
    }

    private static boolean isScreenPortrait(Context context) {
        return context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    @Override protected void onDestroy() {
        cancelWallpaperLoad();
        super.onDestroy();
    }

    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            drawer.openDrawer(GravityCompat.START);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
