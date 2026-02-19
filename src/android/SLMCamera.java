package com.slm.camera;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class SLMCamera extends CordovaPlugin {

    private static final String TAG = "SLMCamera";
    private static final int REQUEST_TAKE_PICTURE = 9001;
    private static final int REQUEST_CHOOSE_GALLERY = 9002;
    private static final int PERMISSION_REQUEST_CAMERA = 9003;
    private static final int PERMISSION_REQUEST_GALLERY = 9004;

    private CallbackContext currentCallbackContext;
    private JSONObject currentOptions;
    private Uri photoUri;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        switch (action) {
            case "takePicture":
                currentCallbackContext = callbackContext;
                currentOptions = args.optJSONObject(0) != null ? args.getJSONObject(0) : new JSONObject();
                takePicture();
                return true;
            case "chooseFromGallery":
                currentCallbackContext = callbackContext;
                currentOptions = args.optJSONObject(0) != null ? args.getJSONObject(0) : new JSONObject();
                chooseFromGallery();
                return true;
            case "cleanup":
                cleanup(callbackContext);
                return true;
            default:
                return false;
        }
    }

    // ============================================
    // takePicture
    // ============================================

    private void takePicture() {
        if (!cordova.hasPermission(Manifest.permission.CAMERA)) {
            cordova.requestPermission(this, PERMISSION_REQUEST_CAMERA, Manifest.permission.CAMERA);
            return;
        }

        try {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            File photoFile = createTempFile("jpg");
            photoUri = FileProvider.getUriForFile(
                    cordova.getActivity(),
                    cordova.getActivity().getPackageName() + ".slm.camera.provider",
                    photoFile);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            cordova.startActivityForResult(this, intent, REQUEST_TAKE_PICTURE);
        } catch (Exception e) {
            Log.e(TAG, "takePicture error: " + e.getMessage());
            currentCallbackContext.error("Error abriendo camara: " + e.getMessage());
        }
    }

    // ============================================
    // chooseFromGallery
    // ============================================

    private void chooseFromGallery() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (!cordova.hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) {
                cordova.requestPermission(this, PERMISSION_REQUEST_GALLERY, Manifest.permission.READ_MEDIA_IMAGES);
                return;
            }
        } else if (!cordova.hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            cordova.requestPermission(this, PERMISSION_REQUEST_GALLERY, Manifest.permission.READ_EXTERNAL_STORAGE);
            return;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            cordova.startActivityForResult(this, intent, REQUEST_CHOOSE_GALLERY);
        } catch (Exception e) {
            Log.e(TAG, "chooseFromGallery error: " + e.getMessage());
            currentCallbackContext.error("Error abriendo galeria: " + e.getMessage());
        }
    }

    // ============================================
    // cleanup
    // ============================================

    private void cleanup(CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                File cacheDir = new File(cordova.getActivity().getCacheDir(), "slm_camera");
                int cleaned = 0;
                if (cacheDir.exists()) {
                    File[] files = cacheDir.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (file.delete()) cleaned++;
                        }
                    }
                }

                JSONObject info = new JSONObject();
                info.put("cleaned", cleaned);
                callbackContext.success(info);
            } catch (Exception e) {
                Log.e(TAG, "cleanup error: " + e.getMessage());
                callbackContext.error("Error limpiando archivos: " + e.getMessage());
            }
        });
    }

    // ============================================
    // Activity Result
    // ============================================

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (currentCallbackContext == null) return;

        if (resultCode != Activity.RESULT_OK) {
            currentCallbackContext.error("Usuario cancelo la captura");
            return;
        }

        cordova.getThreadPool().execute(() -> {
            try {
                Bitmap bitmap = null;
                String sourcePath = null;

                if (requestCode == REQUEST_TAKE_PICTURE && photoUri != null) {
                    InputStream inputStream = cordova.getActivity().getContentResolver().openInputStream(photoUri);
                    bitmap = BitmapFactory.decodeStream(inputStream);
                    if (inputStream != null) inputStream.close();
                    sourcePath = photoUri.getPath();
                } else if (requestCode == REQUEST_CHOOSE_GALLERY && data != null && data.getData() != null) {
                    Uri imageUri = data.getData();
                    InputStream inputStream = cordova.getActivity().getContentResolver().openInputStream(imageUri);
                    bitmap = BitmapFactory.decodeStream(inputStream);
                    if (inputStream != null) inputStream.close();
                }

                if (bitmap == null) {
                    currentCallbackContext.error("No se pudo decodificar la imagen");
                    return;
                }

                processImage(bitmap, sourcePath);

            } catch (Exception e) {
                Log.e(TAG, "onActivityResult error: " + e.getMessage());
                currentCallbackContext.error("Error procesando imagen: " + e.getMessage());
            }
        });
    }

    // ============================================
    // Permission Result
    // ============================================

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == PERMISSION_REQUEST_CAMERA) {
                takePicture();
            } else if (requestCode == PERMISSION_REQUEST_GALLERY) {
                chooseFromGallery();
            }
        } else {
            if (currentCallbackContext != null) {
                currentCallbackContext.error("Permiso denegado");
            }
        }
    }

    // ============================================
    // Image Processing
    // ============================================

    private void processImage(Bitmap originalBitmap, String sourcePath) {
        try {
            Bitmap bitmap = originalBitmap;

            // Corregir orientacion
            boolean correctOrientation = currentOptions.optBoolean("correctOrientation", true);
            if (correctOrientation && sourcePath != null) {
                bitmap = fixOrientation(bitmap, sourcePath);
            }

            // Resize
            int targetWidth = currentOptions.optInt("targetWidth", 0);
            int targetHeight = currentOptions.optInt("targetHeight", 0);
            if (targetWidth > 0 || targetHeight > 0) {
                bitmap = resizeBitmap(bitmap, targetWidth, targetHeight);
            }

            // Encoding
            int quality = currentOptions.optInt("quality", 85);
            int encodingType = currentOptions.optInt("encodingType", 0);
            String returnType = currentOptions.optString("returnType", "base64");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            String format;

            if (encodingType == 1) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
                format = "png";
            } else {
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
                format = "jpeg";
            }

            byte[] imageBytes = baos.toByteArray();

            JSONObject response = new JSONObject();
            response.put("width", bitmap.getWidth());
            response.put("height", bitmap.getHeight());
            response.put("format", format);

            if ("fileURI".equals(returnType)) {
                File file = createTempFile(format.equals("png") ? "png" : "jpg");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                fos.write(imageBytes);
                fos.close();
                response.put("imageData", "file://" + file.getAbsolutePath());
            } else {
                response.put("imageData", Base64.encodeToString(imageBytes, Base64.NO_WRAP));
            }

            // Guardar en galeria si es necesario
            if (currentOptions.optBoolean("saveToGallery", false)) {
                MediaStore.Images.Media.insertImage(
                        cordova.getActivity().getContentResolver(),
                        bitmap, "SLM_Camera_" + System.currentTimeMillis(), "");
            }

            currentCallbackContext.success(response);
        } catch (Exception e) {
            Log.e(TAG, "processImage error: " + e.getMessage());
            currentCallbackContext.error("Error procesando imagen: " + e.getMessage());
        }
    }

    private Bitmap fixOrientation(Bitmap bitmap, String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);

            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    matrix.postRotate(90);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    matrix.postRotate(180);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    matrix.postRotate(270);
                    break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    matrix.preScale(-1.0f, 1.0f);
                    break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    matrix.preScale(1.0f, -1.0f);
                    break;
                default:
                    return bitmap;
            }

            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        } catch (IOException e) {
            Log.e(TAG, "fixOrientation error: " + e.getMessage());
            return bitmap;
        }
    }

    private Bitmap resizeBitmap(Bitmap bitmap, int targetWidth, int targetHeight) {
        int origWidth = bitmap.getWidth();
        int origHeight = bitmap.getHeight();

        int newWidth = targetWidth;
        int newHeight = targetHeight;

        if (targetWidth > 0 && targetHeight > 0) {
            newWidth = targetWidth;
            newHeight = targetHeight;
        } else if (targetWidth > 0) {
            float ratio = (float) targetWidth / origWidth;
            newWidth = targetWidth;
            newHeight = (int) (origHeight * ratio);
        } else if (targetHeight > 0) {
            float ratio = (float) targetHeight / origHeight;
            newHeight = targetHeight;
            newWidth = (int) (origWidth * ratio);
        } else {
            return bitmap;
        }

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
    }

    private File createTempFile(String extension) throws IOException {
        File cacheDir = new File(cordova.getActivity().getCacheDir(), "slm_camera");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        return File.createTempFile("slm_camera_", "." + extension, cacheDir);
    }
}
