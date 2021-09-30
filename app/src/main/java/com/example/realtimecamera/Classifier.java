package com.example.realtimecamera;

import static org.tensorflow.lite.support.image.ops.ResizeOp.ResizeMethod.NEAREST_NEIGHBOR;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Pair;
import android.util.Size;

import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp;
import org.tensorflow.lite.support.image.ops.Rot90Op;
import org.tensorflow.lite.support.label.TensorLabel;
import org.tensorflow.lite.support.model.Model;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Classifier {

    // 변수 선언
    private static final String MODEL_NAME = "mobilenet_imagenet_model.tflite";
    private static final String LABEL_FILE = "labels.txt";

    Context context;
    Model model;
    int modelInputWidth, modelInputHeight, modelInputChannel;
    TensorImage inputImage;
    TensorBuffer outputBuffer;
    private List<String> labels;
    private boolean isInitialized = false;

    // 생성자
    public Classifier(Context context) {
        this.context = context;
    }

    // 초기화 메소드
    public void init() throws IOException {
        model = Model.createModel(context, MODEL_NAME);      // 모델 생성
        initModelShape();                                    // 입출력 관련 데이터 설정 메소드 호출
        labels = FileUtil.loadLabels(context, LABEL_FILE);   // 레이블 파일 내용 읽어옴
        isInitialized = true;                                // 초기화 수행했으니 상태 업데이트
    }

    // 초기화 상태 확인 메소드. a Getter.
    public boolean isInitialized() {
        return isInitialized;
    }

    // 입출력 정보 설정 메소드
    private void initModelShape() {
        Tensor inputTensor = model.getInputTensor(0);       // 모델 입력 데이터 정보 가져옴
        int[] shape = inputTensor.shape();
        modelInputChannel = shape[0];
        modelInputWidth = shape[1];
        modelInputHeight = shape[2];
        inputImage = new TensorImage(inputTensor.dataType());               // 입력 데이터 모양 설정
        Tensor outputTensor = model.getOutputTensor(0);
        outputBuffer = TensorBuffer.createFixedSize(outputTensor.shape(),   // 출력 데이터 모양 설정
                outputTensor.dataType());
    }

    public Size getModelInputSize() {
        if(!isInitialized)
            return new Size(0, 0);
        return new Size(modelInputWidth, modelInputHeight);
    }


    private Bitmap convertBitmapToARGB8888(Bitmap bitmap) {
        return bitmap.copy(Bitmap.Config.ARGB_8888,true);
    }

    // 안드로이드 카메라로 촬영한 이미지를 추론에 맞는 형태로 변환하는 메소드
    private TensorImage loadImage(final Bitmap bitmap, int sensorOrientation) {
        if(bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            inputImage.load(convertBitmapToARGB8888(bitmap));
        } else {
            inputImage.load(bitmap);
        }
        int cropSize = Math.min(bitmap.getWidth(), bitmap.getHeight());     // 자를 이미지 크기 설정. min(작은 크기)은 이미지를 정사각형으로 만들 때 쓰임.
        int numRotation = sensorOrientation / 90;                           // 회전 처리

        // 이미지 전처리
        // 1. 이미지 확대 축소 - 논문을 읽어서 정/직사각형인지에 따라 다름. 직사각형은 처리 불필요.
        // 2. 이미지 사이즈 조정
        // 3. 회전
        // 4. 정규화 - 전이학습을 하려면 논문을 꼭(!) 읽어보세요.

        ImageProcessor imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeWithCropOrPadOp(cropSize, cropSize))
                .add(new ResizeOp(modelInputWidth, modelInputHeight, NEAREST_NEIGHBOR))
                .add(new Rot90Op(numRotation))
                .add(new NormalizeOp(0.0f, 255.0f))
                .build();
        return imageProcessor.process(inputImage);
    }

    // 추론 메소드
    public Pair<String, Float> classify(Bitmap image, int sensorOrientation) {
        inputImage = loadImage(image, sensorOrientation);                      // 입력 데이터 생성
        Object[] inputs = new Object[]{inputImage.getBuffer()};
        Map<Integer, Object> outputs = new HashMap();
        outputs.put(0, outputBuffer.getBuffer().rewind());
        model.run(inputs, outputs);                                            // 추론
        Map<String, Float> output =                                            // 추론 결과 저장
                new TensorLabel(labels, outputBuffer).getMapWithFloatValue();  // 추론 결과를 해석하는 메소드를 호출해서
        return argmax(output);
    }

    // 기기 방향이 없을 때 추론
    public Pair<String, Float> classify(Bitmap image) {
        return classify(image, 0);
    }

    // 추론 결과 해석
    // 추론하면 클래스의 레이블이 리턴되지 않고 인덱스가 리턴되므로
    // 인덱스를 ㅔㄹ이블로 변경하고 가장 확률이 높은 데이터만 추출
    private Pair<String, Float> argmax(Map<String, Float> map) {
        String maxKey = "";
        float maxVal = -1;
        for(Map.Entry<String, Float> entry : map.entrySet()) {
            float f = entry.getValue();
            if(f > maxVal) {
                maxKey = entry.getKey();
                maxVal = f;
            }
        }
        return new Pair<>(maxKey, maxVal);
    }

    // 메모리 정리
    public void finish() {
        if(model != null) {
            model.close();
            isInitialized = false;
        }
    }
}