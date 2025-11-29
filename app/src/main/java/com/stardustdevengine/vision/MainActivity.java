package com.stardustdevengine.vision;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.WindowManager;

import com.stardustdevengine.vision.databinding.ActivityMainBinding;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "JNI_DEBUG";
    private ActivityMainBinding binding;
    private PreviewView previewView;
    private CameraXManager cameraManager;
    private LoadYoloModel loadYoloModel;
    private SpeechManager speechManager;

    // Cargar librerías nativas
    static {
        try {
            System.loadLibrary("opencv_java4"); //Libreria de C++ OpenCV para procesar imagenes con modelo de IA
            System.loadLibrary("vision3"); // Libreria de C++ Nativa para usar con JNI
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Could not load libraries", e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Ocultar ActionBar si existe
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        configureScreen();

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(binding.getRoot());

        previewView = binding.previewView;
        cameraManager = new CameraXManager(
                this,
                this,
                previewView,
                binding.overlayView,
                null,
                BuildConfig.APP_LANGUAGE);

        loadYoloModel = new LoadYoloModel(BuildConfig.APP_LANGUAGE);
        speechManager = SpeechManager.getInstance(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        checkPermissionsSequentially();
        onBackPress();
    }

    private void checkPermissionsSequentially() {
        if (!PermissionManager.checkCameraPermission(this)) return;
        if (!PermissionManager.checkAudioPermission(this)) return;

        // Todos los permisos concedidos → iniciar app
        startApp();
    }

    private void startApp() {
        loadYoloModel.loadModel(cameraManager, this, TAG);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanupAndExit();
    }

    @Override
    protected void onStop() {
        super.onStop();
        cleanupAndExit();
    }

    public void onBackPress() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {

                    speechManager = SpeechManager.getInstance(MainActivity.this);
                    TextToSpeech tts = speechManager.getTTS();
                    // Listener temporal
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) { }

                        @Override
                        public void onDone(String utteranceId) {
                            runOnUiThread(() -> cleanupAndExit());
                        }

                        @Override
                        public void onError(String utteranceId) {
                            runOnUiThread(() -> cleanupAndExit());
                        }
                    });
                // Preparamos params
                Bundle params = new Bundle();
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "AppExit");
                tts.speak(
                        InitialInstructions.getTTSMessage(InitialInstructions.MessageTypes.EXIT),
                        tts.QUEUE_FLUSH,
                        params,
                        "AppExit"
                );

            }
        });
    }

    public void cleanupAndExit() {
        if (speechManager != null) {
            speechManager.stopSpeaking();
            speechManager.shutdown();
            speechManager = null;
        }
        if (cameraManager != null) {
            cameraManager.stopCamera();
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (PermissionManager.handlePermissionResult(requestCode, grantResults)) {
            // Avanzar al siguiente permiso o iniciar app si ya todos concedidos
            checkPermissionsSequentially();
        } else {
            // Algún permiso denegado → cerrar app
            finishAffinity();
        }
    }

    private void configureScreen() {
        // Fullscreen: ocultar status bar y navigation bar
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat insetsController = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        insetsController.hide(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
        insetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
}
