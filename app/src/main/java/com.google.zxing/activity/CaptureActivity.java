package com.google.zxing.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;

import android.os.Vibrator;
import android.provider.Settings;


import android.text.TextUtils;


import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.View;

import android.widget.ImageView;
import android.widget.Toast;


import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.cryptoapp.Base.BaseActivity;
import com.example.cryptoapp.R;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.camera.CameraManager;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.decoding.CaptureActivityHandler;
import com.google.zxing.decoding.InactivityTimer;
import com.google.zxing.decoding.RGBLuminanceSource;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.view.ViewfinderView;

import java.io.IOException;

import java.util.Hashtable;
import java.util.List;
import java.util.Vector;




/**
 * Initial the camera
 */
public class CaptureActivity extends BaseActivity implements Callback {

    private static final int REQUEST_CODE_SCAN_GALLERY = 100;
    public static final String EXTRA_RETURN_RESULT = "return_scan_result";
    public static final String EXTRA_SCAN_RESULT = "scan_result";

    private CaptureActivityHandler handler;
    private ViewfinderView viewfinderView;
    private boolean hasSurface;
    private Vector<BarcodeFormat> decodeFormats;
    private String characterSet;
    private InactivityTimer inactivityTimer;
    private MediaPlayer mediaPlayer;
    private boolean playBeep;
    private static final float BEEP_VOLUME = 0.10f;
    private boolean vibrate;
    private boolean isOpen;
    private android.hardware.Camera.Parameters parameter;
    private Bitmap scanBitmap;
    private android.hardware.Camera camera;
    private FloatingActionButton flash;
    private boolean resultDialogShowing;
    private boolean cameraPermissionGranted;
    private final ActivityResultLauncher<String> cameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                cameraPermissionGranted = granted;
                if (granted && !isFinishing()) setupCameraSurface();
                else if (!granted) showCameraPermissionHelp();
            });

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scanner);

        Toolbar toolbar=(Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar=getSupportActionBar();
        if (actionBar!=null){
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        CameraManager.init(getApplication());
        viewfinderView = (ViewfinderView) findViewById(R.id.viewfinder_content);
        cameraPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        if (!cameraPermissionGranted) requestCameraPermission();

        //闪光灯按钮
        flash=(FloatingActionButton)findViewById(R.id.flash);
        flash.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lightOn();
            }
        });
        findViewById(R.id.gallery).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_CODE_SCAN_GALLERY);
        });
        hasSurface = false;
        inactivityTimer = new InactivityTimer(this);
    }


    /**
     *闪光灯控制
     */
    private void lightOn(){
        if (!cameraPermissionGranted) {
            requestCameraPermission();
            return;
        }
        camera = CameraManager.getCamera();
        if (camera == null) {
            Toast.makeText(this, "相机尚未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        parameter = camera.getParameters();
        if (!isOpen) {
            parameter.setFlashMode(android.hardware.Camera.Parameters.FLASH_MODE_TORCH);
            camera.setParameters(parameter);
            isOpen = true;
        } else {  // 关灯
            parameter.setFlashMode(android.hardware.Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(parameter);
            isOpen = false;
        }
    }

    /**
     * 扫描二维码图片的方法
     * 压缩后进行扫描
     */
    public Result scanningImage(String path) {
        if(TextUtils.isEmpty(path)){
            return null;
        }

        Hashtable<DecodeHintType, String> hints = new Hashtable<>();
        hints.put(DecodeHintType.CHARACTER_SET, "UTF8"); //设置二维码内容的编码

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true; // 先获取原大小
        scanBitmap = BitmapFactory.decodeFile(path, options);
        options.inJustDecodeBounds = false; // 获取新的大小
        int sampleSize = (int) (options.outHeight / (float) 200);
        if (sampleSize <= 0)
            sampleSize = 1;
        options.inSampleSize = sampleSize;
        scanBitmap = BitmapFactory.decodeFile(path, options);
        RGBLuminanceSource source = new RGBLuminanceSource(scanBitmap);
        BinaryBitmap bitmap1 = new BinaryBitmap(new HybridBinarizer(source));
        QRCodeReader reader = new QRCodeReader();
        try {
            return reader.decode(bitmap1, hints);
        } catch (NotFoundException | ChecksumException | FormatException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new MaterialAlertDialogBuilder(this).setTitle("需要相机权限")
                    .setMessage("相机权限仅用于实时扫描二维码和条形码；不授权也可以使用图库识别。")
                    .setNegativeButton("使用图库", null)
                    .setPositiveButton("继续", (dialog, which) ->
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA))
                    .show();
        } else cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
    }

    private void showCameraPermissionHelp() {
        new MaterialAlertDialogBuilder(this).setTitle("相机权限未开启")
                .setMessage("你仍可使用图库识别；如需实时扫描，可前往系统设置开启相机权限。")
                .setNegativeButton("继续使用图库", null)
                .setPositiveButton("去设置", (dialog, which) -> startActivity(
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null))))
                .show();
    }

    private Result scanningImage(Uri uri) {
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(uri));
            if (bitmap == null) return null;
            int maxSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (maxSide > 1800) {
                float scale = 1800f / maxSide;
                bitmap = Bitmap.createScaledBitmap(bitmap,
                        Math.round(bitmap.getWidth() * scale),
                        Math.round(bitmap.getHeight() * scale), true);
            }
            RGBLuminanceSource source = new RGBLuminanceSource(bitmap);
            BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));
            Hashtable<DecodeHintType, Object> hints = new Hashtable<>();
            hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            return new MultiFormatReader().decode(binaryBitmap, hints);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SCAN_GALLERY && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            Result result = scanningImage(data.getData());
            if (result == null) {
                Toast.makeText(this, "没有识别到条码或二维码", Toast.LENGTH_SHORT).show();
                restartScanning();
            } else {
                handleDecode(result, null);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        setTitle("二维码扫描");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!cameraPermissionGranted) return;
        setupCameraSurface();

        decodeFormats = null;
        characterSet = null;

        playBeep = true;
        AudioManager audioService = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioService.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {
            playBeep = false;
        }
        initBeepSound();
        vibrate = true;
    }

    /** 只有相机权限确认后才创建预览 Surface，图库识别不需要任何存储权限。 */
    private void setupCameraSurface() {
        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.scanner_view);
        SurfaceHolder surfaceHolder = surfaceView.getHolder();

        if (hasSurface) {
            initCamera(surfaceHolder);
        } else {
            surfaceHolder.addCallback(this);
            surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (handler != null) {
            handler.quitSynchronously();
            handler = null;
        }
        if (cameraPermissionGranted) CameraManager.get().closeDriver();
//        this.finish();
    }

    @Override
    protected void onDestroy() {
        inactivityTimer.shutdown();
        super.onDestroy();
    }

    /**
     * 这里是用相机扫描的处理方法
     */
    public void handleDecode(Result result, Bitmap barcode) {
        if (resultDialogShowing) return;
        inactivityTimer.onActivity();
        playBeepSoundAndVibrate();
        final String value = result == null ? null : result.getText();
        if (TextUtils.isEmpty(value)) {
            Toast.makeText(this, "识别失败", Toast.LENGTH_SHORT).show();
            restartScanning();
            return;
        }
        if (getIntent().getBooleanExtra(EXTRA_RETURN_RESULT, false)) {
            setResult(Activity.RESULT_OK, new Intent().putExtra(EXTRA_SCAN_RESULT, value));
            finish();
            return;
        }

        resultDialogShowing = true;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("扫描结果")
                .setMessage(value)
                .setNegativeButton("复制", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("扫码结果", value));
                    restartScanning();
                })
                .setPositiveButton("继续扫描", (dialog, which) -> restartScanning());
        if (value.startsWith("http://") || value.startsWith("https://")) {
            builder.setNeutralButton("打开网页", (dialog, which) -> {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(value)));
                restartScanning();
            });
        }
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(ignored -> restartScanning());
        dialog.show();
    }

    private void restartScanning() {
        resultDialogShowing = false;
        if (handler != null) handler.sendEmptyMessage(R.id.restart_preview);
    }

    private void handleDecodeLegacy(Result result, Bitmap barcode) {
        inactivityTimer.onActivity();
        playBeepSoundAndVibrate();
        final String resultString = result.getText();

        if (TextUtils.isEmpty(resultString)) {
            Toast.makeText(CaptureActivity.this, "Scan failed!", Toast.LENGTH_SHORT).show();
        } else {
            //网页识别
            if (result.getText().contains("http")){
                //对话框
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle("扫描结果：")//设置对话框的标题
                        .setMessage("检测到网页内容：\n"+result.getText().toString()+"\n是否跳转到浏览器")//设置对话框的内容
                        //设置对话框的按钮
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                                finish();
                            }
                        })
                        .setPositiveButton("跳转", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                finish();
                                Intent intent_to_web=new Intent(Intent.ACTION_VIEW);
                                intent_to_web.setData(Uri.parse(result.getText().toString()));
                                startActivity(intent_to_web);
                            }
                        }).show();
                dialog.setCanceledOnTouchOutside(false);//点击其他地方对话框不消失
                dialog.show();
            }else {
                //对话框
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle("扫描结果：")//设置对话框的标题
                        .setMessage(result.getText().toString())//设置对话框的内容
                        //设置对话框的按钮
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                                finish();
                            }
                        }).show();
                dialog.setCanceledOnTouchOutside(false);//点击其他地方对话框不消失
                dialog.show();
            }
        }

    }

    private void initCamera(SurfaceHolder surfaceHolder) {
        try {
            CameraManager.get().openDriver(surfaceHolder);
        } catch (IOException | RuntimeException ioe) {
            return;
        }
        if (handler == null) {
            handler = new CaptureActivityHandler(this, decodeFormats,
                    characterSet);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {

    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (!hasSurface) {
            hasSurface = true;
            initCamera(holder);
        }

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        hasSurface = false;

    }

    public ViewfinderView getViewfinderView() {
        return viewfinderView;
    }

    public Handler getHandler() {
        return handler;
    }

    public void drawViewfinder() {
        viewfinderView.drawViewfinder();

    }

    private void initBeepSound() {
        if (playBeep && mediaPlayer == null) {
            // The volume on STREAM_SYSTEM is not adjustable, and users found it
            // too loud,
            // so we now play on the music stream.
            setVolumeControlStream(AudioManager.STREAM_MUSIC);
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setOnCompletionListener(beepListener);

            AssetFileDescriptor file = getResources().openRawResourceFd(
                    R.raw.beep);
            try {
                mediaPlayer.setDataSource(file.getFileDescriptor(),
                        file.getStartOffset(), file.getLength());
                file.close();
                mediaPlayer.setVolume(BEEP_VOLUME, BEEP_VOLUME);
                mediaPlayer.prepare();
            } catch (IOException e) {
                mediaPlayer = null;
            }
        }
    }

    private static final long VIBRATE_DURATION = 200L;

    private void playBeepSoundAndVibrate() {
        if (playBeep && mediaPlayer != null) {
            mediaPlayer.start();
        }
        if (vibrate) {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            vibrator.vibrate(VIBRATE_DURATION);
        }
    }

    /**
     * When the beep has finished playing, rewind to queue up another one.
     */
    private final OnCompletionListener beepListener = new OnCompletionListener() {
        public void onCompletion(MediaPlayer mediaPlayer) {
            mediaPlayer.seekTo(0);
        }
    };

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()){
            case android.R.id.home:
                finish();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
