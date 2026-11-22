package pl.wac.agymobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
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
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONObject;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private static final String APP_PAGE = "file:///android_asset/index.html";
    private static final int REQ_AUDIO = 1001;
    private static final int REQ_FILE = 1002;

    private WebView webView;
    private SpeechRecognizer speechRecognizer;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri pendingCameraUri;
    private String pendingLang;
    private final AtomicBoolean downloadingUpdate = new AtomicBoolean();

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
        s.setAllowFileAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.addJavascriptInterface(new VoiceBridge(), "AndroidVoice");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (request.isForMainFrame() && request.hasGesture()
                        && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (android.content.ActivityNotFoundException ignored) {
                    }
                }
                return true;
            }
        });
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

        webView.loadUrl(APP_PAGE);
    }

    private void evalJs(String js) {
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private void voiceError(String message) {
        evalJs("window.__androidVoiceError && window.__androidVoiceError(" + JSONObject.quote(message) + ")");
    }

    private void releaseSpeechRecognizer() {
        if (speechRecognizer != null) {
            SpeechRecognizer current = speechRecognizer;
            speechRecognizer = null;
            current.destroy();
        }
    }

    private void startNativeSpeech(String lang) {
        if (webView == null || pendingLang != null || speechRecognizer != null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLang = lang;
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceError("Brak usługi rozpoznawania mowy");
            return;
        }

        try {
            SpeechRecognizer current = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer = current;
            current.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onEvent(int eventType, Bundle params) {}

                @Override
                public void onPartialResults(Bundle partialResults) {
                    if (speechRecognizer != current) return;
                    ArrayList<String> results = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (results != null && !results.isEmpty()) {
                        evalJs("window.__androidVoicePartial && window.__androidVoicePartial(" + JSONObject.quote(results.get(0)) + ")");
                    }
                }

                @Override
                public void onResults(Bundle finalResults) {
                    if (speechRecognizer != current) return;
                    ArrayList<String> results = finalResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = results != null && !results.isEmpty() ? results.get(0) : "";
                    releaseSpeechRecognizer();
                    evalJs("window.__androidVoiceFinal && window.__androidVoiceFinal(" + JSONObject.quote(text) + ")");
                }

                @Override
                public void onError(int error) {
                    if (speechRecognizer != current) return;
                    releaseSpeechRecognizer();
                    String message = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            ? "Nie rozpoznano mowy" : "Błąd rozpoznawania mowy (" + error + ")";
                    voiceError(message);
                }
            });

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L);
            current.startListening(intent);
        } catch (RuntimeException e) {
            releaseSpeechRecognizer();
            voiceError("Nie udało się uruchomić mikrofonu");
        }
    }

    private void stopNativeSpeech() {
        pendingLang = null;
        if (speechRecognizer == null) {
            voiceError("");
            return;
        }
        try {
            speechRecognizer.stopListening();
        } catch (RuntimeException e) {
            releaseSpeechRecognizer();
            voiceError("Nie udało się zakończyć dyktowania");
        }
    }

    public class VoiceBridge {
        @JavascriptInterface
        public void startListening(String lang) {
            runOnUiThread(() -> startNativeSpeech((lang == null || lang.isEmpty()) ? "pl-PL" : lang));
        }

        @JavascriptInterface
        public void stopListening() {
            runOnUiThread(MainActivity.this::stopNativeSpeech);
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

        @JavascriptInterface
        public int getAppVersionCode() {
            return BuildConfig.VERSION_CODE;
        }

        @JavascriptInterface
        public String getAppVersionName() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public void installUpdate(String apkUrl) {
            if (!downloadingUpdate.compareAndSet(false, true)) return;
            new Thread(() -> {
                try {
                    evalJs("window.__updateProgress && window.__updateProgress(1, 'Pobieranie...')");
                    File dir = new File(getCacheDir(), "update");
                    if (!dir.exists()) dir.mkdirs();
                    File apk = new File(dir, "AGY_Mobile.apk");

                    URL url = new URL(apkUrl);
                    String releasePrefix = "/miki200xd/agy-mobile/releases/download/";
                    if (!"https".equals(url.getProtocol()) || !"github.com".equalsIgnoreCase(url.getHost())
                            || url.getUserInfo() != null || url.getPort() != -1
                            || !url.getPath().startsWith(releasePrefix) || !url.getPath().endsWith("/AGY_Mobile.apk")) {
                        throw new IllegalArgumentException("Nieprawidłowy adres wydania AGY Mobile");
                    }
                    HttpURLConnection conn = openReleaseDownload(url);
                    try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                        long total = conn.getContentLengthLong();
                        byte[] buf = new byte[16384];
                        long read = 0;
                        int lastPct = 1;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            read += n;
                            if (read > 80L * 1024 * 1024) throw new IllegalStateException("Plik APK jest zbyt duży");
                            out.write(buf, 0, n);
                            if (total > 0) {
                                int pct = (int) Math.min(99, (read * 100) / total);
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    evalJs("window.__updateProgress && window.__updateProgress(" + pct + ", 'Pobieranie " + pct + "%')");
                                }
                            }
                        }
                        if (total > 0 && read != total) throw new IllegalStateException("Niepełny plik APK");
                    } finally {
                        conn.disconnect();
                    }
                    PackageInfo update = getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
                    if (update == null || !getPackageName().equals(update.packageName)
                            || update.versionCode <= BuildConfig.VERSION_CODE) {
                        throw new IllegalStateException("Plik APK nie zawiera nowszej wersji AGY Mobile");
                    }

                    evalJs("window.__updateProgress && window.__updateProgress(100, 'Uruchamianie instalatora...')");
                    Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", apk);
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, "application/vnd.android.package-archive");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    String msg = (e.getMessage() != null) ? e.getMessage() : "Błąd pobierania";
                    evalJs("window.__updateError && window.__updateError(" + JSONObject.quote(msg) + ")");
                } finally {
                    downloadingUpdate.set(false);
                }
            }).start();
        }
    }

    private HttpURLConnection openReleaseDownload(URL url) throws java.io.IOException {
        for (int redirects = 0; redirects < 4; redirects++) {
            String host = url.getHost();
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null || url.getPort() != -1
                    || (!"github.com".equalsIgnoreCase(host)
                        && !"release-assets.githubusercontent.com".equalsIgnoreCase(host)
                        && !"objects.githubusercontent.com".equalsIgnoreCase(host))) {
                throw new java.io.IOException("Nieprawidłowe przekierowanie wydania AGY Mobile");
            }
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_OK) return conn;
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (status < 300 || status >= 400 || location == null) {
                throw new java.io.IOException("Pobranie aktualizacji nie powiodło się (HTTP " + status + ")");
            }
            url = new URL(url, location);
        }
        throw new java.io.IOException("Zbyt wiele przekierowań podczas aktualizacji");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_AUDIO || pendingLang == null) return;
        String lang = pendingLang;
        pendingLang = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startNativeSpeech(lang);
        } else {
            voiceError("Brak uprawnienia do mikrofonu");
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
        pendingLang = null;
        releaseSpeechRecognizer();
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidVoice");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
