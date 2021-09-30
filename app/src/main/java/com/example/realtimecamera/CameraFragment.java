package com.example.realtimecamera;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.app.Fragment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


// Fragment API 28에서 deprecated.
// 이러면 deprecated API에 취소선이 그어짐.
// 취소된 API에 마우스 갖다대면 정보가 보임. 취소선을 없애려면:
// 1. build.gradle (Module)에서 minSdk 의 버전을 이전 버전으로 낮춰주거나(권장 방법이나 실제 개발시에 쉽지는 않음),
// 2. 대체하는 클래스나 메소드를 사용.
@SuppressLint("ValidFragment")
public class CameraFragment extends Fragment {  // 새로운 API로 변경하거나
    public static final String TAG = "[IC]CameraFragment";

    // Callback 이나 Listner 라는 용어
    // 이벤트가 발생했을 때 작업을 수행하기 위한 함수나 클래스, 인터페이스에 붙임
    // Listner: 자바에서 interface로 한정
    // 구현해야 하는 메소드가 하나면 람다로 대체 가능.
    // 람다코드는 -> 이르케 생김.
    private ConnectionCallback connectionCallback;
    private ImageReader.OnImageAvailableListener imageAvailableListener;
    private Size inputSize;                                                 //
    private String cameraId;                                                // 카메라 아이디. 0 후면, 1 전면 카메라

    // 동영상 출력을 위한 사용자 정의 뷰
    private AutoFitTextureView autoFitTextureView = null;

    // 스레드 사용을 위한 변수
    // Handler는 안드로이드에서 MessageQueue에 명령을 전달한 후
    //           객체로 Thread 작업한 후 결과를 화면에 출력할 때 주로 사용
    // main Thread를 제외한 나머지 Thread에서 화면 갱신 불가
    // 거의 대다수의 GUI 시스템이 마찬가지.
    // 하나의 메소드 안에 출력을 여러번 하는 코드가 존재하면 모아서 한 번에 처리
    private HandlerThread backgroundThread = null;
    private Handler backgroundHandler = null;

    // 미리보기 크기와 기기 방향을 저장할 변수
    private Size previewSize;
    private int sensorOrientation;

    // Thread를 사용하기 때문에 멀티 Thread 환경에서
    // 공유자원의 사용문제를 해결하기 위한 인스턴스
    // 정수는 자원을 동시에 사용할 Thread의 개수
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);

    private CameraDevice cameraDevice;
    private CaptureRequest.Builder previewRequestBuilder;
    private ImageReader previewReader;
    private CameraCaptureSession captureSession;

    // 생성자 접근지정자가 private: 외부에서 instance 생성이 불가.
    // 그렇다면 이 class를 대체 어떻게 쓸 것인가? (아래에 정답이 있음 ㅇㅅㅇ)ㅋ)
    private CameraFragment(final ConnectionCallback callback,
                           final ImageReader.OnImageAvailableListener imageAvailableListener,
                           final Size inputSize,
                           final String cameraId) {
        this.connectionCallback = callback;
        this.imageAvailableListener = imageAvailableListener;
        this.inputSize = inputSize;
        this.cameraId = cameraId;
    }

    // 인스턴스를 생성해서 return해주는 static method - Factory pattern
    // 인스턴스를 constructor를 이용하지 않고 별도의 method에서 생성
    // 목적: 생성과정이 복잡해서 생성자를 노출시키지 않기 위함.
    // 서버에서 이런식으로 구현하는 이유는 대부분 Singleton Pattern 적용을 위해.
    // Singleton은 instance를 1개만 생성할 수 있는 class
    // 그 변수가 null 일 때 instance를 생성하는 코드가 들어감.
    public static CameraFragment newInstance(
            final ConnectionCallback callback,
            final ImageReader.OnImageAvailableListener imageAvailableListener,
            final Size inputSize,
            final String cameraId) {
        return new CameraFragment(callback, imageAvailableListener, inputSize, cameraId);
    }

    // 화면을 출력하기 위한 뷰를 만들 때 호출되는 메소드
    // Layout 파일의 내용만 불러서 return
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera, container, false);
    }

    // 화면 출력이 된 후 호출되는 method
    // 동영상 미리보기 뷰를 찾아옴.
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        autoFitTextureView = view.findViewById(R.id.autoFitTextureView);
    }

    // Activity나 Fragment가 화면에 보여질 때마다 호출되는 method
    // Thread 시작
    // TextureView가 사용 가능하면 listener 설정, 그렇지 않으면 카메라 내용 출력
    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        if(!autoFitTextureView.isAvailable())
            autoFitTextureView.setSurfaceTextureListener(surfaceTextureListener);
        else
            openCamera(autoFitTextureView.getWidth(), autoFitTextureView.getHeight());
    }

    // 출력이 중지될 때 호출되는 메소드
    // 카메라를 닫고 스레드를 중지
    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    // 스레드를 생성해서 시작하는 메소드
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("ImageListener");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    // 스레드를 중지하는 메소드
    private void stopBackgroundThread() {
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (final InterruptedException e) {
            e.printStackTrace();
        }
    }

    // TextureView의 리스너
    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {

                // 텍스쳐가 유효하면 호출되는 메소드
                @Override
                public void onSurfaceTextureAvailable(
                        final SurfaceTexture texture, final int width, final int height) {
                    openCamera(width, height);
                }

                // 텍스쳐의 사이즈가 변경되면 호출되는 메소드
                // 회전이 발생하면 호출되는 메소드
                @Override
                public void onSurfaceTextureSizeChanged(
                        final SurfaceTexture texture, final int width, final int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(final SurfaceTexture texture) {
                }
            };

    // 카메라를 사용할 수 있도록 헤주는 메소드
    @SuppressLint("MissingPermission")
    private void openCamera(final int width, final int height) {

        // 카메라 사용 객체 찾아오기
        final Activity activity = getActivity();
        final CameraManager manager =
                (CameraManager)activity.getSystemService(Context.CAMERA_SERVICE);

        // 카메라를 설정하고 크기를 정의하는 사용자 정의 메소드 호출
        setupCameraOutputs(manager);
        configureTransform(width, height);

        try {
            // 2.5초 동안 카메라를 가져오지 못하면 화면을 중지시켜버림.
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                Toast.makeText(getContext(),
                        "Time out waiting to lock camera opening.",
                        Toast.LENGTH_LONG).show();
                activity.finish();
            } else {
                // 카메라를 사용
                manager.openCamera(cameraId, stateCallback, backgroundHandler);
//                manager.openCamera(cameraId, stateCallback, null);
            }
        } catch (final InterruptedException | CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 카메라 설정 메소드
    // 수정할 부분이 거의 없음
    private void setupCameraOutputs(CameraManager manager) {
        try {
            final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

            final StreamConfigurationMap map =characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            previewSize = chooseOptimalSize(
                    map.getOutputSizes(SurfaceTexture.class),
                    inputSize.getWidth(),
                    inputSize.getHeight());

            final int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                autoFitTextureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
            } else {
                autoFitTextureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
            }
        } catch (final CameraAccessException cae) {
            cae.printStackTrace();
        }

        connectionCallback.onPreviewSizeChosen(previewSize, sensorOrientation);
    }

    // 회전 처리를 위한 메소드
    private void configureTransform(final int viewWidth, final int viewHeight) {
        final Activity activity = getActivity();
        if (null == autoFitTextureView || null == previewSize || null == activity) {
            return;
        }

        final int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        final Matrix matrix = new Matrix();
        final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        final RectF bufferRect =
                new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        final float centerX = viewRect.centerX();
        final float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(
                    centerX - bufferRect.centerX(),
                    centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            final float scale = Math.max(
                    (float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        autoFitTextureView.setTransform(matrix);
    }

    // 카메라의 크기를 설정하는 메소드
    // 정사각형 형태로 만들기 위해서 너비와 높이 중 작은 것을 선택해서 비율 조정
    protected Size chooseOptimalSize(final Size[] choices, final int width, final int height) {
        final int minSize = Math.min(width, height);
        final Size desiredSize = new Size(width, height);

        final List<Size> bigEnough = new ArrayList<Size>();
        final List<Size> tooSmall = new ArrayList<Size>();
        for (final Size option : choices) {
            if (option.equals(desiredSize)) {
                return desiredSize;
            }

            if (option.getHeight() >= minSize && option.getWidth() >= minSize) {
                bigEnough.add(option);
            } else {
                tooSmall.add(option);
            }
        }

        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return Collections.max(tooSmall, new CompareSizesByArea());
        }
    }

    // 카메라의 상태가 변경될 때 호출되는 리스너
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(final CameraDevice cd) {
            // Semaphore를 취득해서 lock 해제
            cameraOpenCloseLock.release();
            cameraDevice = cd;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(final CameraDevice cd) {
            cameraOpenCloseLock.release();
            cd.close();
            cameraDevice = null;
        }

        @Override
        public void onError(final CameraDevice cd, final int error) {
            cameraOpenCloseLock.release();
            cd.close();
            cameraDevice = null;
            final Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }
    };

    // 카메라 사용 권한을 취득하고 사용하기 직전에 카메라 호출
    // 미리보기와 카메라에 관련된 설정을 수행
    private void createCameraPreviewSession() {
        try {
            final SurfaceTexture texture = autoFitTextureView.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            final Surface surface = new Surface(texture);

            // 미리보기 포맷 설정
            previewReader = ImageReader.newInstance(previewSize.getWidth(),
                    previewSize.getHeight(), ImageFormat.YUV_420_888, 2);
            previewReader.setOnImageAvailableListener(imageAvailableListener,
                    backgroundHandler);

            previewRequestBuilder = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            previewRequestBuilder.addTarget(previewReader.getSurface());

            previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
            previewRequestBuilder.set(
                    CaptureRequest.FLASH_MODE,
                    CameraMetadata.FLASH_MODE_TORCH);

            cameraDevice.createCaptureSession(
                    Arrays.asList(surface, previewReader.getSurface()),
                    sessionStateCallback,
                    null);
        } catch (final CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 카메라 화면을 가져온 후 호출되는 리스너
    private final CameraCaptureSession.StateCallback sessionStateCallback =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(final CameraCaptureSession cameraCaptureSession) {
                    if (null == cameraDevice) {
                        return;
                    }

                    captureSession = cameraCaptureSession;
                    try {
                        captureSession.setRepeatingRequest(previewRequestBuilder.build(),
                                null, backgroundHandler);
                    } catch (final CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(final CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(getActivity(), "CameraCaptureSession Failed", Toast.LENGTH_SHORT).show();
                }
            };


    // 카메라 종료하는 메소드
    // 이 메소드는 구현하지 않아도 App 자체에는 아무런 영향이 없음.
    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (null != captureSession) {
                captureSession.close();
                captureSession = null;
            }
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (null != previewReader) {
                previewReader.close();
                previewReader = null;
            }
        } catch (final InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }


    public interface ConnectionCallback {
        void onPreviewSizeChosen(Size size, int cameraRotation);
    }

    // 자바는 크기 비교를 할 때 숫자 데이터는 부등호로 하지만
    // 숫자 데이터가 아닌 경우는 Comparator의 compare 메소드 이용
    // 크기 비교를 해서 정렬을 하고자 할 때 구현하는 interface가 Comparator와 Comparable.
    // 아래의 Comparator는 Size를 가지고 크기 비교를 하려고 만듬.
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(final Size lhs, final Size rhs) {
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}