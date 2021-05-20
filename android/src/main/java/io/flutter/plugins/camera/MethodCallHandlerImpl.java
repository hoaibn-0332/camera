package io.flutter.plugins.camera;

import android.app.Activity;
import android.hardware.camera2.CameraAccessException;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugins.camera.CameraPermissions.PermissionsRegistry;
import io.flutter.view.TextureRegistry;

final class MethodCallHandlerImpl implements MethodChannel.MethodCallHandler {
  private final Activity activity;
  private final BinaryMessenger messenger;
  private final CameraPermissions cameraPermissions;
  private final PermissionsRegistry permissionsRegistry;
  private final TextureRegistry textureRegistry;
  private final MethodChannel methodChannel;
  private final EventChannel imageStreamChannel;
  private boolean externalCamera = false;

  private @Nullable Camera camera;
  private @Nullable USBCamera usbCamera;

  MethodCallHandlerImpl(
      Activity activity,
      BinaryMessenger messenger,
      CameraPermissions cameraPermissions,
      PermissionsRegistry permissionsAdder,
      TextureRegistry textureRegistry) {
    this.activity = activity;
    this.messenger = messenger;
    this.cameraPermissions = cameraPermissions;
    this.permissionsRegistry = permissionsAdder;
    this.textureRegistry = textureRegistry;

    methodChannel = new MethodChannel(messenger, "plugins.flutter.io/camera");
    imageStreamChannel = new EventChannel(messenger, "plugins.flutter.io/camera/imageStream");
    methodChannel.setMethodCallHandler(this);
  }

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull final Result result) {
    switch (call.method) {
      case "availableCameras":
        try {
          result.success(CameraUtils.getAvailableCameras(activity));
        } catch (Exception e) {
          handleException(e, result);
        }
        break;
      case "initialize":
        {
          initialize(call, result);
          break;
        }
      case "takePicture":
        {
          takePicture(call, result);
          break;
        }
      case "prepareForVideoRecording":
        {
          // This optimization is not required for Android.
          result.success(null);
          break;
        }
      case "startVideoRecording":
        {
          startVideoRecording(call, result);
          break;
        }
      case "stopVideoRecording":
        {
          stopVideoRecording(result);
          break;
        }
      case "pauseVideoRecording":
        {
          pauseVideoRecording(result);
          break;
        }
      case "resumeVideoRecording":
        {
          resumeVideoRecording(result);
          break;
        }
      case "startImageStream":
        {
          startImageStream(result);
          break;
        }
      case "stopImageStream":
        {
          stopImageStream(result);
          break;
        }
      case "dispose":
        {
          dispose(result);
          break;
        }
      default:
        result.notImplemented();
        break;
    }
  }

  private void initialize(MethodCall call, Result result) {
    if (camera != null) {
      camera.close();
    }

    if (usbCamera != null) {
      usbCamera.close();
    }

    if (call.hasArgument("externalCamera")) {
      externalCamera = call.argument("externalCamera");
    }

    cameraPermissions.requestPermissions(
            activity,
            permissionsRegistry,
            call.argument("enableAudio"),
            externalCamera,
            (String errCode, String errDesc) -> {
              if (errCode == null) {
                try {
                  if (externalCamera) {
                    instantiateUSBCamera(call, result);
                  } else {
                    instantiateCamera(call, result);
                  }
                } catch (Exception e) {
                  handleException(e, result);
                }
              } else {
                result.error(errCode, errDesc, null);
              }
            });
  }

  private void takePicture(final MethodCall call, final Result result) {
    if (camera != null) {
      camera.takePicture(call.argument("path"), result);
    }

    if (usbCamera != null) {
      usbCamera.takeCapture(call.argument("path"), result);
    }
  }

  private void startVideoRecording(final MethodCall call, final Result result) {
    if (camera != null) {
      camera.startVideoRecording(call.argument("filePath"), result);
    }
  }

  private void stopVideoRecording(final Result result) {
    if (camera != null) {
      camera.stopVideoRecording(result);
    }
  }

  private void pauseVideoRecording(final Result result) {
    if (camera != null) {
      camera.pauseVideoRecording(result);
    }
  }

  private void resumeVideoRecording(final Result result) {
    if(camera != null) {
      camera.resumeVideoRecording(result);
    }
  }

  private void startImageStream(final Result result) {
    if (camera != null) {
      try {
        camera.startPreviewWithImageStream(imageStreamChannel);
        result.success(null);
      } catch (Exception e) {
        handleException(e, result);
      }
    }

    if (usbCamera !=  null) {
      try {
        usbCamera.startPreviewWithImageStream(imageStreamChannel);
        result.success(null);
      } catch (Exception e) {
        handleException(e, result);
      }
    }
  }

  private void stopImageStream(final Result result) {
    if (camera != null) {
      try {
        camera.startPreview();
        result.success(null);
      } catch (Exception e) {
        handleException(e, result);
      }
    }

    if (usbCamera != null) {
      try {
        usbCamera.stopPreview();
        result.success(null);
      } catch (Exception e) {
        handleException(e, result);
      }
    }
  }

  private void dispose(final Result result) {
    if (camera != null) {
      camera.dispose();
    }

    if (usbCamera != null) {
      usbCamera.dispose();
    }

    result.success(null);
  }

  void onAttachedToActivity() {
  }

  void onDetachedFromActivity() {
    if (usbCamera != null) {
      usbCamera.onStop();
    }
  }

  void stopListening() {
    methodChannel.setMethodCallHandler(null);
  }

  private void instantiateUSBCamera(MethodCall call, Result result) {
    String cameraName = call.argument("cameraName");
    String resolutionPreset = call.argument("resolutionPreset");
    boolean enableAudio = call.argument("enableAudio");
    TextureRegistry.SurfaceTextureEntry flutterSurfaceTexture =
            textureRegistry.createSurfaceTexture();
    DartMessenger dartMessenger = new DartMessenger(messenger, flutterSurfaceTexture.id());
    usbCamera =
            new USBCamera(
                    activity,
                    flutterSurfaceTexture,
                    dartMessenger,
                    resolutionPreset,
                    enableAudio);

    usbCamera.open(result);
  }

  private void instantiateCamera(MethodCall call, Result result) throws CameraAccessException {
    String cameraName = call.argument("cameraName");
    String resolutionPreset = call.argument("resolutionPreset");
    boolean enableAudio = call.argument("enableAudio");
    TextureRegistry.SurfaceTextureEntry flutterSurfaceTexture =
        textureRegistry.createSurfaceTexture();
    DartMessenger dartMessenger = new DartMessenger(messenger, flutterSurfaceTexture.id());
    camera =
        new Camera(
            activity,
            flutterSurfaceTexture,
            dartMessenger,
            cameraName,
            resolutionPreset,
            enableAudio);

    camera.open(result);
  }

  // We move catching CameraAccessException out of onMethodCall because it causes a crash
  // on plugin registration for sdks incompatible with Camera2 (< 21). We want this plugin to
  // to be able to compile with <21 sdks for apps that want the camera and support earlier version.
  @SuppressWarnings("ConstantConditions")
  private void handleException(Exception exception, Result result) {
    if (exception instanceof CameraAccessException) {
      result.error("CameraAccess", exception.getMessage(), null);
      return;
    }

    // CameraAccessException can not be cast to a RuntimeException.
    throw (RuntimeException) exception;
  }
}
