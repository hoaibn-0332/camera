package io.flutter.plugins.camera;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.jiangdg.usbcamera.UVCCameraHelper;
import com.jiangdg.usbcamera.utils.FileUtils;
import com.serenegiant.usb.common.AbstractUVCCameraHandler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.view.TextureRegistry;

public class USBCamera {
    private static final String TAG = "USBCamera";
    private final TextureRegistry.SurfaceTextureEntry flutterTexture;

    private final UVCCameraHelper uvcCameraHelper;
    private final USBCameraView cameraView;
    private boolean isRequest;
    private int width = 480;
    private int height = 640;
    private int format = UVCCameraHelper.FRAME_FORMAT_YUYV;
    private boolean isPreview = false;
    private Context context;

    public USBCamera(
            final Activity activity,
            final TextureRegistry.SurfaceTextureEntry flutterTexture,
            final DartMessenger dartMessenger,
            final String resolutionPreset,
            final boolean enableAudio) {

        if (activity == null) {
            throw new IllegalStateException("No activity available!");
        }

        this.context = activity.getApplicationContext();
        this.flutterTexture = flutterTexture;

        Camera.ResolutionPreset preset = Camera.ResolutionPreset.valueOf(resolutionPreset);
        updateResolution(preset);

        cameraView = new USBCameraView();
        uvcCameraHelper = UVCCameraHelper.getInstance();

        uvcCameraHelper.setDefaultFrameFormat(format);
        // request open permission
        // close camera
        // need to wait UVCCamera initialize over
        UVCCameraHelper.OnMyDevConnectListener listener = new UVCCameraHelper.OnMyDevConnectListener() {
            @Override
            public void onAttachDev(UsbDevice device) {
                // request open permission
                if (!isRequest) {
                    isRequest = true;
                    if (uvcCameraHelper != null) {
                        uvcCameraHelper.requestPermission(0);
                    }
                }
            }

            @Override
            public void onDettachDev(UsbDevice device) {
                // close camera
                if (isRequest) {
                    isRequest = false;
                    uvcCameraHelper.closeCamera();
                    dartMessenger.sendCameraClosingEvent();
                }
            }

            @Override
            public void onConnectDev(UsbDevice device, boolean isConnected) {
                if (!isConnected) {
                    dartMessenger.send(DartMessenger.EventType.ERROR, "The camera was disconnected.");
                    isPreview = false;
                } else {
                    // need to wait UVCCamera initialize over
                    isPreview = true;
                    showShortMsg("connecting");
                }
            }

            @Override
            public void onDisConnectDev(UsbDevice device) {
                showShortMsg("Disconnecting");
                dartMessenger.send(DartMessenger.EventType.ERROR, "The camera was disconnected.");
            }
        };
        uvcCameraHelper.initUSBMonitor(activity, cameraView, listener);

        uvcCameraHelper.registerUSB();
    }

    private void updateResolution(Camera.ResolutionPreset preset) {
        switch (preset) {
            case low:
                width = 240;
                height = 320;
                break;

            case medium:
                width = 480;
                height = 640;
                break;

            case high:
                width = 720;
                height = 1280;
                break;

            case veryHigh:
                width = 1080;
                height = 1920;
                break;

            case ultraHigh:
            case max:
                width = 1296;
                height = 2340;
                break;
        }

        if (context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            final int temp = width;
            width = height;
            height = temp;
        }
    }

    private void showShortMsg(String msg) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
    }

    public void close() {
        flutterTexture.release();
    }

    /**
     * Open camera
     */
    public void open(MethodChannel.Result result) {
        Map<String, Object> reply = new HashMap<>();
        reply.put("textureId", flutterTexture.id());
        reply.put("previewWidth", width);
        reply.put("previewHeight", height);
        result.success(reply);


        flutterTexture.surfaceTexture().setDefaultBufferSize(width, height);
        cameraView.initRender(flutterTexture.surfaceTexture(), width, height);
    }

    /**
     * Take capture to file path
     */
    public void takeCapture(String filePath, @NonNull final MethodChannel.Result result) {
        final File file = new File(filePath);
        if (file.exists()) {
            result.error("fileExists", "File at path '" + filePath + "' already exists. Cannot overwrite.", null);
            return;
        }

        if (uvcCameraHelper == null || !uvcCameraHelper.isCameraOpened()) {
            result.error("IOError", "sorry,camera open failed", null);
            return;
        }

        try {
            uvcCameraHelper.capturePicture(filePath, picPath -> new Handler(Looper.getMainLooper()).post(() -> result.success(null)));
        } catch (Exception e) {
            result.error("IOError", "Failed saving image", null);
        }
    }

    /**
     * Start image stream
     * Convert image to plan
     */
    public void startPreviewWithImageStream(EventChannel imageStreamChannel) {
        imageStreamChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                uvcCameraHelper.setOnPreviewFrameListener(nv21 -> {

                    byte[] jpeg = nv21ToJpeg(nv21, width, height);

                    List<Map<String, Object>> planes = new ArrayList<>();

                    // create Plans
                    Map<String, Object> planeBuffer = new HashMap<>();
                    planeBuffer.put("bytesPerRow", 0);
                    planeBuffer.put("bytesPerPixel", 0);
                    planeBuffer.put("bytes", nv21);
                    planes.add(planeBuffer);

                    final Map<String, Object> imageBuffer = new HashMap<>();
                    imageBuffer.put("width", width);
                    imageBuffer.put("height", height);
                    imageBuffer.put("format", ImageFormat.NV21);
                    imageBuffer.put("jpeg", jpeg);
                    imageBuffer.put("planes", planes);

                    new Handler(Looper.getMainLooper()).post(() -> events.success(imageBuffer));
                });
            }

            @Override
            public void onCancel(Object arguments) {
                uvcCameraHelper.setOnPreviewFrameListener(null);
            }
        });
    }

    private byte[] nv21ToJpeg(byte[] nv21, int width, int height) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        YuvImage image = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        image.compressToJpeg(new Rect(0, 0, width, height), 100, outputStream);
        return outputStream.toByteArray();
    }

    public static byte[] nv21ToYuv420sp(byte[] src, int width, int height) {
        int yLength = width * height;
        int uLength = yLength / 4;
        int vLength = yLength / 4;
        int frameSize = yLength + uLength + vLength;
        byte[] yuv420sp = new byte[frameSize];
        // Y分量
        System.arraycopy(src, 0, yuv420sp, 0, yLength);
        for (int i = 0; i < yLength / 4; i++) {
            // U分量
            yuv420sp[yLength + 2 * i] = src[yLength + 2 * i + 1];
            // V分量
            yuv420sp[yLength + 2 * i + 1] = src[yLength + 2 * i];
        }
        return yuv420sp;
    }

    public void stopPreview() {
        uvcCameraHelper.setOnPreviewFrameListener(null);
    }

    public void pause() {
        if (isPreview && uvcCameraHelper.isCameraOpened()) {
            uvcCameraHelper.stopPreview();
            isPreview = false;
        }
    }

    public void resume() {
        if (!isPreview && uvcCameraHelper.isCameraOpened()) {
            uvcCameraHelper.startPreview(cameraView);
            isPreview = true;
        }
    }

    public void onStop() {
        if (uvcCameraHelper != null) {
            uvcCameraHelper.unregisterUSB();
        }

        cameraView.destroy();
    }

    /**
     * Dispose on flutter
     */
    public void dispose() {
        FileUtils.releaseFile();
        if (uvcCameraHelper != null) {
            uvcCameraHelper.release();
        }

        close();
    }

}
