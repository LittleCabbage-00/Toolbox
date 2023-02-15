package com.example.cryptoapp.Activities;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;


import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;

import com.bumptech.glide.Glide;
import com.example.cryptoapp.Base.BaseActivity;
import com.example.cryptoapp.R;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.activity.CaptureActivity;
import com.gyf.immersionbar.ImmersionBar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends BaseActivity {

    ImageView image_bing;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //读取自定义配置
        SharedPreferences config_get=getSharedPreferences("config",MODE_PRIVATE);
        //判断是否被打开过，打开过，读取值，否则执行初始化
        if (config_get.getBoolean("isFirstIn",true)) {
            //实施初始化，设定默认值
            initView();
        }

        //初始化控件
        DrawerLayout mDrawerLayout=(DrawerLayout) findViewById(R.id.drawerLayout);
        Toolbar toolbar=(Toolbar) findViewById(R.id.toolbar);
        NavigationView navView=(NavigationView) findViewById(R.id.navView);
        TextView search_text_tv=(TextView) findViewById(R.id.search_text_tv);
        ImageView search_button=(ImageView) findViewById(R.id.search_button);

        Snackbar.make(search_text_tv,"当前选择的搜索引擎为 "+config_get.getString("method_name",""),Snackbar.LENGTH_SHORT).show();

        search_text_tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                Intent intent_search_str = new Intent(MainActivity.this, EnterSearchStringFragment.class);
                int[] location = new int[2];
                search_text_tv.getLocationOnScreen(location);
                intent_search_str.putExtra("x",location[0]);
                intent_search_str.putExtra("y",location[1]);
                startActivity(intent_search_str);
                overridePendingTransition(0,0);
            }
        }
        );
        search_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent_search_str = new Intent(MainActivity.this, EnterSearchStringFragment.class);
                int[] location = new int[2];
                search_text_tv.getLocationOnScreen(location);
                intent_search_str.putExtra("x",location[0]);
                intent_search_str.putExtra("y",location[1]);
                startActivity(intent_search_str);
                overridePendingTransition(0,0);
            }
        });

        ImmersionBar.with(this)
                .titleBar(toolbar)
                .init();

        //安卓13兼容性警告
        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU) {
            AlertDialog.Builder dialog = new AlertDialog.Builder(MainActivity.this);
            dialog.setTitle("警告");
            dialog.setMessage("安卓13以上版本的系统存在功能缺失，作者正在使劲想办法修");
            dialog.setCancelable(true);
            dialog.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                }
            });
            dialog.show();
        }

        //顶部设置为toolbar界面
        setSupportActionBar(toolbar);
        ActionBar actionBar=getSupportActionBar();
        //侧滑栏相关设置
        if (actionBar!=null){
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeAsUpIndicator(R.drawable.ic_menu);
        }

        //侧滑栏选项点击事件
        navView.setCheckedItem(R.id.home);
        navView.setNavigationItemSelectedListener(new NavigationView.OnNavigationItemSelectedListener() {
            @SuppressLint("NonConstantResourceId")
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                switch (item.getItemId()){
                    //关于->弹出关于app的窗口
                    case R.id.info:
                        Intent intent_info=new Intent(MainActivity.this,AboutActivity.class);
//                        startActivity(intent_info);

                        new Handler().postDelayed(()->startActivity(intent_info),200);
                        mDrawerLayout.close();
                        break;
                    //进入软件设置
                    case R.id.settings:
                        Intent intent_to_settings=new Intent(MainActivity.this,SettingsActivity.class);
                        new Handler().postDelayed(()->startActivity(intent_to_settings),200);
                        mDrawerLayout.close();
                        break;
                    //文本加密->进入相关activity
                    case R.id.navTextLayout:
                        Intent intent_text=new Intent(MainActivity.this,TextActivity.class);
//                        startActivity(intent_text);

                        new Handler().postDelayed(()->startActivity(intent_text),200);
                        mDrawerLayout.close();
                        break;
                    //文件加密->进入相关activity
                    case R.id.navFileLayout:
                        Intent intent_file=new Intent(MainActivity.this,FileActivity.class);
//                        startActivity(intent_file);

                        new Handler().postDelayed(()->startActivity(intent_file),200);
                        mDrawerLayout.close();
                        break;
                    //图片解密预览->进入相关activity
                    case R.id.navPicLayout:
                        Intent intent_pic=new Intent(MainActivity.this,PicActivity.class);
                        new Handler().postDelayed(()->startActivity(intent_pic),200);
                        mDrawerLayout.close();
                        break;
                    //进入系统自带文件管理器
                    case R.id.open_sys_file_mgr:
                        try{
                            if (Build.VERSION.SDK_INT<=Build.VERSION_CODES.S_V2){
                                String package_name = "com.android.documentsui";
                                PackageManager packageManager = getPackageManager();
                                Intent it = packageManager.getLaunchIntentForPackage(package_name);
                                new Handler().postDelayed(() -> startActivity(it),200);
                            } else if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU) {
                                AlertDialog.Builder dialog = new AlertDialog.Builder(MainActivity.this);
                                dialog.setTitle("警告");
                                dialog.setMessage("安卓13以上版本的SAF框架依然无法访问Android/data目录，暂时未找到有效解决办法");
                                dialog.setCancelable(true);
                                dialog.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {


//                                String package_name = "com.android.documentsui";
//                                PackageManager packageManager = getPackageManager();
//                                Intent it = packageManager.getLaunchIntentForPackage(package_name);

                                        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                                        it.setType("*/*");
                                        it.addCategory(Intent.CATEGORY_OPENABLE);

                                        new Handler().postDelayed(() -> startActivity(it),200);
                                        mDrawerLayout.close();
                                    }
                                });
                                dialog.show();
                            }
                        }catch (Exception e) {
                            AlertDialog.Builder dialog = new AlertDialog.Builder(MainActivity.this);
                            dialog.setTitle("警告");
                            dialog.setMessage("检测到您没有相关的软件");
                            dialog.setCancelable(true);
                            dialog.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                }
                            });
                            dialog.show();
                            mDrawerLayout.close();
                        }
                        break;
                    //进入简易终端界面
                    case R.id.fake_terminal:
                        Intent intent_fake_terminal=new Intent(MainActivity.this,FakeTerminalActivity.class);
//                        startActivity(intent_fake_terminal);
                        new Handler().postDelayed(()->startActivity(intent_fake_terminal),200);
                        mDrawerLayout.close();
                        break;
                    //开始二维码扫描
                    case R.id.qr_scan:
                        mDrawerLayout.close();
                        Intent intent_qr_scan=new Intent(MainActivity.this, CaptureActivity.class);
//                        startActivity(intent_qr_scan);
                        new Handler().postDelayed(()->startActivity(intent_qr_scan),300);
                        break;
                    //主界面->什么也不做
                    default:
                        mDrawerLayout.close();
                        break;
                }
                return true;
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        NavigationView navView=(NavigationView) findViewById(R.id.navView);
        navView.setCheckedItem(R.id.home);

        //读取自定义配置
        SharedPreferences config_get=getSharedPreferences("config",MODE_PRIVATE);

        //读取bing每日一图选项，如果为true就加载，否则不做动作
        boolean bing_pic_check=config_get.getBoolean("bing_pic_check",true);
        if (bing_pic_check) {
            image_bing = (ImageView) findViewById(R.id.image_bing);
            sendRequestWithHttpURLConnection();
        }else{
            image_bing = (ImageView) findViewById(R.id.image_bing);
            image_bing.setImageDrawable(null);
        }
    }
    //toolbar上的菜单逻辑
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        //使用menu.layout作为界面
        getMenuInflater().inflate(R.menu.crypt_func_toolbar,menu);
        return true;
    }

    //请求bing的api
    private void sendRequestWithHttpURLConnection() {
        // 开启线程来发起网络请求
        new Thread(new Runnable() {
            @Override
            public void run() {
        //优化图片的请求方式
                try {
                    OkHttpClient client = new OkHttpClient();
                    Request request = new Request.Builder()
                            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/68.0.3440.106 Safari/537.36")
                            .url("https://cn.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1")
                            .build();
                    Response response = null;
                    response = client.newCall(request).execute();
                    assert response.body() != null;
                    parseJSONWithJSONObject(response.body().string());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    //解析bing的api返回值
    private void parseJSONWithJSONObject(String jsonData) {
        try {
            // JSONArray jsonArray = new JSONArray(jsonData);
            Log.d("MainActivity get",jsonData.toString());

            JSONArray jsonArray = new JSONObject(jsonData).getJSONArray("images");

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                String url = jsonObject.getString("url");

                Log.d("MainActivity", "url is " + url);
                String url1="https://cn.bing.com"+url;
                showResponse(url1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    //使用开源框架glide进行图片加载
    private void showResponse(final String response) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // 在这里进行UI操作，将结果显示到界面上
                Glide.with(MainActivity.this).load(response).into(image_bing);
                //  text.setText(response);
                Log.i("123",response);
            }
        });
    }

    //初始化设置
    private void initView(){
        //写入初始化配置
        SharedPreferences.Editor init_get=getSharedPreferences("config",Context.MODE_PRIVATE).edit();
        //读取自定义配置
        SharedPreferences config_get=getSharedPreferences("config",MODE_PRIVATE);
        //初始化bing壁纸开关，默认打开
        init_get.putBoolean("bing_pic_check",true);

        //初始化搜索引擎
        init_get.putString("home_url", "https://cn.bing.com");
        init_get.putString("search_method", "https://cn.bing.com/search?q=");
        init_get.putString("method_name", "必应");

        //初始化搜索引擎次序
        init_get.putInt("method_num",0);

        //初始化配置之后，做第一次打开标记
        init_get.putBoolean("isFirstIn", false);

        init_get.apply();
    }
}