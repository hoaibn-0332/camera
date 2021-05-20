package io.flutter.plugins.camera;

import android.Manifest;
import android.Manifest.permission;
import android.app.Activity;
import android.content.pm.PackageManager;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

final class CameraPermissions {
  interface PermissionsRegistry {
    void addListener(RequestPermissionsResultListener handler);
  }

  interface ResultCallback {
    void onResult(String errorCode, String errorDescription);
  }

  private static final int CAMERA_REQUEST_ID = 9796;
  private boolean ongoing = false;

  void requestPermissions(
      Activity activity,
      PermissionsRegistry permissionsRegistry,
      boolean enableAudio,
      boolean enableStorage,
      ResultCallback callback) {
    if (ongoing) {
      callback.onResult("cameraPermission", "Camera permission request ongoing");
    }
    if (!hasCameraPermission(activity) || (enableAudio && !hasAudioPermission(activity)) || (enableStorage && !hasStoragePermission(activity))) {
      permissionsRegistry.addListener(
          new CameraRequestPermissionsListener(
              (String errorCode, String errorDescription) -> {
                ongoing = false;
                callback.onResult(errorCode, errorDescription);
              }));
      ongoing = true;
      ActivityCompat.requestPermissions(
          activity,
          requestCameraPermissions(enableAudio, enableStorage),
          CAMERA_REQUEST_ID);
    } else {
      // Permissions already exist. Call the callback with success.
      callback.onResult(null, null);
    }
  }

  private String[] requestCameraPermissions(boolean audio, boolean storage) {
    List<String> result = new ArrayList<>();
    result.add(Manifest.permission.CAMERA);
    if (audio) {
      result.add(Manifest.permission.RECORD_AUDIO);
    }

    if (storage) {
      result.add(permission.WRITE_EXTERNAL_STORAGE);
    }

    String[] arrays = new String [result.size()];
    return result.toArray(arrays);
  }

  private boolean hasCameraPermission(Activity activity) {
    return ContextCompat.checkSelfPermission(activity, permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED;
  }

  private boolean hasAudioPermission(Activity activity) {
    return ContextCompat.checkSelfPermission(activity, permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED;
  }

  private boolean hasStoragePermission(Activity activity) {
    return ContextCompat.checkSelfPermission(activity, permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
  }

  @VisibleForTesting
  static final class CameraRequestPermissionsListener
      implements PluginRegistry.RequestPermissionsResultListener {

    // There's no way to unregister permission listeners in the v1 embedding, so we'll be called
    // duplicate times in cases where the user denies and then grants a permission. Keep track of if
    // we've responded before and bail out of handling the callback manually if this is a repeat
    // call.
    boolean alreadyCalled = false;

    final ResultCallback callback;

    @VisibleForTesting
    CameraRequestPermissionsListener(ResultCallback callback) {
      this.callback = callback;
    }

    @Override
    public boolean onRequestPermissionsResult(int id, String[] permissions, int[] grantResults) {
      if (alreadyCalled || id != CAMERA_REQUEST_ID) {
        return false;
      }

      alreadyCalled = true;
      if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
        callback.onResult("cameraPermission", "MediaRecorderCamera permission not granted");
      } else if (grantResults.length > 1 && grantResults[1] != PackageManager.PERMISSION_GRANTED) {
        callback.onResult("cameraPermission", "MediaRecorderAudio permission not granted");
      } else {
        callback.onResult(null, null);
      }
      return true;
    }
  }
}
