package com.stardustdevengine.vision;

import android.content.Context;
import android.util.Log;
import android.util.Size;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

public class CameraXManager {
    private static final String TAG = "CameraManager";  // Etiqueta para logs en Logcat
    private final LifecycleOwner lifecycleOwner;        // Ciclo de vida (ej: Activity/Fragment) para controlar CameraX
    private final PreviewView previewView;              // Vista donde se muestra la cámara
    private final OverlayView overlayView;              // Vista personalizada donde se dibujan las detecciones
    private ExecutorService analysisExecutor;           // Hilo separado para procesar frames (evita bloquear UI)
    private ExecutorService inferenceExecutor;          // Hilo para inferencia
    private FrameAnalyzer frameAnalyzer;
    private InferenceRunnable inferenceRunnable;
    private DetectionControl detectionControl;
    private Context context;
    private DetectionSpeaker detectionSpeaker;
    private LightManager lightManager;
    private Camera camera;
    private String language;


    private final BlockingQueue<ImageData> frameQueue = new LinkedBlockingQueue<>(1);// Cola concurrente para los frames

    // Constructor: inicializa con lifecycleOwner, vista de cámara y overlay de detecciones
    public CameraXManager(LifecycleOwner lifecycleOwner, Context context,
                          PreviewView previewView,
                          OverlayView overlayView,
                          VoiceCommandHelper voiceHelper,
                          String language) {
        this.lifecycleOwner = lifecycleOwner;
        this.previewView = previewView;
        this.overlayView = overlayView;
        this.context = context;
        this.frameAnalyzer = new FrameAnalyzer(frameQueue);
        this.detectionSpeaker = new DetectionSpeaker(context,language);
        this.overlayView.setDetectionSpeaker(detectionSpeaker);
        this.lightManager = new LightManager(context);
        this.language = language;
    }

    public void startCamera() {

        analysisExecutor = Executors.newSingleThreadExecutor();// Crea un hilo único para análisis de imágenes
        inferenceExecutor = Executors.newSingleThreadExecutor();  // Hilo para inferencia JNI

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture
                = ProcessCameraProvider.getInstance(previewView.getContext());// Obtiene instancia de CameraX de forma asíncrona

        cameraProviderFuture.addListener(() -> {

            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();// Obtiene instancia de CameraX

                Preview preview = new Preview.Builder().build();// Crea la configuración de la vista previa
                preview.setSurfaceProvider(previewView.getSurfaceProvider());// Conecta la vista de la cámara con el PreviewView

                CameraSelector cameraSelector = ListCameraSelector.getBestCameraSelector(cameraProvider);
                ImageAnalysis imageAnalysis = getImageAnalysis();// Obtiene configuración de análisis de imágenes
                SensorCameraInfo info = ListCameraSelector.getSensorCameraInfo();

                if (inferenceRunnable == null) {
                    inferenceRunnable = new InferenceRunnable(frameQueue, overlayView, info);
                    detectionControl = new DetectionControl(
                            context,
                            inferenceRunnable,
                            detectionSpeaker,
                            null);
                    previewView.setOnClickListener(detectionControl.getToggleListener());
                }
                cameraProvider.unbindAll();// Desvincula cualquier uso previo de la cámara

                camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                );

                imageAnalysis.setAnalyzer(analysisExecutor,
                        new CombinedFrameAnalyzer(
                                frameQueue,
                                lightManager));

                overlayView.setCountry(BuildConfig.APP_LANGUAGE);

                // Give camera reference to LightManager so it can toggle the torch
                lightManager.setCamera(camera);
                detectionControl.setLightManager(lightManager);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting CameraX", e);// Captura errores al iniciar CameraX
            }

        }, ContextCompat.getMainExecutor(previewView.getContext()));// Ejecuta el listener en el hilo principal
    }

    private ImageAnalysis getImageAnalysis() {
        // Configuración para procesar imágenes
        return new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 640))  // Resolución objetivo del frame
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)// Solo mantiene el último frame (descarta los viejos)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)// Formato de salida de la imagen
                .setOutputImageRotationEnabled(false)// No aplicar rotación automática (se maneja manualmente en JNI)
                .setImageQueueDepth(1)// Máximo 1 frame en cola
                .build();
    }
    public void stopCamera() {
        if (analysisExecutor != null && !analysisExecutor.isShutdown()) {
            analysisExecutor.shutdownNow();
            lightManager.stop();
            camera = null;

        }
        if (inferenceExecutor != null && !inferenceExecutor.isShutdown()) {
            inferenceExecutor.shutdownNow();
            lightManager.stop();
            camera = null;
        }
    }

    public void stopDetection(){
        detectionControl.stopDetection();
    }
    public OverlayView getOverlayView() {
        return overlayView;// Devuelve la vista overlay para acceder desde fuera
    }
    public native void nativeLoadModel(String modelPath);// Carga el modelo en JNI junto con los nombres de las clases
}

