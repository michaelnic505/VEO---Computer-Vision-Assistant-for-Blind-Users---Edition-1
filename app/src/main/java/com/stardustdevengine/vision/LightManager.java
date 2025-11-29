package com.stardustdevengine.vision;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.camera.core.Camera;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

public class LightManager {
    private static final String TAG = "LightManager";
    private static final float SENSOR_THRESHOLD = 11f;      // Lux threshold
    private static final float BRIGHTNESS_THRESHOLD = 11f;  // Average Y brightness threshold

    private final SensorManager sensorManager;
    private final Sensor lightSensor;
    private final Context context;
    private Camera camera; // Reference to CameraX Camera object
    private boolean hasLightSensor = false;
    private String torchId = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable delayedTurnOffRunnable = null;
    private boolean isTorchOn = false; // estado actual de la linterna

    private final SensorEventListener lightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            float lux = event.values[0];
            Log.d(TAG, "Lux: " + lux);
            if (lux < SENSOR_THRESHOLD) {
                turnFlashOn();
            } else {
                turnFlashOff();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };
    

    public LightManager(Context context) {
        this.context = context;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) : null;
        hasLightSensor = (lightSensor != null);
    }

    public boolean hasLightSensor(){
        return hasLightSensor;
    }

    // Start listening for ambient light sensor changes
    public void start() {
        if (hasLightSensor) {
            sensorManager.registerListener(lightListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
            Log.d(TAG, "Light sensor listener started");
        } else {
            Log.w(TAG, "No light sensor available, falling back to CameraX brightness");
        }
    }

    // Stop the light sensor listener
    public void stop() {
        if (hasLightSensor) {
            sensorManager.unregisterListener(lightListener);
            turnFlashOff();
            Log.d(TAG, "Light sensor listener stopped");
        }
    }

    // Fallback: calculate brightness from CameraX frames
    public void analyzeFrame(ImageProxy image) {
        if (hasLightSensor) return; // if sensor exists, no need for fallback
        if (camera == null) return; // skip until camera is assigned

        float brightness = calculateAverageBrightness(image);
        Log.d(TAG, "Average brightness: " + brightness);

        if (brightness < BRIGHTNESS_THRESHOLD) {
            turnFlashOn();
        } else {
            turnFlashOff();
        }
        image.close();
    }

    private float calculateAverageBrightness(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer(); // Y plane
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);

        long sum = 0;
        for (byte b : data) {
            sum += (b & 0xFF); // 0–255
        }
        return (float) sum / data.length;
    }

    // Set CameraX camera reference
    public void setCamera(Camera camera) {
        this.camera = camera;
    }

    public void turnFlashOn() {
        Log.d(TAG, "Attempt to turn Torch ON");

        if (torchId == null) initTorchId();
        if (torchId == null) return;

        try {
            CameraManager systemCameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            // Cancelamos apagado pendiente si existe
            if (delayedTurnOffRunnable != null) {
                handler.removeCallbacks(delayedTurnOffRunnable);
                delayedTurnOffRunnable = null;
                Log.d(TAG, "Cancelled pending torch OFF");
            }

            // Solo encendemos si no está encendida
            if (!isTorchOn) {
                systemCameraManager.setTorchMode(torchId, true);
                isTorchOn = true;
                Log.d(TAG, "Torch ON via system CameraManager");
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to turn torch ON", e);
        }
    }

    public void turnFlashOff() {
        Log.d(TAG, "Attempt to turn Torch OFF");

        if (!isTorchOn) return;

        // Cancelamos cualquier apagado anterior
        if (delayedTurnOffRunnable != null) {
            handler.removeCallbacks(delayedTurnOffRunnable);
        }

        isTorchOn = false;

        delayedTurnOffRunnable = new Runnable() {
            @Override
            public void run() {
                if (torchId == null) initTorchId();
                if (torchId == null) return;

                try {
                    CameraManager systemCameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                    systemCameraManager.setTorchMode(torchId, false);
                    isTorchOn = false; // actualizar estado al apagar
                    Log.d(TAG, "Torch OFF via system CameraManager");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to turn torch OFF", e);
                } finally {
                    delayedTurnOffRunnable = null;
                }
            }
        };

        handler.postDelayed(delayedTurnOffRunnable, 500);
    }
    private void initTorchId() {
        if (torchId != null) return; // ya inicializado

        try {
            CameraManager systemCameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            for (String id : systemCameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = systemCameraManager.getCameraCharacteristics(id);
                Boolean hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (hasFlash != null && hasFlash && lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchId = id;
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get torch ID", e);
        }
    }


}
