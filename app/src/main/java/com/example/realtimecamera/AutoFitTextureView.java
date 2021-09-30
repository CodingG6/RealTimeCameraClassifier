package com.example.realtimecamera;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

public class AutoFitTextureView extends TextureView {

    // 가로세로 크기 저장
    private int ratioWidth = 0;
    private int ratioHeight = 0;

    // 생성자
    // 생성자를 만드는 경우: 별도의 초기화 작업을 수해하고자 하는 경우
    // 상위 클래스에 매개변수가 없는 생성자가 없을 때는 생성자를 반드시 만들어야 함
    public AutoFitTextureView(final Context context) {
        this(context, null);
    }

    public AutoFitTextureView(final Context context, final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AutoFitTextureView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);
    }


    public void setAspectRatio(final int width, final int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative.");
        }
        ratioWidth = width;
        ratioHeight = height;
        requestLayout();            // 레이아웃을 다시 그려달라고 요청하는 메소드 호출
    }

    // 화면이 다시 그려질 때 화면의 크기를 설정하기 위해 호출하는 메소드
    // onDraw나 onPaint는 다시 그리는 메소드
    // 이 메소드(onMeasure)는 그릴 크기를 설정하는 메소드
    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = MeasureSpec.getSize(heightMeasureSpec);
        if (0 == ratioWidth || 0 == ratioHeight) {
            setMeasuredDimension(width, height);
        } else {
            if (width < height * ratioWidth / ratioHeight) {
                setMeasuredDimension(width, width * ratioHeight / ratioWidth);
            } else {
                setMeasuredDimension(height * ratioWidth / ratioHeight, height);
            }
        }
    }
}
