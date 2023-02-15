package com.example.cryptoapp.Activities;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.example.cryptoapp.Base.BaseActivity;
import com.example.cryptoapp.R;
import com.gyf.immersionbar.ImmersionBar;

import java.util.Timer;
import java.util.TimerTask;

public class EnterSearchStringFragment extends BaseActivity {
    private LinearLayout et_bg;
    private EditText et_content;
    private FrameLayout fl;
    private boolean finishing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_enter_search_string);
        et_bg=(LinearLayout) findViewById(R.id.input_layout);
        et_content=(EditText) findViewById(R.id.search_text_tv);
        fl=(FrameLayout)findViewById(R.id.fl);

        ImmersionBar.with(this)
                .transparentStatusBar()
                .fitsSystemWindows(true)
                .statusBarDarkFont(true)
                .init();

        //监听布局是否发生变化
        et_bg.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                et_bg.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                inAnimation();

                et_content.requestFocus();
                Timer timer = new Timer();
                timer.schedule(new TimerTask() {
                    public void run() {
                        InputMethodManager inputManager = (InputMethodManager) et_content.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        inputManager.showSoftInput(et_content, 0);
                    }
                }, 1500);
            }
        });

    }

    private void inAnimation() {
        float originY=getIntent().getIntExtra("y",0);
        //获取到搜索框在这个Activity界面的位置
        int[] location=new int[2];
        et_bg.getLocationOnScreen(location);
        //计算位置的差值
        final float translateY=originY-(float)location[1];
        //将第一个界面的位置设置给搜索框
        et_bg.setY(et_bg.getY()+translateY);
        //同步设置搜索框中的文字
        et_content.setY(et_bg.getY()+(et_bg.getHeight()-et_content.getHeight())/2);
        float top = getResources().getDisplayMetrics().density * 20;
        //ValueAnimator是一个很厉害的东西，你只需要给他初始值和结束值，他会自动计算中间的过度
        final ValueAnimator translateVa = ValueAnimator.ofFloat(et_bg.getY(), top);
        //这个是由下移动到上面的监听
        translateVa.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                et_bg.setY((Float) valueAnimator.getAnimatedValue());
//                et_content.setY(et_bg.getY() + (et_bg.getHeight() - et_content.getHeight()) / 2);
//                tv_search.setY(et_bg.getY() + (et_bg.getHeight() - tv_search.getHeight()) / 2);
                et_content.setY(et_bg.getY() /2);
            }
        });
        //这个是缩小搜索框的监听
        ValueAnimator scaleVa = ValueAnimator.ofFloat(1, 1);
        scaleVa.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                et_bg.setScaleX((Float) valueAnimator.getAnimatedValue());
            }
        });
        //这个是设置透明度
        ValueAnimator alphaVa = ValueAnimator.ofFloat(0, 1f);
        alphaVa.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                fl.setAlpha((Float) valueAnimator.getAnimatedValue());
            }
        });

        alphaVa.setDuration(500);
        translateVa.setDuration(500);
        scaleVa.setDuration(500);

        alphaVa.start();
        translateVa.start();
        scaleVa.start();
    }

    private void outAnimation() {
        float originY=getIntent().getIntExtra("y",0);

        int[] location=new int[2];
        et_bg.getLocationOnScreen(location);

        final float translateY=originY-(float)location[1];
        et_bg.setY(et_bg.getY()+translateY);
        et_content.setY(et_bg.getY()+(et_bg.getHeight()-et_content.getHeight())/2);
        float top = getResources().getDisplayMetrics().density * 20;
        final ValueAnimator translateVa = ValueAnimator.ofFloat(top, et_bg.getY());

        translateVa.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                et_bg.setY((Float) valueAnimator.getAnimatedValue());
                et_content.setY(et_bg.getY() + (et_bg.getHeight() - et_content.getHeight()) / 2);
            }
        });

        translateVa.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animator) {
            }

            @Override
            public void onAnimationEnd(Animator animator) {
                finish();
                overridePendingTransition(0, 0);
            }

            @Override
            public void onAnimationCancel(Animator animator) {

            }

            @Override
            public void onAnimationRepeat(Animator animator) {

            }
        });

        ValueAnimator scaleVa = ValueAnimator.ofFloat(0.8f, 1);
        scaleVa.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                et_bg.setScaleX((Float) valueAnimator.getAnimatedValue());
            }
        });

        ValueAnimator alphaVa = ValueAnimator.ofFloat(1f, 0);
        alphaVa.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                fl.setAlpha((Float) valueAnimator.getAnimatedValue());
            }
        });

        alphaVa.setDuration(500);
        translateVa.setDuration(500);
        scaleVa.setDuration(500);

        alphaVa.start();
        translateVa.start();
        scaleVa.start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        et_content.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if(actionId== EditorInfo.IME_ACTION_SEARCH) {
                    Intent intent_to_search=new Intent(EnterSearchStringFragment.this,SearchActivity.class);
                    intent_to_search.putExtra("web_address",et_content.getText().toString());
                    startActivity(intent_to_search);
                    return true;
                }
                if(event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&v.getText()!=null&& event.getAction() == KeyEvent.ACTION_DOWN){
                    Intent intent_to_search=new Intent(EnterSearchStringFragment.this,SearchActivity.class);
                    intent_to_search.putExtra("web_address",et_content.getText().toString());
                    startActivity(intent_to_search);
                }
                return true;
            }
        });
    }

    @Override
    public void onBackPressed() {
        if(!finishing){
            finishing=true;
            outAnimation();
        }
    }
}