package pl.wac.agymobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONObject;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_AUDIO = 1001;
    private static final int REQ_FILE = 1002;

    private WebView webView;
    private SpeechRecognizer speechRecognizer;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri pendingCameraUri;
    private String pendingLang = "pl-PL";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#1a1917"));
        w.setNavigationBarColor(Color.parseColor("#1a1917"));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#1a1917"));
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.addJavascriptInterface(new VoiceBridge(), "AndroidVoice");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                pendingCameraUri = null;

                if (params.isCaptureEnabled()) {
                    try {
                        File camDir = new File(getCacheDir(), "camera");
                        if (!camDir.exists()) camDir.mkdirs();
                        File photo = new File(camDir, "foto_" + System.currentTimeMillis() + ".jpg");
                        pendingCameraUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", photo);
                        Intent camIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        camIntent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
                        camIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivityForResult(camIntent, REQ_FILE);
                        return true;
                    } catch (Exception e) {
                        pendingCameraUri = null;
                    }
                }

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                String[] accept = params.getAcceptTypes();
                String mime = (accept != null && accept.length > 0 && accept[0] != null && !accept[0].isEmpty()) ? accept[0] : "*/*";
                intent.setType(mime);
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(intent, "Wybierz plik lub zdjęcie"), REQ_FILE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void evalJs(String js) {
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void startNativeSpeech(String lang) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLang = lang;
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        runOnUiThread(() -> {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                evalJs("window.__androidVoiceError && window.__androidVoiceError('Brak usługi rozpoznawania mowy Google')");
                return;
            }
            if (speechRecognizer != null) {
                speechRecognizer.destroy();
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onEvent(int eventType, Bundle params) {}

                @Override
                public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) {
                        String q = JSONObject.quote(list.get(0));
                        evalJs("window.__androidVoicePartial && window.__androidVoicePartial(" + q + ")");
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String txt = (list != null && !list.isEmpty()) ? list.get(0) : "";
                    evalJs("window.__androidVoiceFinal && window.__androidVoiceFinal(" + JSONObject.quote(txt) + ")");
                }

                @Override
                public void onError(int error) {
                    String msg = (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                            ? "" : ("Kod błędu mowy: " + error);
                    evalJs("window.__androidVoiceError && window.__androidVoiceError(" + JSONObject.quote(msg) + ")");
                }
            });

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L);
            speechRecognizer.startListening(intent);
        });
    }

    private void stopNativeSpeech() {
        runOnUiThread(() -> {
            if (speechRecognizer != null) {
                speechRecognizer.stopListening();
            }
        });
    }

    public class VoiceBridge {
        @JavascriptInterface
        public void startListening(String lang) {
            startNativeSpeech((lang == null || lang.isEmpty()) ? "pl-PL" : lang);
        }

        @JavascriptInterface
        public void stopListening() {
            stopNativeSpeech();
        }

        @JavascriptInterface
        public void scanQr() {
            runOnUiThread(() -> {
                try {
                    GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .enableAutoZoom()
                            .build();
                    GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(MainActivity.this, options);
                    scanner.startScan()
                            .addOnSuccessListener(barcode -> {
                                String raw = barcode.getRawValue();
                                evalJs("window.__androidQrResult && window.__androidQrResult(" + JSONObject.quote(raw != null ? raw : "") + ")");
                            })
                            .addOnFailureListener(e -> {
                                String msg = (e != null && e.getMessage() != null) ? e.getMessage() : "Nie udało się uruchomić skanera QR";
                                evalJs("window.__androidQrError && window.__androidQrError(" + JSONObject.quote(msg) + ")");
                            });
                } catch (Exception e) {
                    String msg = (e.getMessage() != null) ? e.getMessage() : "Błąd skanera QR";
                    evalJs("window.__androidQrError && window.__androidQrError(" + JSONObject.quote(msg) + ")");
                }
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startNativeSpeech(pendingLang);
        } else if (requestCode == REQ_AUDIO) {
            evalJs("window.__androidVoiceError && window.__androidVoiceError('Brak uprawnienia do mikrofonu')");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && filePathCallback != null) {
            Uri[] result = null;
            if (resultCode == Activity.RESULT_OK) {
                if (pendingCameraUri != null) {
                    result = new Uri[]{pendingCameraUri};
                } else if (data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        result = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            result[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        result = new Uri[]{data.getData()};
                    }
                }
            }
            pendingCameraUri = null;
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (speechRecognizer != null) speechRecognizer.destroy();
        super.onDestroy();
    }
}
