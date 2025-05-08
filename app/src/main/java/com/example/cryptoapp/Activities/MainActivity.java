package com.example.cryptoapp.Activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.MenuItem;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.example.cryptoapp.Base.BaseActivity;
import com.example.cryptoapp.R;
import com.google.android.material.navigation.NavigationView;
import com.gyf.immersionbar.ImmersionBar;

public class MainActivity extends BaseActivity {

    private DrawerLayout mDrawerLayout;
    private NavigationView navView;
    private Toolbar toolbar;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化控件
        mDrawerLayout = findViewById(R.id.drawerLayout);
        toolbar = findViewById(R.id.toolbar);
        navView = findViewById(R.id.navView);

        // 沉浸式状态栏
        ImmersionBar.with(this)
                .titleBar(toolbar)
                .init();

        // 设置 Toolbar
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeAsUpIndicator(R.drawable.ic_menu);
        }

        // 绑定 ActionBarDrawerToggle
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this,
                mDrawerLayout,
                toolbar,
                0,
                0
        );

        mDrawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        // 侧滑栏选项点击事件
        navView.setCheckedItem(R.id.home);
        navView.setNavigationItemSelectedListener(item -> {
            switch (item.getItemId()) {
                case R.id.navTextLayout:
                    Intent intent_text = new Intent(MainActivity.this, TextActivity.class);
                    new Handler().postDelayed(() -> startActivity(intent_text), 200);
                    mDrawerLayout.closeDrawer(GravityCompat.START);
                    break;
                case R.id.navFileLayout:
                    Intent intent_file = new Intent(MainActivity.this, FileActivity.class);
                    new Handler().postDelayed(() -> startActivity(intent_file), 200);
                    mDrawerLayout.closeDrawer(GravityCompat.START);
                    break;
                case R.id.navPicLayout:
                    Intent intent_pic = new Intent(MainActivity.this, PicActivity.class);
                    new Handler().postDelayed(() -> startActivity(intent_pic), 200);
                    mDrawerLayout.closeDrawer(GravityCompat.START);
                    break;
                default:
                    mDrawerLayout.closeDrawer(GravityCompat.START);
                    break;
            }
            return true;
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                mDrawerLayout.closeDrawer(GravityCompat.START);
            } else {
                mDrawerLayout.openDrawer(GravityCompat.START);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onStart() {
        super.onStart();
        navView.setCheckedItem(R.id.home);
    }
}
