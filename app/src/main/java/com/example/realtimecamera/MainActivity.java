package com.example.realtimecamera;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String CAMERA_PERMISSION =         //카메라 사용 권한을 위한 변수
            Manifest.permission.CAMERA;
    private static final int PERMISSION_REQUEST_CODE = 1;   //사용 권한을 요청하고 구분하기 위한 변수
    private TextView textView;     //결과를 출력할 텍스트 뷰
    private Classifier cls;        //분류기

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //액티비티가 실행되는 동안 화면이 계속 켜져 있도록 설정
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        textView = findViewById(R.id.idMainTV);
        try {
            cls = new Classifier(this);    //  분류기 초기화
            cls.init();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }

        // 동적 권한 요청
        if(checkSelfPermission(CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            //Fragment 설정을 위한 메소드 호출
            setFragment();
        } else {
            requestPermissions(new String[]{CAMERA_PERMISSION}, PERMISSION_REQUEST_CODE);
        }
    }

    // 여러 권한을 요청한 경우 모든 권한을 확인하는 사용자 정의 메소드
    private boolean allPermissionsGranted(final int[] grantResults) {
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // 권한 요청을 한 후 선택하면 호출되는 콜백 메소드
    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(requestCode == PERMISSION_REQUEST_CODE) {
            //권한 설정을 하면 호출
            if(grantResults.length > 0 && allPermissionsGranted(grantResults)) {
                setFragment();
            }
            //권한 설정을 취소 하면 호출
            else {
                Toast.makeText(this, "permission denied", Toast.LENGTH_LONG).show();
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // 스레드가 작업을 수행하다가 화면 출력을 하기 위해 사용하는 객체
    private Handler handler;

    // synchronised는 동기화 메소드를 만들어줌.
    // 이 메소드는 동시에 호출되지 않음
    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            // MessageQueue에 message 전달
            // POST로 호출하면 다른 작업이 없을 때 작업 수행
            handler.post(r);
        }
    }

    // 카메라 미리보기 크기
    private int previewWidth = 0;
    private int previewHeight = 0;

    private Bitmap rgbFrameBitmap = null;      // 카메라 이미지

    private boolean isProcessingFrame = false; // 작업중인지 확인. boolean 변수는 앞에 is를 붙여 구분.

    private int sensorOrientation = 0;         // 기기 방향을 위한 변수

    protected void processImage(ImageReader reader) {
        if (previewWidth == 0 || previewHeight == 0) {
            return;
        }
        if(rgbFrameBitmap == null) {
            rgbFrameBitmap = Bitmap.createBitmap(
                    previewWidth,
                    previewHeight,
                    Bitmap.Config.ARGB_8888);
        }
        if (isProcessingFrame) {
            return;
        }

        isProcessingFrame = true;

        // 이미지 가져오기
        final Image image = reader.acquireLatestImage();
        if (image == null) {
            isProcessingFrame = false;
            return;
        }

        // yuv 포맷을 rbg로 변경
        YuvToRgbConverter.yuvToRgb(this, image, rgbFrameBitmap);

        // 람다를 이용한 Thread 처리
        runInBackground(() -> {
            if (cls != null && cls.isInitialized()) {
                // 추론
                final Pair<String, Float> output = cls.classify(rgbFrameBitmap,
                        sensorOrientation);
                runOnUiThread(() -> {
                    // 추론한 결과를 출력
                    String resultStr = String.format(Locale.ENGLISH,
                            "class : %s, prob : %.2f%%",
                            output.first, output.second * 100);
                    textView.setText(resultStr);
                });
            }
            image.close();
            isProcessingFrame = false;
        });
    }


    // Thread 참조 변수
    private HandlerThread handlerThread;


    // Activity가 활성화 될 때 Thread와 Handler 생성
    @Override
    public synchronized void onResume() {
        super.onResume();
        handlerThread = new HandlerThread("InferenceThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    // Activity가 중지되었을 때 Thread 중지
    @Override
    public synchronized void onPause() {
        handlerThread.quitSafely();
        try {
            handlerThread.join();
            handlerThread = null;
            handler = null;
        } catch (final InterruptedException e) {
            e.printStackTrace();
        }
        super.onPause();
    }

    // Activity를 파괴 - 메모리 정리
    @Override
    protected synchronized void onDestroy() {
        cls.finish();
        super.onDestroy();
    }


    @Override
    public synchronized void onStart() {
        super.onStart();
    }

    // Activity가 중지될 떄
    @Override
    public synchronized void onStop() {
        super.onStop();
    }

    // 기기 방향 return
    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            default:
                return 0;
        }
    }


    // 전/후면 카메라로 카메라를 고정할 것이라면
    // 0이나 1 사용하면 됨. 이 메소드를 구현할 필요가 없음.
    private String chooseCamera() {
        final CameraManager manager =
                (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        try {
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics =
                        manager.getCameraCharacteristics(cameraId);
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return cameraId;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return "";
    }


    public static final String TAG = "[IC]MainActivity";
    protected void setFragment() {
        // 모델 입력 사이즈 받아오기
        Size inputSize = cls.getModelInputSize();
        // 사용할 카메라 id 받아오기
        String cameraId = chooseCamera();

        // 모델의 크기를 가지고 카메라 화면의 크기 설정
        if(inputSize.getWidth() > 0 && inputSize.getHeight() > 0 && !cameraId.isEmpty()) {
            Fragment fragment = CameraFragment.newInstance(
                    (size, rotation) -> {
                        previewWidth = size.getWidth();
                        previewHeight = size.getHeight();
                        sensorOrientation = rotation - getScreenOrientation();
                    },
                    // 카메라로부터 이미지 받아오기
                    reader->processImage(reader),
                    inputSize,
                    cameraId);

            Log.d(TAG, "inputSize : " + cls.getModelInputSize() +
                    "sensorOrientation : " + sensorOrientation);
            // 프래그먼트 설정
            getFragmentManager().beginTransaction().replace(
                    R.id.idMainFL, fragment).commit();
        } else {
            Toast.makeText(this, "Can't find camera", Toast.LENGTH_SHORT).show();
        }
    }
}