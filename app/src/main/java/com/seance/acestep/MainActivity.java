package com.seance.acestep;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView web;
    private ExecutorService pool;
    private File audioCacheDir;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pool = Executors.newFixedThreadPool(4);

        // fetched songs are cached here and served back to the page same-origin;
        // safe to wipe on every launch — the page keeps its own copies in IndexedDB
        audioCacheDir = new File(getFilesDir(), "audio");
        if (audioCacheDir.exists()) {
            File[] old = audioCacheDir.listFiles();
            if (old != null) for (File f : old) f.delete();
        } else {
            audioCacheDir.mkdirs();
        }

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/audio/", new WebViewAssetLoader.InternalStoragePathHandler(this, audioCacheDir))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl("https://appassets.androidplatform.net/assets/ace-step-studio-mobile.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void callJs(final String script) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(script, null);
            }
        });
    }

    /**
     * Native bridge. All ACE-Step API traffic goes through here instead of fetch():
     * WebView (Chromium) increasingly blocks "secure page → plain-http LAN device"
     * requests (private-network preflights the server doesn't answer), while requests
     * made from Java are subject to no such web policies.
     */
    private class Bridge {

        /** Generic HTTP for the JSON API. Small payloads only — response returns as base64 via callback. */
        @JavascriptInterface
        public void httpRequest(final String id, final String method, final String url,
                                final String headersJson, final String bodyBase64) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setRequestMethod(method);
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(120000);
                        JSONObject headers = new JSONObject(headersJson);
                        Iterator<String> it = headers.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            c.setRequestProperty(k, headers.getString(k));
                        }
                        if (bodyBase64 != null && bodyBase64.length() > 0) {
                            c.setDoOutput(true);
                            byte[] body = Base64.decode(bodyBase64, Base64.DEFAULT);
                            OutputStream os = c.getOutputStream();
                            os.write(body);
                            os.close();
                        }
                        int status = c.getResponseCode();
                        InputStream in = (status >= 400) ? c.getErrorStream() : c.getInputStream();
                        byte[] resp = readAll(in);
                        c.disconnect();
                        String b64 = Base64.encodeToString(resp, Base64.NO_WRAP);
                        callJs("window.__nativeHttpDone('" + id + "'," + status + ",'" + b64 + "')");
                    } catch (Exception e) {
                        String msg = String.valueOf(e.getMessage()).replace("\\", "").replace("'", "");
                        callJs("window.__nativeHttpDone('" + id + "',0,'')");
                        toastOnUi("请求失败: " + msg);
                    }
                }
            });
        }

        /**
         * Audio files can be many MB — shuttling them through evaluateJavascript as
         * base64 is fragile, so they are downloaded to the app cache instead and
         * served back to the page same-origin under /audio/.
         */
        @JavascriptInterface
        public void downloadToCache(final String id, final String url) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String ext = "mp3";
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("\\.(mp3|wav|flac|opus|aac|ogg|m4a)\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                                .matcher(url);
                        if (m.find()) ext = m.group(1).toLowerCase();
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(300000);
                        int status = c.getResponseCode();
                        if (status < 200 || status >= 300) throw new Exception("HTTP " + status);
                        File out = new File(audioCacheDir, UUID.randomUUID().toString() + "." + ext);
                        InputStream in = c.getInputStream();
                        FileOutputStream fos = new FileOutputStream(out);
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                        fos.close();
                        in.close();
                        c.disconnect();
                        String virtualUrl = "https://appassets.androidplatform.net/audio/" + out.getName();
                        callJs("window.__nativeDlDone('" + id + "',true,'" + virtualUrl + "')");
                    } catch (Exception e) {
                        String msg = String.valueOf(e.getMessage()).replace("\\", "").replace("'", "");
                        callJs("window.__nativeDlDone('" + id + "',false,'" + msg + "')");
                    }
                }
            });
        }

        /** Writes a finished song into the system Downloads collection. */
        @JavascriptInterface
        public void saveFile(final String name, final String base64, final String mime) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("MediaStore insert failed");
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(data);
                os.close();
                toastOnUi("已保存到「下载」：" + name);
            } catch (Exception e) {
                toastOnUi("保存失败: " + e.getMessage());
            }
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private void toastOnUi(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}
