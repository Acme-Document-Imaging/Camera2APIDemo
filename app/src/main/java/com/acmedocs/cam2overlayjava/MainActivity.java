package com.acmedocs.cam2overlayjava;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.RelativeLayout;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private final static int REQUEST_CAMERA_PERMISSION_RESULT = 0;
    private static final int ACTIVITY_START_CAMERA_APP = 0;
    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAIT_LOCK = 1;
    private static final int IMAGE_SIZE_MIN_VALUES = 0;
    private static final int IMAGE_SIZE_MAX_VALUES = 1;
    private int mState;

    private Image mImage;
    private Image mImageCrop;
    private ImageReader mImageReader;
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            Point mTopLeft = new Point(100,200);
            Point mBottomRight = new Point(200,400);
            Image mImage = imageReader.acquireLatestImage();
            mBackgroundOverlayHandler.post(new ImageCropper(mImage,mImageCrop,
                    mTopLeft, mBottomRight));
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.capacity()];
            buffer.get(bytes);
            final Bitmap resBit = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, null);

            runOnUiThread(new Runnable() {

                @Override
                public void run() {

                    // Stuff that updates the UI

                    mImageResult.setImageBitmap(resBit);
                }
            });

            mImage.close();

        }
    };

    private File imageFile;
    private ImageView mImageResult;
    private Button mButton;
    private TextureView mTextureView;
    private TextureView.SurfaceTextureListener mSTL = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {

            setupCamera(i, i1);
            ConnectCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }
    };

    private CameraDevice mCameraDevice;
    private CaptureRequest mPreviewCaptureRequest;
    private CaptureRequest.Builder mPreviewCaptureRequestBuilder;
    private CameraCaptureSession mCameraCaptureSession;
    private CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback(){

        private void process (CaptureResult result){
            switch (mState){
                case STATE_PREVIEW:
                    //Do nothing
                    break;
                case STATE_WAIT_LOCK:
                    int afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if(afState == CaptureRequest.CONTROL_AF_STATE_FOCUSED_LOCKED |
                            afState == CaptureRequest.CONTROL_AF_STATE_PASSIVE_FOCUSED){
                    //    Toast.makeText(getApplicationContext(), "Focus locked!", Toast.LENGTH_SHORT).show();
                        captureStillImage();
                        unlockFocus();
                    }
                    break;
            }

        }

        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber){
            super.onCaptureStarted(session, request, timestamp, frameNumber);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);

            process(result);
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);

            Toast.makeText(getApplicationContext(), "Capture failed :(", Toast.LENGTH_SHORT).show();
        }

    };
    private CameraDevice.StateCallback mCameraDeviceSC = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            //Toast.makeText(getApplicationContext(),"Cam connected", Toast.LENGTH_SHORT).show();
            startPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            cameraDevice.close();
            mCameraDevice = null;
        }
    };

    private HandlerThread mBackgroundOverlayHandlerThread;
    private Handler mBackgroundOverlayHandler;
    private String mCameraId;
    private Size mPreviewSize;
    private Size mCaptureSize;
    private static SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private static class CompareSizeByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            //lhs = leftHandSide rhs= rightHandSide
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() /
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mButton = findViewById((R.id.buttonTest));
        mButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
               // Toast.makeText(getApplication(),"Pressed",Toast.LENGTH_SHORT).show();
                // Code here executes on main thread after user presses button
                captureMRZ();
            }
        });

        mImageResult = findViewById(R.id.imageViewResult);
        mTextureView = findViewById(R.id.textureView);
        //Set the height of the TextureView to maintain the aspect ratio.

        //Use the screen size for the X dimension because the control flows from one part of the
        //Screen to the other.
        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        int newHeight = size.x;
        //int newHeight = (int)(size.x * .75);  //This would have given a 3:4 aspect ratio

        //Invalidate the textureView layout
        mTextureView.requestLayout();

       // int newHeight = (int)(mTextureView.getLayoutParams().width * .75);
        mTextureView.getLayoutParams().height = newHeight;



    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgoundOverlayThread();

        if (mTextureView.isAvailable()) {
            setupCamera(mTextureView.getWidth(), mTextureView.getHeight());
            ConnectCamera();
        } else {

            mTextureView.setSurfaceTextureListener(mSTL);
        }
    }

    @Override
    protected void onPause() {
        //Free up resources
      //  mCameraDevice.close();
        closeCamera();

        stopBackgroundOverlayThread();
        super.onPause();
    }

    private void setupCamera(int width, int height) {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }
                //RALPH - Gets minimun focus distance for particular camera
                float minimumLens;
                if(cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) == null)
                {
                    minimumLens = 0;
                } else {
                    minimumLens = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                }

            //    List<int> focusModes = new List<int>;

            //    cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
               // StreamConfigurationMap mapFinal = cameraCharacteristics.get(CameraCharacteristics.MAP);
                //Swap width and height if the phone is rotated
                int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
                int totalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation);
                boolean swapRotation = totalRotation == 90 | totalRotation == 270;
                int rotatedWidth = width;
                int rotatedHeight = height;
                if (swapRotation) {
                    rotatedWidth = height;
                    rotatedHeight = width;
                }

                //Rotated Width and Height are confusing because Height and Width are Camera Relative for most phones in Portrait Mode.

                mPreviewSize = ChooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedWidth, rotatedHeight, IMAGE_SIZE_MAX_VALUES);
                //With a 4.9 inch passport width, this gives us about 300DPI. Remember, in portrait mode the camera
                //  is actually rotated 90 degrees which is why we start with 1200 for width and 1600 for height.
                // if we don;t get a


                mCaptureSize = ChooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG), 1600, 1200, IMAGE_SIZE_MIN_VALUES);

                mImageReader = ImageReader.newInstance(mCaptureSize.getWidth(),
                        mCaptureSize.getHeight(), ImageFormat.JPEG, 1);
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundOverlayHandler);

                mCameraId = cameraId;
                return;

            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == REQUEST_CAMERA_PERMISSION_RESULT){
            if(grantResults[0] != PackageManager.PERMISSION_GRANTED){
                //Rejected
                Toast.makeText(getApplicationContext(),"I really need cameara access",Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void ConnectCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling DONE :)
                requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION_RESULT);
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            cameraManager.openCamera(mCameraId, mCameraDeviceSC, mBackgroundOverlayHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }



    private void startPreview(){
        SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
     //   surfaceTexture.setDefaultBufferSize(480,640);
        Surface previewTextureSurface = new Surface(surfaceTexture);

        //PREVIEW, MANUAL, RECORD..  What are the differences???
        try {
            mPreviewCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            //Gray scale
        //    mCaptureRequestBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_MONO);
            //Fixed focal length

           // mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);

            //mCaptureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 8.0f);

     //       mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            mPreviewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
     //       mPreviewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_MACRO);
            mPreviewCaptureRequestBuilder.addTarget(previewTextureSurface);

            List<Surface> mSurfaces = new ArrayList<>();
            mSurfaces.add(previewTextureSurface);
            mSurfaces.add(mImageReader.getSurface());

            mCameraDevice.createCaptureSession(mSurfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {

                            if(mCameraDevice == null){
                                return;
                            }

                            try {
                                mPreviewCaptureRequest = mPreviewCaptureRequestBuilder.build();
                                mCameraCaptureSession = cameraCaptureSession;
                                mCameraCaptureSession.setRepeatingRequest(mPreviewCaptureRequest, captureCallback, mBackgroundOverlayHandler);
                     //           cameraCaptureSession.setRepeatingRequest(mPreviewCaptureRequestBuilder.build(), captureCallback
                     //                   , mBackgroundOverlayHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            Toast.makeText(getApplicationContext(),
                                    "Cannot start capture session :(", Toast.LENGTH_SHORT).show();
                        }
                    }, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    private void closeCamera() {
        if(mCameraDevice != null){
            mCameraDevice.close();
            mCameraDevice = null;
        }

        if(mCameraCaptureSession != null){
            mCameraCaptureSession.close();
            mCameraCaptureSession = null;
        }

        if(mImageReader != null){
            mImageReader.close();
            mImageReader = null;
        }
    }

    private void startBackgoundOverlayThread(){
        mBackgroundOverlayHandlerThread = new HandlerThread("AcmeOverlay");
        mBackgroundOverlayHandlerThread.start();
        mBackgroundOverlayHandler = new Handler(mBackgroundOverlayHandlerThread.getLooper());
    }

    private void stopBackgroundOverlayThread(){
        mBackgroundOverlayHandlerThread.quitSafely();

        try {
            mBackgroundOverlayHandlerThread.join();
            mBackgroundOverlayHandlerThread = null;
            mBackgroundOverlayHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static int sensorToDeviceRotation(CameraCharacteristics cameraCharacteristics, int deviceOrientation){
        int sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        //This next line converts device orientation to degrees instead of the enumeration.
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        return (sensorOrientation + deviceOrientation + 360) % 360;

    }

    //Make this static after debugging
    private Size ChooseOptimalSize(Size[] choices, int width, int height, int minOrMax){
        List<Size> bigEnough = new ArrayList<Size>();

        //Checks to make sure camera size is not to big for surface
        //Which will make things crash.  The following code selects the size of the
        // TextureView (with option) and makes sure the aspect ratio is correct.
        // However, we need a square aspect ratio image to ensure the clipping
        // is the same for the preview and the final output
        // so we will force this aspect ratio by checking that width = height
        //
        //  Here is code to try and force the aspect ratio of the control size.
        //             if (option.getHeight() == option.getWidth() * height / width &&
        //                    option.getHeight() <= height && option.getWidth() <= width) {
        //                bigEnough.add(option);
        //            }.
        //
        if(minOrMax == IMAGE_SIZE_MAX_VALUES) {
            for (Size option : choices) {
                if (option.getHeight() == option.getWidth() &&
                        option.getHeight() <= height && option.getWidth() <= width) {
                    bigEnough.add(option);
                }
            }

            if (bigEnough.size() > 0) {
                return Collections.min(bigEnough, new CompareSizeByArea());
            } else {
                Toast.makeText(getApplicationContext(), "Image too big for View", Toast.LENGTH_SHORT).show();
                return choices[0];
            }
        } else{

            for (Size option : choices) {
                if (option.getHeight() == option.getWidth() &&
                        option.getHeight() >= height && option.getWidth() >= width) {
                    bigEnough.add(option);
                }
            }

            if (bigEnough.size() > 0) {
                return Collections.max(bigEnough, new CompareSizeByArea());
            } else {
                Toast.makeText(getApplicationContext(), "Image too small for OCR", Toast.LENGTH_SHORT).show();
                return choices[0];
            }
        }
    }

    private void lockFocus(){
        try {
        mState = STATE_WAIT_LOCK;
     //   mPreviewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
        mPreviewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            mCameraCaptureSession.capture(mPreviewCaptureRequestBuilder.build(), captureCallback, mBackgroundOverlayHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    private void unlockFocus(){
        try {
        mState = STATE_PREVIEW;
        //Might want this to be IDLE and not CANCEL  | CONTROL_AF_TRIGGER_CANCEL
        mPreviewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            mCameraCaptureSession.capture(mPreviewCaptureRequestBuilder.build(), captureCallback, mBackgroundOverlayHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    private void captureMRZ(){

      /*  try
        {
          //  mImageFile = createImageFile();

        } catch (IOException e) {
            e.printStackTrace();
        } */


        lockFocus();
    }

    private static class ImageCropper implements Runnable{

        private final Image mImage;
        private ImageCropper(Image imageIn, Image imageCrop, Point topLeft, Point bottomRight){
            mImage = imageIn;
        }

        @Override
        public void run() {
            //Crop Image
            int wert = 1;

        }
    }

    private void captureStillImage() {
        CaptureRequest.Builder captureStillBuilder = null;
        try {
            captureStillBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        captureStillBuilder.addTarget(mImageReader.getSurface());

        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        rotation = 90;
        captureStillBuilder.set(CaptureRequest.JPEG_ORIENTATION, 180);
     //           ORIENTATIONS.get(rotation));

        CameraCaptureSession.CaptureCallback captureStillCallback = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);
            //    Toast.makeText(getApplicationContext(),"Got Image :)", Toast.LENGTH_SHORT).show();
                unlockFocus();

            }

            @Override
            public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                super.onCaptureFailed(session, request, failure);
                //Why failed?
                int wert = 2;
            }

        };

        //Already running on the background thread
        try {
            mCameraCaptureSession.capture(captureStillBuilder.build(), captureStillCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

}
