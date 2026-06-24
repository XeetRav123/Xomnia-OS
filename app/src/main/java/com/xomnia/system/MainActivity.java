package com.xomnia.system;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.widget.FrameLayout;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    WebView web;
    // ── Встроенный браузер — второй WebView, накладывается поверх основного.
    // Создаётся лениво (при первом открытии окна браузера в JS), живёт пока
    // не закроют окно браузера — переиспользуется при повторных открытиях.
    // Раньше было единственное поле WebView browserWeb — один общий нативный
    // WebView для ВСЕХ окон браузера, из-за чего открытие второго окна
    // браузера просто перемещало тот же самый WebView на новую позицию,
    // а первое окно оставалось пустым (тот же URL дублировался визуально).
    // Теперь каждое HTML-окно браузера получает свой собственный WebView,
    // привязанный к уникальному browserId, который генерирует JS при
    // создании окна. MAX_EMBEDDED_BROWSERS — защита от истощения памяти на
    // слабых устройствах, если пользователь нашпигует десктоп браузерами.
    java.util.Map<String, WebView> browserWebViews = new java.util.HashMap<String, WebView>();
    // Отслеживает, играет ли видео в каждом конкретном окне браузера —
    // заполняется через JsBridge.notifyVideoPlaybackState (см. инъекцию в
    // onPageFinished). Нужно для реактивного приглушения звука видео-обоев:
    // если хотя бы одно окно сообщает "играет", общий сигнал — "видео где-то
    // в браузере играет", независимо от того, сколько окон открыто всего.
    java.util.Map<String, Boolean> browserVideoPlayingState = new java.util.HashMap<String, Boolean>();
    static final int MAX_EMBEDDED_BROWSERS = 4;
    String lastActiveBrowserId = null; // для системной кнопки Back — какой браузер сейчас "активен"
    FrameLayout rootContainer;
    // ── Полноэкранное HTML5-видео (например YouTube) — WebChromeClient
    // подменяет содержимое экрана на customView пока видео в fullscreen.
    View fullscreenCustomView;
    android.webkit.WebChromeClient.CustomViewCallback fullscreenCallback;
    // browserId окна, из которого пришёл текущий активный fullscreen-запрос —
    // нужен, чтобы updateEmbeddedBrowserBounds мог синхронизировать геометрию
    // fullscreenCustomView вместе с самим окном при его перетаскивании или
    // изменении размера (без этого видео "замораживалось" на координатах
    // момента входа в fullscreen и окно становилось неотзывчивым на drag/resize).
    String fullscreenSourceBrowserId;
    // Минимальный размер (в dp) для customView в режиме "fullscreen в рамках
    // окна" — ниже этого порога видеоповерхность (Surface) теряет валидность
    // для декодера, и видео полностью зависает (картинка и звук), не
    // восстанавливаясь даже после увеличения размера окна обратно.
    private static final float MIN_FULLSCREEN_VIDEO_DP = 240f;
    // Отдельный конвертер dp→px прямо в MainActivity — аналогичный метод
    // внутри JsBridge (см. ниже) недоступен отсюда, так как объявлен в
    // другом (вложенном) классе.
    private float dpToPxLocal(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
    private static final int SAF_PICK_TREE_CODE = 2001;
    private static final int SAF_PICK_WALLPAPER_FILE_CODE = 2002;
    private static final int VOICE_INPUT_REQUEST_CODE = 2003;
    private static final int SAF_PICK_STORE_FILE_CODE = 2004;
    private static final int SAF_PICK_PROFILE_PHOTO_CODE = 2005;
    private static final String PREFS_NAME = "xomnia_prefs";
    // Прокси без авторизации, HTTP/HTTPS CONNECT — применяется ко всему
    // сетевому трафику приложения: и к нативным запросам (httpGet/httpPost,
    // включая обращения GODY к Anthropic API), и к встроенному браузеру
    // (через перехват запросов в shouldInterceptRequest).
    // Несколько именованных слотов — пользователь сам вписывает host/port
    // своих серверов (свой VPS, арендованный прокси и т.п.) и переключается
    // между ними. Хранится как JSON-массив [{"name":"Server 1","host":"...","port":N}, ...]
    // под одним ключом, плюс отдельно индекс активного слота (-1 = прокси выключен).
    private static final String PREF_PROXY_SLOTS = "proxy_slots";
    private static final String PREF_PROXY_ACTIVE_INDEX = "proxy_active_index";
    private static final String PREF_TREES = "saf_tree_uris"; // JSON-массив строк URI
    private final ExecutorService netExecutor = Executors.newFixedThreadPool(4);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        rootContainer = new FrameLayout(this);

        web = new WebView(this);

        // Включаем удалённую отладку WebView — позволяет подключиться через
        // chrome://inspect/#devices на компьютере (по USB) и увидеть точную
        // причину сетевой ошибки в консоли DevTools вместо общего "Failed to fetch"
        WebView.setWebContentsDebuggingEnabled(true);

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.getSettings().setAllowFileAccessFromFileURLs(true);
        web.getSettings().setAllowUniversalAccessFromFileURLs(true);
        web.getSettings().setMixedContentMode(
            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        );

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onPermissionRequest(android.webkit.PermissionRequest request) {
                request.grant(request.getResources());
            }
        });
        web.setScrollBarStyle(WebView.SCROLLBARS_INSIDE_OVERLAY);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);

        rootContainer.addView(web, new FrameLayout.LayoutParams(
								  FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
							  ));

        setContentView(rootContainer);
        hideUI();

        web.addJavascriptInterface(new JsBridge(this), "Android");

        // ── Проверяем, есть ли скачанное обновление во внутреннем хранилище ──
        // assets/ — read-only внутри APK, но getFilesDir() — writable даже
        // без разрешений (приватное хранилище приложения). Если пользователь
        // скачал обновление через Store → Обновления, оно сохранено сюда
        // через saveUpdateFile() ниже, и при следующем старте грузится
        // автоматически вместо встроенного assets/index.html.
        // Так работает "самообновление" WebView-приложения без пересборки APK.
        java.io.File updateFile = new java.io.File(getFilesDir(), "xomnia_update.html");
        if (updateFile.exists()) {
            // Грузим через loadDataWithBaseURL, а не loadUrl — нужен baseURL
            // file:///android_asset/ чтобы относительные пути к ресурсам
            // (звуки, картинки из assets) по-прежнему работали корректно.
            try {
                java.io.FileInputStream fis = new java.io.FileInputStream(updateFile);
                byte[] bytes = new byte[(int) updateFile.length()];
                fis.read(bytes);
                fis.close();
                String html = new String(bytes, "UTF-8");
                web.loadDataWithBaseURL(
                    "file:///android_asset/",
                    html,
                    "text/html",
                    "UTF-8",
                    null
                );
            } catch (Exception e) {
                // Если файл обновления повреждён — грузим оригинал из assets
                // и удаляем повреждённый файл, чтобы не застрять навсегда
                updateFile.delete();
                web.loadUrl("file:///android_asset/index.html");
            }
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    // ── onResume — вызывается когда пользователь возвращается из Settings ──
    @Override
    protected void onResume() {
        super.onResume();
        hideUI();
        // Уведомляем JS что разрешения могли измениться
        if (web != null) {
            web.post(new Runnable() {
					public void run() {
						web.evaluateJavascript(
							"if(typeof onPermissionResult==='function') onPermissionResult();",
							null
						);
					}
				});
        }
    }

    // ── onActivityResult — результат выбора папки через SAF ──────────────
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == SAF_PICK_TREE_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri treeUri = data.getData();
                if (treeUri != null) {
                    // Получаем долговременное разрешение на этот URI
                    int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    try {
                        getContentResolver().takePersistableUriPermission(treeUri, flags);
                    } catch (Exception e) { /* некоторые провайдеры не поддерживают persist */ }

                    // Добавляем URI в список (если ещё не добавлен)
                    addTreeUri(treeUri.toString());

                    notifyJs("onFolderSelected", treeUri.toString());
                }
            } else {
                notifyJs("onFolderSelectCancelled", "");
            }
        } else if (requestCode == SAF_PICK_WALLPAPER_FILE_CODE) {
            // Выбор ОДНОГО конкретного файла (картинка или видео) через
            // ACTION_OPEN_DOCUMENT — в отличие от SAF_PICK_TREE_CODE, который
            // выбирает целую папку. Нужно отдельное persistable-разрешение,
            // т.к. это самостоятельный URI, не связанный с уже выбранными деревьями.
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri fileUri = data.getData();
                if (fileUri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                            fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception e) { /* некоторые провайдеры не поддерживают persist */ }
                    String mime = getContentResolver().getType(fileUri);
                    String kind = (mime != null && mime.startsWith("video/")) ? "video" : "image";
                    notifyJs("onWallpaperFileSelected",
							 "{\"uri\":\"" + fileUri.toString().replace("\\","\\\\").replace("\"","\\\"") +
							 "\",\"kind\":\"" + kind + "\"}");
                }
            } else {
                notifyJs("onWallpaperFileSelectCancelled", "");
            }
        } else if (requestCode == SAF_PICK_STORE_FILE_CODE || requestCode == SAF_PICK_PROFILE_PHOTO_CODE) {
            // Общая логика для двух разных пикеров (публикация обоев в Store
            // и фото профиля) — отличается только то, какое JS-событие
            // получает результат. Читаем байты СРАЗУ здесь (на устройстве),
            // чтобы JS получил готовый data: URI без второго round-trip
            // через readUriBase64 — проще и быстрее для разовой загрузки файла.
            String eventName = (requestCode == SAF_PICK_STORE_FILE_CODE)
                ? "onStoreFileSelected" : "onProfilePhotoSelected";
            String cancelEventName = (requestCode == SAF_PICK_STORE_FILE_CODE)
                ? "onStoreFileSelectCancelled" : "onProfilePhotoSelectCancelled";
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri fileUri = data.getData();
                if (fileUri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                            fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception e) { /* некоторые провайдеры не поддерживают persist */ }
                    String mime = getContentResolver().getType(fileUri);
                    if (mime == null) mime = "application/octet-stream";
                    String kind = mime.startsWith("video/") ? "video" : "image";
                    // Жёсткий лимит на размер исходного файла, читаемого в память —
                    // 5MB сырых байт (после base64 будет крупнее, но это уже
                    // дополнительно проверяется на сервере). Для фото профиля
                    // этого с запасом достаточно; для видео-обоев — намеренно
                    // совпадает примерно с лимитом сервера (см. STORE_MAX_VIDEO_LENGTH
                    // в gody-worker.js), чтобы не читать в память то, что
                    // всё равно потом отклонит сервер.
                    final long MAX_READ_BYTES = 5 * 1024 * 1024;
                    try {
                        java.io.InputStream is = getContentResolver().openInputStream(fileUri);
                        if (is == null) {
                            notifyJs(eventName, "{\"error\":\"Cannot open file\"}");
                        } else {
                            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                            byte[] tmp = new byte[16384];
                            int total = 0, n;
                            boolean tooLarge = false;
                            while ((n = is.read(tmp)) != -1) {
                                total += n;
                                if (total > MAX_READ_BYTES) { tooLarge = true; break; }
                                buffer.write(tmp, 0, n);
                            }
                            is.close();
                            if (tooLarge) {
                                notifyJs(eventName, "{\"error\":\"File too large (max 5MB)\"}");
                            } else {
                                String b64 = android.util.Base64.encodeToString(
                                    buffer.toByteArray(), android.util.Base64.NO_WRAP);
                                String dataUri = "data:" + mime + ";base64," + b64;
                                // JSON собирается вручную (без библиотеки) — значения
                                // тут все безопасны для прямой вставки (base64-алфавит
                                // не содержит кавычек/спецсимволов, kind — наша же строка).
                                notifyJs(eventName, "{\"dataUri\":\"" + dataUri +
                                    "\",\"mediaType\":\"" + kind + "\"}");
                            }
                        }
                    } catch (Exception e) {
                        notifyJs(eventName, "{\"error\":\"" +
                            e.getMessage().replace("\"", "'") + "\"}");
                    }
                }
            } else {
                notifyJs(cancelEventName, "");
            }
        } else if (requestCode == VOICE_INPUT_REQUEST_CODE) {
            // Системный диалог распознавания речи Android (RecognizerIntent) —
            // встроен в ОС, не требует своего UI и работает без доп. разрешений
            // на большинстве устройств (использует Google app для распознавания).
            if (resultCode == Activity.RESULT_OK && data != null) {
                java.util.ArrayList<String> results = data.getStringArrayListExtra(
                    android.speech.RecognizerIntent.EXTRA_RESULTS);
                String text = (results != null && !results.isEmpty()) ? results.get(0) : "";
                notifyJs("onVoiceInputResult", text.replace("\\","\\\\").replace("\"","\\\""));
            } else {
                notifyJs("onVoiceInputCancelled", "");
            }
        }
    }

    // ── Простое хранение списка URI как строк, разделённых '\n' ──────────
    private java.util.List<String> getTreeUriList() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String raw = prefs.getString(PREF_TREES, "");
        java.util.List<String> result = new java.util.ArrayList<String>();
        if (raw == null || raw.isEmpty()) return result;
        for (String s : raw.split("\n")) {
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    private void saveTreeUriList(java.util.List<String> list) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        for (String s : list) { sb.append(s).append("\n"); }
        prefs.edit().putString(PREF_TREES, sb.toString()).apply();
    }

    private void addTreeUri(String uri) {
        java.util.List<String> list = getTreeUriList();
        if (!list.contains(uri)) {
            list.add(uri);
            saveTreeUriList(list);
        }
    }

    private void removeTreeUri(String uri) {
        java.util.List<String> list = getTreeUriList();
        list.remove(uri);
        saveTreeUriList(list);
        // Освобождаем разрешение на этот URI
        try {
            Uri treeUri = Uri.parse(uri);
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
				| Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            getContentResolver().releasePersistableUriPermission(treeUri, flags);
        } catch (Exception e) { /* ignore */ }
    }

    // Вызывает JS-функцию с одним строковым аргументом (JSON-экранированным)
    private void notifyJs(final String fn, final String arg) {
        if (web == null) return;
        web.post(new Runnable() {
				public void run() {
					String safeArg = arg.replace("\\","\\\\").replace("'","\\'");
					// Оборачиваем вызов в try/catch прямо в JS — при ошибке
					// показываем реальное сообщение в оверлее вместо "Script error. ?:?"
					String js = "(function(){try{" +
						"if(typeof " + fn + "==='function')" + fn + "('" + safeArg + "');" +
						"}catch(e){try{var o=document.getElementById('xomniaErrorOverlay');" +
						"if(o){o.style.display='block';" +
						"var d=document.createElement('div');" +
						"d.style.cssText='margin-bottom:4px;border-bottom:1px solid #622;padding-bottom:4px;';" +
						"d.textContent='[notify:" + fn + "] '+(e.message||String(e));" +
						"o.insertBefore(d,o.querySelector('span')||o.firstChild);" +
						"}}catch(x){}}" +
						"})();";
					web.evaluateJavascript(js, null);
				}
			});
    }

    // Уведомляет JS о событии конкретного встроенного браузера (загрузка
    // началась/закончилась), передавая browserId — без этого при нескольких
    // одновременных окнах браузера JS не смог бы понять, какое именно окно
    // обновлять (adress bar, кнопки back/forward, заголовок таскбара).
    private void notifyJsBrowserEvent(final String fn, final String browserId,
									  final String url, final String title,
									  final boolean canGoBack, final boolean canGoForward) {
        if (web == null) return;
        web.post(new Runnable() {
				public void run() {
					String safeBrowserId = browserId.replace("\\","\\\\").replace("'","\\'");
					String safeUrl = (url == null ? "" : url).replace("\\","\\\\").replace("'","\\'");
					String safeTitle = (title == null ? "" : title).replace("\\","\\\\").replace("'","\\'");
					String js = "if(typeof " + fn + "==='function') " + fn + "({"
						+ "browserId:'" + safeBrowserId + "',"
						+ "url:'" + safeUrl + "',"
						+ "title:'" + safeTitle + "',"
						+ "canGoBack:" + canGoBack + ","
						+ "canGoForward:" + canGoForward + "});";
					web.evaluateJavascript(js, null);
				}
			});
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideUI();
    }

    private void hideUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideUI();
    }

    // ── Кнопка "назад" — если открыт встроенный браузер и может листать
    // назад по истории, листаем там вместо выхода из приложения/закрытия окна.
    // С несколькими одновременными браузерами берём именно тот, с которым
    // взаимодействовали последним (lastActiveBrowserId), а не произвольный
    // из карты — иначе кнопка Back могла бы листать историю в чужом окне.
    @Override
    public void onBackPressed() {
        if (fullscreenCustomView != null) {
            exitFullscreenVideo();
            return;
        }
        if (lastActiveBrowserId != null) {
            WebView active = browserWebViews.get(lastActiveBrowserId);
            if (active != null && active.getVisibility() == View.VISIBLE && active.canGoBack()) {
                active.goBack();
                return;
            }
        }
        super.onBackPressed();
    }

    // Показывает HTML5-видео (например YouTube) в полноэкранном режиме —
    // вызывается из WebChromeClient.onShowCustomView когда страница
    // запрашивает fullscreen для <video>. ШАГ 1 (по просьбе пользователя):
    // вместо растягивания на весь физический экран устройства (MATCH_PARENT,
    // что перекрывало рамку и таскбар XomniaOS целиком), видео теперь
    // занимает ровно те же координаты и размер, что и окно браузера, из
    // которого пришёл запрос — то есть остаётся "в пределах нашего окна".
    // sourceWebView — тот самый встроенный браузер, который вызвал
    // fullscreen; его текущие LayoutParams (позиция и размер) копируются
    // на customView вместо MATCH_PARENT. sourceBrowserId запоминается в
    // fullscreenSourceBrowserId, чтобы updateEmbeddedBrowserBounds мог
    // синхронизировать геометрию customView вместе с окном при последующем
    // перетаскивании/изменении размера — без этого окно "замораживалось"
    // на координатах момента входа в fullscreen и не реагировало на drag/resize.
    void enterFullscreenVideo(View customView, android.webkit.WebChromeClient.CustomViewCallback callback,
							  WebView sourceWebView, String sourceBrowserId) {
        if (fullscreenCustomView != null) {
            // Уже есть активный fullscreen — закрываем старый перед показом нового
            try { fullscreenCallback.onCustomViewHidden(); } catch (Exception e) { /* ignore */ }
        }
        fullscreenCustomView = customView;
        fullscreenCallback = callback;
        fullscreenSourceBrowserId = sourceBrowserId;
        FrameLayout.LayoutParams sourceLp = (sourceWebView != null)
            ? (FrameLayout.LayoutParams) sourceWebView.getLayoutParams() : null;
        FrameLayout.LayoutParams lp;
        if (sourceLp != null) {
            // Тот же минимальный порог, что и в updateEmbeddedBrowserBounds —
            // если окно браузера уже было очень маленьким в момент входа в
            // fullscreen, видео всё равно получает безопасный минимальный
            // размер, чтобы не потерять Surface сразу при создании.
            int minPx = (int) dpToPxLocal(MIN_FULLSCREEN_VIDEO_DP);
            lp = new FrameLayout.LayoutParams(
                Math.max(sourceLp.width, minPx), Math.max(sourceLp.height, minPx)
            );
            lp.leftMargin = sourceLp.leftMargin;
            lp.topMargin = sourceLp.topMargin;
        } else {
            // Запасной вариант, если по какой-то причине sourceWebView
            // недоступен — лучше показать на весь экран, чем не показать вообще.
            lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            );
        }
        customView.setLayoutParams(lp);
        rootContainer.addView(customView);
        rootContainer.bringChildToFront(customView);
    }

    // Закрывает полноэкранное видео — вызывается из onHideCustomView или
    // при нажатии "назад" пока видео в fullscreen.
    void exitFullscreenVideo() {
        if (fullscreenCustomView == null) return;
        rootContainer.removeView(fullscreenCustomView);
        fullscreenCustomView = null;
        try {
            if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        } catch (Exception e) { /* ignore */ }
        fullscreenCallback = null;
        fullscreenSourceBrowserId = null;
    }

    // ── Прокси (HTTP/HTTPS, без авторизации, несколько именованных слотов) ──
    // Возвращает java.net.Proxy исходя из активного слота, или Proxy.NO_PROXY
    // если прокси отключён (нет выбранного слота). Используется как нативными
    // HTTP-запросами (doHttpRequest), так и перехватом запросов встроенного
    // браузера (shouldInterceptRequest).
    java.net.Proxy getConfiguredProxy() {
        org.json.JSONObject slot = getActiveProxySlot();
        if (slot == null) return java.net.Proxy.NO_PROXY;
        try {
            String host = slot.optString("host", "");
            int port = slot.optInt("port", 0);
            if (host.isEmpty() || port <= 0) return java.net.Proxy.NO_PROXY;
            return new java.net.Proxy(java.net.Proxy.Type.HTTP,
									  new java.net.InetSocketAddress(host, port));
        } catch (Exception e) {
            return java.net.Proxy.NO_PROXY;
        }
    }

    boolean isProxyEnabled() {
        return getActiveProxySlot() != null;
    }

    // Возвращает JSONObject активного слота {"name","host","port"}, или null
    // если прокси выключен (активный индекс -1) либо индекс невалиден.
    private org.json.JSONObject getActiveProxySlot() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int activeIndex = prefs.getInt(PREF_PROXY_ACTIVE_INDEX, -1);
        if (activeIndex < 0) return null;
        try {
            org.json.JSONArray slots = new org.json.JSONArray(prefs.getString(PREF_PROXY_SLOTS, "[]"));
            if (activeIndex >= slots.length()) return null;
            return slots.getJSONObject(activeIndex);
        } catch (Exception e) {
            return null;
        }
    }

    // ── JavaScript Bridge ──────────────────────────────────────────────

    public class JsBridge {

        private MainActivity activity;

        public JsBridge(MainActivity activity) {
            this.activity = activity;
        }

        @JavascriptInterface
        public void boot() {}

        // ══════════════════════════════════════════════════════════════
        // GODY MEMORY — хранится во внутреннем хранилище приложения
        // (getFilesDir()), доступном без каких-либо разрешений и без
        // выбора папки пользователем. Файл: /data/data/<pkg>/files/gody_memory.json
        // ══════════════════════════════════════════════════════════════

        private static final String GODY_MEMORY_FILENAME = "gody_memory.json";

        // Сохраняет содержимое памяти GODY (JSON-строка целиком).
        // callbackName(result) где result = {ok:true} или {ok:false, error:"..."}
        @JavascriptInterface
        public void saveMemoryFile(final String content, final String callbackName) {
            netExecutor.execute(new Runnable() {
					public void run() {
						String resultJson;
						try {
							java.io.File f = new java.io.File(activity.getFilesDir(), GODY_MEMORY_FILENAME);
							java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
							fos.write(content.getBytes("UTF-8"));
							fos.flush();
							fos.close();
							resultJson = "{\"ok\":true}";
						} catch (Exception e) {
							resultJson = "{\"ok\":false,\"error\":\"" + jsonEscape(e.toString()) + "\"}";
						}
						postResultToJs(callbackName, resultJson);
					}
				});
        }

        // Загружает содержимое памяти GODY.
        // callbackName(result) где result = {ok:true, content:"..."} если файл есть,
        // или {ok:false} если файла ещё нет (первый запуск) или ошибка чтения.
        @JavascriptInterface
        public void loadMemoryFile(final String callbackName) {
            netExecutor.execute(new Runnable() {
					public void run() {
						String resultJson;
						try {
							java.io.File f = new java.io.File(activity.getFilesDir(), GODY_MEMORY_FILENAME);
							if (!f.exists()) {
								resultJson = "{\"ok\":false}";
							} else {
								java.io.FileInputStream fis = new java.io.FileInputStream(f);
								java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
								byte[] tmp = new byte[8192];
								int n;
								while ((n = fis.read(tmp)) != -1) buffer.write(tmp, 0, n);
								fis.close();
								String content = buffer.toString("UTF-8");
								// Используем полное экранирование (jsonEscapeFull), а не jsonEscape —
								// история чата и текст сообщений могут содержать переводы строк,
								// которые важно сохранить, а не вырезать.
								resultJson = "{\"ok\":true,\"content\":\"" + jsonEscapeFull(content) + "\"}";
							}
						} catch (Exception e) {
							resultJson = "{\"ok\":false,\"error\":\"" + jsonEscape(e.toString()) + "\"}";
						}
						postResultToJs(callbackName, resultJson);
					}
				});
        }

        // Удаляет файл памяти GODY (сброс памяти).
        @JavascriptInterface
        public void deleteMemoryFile(final String callbackName) {
            netExecutor.execute(new Runnable() {
					public void run() {
						String resultJson;
						try {
							java.io.File f = new java.io.File(activity.getFilesDir(), GODY_MEMORY_FILENAME);
							boolean deleted = !f.exists() || f.delete();
							resultJson = deleted ? "{\"ok\":true}" : "{\"ok\":false}";
						} catch (Exception e) {
							resultJson = "{\"ok\":false,\"error\":\"" + jsonEscape(e.toString()) + "\"}";
						}
						postResultToJs(callbackName, resultJson);
					}
				});
        }

        // ══════════════════════════════════════════════════════════════
        // PROXY SETTINGS — несколько именованных слотов HTTP/HTTPS прокси
        // без авторизации (пользователь сам вписывает host/port своих
        // серверов). Применяется к нативным HTTP-запросам (doHttpRequest,
        // включая обращения GODY к Anthropic API) и к встроенному браузеру
        // (через shouldInterceptRequest в WebViewClient).
        // ══════════════════════════════════════════════════════════════

        // Возвращает все слоты + активный индекс как JSON:
        // {activeIndex:N, slots:[{name,host,port}, ...]}
        // activeIndex = -1 означает "прокси выключен".
        @JavascriptInterface
        public String getProxySettings() {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int activeIndex = prefs.getInt(PREF_PROXY_ACTIVE_INDEX, -1);
            String slotsJson = prefs.getString(PREF_PROXY_SLOTS, "[]");
            // slotsJson уже валидный JSON-массив (записан через setProxySlots) —
            // вставляем как есть, без повторного экранирования.
            return "{\"activeIndex\":" + activeIndex + ",\"slots\":" + slotsJson + "}";
        }

        // Сохраняет весь список слотов целиком (приходит как готовая JSON-строка
        // массива из JS — проще, чем передавать через мост массив объектов).
        @JavascriptInterface
        public void setProxySlots(String slotsJson) {
            try {
                // Валидируем, что это действительно корректный JSON-массив,
                // прежде чем сохранять — иначе getActiveProxySlot() в будущем
                // будет ловить исключение при каждом запросе.
                new org.json.JSONArray(slotsJson);
            } catch (Exception e) {
                return; // не сохраняем повреждённые данные
            }
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putString(PREF_PROXY_SLOTS, slotsJson).apply();
        }

        // Устанавливает активный слот по индексу, или -1 чтобы выключить прокси.
        @JavascriptInterface
        public void setActiveProxySlot(int index) {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putInt(PREF_PROXY_ACTIVE_INDEX, index).apply();
        }

        @JavascriptInterface
        public int getBattery() {
            android.os.BatteryManager bm =
                (android.os.BatteryManager)
                activity.getSystemService(BATTERY_SERVICE);
            return bm.getIntProperty(
                android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
            );
        }

        @JavascriptInterface
        public void openBrowser(final String url) {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						try {
							String u = (url == null || url.isEmpty())
								? "https://www.google.com" : url;
							if (!u.startsWith("http")) u = "https://" + u;
							Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
							activity.startActivity(i);
						} catch (Exception e) {
							android.widget.Toast.makeText(
								activity, e.toString(),
								android.widget.Toast.LENGTH_LONG
							).show();
						}
					}
				});
        }

        // ── Нативный HTTP-клиент (Java HttpURLConnection) ──────────────────
        // Выполняется в отдельном потоке Android, минуя сетевой стек WebView,
        // на случай если WebView-контекст специфично заблокирован на устройстве.
        // Результат передаётся обратно в JS через callbackName(JSON-строка).

        @JavascriptInterface
        public void httpGet(final String urlStr, final String callbackName) {
            netExecutor.execute(new Runnable() {
					public void run() {
						doHttpRequest(urlStr, "GET", null, callbackName);
					}
				});
        }

        @JavascriptInterface
        public void httpPost(final String urlStr, final String body, final String callbackName) {
            netExecutor.execute(new Runnable() {
					public void run() {
						doHttpRequest(urlStr, "POST", body, callbackName);
					}
				});
        }

        private void doHttpRequest(String urlStr, String method, String body, final String callbackName) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                // Прокси (если включён в Settings) применяется здесь же —
                // это покрывает и httpGet/httpPost из JS, и обращения GODY
                // к Anthropic API (которые тоже идут через этот метод —
                // см. askAI -> Android.httpPost в index.html).
                java.net.Proxy proxy = activity.getConfiguredProxy();
                conn = (HttpURLConnection) url.openConnection(proxy);
                conn.setRequestMethod(method);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (XomniaOS)");

                if ("POST".equals(method) && body != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                }

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 400)
                    ? conn.getInputStream() : conn.getErrorStream();

                String responseText = readStream(is);

                final String resultJson = "{"
                    + "\"ok\":" + (code >= 200 && code < 300 ? "true" : "false") + ","
                    + "\"status\":" + code + ","
                    + "\"body\":\"" + jsonEscape(responseText) + "\""
                    + "}";

                postResultToJs(callbackName, resultJson);

            } catch (Exception e) {
                final String errJson = "{"
                    + "\"ok\":false,"
                    + "\"status\":0,"
                    + "\"error\":\"" + jsonEscape(e.toString()) + "\""
                    + "}";
                postResultToJs(callbackName, errJson);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private String readStream(InputStream is) throws Exception {
            if (is == null) return "";
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int nRead;
            int totalRead = 0;
            int maxBytes = 2 * 1024 * 1024; // лимит 2MB на ответ
            while ((nRead = is.read(data, 0, data.length)) != -1 && totalRead < maxBytes) {
                buffer.write(data, 0, nRead);
                totalRead += nRead;
            }
            is.close();
            return buffer.toString("UTF-8");
        }

        private void postResultToJs(final String callbackName, final String resultJson) {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						try {
							// Передаём результат как JS-строку через base64,
							// чтобы избежать проблем с экранированием спецсимволов
							String b64 = android.util.Base64.encodeToString(
								resultJson.getBytes("UTF-8"), android.util.Base64.NO_WRAP
							);
									// escape() падает на бинарных данных — заменяем на TextDecoder.
							// Ошибка перехватывается и отправляется в xomniaErrorOverlay
							// как нормальный ErrorEvent — пользователь видит реальную причину
							// вместо бесполезного "Script error. — ?:?".
							String js = "(function(){" +
								"try{" +
								"var b=atob('" + b64 + "');" +
								"var u=new Uint8Array(b.length);" +
								"for(var i=0;i<b.length;i++)u[i]=b.charCodeAt(i);" +
								"var t=new TextDecoder('utf-8').decode(u);" +
								"if(typeof " + callbackName + "==='function')" +
								callbackName + "(JSON.parse(t));" +
								"}catch(e){" +
								"try{var o=document.getElementById('xomniaErrorOverlay');" +
								"if(o){o.style.display='block';" +
								"var d=document.createElement('div');" +
								"d.style.cssText='margin-bottom:4px;border-bottom:1px solid #622;padding-bottom:4px;';" +
								"d.textContent='[bridge:' + '" + callbackName + "' + '] '+(e.message||e)+(e.stack?' | '+e.stack.split('\\n')[1]:'');" +
								"o.insertBefore(d,o.querySelector('span')||o.firstChild);" +
								"}}catch(x){}" +
								"}" +
								"})();";
							web.evaluateJavascript(js, null);
						} catch (Exception e) { /* ignore */ }
					}
				});
        }

        @JavascriptInterface
        public void setPortrait() {
            activity.setRequestedOrientation(
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            );
        }

        @JavascriptInterface
        public void setLandscape() {
            activity.setRequestedOrientation(
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            );
        }

        @JavascriptInterface
        public String getDeviceModel() {
            return android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        }

        @JavascriptInterface
        public String getAndroidVersion() {
            return "Android " + android.os.Build.VERSION.RELEASE;
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            android.os.Vibrator v =
                (android.os.Vibrator) activity.getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(ms);
        }

        // ══════════════════════════════════════════════════════════════
        // EMBEDDED BROWSER — второй WebView внутри приложения (не системный
        // Chrome). Координаты/размер задаются из JS в density-independent
        // пикселях (dp), как отображается окно браузера на десктопе,
        // чтобы встроенный WebView совпадал с рамкой окна в UI.
        // ══════════════════════════════════════════════════════════════

        private float dpToPx(float dp) {
            return dp * activity.getResources().getDisplayMetrics().density;
        }

        private WebView ensureBrowserWebView(final String browserId) {
            WebView existing = activity.browserWebViews.get(browserId);
            if (existing != null) return existing;

            if (activity.browserWebViews.size() >= MainActivity.MAX_EMBEDDED_BROWSERS) {
                // Превышен лимит одновременных встроенных браузеров — не создаём
                // новый WebView (на слабых устройствах это реальный риск ANR/OOM),
                // сигналим в JS, чтобы показать пользователю объяснение вместо
                // тихого "ничего не происходит".
                activity.notifyJs("onEmbeddedBrowserLimitReached", browserId);
                return null;
            }

            final WebView bw = new WebView(activity);
            bw.getSettings().setJavaScriptEnabled(true);
            bw.getSettings().setDomStorageEnabled(true);
            bw.getSettings().setMediaPlaybackRequiresUserGesture(false);
            bw.getSettings().setMixedContentMode(
                android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            );
            bw.getSettings().setSupportZoom(true);
            bw.getSettings().setBuiltInZoomControls(true);
            bw.getSettings().setDisplayZoomControls(false);
            bw.getSettings().setLoadWithOverviewMode(true);
            bw.getSettings().setUseWideViewPort(true);
            bw.setWebViewClient(new android.webkit.WebViewClient() {
					@Override
					public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
						activity.notifyJsBrowserEvent("onEmbeddedBrowserLoading", browserId, url == null ? "" : url, null, false, false);
					}
					@Override
					public void onPageFinished(WebView view, String url) {
						String title = view.getTitle();
						activity.notifyJsBrowserEvent("onEmbeddedBrowserLoaded", browserId,
													  url == null ? "" : url, title == null ? "" : title,
													  view.canGoBack(), view.canGoForward());
						// Вешаем слушатели play/pause/ended на все <video> элементы
						// страницы (и на любые, что появятся позже динамически —
						// YouTube и подобные сайты подгружают сам плеер через JS
						// после события load, поэтому простого querySelectorAll
						// сразу после onPageFinished было бы недостаточно).
						// Нужно для реактивного приглушения звука видео-обоев
						// XomniaOS, пока в любом окне браузера играет видео —
						// событийный подход выбран вместо поллинга, чтобы
						// реагировать мгновенно без лишней нагрузки.
						view.evaluateJavascript(
							"(function(){" +
							"  if (window.__xomniaVideoWatcherInstalled) return;" +
							"  window.__xomniaVideoWatcherInstalled = true;" +
							"  function report(){" +
							"    var vids = document.querySelectorAll('video');" +
							"    var playing = false;" +
							"    for (var i = 0; i < vids.length; i++) { if (!vids[i].paused) { playing = true; break; } }" +
							"    if (window.XomniaVideoState !== playing) {" +
							"      window.XomniaVideoState = playing;" +
							"      try { Android.notifyVideoPlaybackState('" + browserId + "', playing); } catch(e) {}" +
							"    }" +
							"  }" +
							"  function attach(v){" +
							"    if (v.__xomniaWatched) return;" +
							"    v.__xomniaWatched = true;" +
							"    v.addEventListener('play', report);" +
							"    v.addEventListener('pause', report);" +
							"    v.addEventListener('ended', report);" +
							"  }" +
							"  var vids = document.querySelectorAll('video');" +
							"  for (var i = 0; i < vids.length; i++) attach(vids[i]);" +
							"  var mo = new MutationObserver(function(){" +
							"    var vids2 = document.querySelectorAll('video');" +
							"    for (var j = 0; j < vids2.length; j++) attach(vids2[j]);" +
							"  });" +
							"  mo.observe(document.documentElement, {childList:true, subtree:true});" +
							"})();", null);
					}
					@Override
					public boolean shouldOverrideUrlLoading(WebView view, String url) {
						return false; // грузим всё внутри встроенного браузера
					}

					// ── Прокси для встроенного браузера ──────────────────────
					// Android WebView не даёт публичного API для прокси без
					// androidx.webkit.ProxyController (требует AndroidX-зависимость,
					// которой нет в этом AIDE-проекте) — поэтому каждый запрос
					// страницы перехватывается здесь и вручную прогоняется через
					// HttpURLConnection с заданным Proxy. Известные ограничения:
					// не покрывает WebSocket-соединения, а потоковое видео с
					// диапазонными (Range) запросами может идти медленнее, чем
					// через нормальный системный прокси — но обычная навигация
					// по сайтам работает корректно.
					@Override
					public android.webkit.WebResourceResponse shouldInterceptRequest(
                        WebView view, android.webkit.WebResourceRequest request) {
						if (!activity.isProxyEnabled()) return null; // null = обычная загрузка
						String urlStr = request.getUrl().toString();
						// Не проксируем сами data:/file:/javascript: URI — их прокси не касается
						if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) return null;
						try {
							java.net.Proxy proxy = activity.getConfiguredProxy();
							URL url = new URL(urlStr);
							HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
							conn.setConnectTimeout(10000);
							conn.setReadTimeout(15000);
							conn.setInstanceFollowRedirects(true);
							java.util.Map<String, String> headers = request.getRequestHeaders();
							if (headers != null) {
								for (java.util.Map.Entry<String, String> h : headers.entrySet()) {
									// Accept-Encoding пропускаем — иначе сервер может прислать
									// gzip-сжатый ответ, а мы передадим его в WebResourceResponse
									// без разжатия (поток будет битым для страницы).
									// HttpURLConnection сам добавит Accept-Encoding: identity
									// по умолчанию, получая несжатый ответ.
									if ("accept-encoding".equalsIgnoreCase(h.getKey())) continue;
									try { conn.setRequestProperty(h.getKey(), h.getValue()); }
									catch (Exception e) { /* skip restricted headers */ }
								}
							}
							int code = conn.getResponseCode();
							String mime = conn.getContentType();
							// encoding здесь означает character encoding (charset), а НЕ
							// HTTP Content-Encoding (gzip/deflate) — путать их нельзя.
							// Передаём null: WebView сам разберёт charset из Content-Type
							// заголовка (который мы пробрасываем ниже в responseHeaders).
							if (mime != null && mime.indexOf(';') != -1) {
								mime = mime.substring(0, mime.indexOf(';')).trim();
							}
							InputStream stream = (code >= 200 && code < 400)
								? conn.getInputStream() : conn.getErrorStream();
							if (stream == null) return null;
							// setInstanceFollowRedirects(true) выше гарантирует, что code
							// здесь почти всегда не будет 3xx — но WebResourceResponse
							// явно не поддерживает 3xx статусы ("not supported"), поэтому
							// на случай редкого edge-кейса (например бесконечный редирект
							// обрублен системой на промежуточном 3xx) подстраховываемся.
							int safeCode = (code >= 100 && code < 600 && !(code >= 300 && code < 400)) ? code : 200;
							String reason = safeCode >= 200 && safeCode < 300 ? "OK" : "Error";
							java.util.Map<String, String> responseHeaders = new java.util.HashMap<String, String>();
							java.util.Map<String, java.util.List<String>> rawHeaders = conn.getHeaderFields();
							if (rawHeaders != null) {
								for (java.util.Map.Entry<String, java.util.List<String>> e : rawHeaders.entrySet()) {
									if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
									// Пропускаем Content-Encoding — мы запросили identity (без сжатия)
									// через отсутствие Accept-Encoding, поток уже не сжат; если сервер
									// всё равно прислал этот заголовок, оставлять его опасно — WebView
									// решит, что поток сжат, и не сможет его разобрать.
									if ("content-encoding".equalsIgnoreCase(e.getKey())) continue;
									responseHeaders.put(e.getKey(), e.getValue().get(0));
								}
							}
							return new android.webkit.WebResourceResponse(
								mime != null ? mime : "text/plain",
								null,
								safeCode, reason,
								responseHeaders,
								stream
							);
						} catch (Exception e) {
							// Прокси недоступен/упал — пропускаем запрос как обычно,
							// чтобы не блокировать всю страницу из-за одного ресурса.
							return null;
						}
					}
				});
            // WebChromeClient — обязателен для работы HTML5 fullscreen video
            // (YouTube и другие плееры запрашивают fullscreen через стандартный
            // browser Fullscreen API; без onShowCustomView/onHideCustomView
            // кнопка fullscreen в плеере просто ничего не делает).
            bw.setWebChromeClient(new android.webkit.WebChromeClient() {
					@Override
					public void onShowCustomView(View view, CustomViewCallback callback) {
						activity.enterFullscreenVideo(view, callback, bw, browserId);
					}
					@Override
					public void onHideCustomView() {
						activity.exitFullscreenVideo();
					}
					@Override
					public void onShowCustomView(View view, int requestedOrientation, CustomViewCallback callback) {
						activity.enterFullscreenVideo(view, callback, bw, browserId);
					}
				});
            activity.browserWebViews.put(browserId, bw);
            activity.rootContainer.addView(bw, new FrameLayout.LayoutParams(0, 0));
            bw.setVisibility(View.GONE);
            return bw;
        }

        // Показывает встроенный браузер с заданными позицией/размером (dp)
        // и грузит URL (если переход на новый сайт) или просто меняет геометрию
        // (если окно просто подвинули/изменили размер на десктопе).
        // browserId — уникальный идентификатор HTML-окна (генерируется в JS),
        // привязывающий конкретный нативный WebView к конкретному окну —
        // без него все окна делили бы один и тот же WebView (старое поведение,
        // из-за которого второе открытое окно браузера показывало то же самое,
        // что первое, см. жалобу пользователя со скриншотом двух одинаковых YouTube).
        @JavascriptInterface
        public void showEmbeddedBrowser(final String browserId, final double left, final double top,
										final double width, final double height,
										final String url) {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						WebView bw = ensureBrowserWebView(browserId);
						if (bw == null) return; // лимит окон достигнут, сигнал уже отправлен в JS
						activity.lastActiveBrowserId = browserId;
						FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bw.getLayoutParams();
						lp.width = (int) dpToPx((float) width);
						lp.height = (int) dpToPx((float) height);
						lp.leftMargin = (int) dpToPx((float) left);
						lp.topMargin = (int) dpToPx((float) top);
						bw.setLayoutParams(lp);
						bw.setVisibility(View.VISIBLE);
						bw.bringToFront();
						if (url != null && !url.isEmpty() && !url.equals(bw.getUrl())) {
							bw.loadUrl(url);
						}
					}
				});
        }

        // Просто обновляет геометрию (при drag/resize окна на десктопе),
        // без перезагрузки страницы.
        @JavascriptInterface
        public void updateEmbeddedBrowserBounds(final String browserId, final double left, final double top,
												final double width, final double height) {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						WebView bw = activity.browserWebViews.get(browserId);
						if (bw == null) return;
						FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bw.getLayoutParams();
						lp.width = (int) dpToPx((float) width);
						lp.height = (int) dpToPx((float) height);
						lp.leftMargin = (int) dpToPx((float) left);
						lp.topMargin = (int) dpToPx((float) top);
						bw.setLayoutParams(lp);
						// Если сейчас активен fullscreen-видео именно из ЭТОГО окна —
						// подвинуть/изменить размер customView вместе с окном, иначе
						// видео осталось бы "приклеенным" к старым координатам момента
						// входа в fullscreen, и окно выглядело бы неотзывчивым на
						// перетаскивание/resize, пока видео раскрыто.
						// ВАЖНО: ширина/высота customView НЕ опускается ниже
						// MIN_FULLSCREEN_VIDEO_DP (в пикселях) даже если реальное
						// окно XomniaOS уменьшено сильнее — видеоплеер использует
						// нативную Surface для рендеринга кадра, и при околонулевом
						// размере эта поверхность становится невалидной для
						// видеодекодера, что приводит к полному "зависанию" видео
						// (и картинки, и звука), которое САМО не восстанавливается
						// даже после увеличения окна обратно — подтверждено
						// пользователем на реальном устройстве. Позиция (left/top)
						// при этом всё равно синхронизируется точно с окном — это
						// лишь нижняя граница для самого размера видео.
						if (activity.fullscreenCustomView != null
                            && browserId.equals(activity.fullscreenSourceBrowserId)) {
							FrameLayout.LayoutParams fsLp =
								(FrameLayout.LayoutParams) activity.fullscreenCustomView.getLayoutParams();
							if (fsLp != null) {
								int minPx = (int) dpToPx(MIN_FULLSCREEN_VIDEO_DP);
								fsLp.width = Math.max(lp.width, minPx);
								fsLp.height = Math.max(lp.height, minPx);
								fsLp.leftMargin = lp.leftMargin;
								fsLp.topMargin = lp.topMargin;
								activity.fullscreenCustomView.setLayoutParams(fsLp);
							}
						}
					}
				});
        }

        @JavascriptInterface
        public void embeddedBrowserNavigate(final String browserId, final String url) {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						WebView bw = activity.browserWebViews.get(browserId);
						if (bw == null || url == null || url.isEmpty()) return;
						activity.lastActiveBrowserId = browserId;
						bw.loadUrl(url);
					}
				});
        }

        @JavascriptInterface
        public void embeddedBrowserGoBack(final String browserId) {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						WebView bw = activity.browserWebViews.get(browserId);
						if (bw != null && bw.canGoBack()) {
							activity.lastActiveBrowserId = browserId;
							bw.goBack();
						}
					}
				});
        }

        @JavascriptInterface
        public void embeddedBrowserGoForward(final String browserId) {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						WebView bw = activity.browserWebViews.get(browserId);
						if (bw != null && bw.canGoForward()) {
							activity.lastActiveBrowserId = browserId;
							bw.goForward();
						}
					}
				});
        }

        @JavascriptInterface
        public void embeddedBrowserReload(final String browserId) {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						WebView bw = activity.browserWebViews.get(browserId);
						if (bw != null) bw.reload();
					}
				});
        }

        // Пытается развернуть видео-плеер на текущей странице во весь экран
        // через стандартный HTML5 Fullscreen API — имитирует нажатие кнопки
        // fullscreen внутри самого плеера (YouTube и т.п.). requestFullscreen()
        // на найденном <video> вызывает то же самое событие, что обрабатывается
        // в WebChromeClient.onShowCustomView (см. enterFullscreenVideo ниже).
        // ПРИМЕЧАНИЕ: кнопка "⛶" в UI сейчас НЕ вызывает этот метод — она
        // переключает видимость тулбара браузера внутри окна XomniaOS
        // (см. toggleVideoFillWindow в index.html), что пользователь явно
        // предпочёл этому системному fullscreen (тот убирает рамку окна
        // XomniaOS целиком, что оказалось нежелательным). Метод оставлен
        // рабочим на случай, если потребуется снова — не вызывается напрямую.
        @JavascriptInterface
        public void injectFullscreenVideo(final String browserId) {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						WebView bw = activity.browserWebViews.get(browserId);
						if (bw == null) return;
						String js =
							"(function(){" +
							"  var vids = document.querySelectorAll('video');" +
							"  if (!vids.length) return 'no-video';" +
							"  var target = null;" +
							"  for (var i = 0; i < vids.length; i++) {" +
							"    if (!vids[i].paused) { target = vids[i]; break; }" +
							"  }" +
							"  if (!target) {" +
							"    var bestArea = 0;" +
							"    for (var j = 0; j < vids.length; j++) {" +
							"      var r = vids[j].getBoundingClientRect();" +
							"      var area = r.width * r.height;" +
							"      if (area > bestArea) { bestArea = area; target = vids[j]; }" +
							"    }" +
							"  }" +
							"  if (!target) return 'no-video';" +
							"  var rf = target.requestFullscreen || target.webkitRequestFullscreen || target.webkitEnterFullscreen;" +
							"  if (rf) { rf.call(target); return 'ok'; }" +
							"  return 'unsupported';" +
							"})();";
						bw.evaluateJavascript(js, null);
					}
				});
        }

        // Вызывается из JS-инъекции в onPageFinished каждый раз, когда
        // какой-то <video> элемент внутри встроенной страницы начинает или
        // заканчивает играть (событийно, без поллинга). Обновляет состояние
        // конкретного окна в browserVideoPlayingState и пересылает общий
        // объединённый сигнал ("играет ли видео хоть в каком-то окне") в
        // основной JS-интерфейс XomniaOS — используется там для реактивного
        // приглушения звука видео-обоев на время просмотра видео в браузере.
        @JavascriptInterface
        public void notifyVideoPlaybackState(final String browserId, final boolean playing) {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						activity.browserVideoPlayingState.put(browserId, playing);
						boolean anyPlaying = false;
						for (Boolean v : activity.browserVideoPlayingState.values()) {
							if (v != null && v) { anyPlaying = true; break; }
						}
						activity.notifyJs("onAnyEmbeddedVideoPlaybackChanged", String.valueOf(anyPlaying));
					}
				});
        }

        // Проверяет, играет ли видео хотя бы в ОДНОМ из открытых окон
        // встроенного браузера прямо сейчас — используется системой
        // определения простоя (AFK/screensaver), чтобы не включать заставку,
        // если пользователь смотрит YouTube без активного взаимодействия
        // с экраном (то же самое касается музыки и видео-обоев, которые
        // проверяются отдельно прямо в JS без необходимости в этом методе,
        // поскольку они не изолированы внутри нативного WebView).
        // Каждый открытый браузер опрашивается через evaluateJavascript
        // (асинхронно) — результаты агрегируются здесь и передаются в JS
        // одним финальным вызовом callback после получения ВСЕХ ответов
        // (или истечения короткого таймаута, чтобы зависший WebView не
        // блокировал проверку навечно).
        @JavascriptInterface
        public void checkAnyEmbeddedVideoPlaying(final String callbackName) {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						final java.util.List<WebView> views = new java.util.ArrayList<WebView>(activity.browserWebViews.values());
						if (views.isEmpty()) {
							activity.notifyJs(callbackName, "false");
							return;
						}
						final int total = views.size();
						final int[] responded = {0};
						final boolean[] anyPlaying = {false};
						final boolean[] alreadyAnswered = {false};
						final String checkJs =
							"(function(){" +
							"  var vids = document.querySelectorAll('video');" +
							"  for (var i = 0; i < vids.length; i++) { if (!vids[i].paused) return 'true'; }" +
							"  return 'false';" +
							"})();";
						// Защитный таймаут — если какой-то WebView не ответит (страница
						// зависла/грузится), не блокируем весь механизм АФК навечно.
						final android.os.Handler timeoutHandler = new android.os.Handler(activity.getMainLooper());
						final Runnable timeoutRunnable = new Runnable() {
							public void run() {
								if (!alreadyAnswered[0]) {
									alreadyAnswered[0] = true;
									activity.notifyJs(callbackName, String.valueOf(anyPlaying[0]));
								}
							}
						};
						timeoutHandler.postDelayed(timeoutRunnable, 2000);
						for (WebView bw : views) {
							bw.evaluateJavascript(checkJs, new android.webkit.ValueCallback<String>() {
									public void onReceiveValue(String value) {
										if (alreadyAnswered[0]) return;
										if ("\"true\"".equals(value)) anyPlaying[0] = true;
										responded[0]++;
										if (responded[0] >= total) {
											alreadyAnswered[0] = true;
											timeoutHandler.removeCallbacks(timeoutRunnable);
											activity.notifyJs(callbackName, String.valueOf(anyPlaying[0]));
										}
									}
								});
						}
					}
				});
        }

        // Скрывает (но не уничтожает) встроенный браузер — переиспользуется
        // при следующем открытии окна без потери истории навигации
        // (например при минимизации окна на десктопе).
        @JavascriptInterface
        public void hideEmbeddedBrowser(final String browserId) {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						WebView bw = activity.browserWebViews.get(browserId);
						if (bw != null) bw.setVisibility(View.GONE);
					}
				});
        }

        // Полностью закрывает и уничтожает встроенный браузер этого конкретного
        // окна (освобождает память) — остальные открытые окна браузера не трогает.
        @JavascriptInterface
        public void closeEmbeddedBrowser(final String browserId) {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						WebView bw = activity.browserWebViews.get(browserId);
						if (bw != null) {
							activity.rootContainer.removeView(bw);
							bw.destroy();
							activity.browserWebViews.remove(browserId);
						}
						if (browserId.equals(activity.lastActiveBrowserId)) {
							activity.lastActiveBrowserId = null;
						}
						// Закрытое окно больше не может "играть видео" — убираем
						// его из учёта и пересчитываем общий сигнал, иначе обои
						// могли бы навсегда остаться приглушёнными, если видео
						// играло в момент закрытия окна (а не было поставлено
						// на паузу перед закрытием, что не вызвало бы событие 'pause').
						if (activity.browserVideoPlayingState.remove(browserId) != null) {
							boolean anyPlaying = false;
							for (Boolean v : activity.browserVideoPlayingState.values()) {
								if (v != null && v) { anyPlaying = true; break; }
							}
							activity.notifyJs("onAnyEmbeddedVideoPlaybackChanged", String.valueOf(anyPlaying));
						}
					}
				});
        }

        // ══════════════════════════════════════════════════════════════
        // FILE MANAGER BRIDGE
        // ══════════════════════════════════════════════════════════════

        // ─────────────────────────────────────────────────────────
        // SAF helpers — резолвинг относительного пути в document URI
        // относительно выбранного дерева. Путь "" означает корень дерева.
        // Сегменты разделены '/'. Каждый сегмент ищется среди детей
        // через DocumentsContract (без androidx.documentfile).
        // ─────────────────────────────────────────────────────────

        private static final String COL_DOC_ID   = DocumentsContract.Document.COLUMN_DOCUMENT_ID;
        private static final String COL_NAME     = DocumentsContract.Document.COLUMN_DISPLAY_NAME;
        private static final String COL_MIME     = DocumentsContract.Document.COLUMN_MIME_TYPE;
        private static final String COL_SIZE     = DocumentsContract.Document.COLUMN_SIZE;
        private static final String COL_MODIFIED = DocumentsContract.Document.COLUMN_LAST_MODIFIED;
        private static final String DIR_MIME     = DocumentsContract.Document.MIME_TYPE_DIR;

        // Находит document URI и mime для заданного относительного пути.
        // Возвращает массив [documentUri, mimeType] или null если не найдено.
        private String[] resolvePath(Uri treeUri, String relPath) {
            try {
                String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
                Uri currentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId);
                String currentMime = DIR_MIME;

                if (relPath == null || relPath.isEmpty()) {
                    return new String[]{ currentUri.toString(), currentMime };
                }

                String[] segments = relPath.split("/");
                for (String seg : segments) {
                    if (seg.isEmpty()) continue;
                    String parentDocId = DocumentsContract.getDocumentId(currentUri);
                    Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);

                    boolean found = false;
                    Cursor c = activity.getContentResolver().query(
                        childrenUri,
                        new String[]{ COL_DOC_ID, COL_NAME, COL_MIME },
                        null, null, null
                    );
                    if (c != null) {
                        try {
                            while (c.moveToNext()) {
                                String name = c.getString(c.getColumnIndex(COL_NAME));
                                if (seg.equals(name)) {
                                    String docId = c.getString(c.getColumnIndex(COL_DOC_ID));
                                    currentMime = c.getString(c.getColumnIndex(COL_MIME));
                                    currentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                                    found = true;
                                    break;
                                }
                            }
                        } finally {
                            c.close();
                        }
                    }
                    if (!found) return null;
                }
                return new String[]{ currentUri.toString(), currentMime };
            } catch (Exception e) {
                return null;
            }
        }

        // Возвращает [parentRelPath, name] для пути "a/b/c" -> ["a/b", "c"]
        private String[] splitParent(String relPath) {
            int idx = relPath.lastIndexOf('/');
            if (idx == -1) return new String[]{ "", relPath };
            return new String[]{ relPath.substring(0, idx), relPath.substring(idx + 1) };
        }

        private String jsonEscape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "")
				.replace("\r", "");
        }

        // Полное JSON-экранирование (в отличие от jsonEscape выше — сохраняет
        // переводы строк как \n вместо удаления). Нужно для текста с историей
        // чата/памятью GODY, где переводы строк значимы и не должны пропадать.
        private String jsonEscapeFull(String s) {
            if (s == null) return "";
            StringBuilder sb = new StringBuilder(s.length() + 16);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '\\': sb.append("\\\\"); break;
                    case '"':  sb.append("\\\""); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            return sb.toString();
        }

        // ══════════════════════════════════════════════════════════════
        // FILE MANAGER BRIDGE — SAF (Storage Access Framework)
        // path: относительный путь от корня выбранного дерева, например
        //       "" (корень), "Pictures", "Pictures/vacation/photo.jpg"
        // ══════════════════════════════════════════════════════════════

        // Список файлов и папок — возвращает JSON массив
        // path может быть с префиксом "N:relativePath" для корня N
        @JavascriptInterface
        public String listDir(String path) {
            String[] tp = parseTreePath(path);
            if (tp == null) return "[]";
            String treeUriStr = tp[0], relPath = tp[1];

            try {
                Uri treeUri = Uri.parse(treeUriStr);
                String[] resolved = resolvePath(treeUri, relPath);
                if (resolved == null) return "[]";

                Uri dirUri = Uri.parse(resolved[0]);
                String dirMime = resolved[1];
                if (!DIR_MIME.equals(dirMime)) return "[]";

                String docId = DocumentsContract.getDocumentId(dirUri);
                Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);

                java.util.List<String[]> entries = new java.util.ArrayList<String[]>();
                Cursor c = activity.getContentResolver().query(
                    childrenUri,
                    new String[]{ COL_DOC_ID, COL_NAME, COL_MIME, COL_SIZE, COL_MODIFIED },
                    null, null, null
                );
                if (c != null) {
                    try {
                        while (c.moveToNext()) {
                            String name = c.getString(c.getColumnIndex(COL_NAME));
                            String mime = c.getString(c.getColumnIndex(COL_MIME));
                            long size = c.getLong(c.getColumnIndex(COL_SIZE));
                            long modified = c.getLong(c.getColumnIndex(COL_MODIFIED));
                            boolean isDir = DIR_MIME.equals(mime);
                            if (name != null && name.startsWith(".")) continue; // скрытые
                            entries.add(new String[]{
											name,
											isDir ? "dir" : "file",
											String.valueOf(isDir ? 0 : size),
											String.valueOf(modified)
										});
                        }
                    } finally {
                        c.close();
                    }
                }

                // Сортировка: папки первыми, потом по имени
                java.util.Collections.sort(entries, new java.util.Comparator<String[]>() {
						public int compare(String[] a, String[] b) {
							boolean aDir = a[1].equals("dir");
							boolean bDir = b[1].equals("dir");
							if (aDir && !bDir) return -1;
							if (!aDir && bDir) return 1;
							return a[0].compareToIgnoreCase(b[0]);
						}
					});

                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (String[] e : entries) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{");
                    sb.append("\"name\":\"").append(jsonEscape(e[0])).append("\",");
                    sb.append("\"type\":\"").append(e[1]).append("\",");
                    sb.append("\"size\":").append(e[2]).append(",");
                    sb.append("\"canRead\":true,");
                    sb.append("\"canWrite\":true,");
                    sb.append("\"modified\":").append(e[3]);
                    sb.append("}");
                }
                sb.append("]");
                return sb.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        // Читает текстовый файл (до 512KB)
        @JavascriptInterface
        public String readFile(String path) {
            String[] tp = parseTreePath(path);
            if (tp == null) return "__ERR__:No folder selected";
            String treeUriStr = tp[0], relPath = tp[1];

            try {
                Uri treeUri = Uri.parse(treeUriStr);
                String[] resolved = resolvePath(treeUri, relPath);
                if (resolved == null) return "__ERR__:File not found";

                Uri fileUri = Uri.parse(resolved[0]);
                if (DIR_MIME.equals(resolved[1])) return "__ERR__:Is a directory";

                java.io.InputStream is = activity.getContentResolver().openInputStream(fileUri);
                if (is == null) return "__ERR__:Cannot open";

                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] tmp = new byte[8192];
                int total = 0, n;
                while ((n = is.read(tmp)) != -1) {
                    total += n;
                    if (total > 10 * 1024 * 1024) { is.close(); return "__ERR__:File too large (max 10MB)"; }
                    buffer.write(tmp, 0, n);
                }
                is.close();
                return new String(buffer.toByteArray(), "UTF-8");
            } catch (Exception e) {
                return "__ERR__:" + e.getMessage();
            }
        }

        // Читает любой файл (бинарный, до 25MB) и возвращает Base64 строку
        // Используется для аудио/изображений — JS строит data: URI
        @JavascriptInterface
        public String readFileBase64(String path) {
            String[] tp = parseTreePath(path);
            if (tp == null) return "__ERR__:No folder selected";
            String treeUriStr = tp[0], relPath = tp[1];

            try {
                Uri treeUri = Uri.parse(treeUriStr);
                String[] resolved = resolvePath(treeUri, relPath);
                if (resolved == null) return "__ERR__:File not found";

                Uri fileUri = Uri.parse(resolved[0]);
                if (DIR_MIME.equals(resolved[1])) return "__ERR__:Is a directory";

                java.io.InputStream is = activity.getContentResolver().openInputStream(fileUri);
                if (is == null) return "__ERR__:Cannot open";

                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] tmp = new byte[16384];
                int total = 0, n;
                while ((n = is.read(tmp)) != -1) {
                    total += n;
                    if (total > 25 * 1024 * 1024) { is.close(); return "__ERR__:File too large (max 25MB)"; }
                    buffer.write(tmp, 0, n);
                }
                is.close();
                return android.util.Base64.encodeToString(buffer.toByteArray(), android.util.Base64.NO_WRAP);
            } catch (Exception e) {
                return "__ERR__:" + e.getMessage();
            }
        }

        // Возвращает MIME-тип файла (для построения data: URI на стороне JS)
        @JavascriptInterface
        public String getMimeType(String path) {
            String[] tp = parseTreePath(path);
            if (tp == null) return "application/octet-stream";
            String treeUriStr = tp[0], relPath = tp[1];

            try {
                Uri treeUri = Uri.parse(treeUriStr);
                String[] resolved = resolvePath(treeUri, relPath);
                if (resolved != null && resolved[1] != null && !resolved[1].isEmpty()
					&& !DIR_MIME.equals(resolved[1])) {
                    return resolved[1];
                }
            } catch (Exception e) { /* fall through */ }
            return getMimeFromName(relPath);
        }

        // Записывает текстовый файл (создаёт если не существует)
        @JavascriptInterface
        public String writeFile(String path, String content) {
            String[] tp = parseTreePath(path);
            if (tp == null) return "ERROR:No folder selected";
            String treeUriStr = tp[0], relPath = tp[1];

            try {
                Uri treeUri = Uri.parse(treeUriStr);
                String[] resolved = resolvePath(treeUri, relPath);
                Uri fileUri;

                if (resolved == null) {
                    // Файл не существует — создаём в родительской папке
                    String[] pn = splitParent(relPath);
                    String[] parentResolved = resolvePath(treeUri, pn[0]);
                    if (parentResolved == null) return "ERROR:Parent folder not found";

                    Uri parentUri = Uri.parse(parentResolved[0]);
                    String parentDocId = DocumentsContract.getDocumentId(parentUri);
                    String mime = guessMimeForName(pn[1]);
                    fileUri = DocumentsContract.createDocument(
                        activity.getContentResolver(), parentUri, mime, pn[1]
                    );
                    if (fileUri == null) return "ERROR:Cannot create file";
                } else {
                    if (DIR_MIME.equals(resolved[1])) return "ERROR:Is a directory";
                    fileUri = Uri.parse(resolved[0]);
                }

                java.io.OutputStream os = activity.getContentResolver().openOutputStream(fileUri, "wt");
                if (os == null) return "ERROR:Cannot open for writing";
                os.write(content.getBytes("UTF-8"));
                os.flush();
                os.close();
                return "OK";
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            }
        }

        // Удаляет файл или папку (рекурсивно для папок — через DocumentsContract)
        @JavascriptInterface
        public String deleteFile(String path) {
            String[] tp = parseTreePath(path);
            if (tp == null) return "ERROR:No folder selected";
            String treeUriStr = tp[0], relPath = tp[1];

            try {
                Uri treeUri = Uri.parse(treeUriStr);
                String[] resolved = resolvePath(treeUri, relPath);
                if (resolved == null) return "ERROR:Not found";

                Uri targetUri = Uri.parse(resolved[0]);
                boolean ok = DocumentsContract.deleteDocument(activity.getContentResolver(), targetUri);
                return ok ? "OK" : "ERROR:Cannot delete";
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            }
        }

        // Переименовывает файл/папку (в той же родительской папке, в том же корне)
        @JavascriptInterface
        public String renameFile(String oldPath, String newPath) {
            String[] tpOld = parseTreePath(oldPath);
            String[] tpNew = parseTreePath(newPath);
            if (tpOld == null || tpNew == null) return "ERROR:No folder selected";
            if (!tpOld[0].equals(tpNew[0])) return "ERROR:Cannot rename across different roots";

            String treeUriStr = tpOld[0];
            String oldRel = tpOld[1], newRel = tpNew[1];

            try {
                Uri treeUri = Uri.parse(treeUriStr);
                String[] oldResolved = resolvePath(treeUri, oldRel);
                if (oldResolved == null) return "ERROR:Source not found";

                String[] oldPn = splitParent(oldRel);
                String[] newPn = splitParent(newRel);
                if (!oldPn[0].equals(newPn[0])) {
                    return "ERROR:Move between folders not supported, only rename";
                }

                String[] existingNew = resolvePath(treeUri, newRel);
                if (existingNew != null) return "ERROR:Destination exists";

                Uri srcUri = Uri.parse(oldResolved[0]);
                Uri renamed = DocumentsContract.renameDocument(
                    activity.getContentResolver(), srcUri, newPn[1]
                );
                return renamed != null ? "OK" : "ERROR:Cannot rename";
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            }
        }

        // Создаёт папку
        @JavascriptInterface
        public String makeDir(String path) {
            String[] tp = parseTreePath(path);
            if (tp == null) return "ERROR:No folder selected";
            String treeUriStr = tp[0], relPath = tp[1];

            try {
                Uri treeUri = Uri.parse(treeUriStr);

                // Уже существует?
                if (resolvePath(treeUri, relPath) != null) return "ERROR:Already exists";

                String[] pn = splitParent(relPath);
                String[] parentResolved = resolvePath(treeUri, pn[0]);
                if (parentResolved == null) return "ERROR:Parent folder not found";

                Uri parentUri = Uri.parse(parentResolved[0]);
                Uri created = DocumentsContract.createDocument(
                    activity.getContentResolver(), parentUri, DIR_MIME, pn[1]
                );
                return created != null ? "OK" : "ERROR:Cannot create";
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            }
        }

        // ── Copy / Move — поддерживает перемещение между разными деревьями
        // SAF (разными добавленными корнями), в отличие от renameFile (которая
        // умеет только переименовать в той же папке). Реализовано через
        // потоковое чтение+запись (а не DocumentsContract.moveDocument,
        // который появился только в API 24 — а minSdk проекта 21), поэтому
        // работает на любой поддерживаемой версии Android.
        //
        // copyFile/moveFile рекурсивно обходят папки. destDirPath — путь
        // ЦЕЛЕВОЙ ПАПКИ (куда кладём), а не путь нового файла — имя берётся
        // из srcPath автоматически. Если в целевой папке уже есть файл с
        // таким именем, к имени добавляется "(2)", "(3)" и т.д.

        @JavascriptInterface
        public String copyFile(String srcPath, String destDirPath) {
            return copyOrMove(srcPath, destDirPath, false);
        }

        @JavascriptInterface
        public String moveFile(String srcPath, String destDirPath) {
            return copyOrMove(srcPath, destDirPath, true);
        }

        private String copyOrMove(String srcPath, String destDirPath, boolean deleteSource) {
            String[] tpSrc = parseTreePath(srcPath);
            String[] tpDest = parseTreePath(destDirPath);
            if (tpSrc == null || tpDest == null) return "ERROR:No folder selected";

            try {
                Uri srcTreeUri = Uri.parse(tpSrc[0]);
                Uri destTreeUri = Uri.parse(tpDest[0]);
                String srcRel = tpSrc[1];
                String destDirRel = tpDest[1];

                String[] srcResolved = resolvePath(srcTreeUri, srcRel);
                if (srcResolved == null) return "ERROR:Source not found";
                String[] destDirResolved = resolvePath(destTreeUri, destDirRel);
                if (destDirResolved == null) return "ERROR:Destination folder not found";
                if (!DIR_MIME.equals(destDirResolved[1])) return "ERROR:Destination is not a folder";

                String[] srcPn = splitParent(srcRel);
                String srcName = srcPn[1];

                // Перемещение/копирование "в саму себя" или в свою же подпапку
                // привело бы к бесконечной рекурсии или потере данных — блокируем.
                String destRelWithSlash = destDirRel.isEmpty() ? "" : destDirRel + "/";
                if (tpSrc[0].equals(tpDest[0]) &&
					(destDirRel.equals(srcRel) || destRelWithSlash.startsWith(srcRel + "/"))) {
                    return "ERROR:Cannot move a folder into itself";
                }

                // Не перезаписываем существующий файл — подбираем свободное имя
                // вида "name (2).ext" аналогично поведению десктопных ОС.
                String finalName = findAvailableName(destTreeUri, destDirRel, srcName);

                boolean isDir = DIR_MIME.equals(srcResolved[1]);
                Uri destParentUri = Uri.parse(destDirResolved[0]);

                if (isDir) {
                    Uri newDirUri = DocumentsContract.createDocument(
                        activity.getContentResolver(), destParentUri, DIR_MIME, finalName
                    );
                    if (newDirUri == null) return "ERROR:Cannot create destination folder";
                    String newDirRel = destDirRel.isEmpty() ? finalName : destDirRel + "/" + finalName;
                    String err = copyDirRecursive(srcTreeUri, srcRel, destTreeUri, newDirRel);
                    if (err != null) return err;
                } else {
                    String mime = srcResolved[1];
                    Uri newFileUri = DocumentsContract.createDocument(
                        activity.getContentResolver(), destParentUri,
                        mime != null && !mime.isEmpty() ? mime : "application/octet-stream",
                        finalName
                    );
                    if (newFileUri == null) return "ERROR:Cannot create destination file";
                    String err = copyFileContents(Uri.parse(srcResolved[0]), newFileUri);
                    if (err != null) return err;
                }

                if (deleteSource) {
                    Uri srcUri = Uri.parse(srcResolved[0]);
                    DocumentsContract.deleteDocument(activity.getContentResolver(), srcUri);
                    // Не считаем ошибкой если удаление исходника не удалось —
                    // копия уже на месте, это лучше чем потерять данные.
                }

                return "OK";
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            }
        }

        // Копирует один файл байт-в-байт через потоки (до 512MB — разумный
        // предел, чтобы не зависнуть навечно на огромных видео через WebView).
        private String copyFileContents(Uri srcUri, Uri destUri) {
            java.io.InputStream is = null;
            java.io.OutputStream os = null;
            try {
                is = activity.getContentResolver().openInputStream(srcUri);
                os = activity.getContentResolver().openOutputStream(destUri, "wt");
                if (is == null || os == null) return "ERROR:Cannot open streams";
                byte[] buf = new byte[65536];
                long total = 0;
                long maxBytes = 512L * 1024 * 1024;
                int n;
                while ((n = is.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) return "ERROR:File too large to copy (max 512MB)";
                    os.write(buf, 0, n);
                }
                os.flush();
                return null; // success
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            } finally {
                try { if (is != null) is.close(); } catch (Exception e) { /* ignore */ }
                try { if (os != null) os.close(); } catch (Exception e) { /* ignore */ }
            }
        }

        // Рекурсивно копирует содержимое папки srcRel (в дереве srcTreeUri)
        // в уже существующую папку destRel (в дереве destTreeUri).
        private String copyDirRecursive(Uri srcTreeUri, String srcRel, Uri destTreeUri, String destRel) {
            try {
                String[] srcResolved = resolvePath(srcTreeUri, srcRel);
                if (srcResolved == null) return "ERROR:Source folder vanished mid-copy";
                Uri srcDirUri = Uri.parse(srcResolved[0]);
                String srcDocId = DocumentsContract.getDocumentId(srcDirUri);
                Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(srcTreeUri, srcDocId);

                String[] destResolved = resolvePath(destTreeUri, destRel);
                if (destResolved == null) return "ERROR:Destination folder vanished mid-copy";
                Uri destDirUri = Uri.parse(destResolved[0]);

                Cursor c = activity.getContentResolver().query(
                    childrenUri, new String[]{ COL_DOC_ID, COL_NAME, COL_MIME }, null, null, null
                );
                if (c != null) {
                    try {
                        while (c.moveToNext()) {
                            String name = c.getString(c.getColumnIndex(COL_NAME));
                            String mime = c.getString(c.getColumnIndex(COL_MIME));
                            String childSrcRel = srcRel.isEmpty() ? name : srcRel + "/" + name;
                            boolean childIsDir = DIR_MIME.equals(mime);

                            if (childIsDir) {
                                Uri newSubDir = DocumentsContract.createDocument(
                                    activity.getContentResolver(), destDirUri, DIR_MIME, name
                                );
                                if (newSubDir == null) return "ERROR:Cannot create subfolder " + name;
                                String childDestRel = destRel.isEmpty() ? name : destRel + "/" + name;
                                String err = copyDirRecursive(srcTreeUri, childSrcRel, destTreeUri, childDestRel);
                                if (err != null) return err;
                            } else {
                                String[] childResolved = resolvePath(srcTreeUri, childSrcRel);
                                if (childResolved == null) continue;
                                Uri newFileUri = DocumentsContract.createDocument(
                                    activity.getContentResolver(), destDirUri,
                                    mime != null && !mime.isEmpty() ? mime : "application/octet-stream",
                                    name
                                );
                                if (newFileUri == null) return "ERROR:Cannot create file " + name;
                                String err = copyFileContents(Uri.parse(childResolved[0]), newFileUri);
                                if (err != null) return err;
                            }
                        }
                    } finally {
                        c.close();
                    }
                }
                return null; // success
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            }
        }

        // Подбирает свободное имя в целевой папке: если "photo.jpg" уже занято,
        // пробует "photo (2).jpg", "photo (3).jpg" и так далее.
        private String findAvailableName(Uri treeUri, String destDirRel, String desiredName) {
            String existingCheck = destDirRel.isEmpty() ? desiredName : destDirRel + "/" + desiredName;
            if (resolvePath(treeUri, existingCheck) == null) return desiredName;

            String base = desiredName;
            String ext = "";
            int dotIdx = desiredName.lastIndexOf('.');
            // Не отделяем расширение у скрытых файлов вида ".gitignore"
            if (dotIdx > 0) {
                base = desiredName.substring(0, dotIdx);
                ext = desiredName.substring(dotIdx);
            }
            for (int i = 2; i < 1000; i++) {
                String candidate = base + " (" + i + ")" + ext;
                String candidateRel = destDirRel.isEmpty() ? candidate : destDirRel + "/" + candidate;
                if (resolvePath(treeUri, candidateRel) == null) return candidate;
            }
            return desiredName + "_" + System.currentTimeMillis(); // крайний случай
        }

        private String guessMimeForName(String name) {
            String ext = name.contains(".")
                ? name.substring(name.lastIndexOf('.') + 1).toLowerCase()
                : "";
            if (ext.equals("txt") || ext.equals("log") || ext.equals("md")) return "text/plain";
            if (ext.equals("json")) return "application/json";
            if (ext.equals("html") || ext.equals("htm")) return "text/html";
            if (ext.equals("js")) return "text/javascript";
            if (ext.equals("xml")) return "text/xml";
            return "application/octet-stream";
        }

        // Возвращает размер файла
        @JavascriptInterface
        public long getFileSize(String path) {
            String[] tp = parseTreePath(path);
            if (tp == null) return -1;
            String treeUriStr = tp[0], relPath = tp[1];

            try {
                Uri treeUri = Uri.parse(treeUriStr);
                String[] resolved = resolvePath(treeUri, relPath);
                if (resolved == null) return -1;
                if (DIR_MIME.equals(resolved[1])) return 0;

                Uri fileUri = Uri.parse(resolved[0]);
                Cursor c = activity.getContentResolver().query(
                    fileUri, new String[]{ COL_SIZE }, null, null, null
                );
                if (c != null) {
                    try {
                        if (c.moveToFirst()) return c.getLong(c.getColumnIndex(COL_SIZE));
                    } finally { c.close(); }
                }
                return -1;
            } catch (Exception e) { return -1; }
        }

        // Возвращает информацию о первом корне SAF (для проверки "выбрана ли хоть одна папка")
        @JavascriptInterface
        public String getStoragePaths() {
            java.util.List<String> uris = getValidTreeUris();
            if (uris.isEmpty()) {
                return "{\"hasRoot\":false}";
            }
            try {
                String treeUriStr = uris.get(0);
                Uri treeUri = Uri.parse(treeUriStr);
                String rootName = "Selected Folder";

                // Пробуем получить имя корневой папки
                String[] resolved = resolvePath(treeUri, "");
                if (resolved != null) {
                    Uri rootUri = Uri.parse(resolved[0]);
                    Cursor c = activity.getContentResolver().query(
                        rootUri, new String[]{ COL_NAME }, null, null, null
                    );
                    if (c != null) {
                        try {
                            if (c.moveToFirst()) {
                                String n = c.getString(c.getColumnIndex(COL_NAME));
                                if (n != null && !n.isEmpty()) rootName = n;
                            }
                        } finally { c.close(); }
                    }
                }

                return "{"
                    + "\"hasRoot\":true,"
                    + "\"rootCount\":" + uris.size() + ","
                    + "\"rootName\":\"" + jsonEscape(rootName) + "\","
                    + "\"treeUri\":\"" + jsonEscape(treeUriStr) + "\""
                    + "}";
            } catch (Exception e) {
                return "{\"hasRoot\":false}";
            }
        }

        // Открывает системный диалог выбора папки (SAF) — вызывается из JS
        // Можно вызывать многократно — каждая выбранная папка добавляется в список
        @JavascriptInterface
        public void pickFolder() {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						try {
							Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
							intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
											| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
											| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
							activity.startActivityForResult(intent, SAF_PICK_TREE_CODE);
						} catch (Exception e) {
							android.widget.Toast.makeText(activity,
														  "Cannot open folder picker: " + e.getMessage(),
														  android.widget.Toast.LENGTH_LONG).show();
						}
					}
				});
        }
        // Тот же принцип, что pickWallpaperFile() выше, но для загрузки файла
        // в Store (картинка ИЛИ видео-обои для публикации) — отдельный код
        // запроса, чтобы результат шёл в другое JS-событие.
        // ── Сохраняет скачанный HTML-файл обновления во внутреннее хранилище ──
        // При следующем старте MainActivity проверит этот файл и загрузит
        // его вместо assets/index.html (см. логику в onCreate выше).
        // Возвращает "ok" или "error:..." в JS-колбэк.
        @JavascriptInterface
        public void saveUpdateFile(final String content, final String callbackName) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        java.io.File f = new java.io.File(activity.getFilesDir(), "xomnia_update.html");
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                        fos.write(content.getBytes("UTF-8"));
                        fos.close();
                        activity.runOnUiThread(new Runnable() {
                            public void run() {
                                activity.notifyJs(callbackName, "ok");
                            }
                        });
                    } catch (final Exception e) {
                        activity.runOnUiThread(new Runnable() {
                            public void run() {
                                activity.notifyJs(callbackName, "error:" + e.getMessage());
                            }
                        });
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public boolean hasUpdateFile() {
            return new java.io.File(activity.getFilesDir(), "xomnia_update.html").exists();
        }

        @JavascriptInterface
        public void deleteUpdateFile() {
            new java.io.File(activity.getFilesDir(), "xomnia_update.html").delete();
        }

        // Играет звук из assets через нативный Android MediaPlayer — минуя
        // WebView autoplay-ограничения (которые блокируют Audio/AudioContext
        // без пользовательского жеста). Используется для звуков загрузочной
        // анимации, которые должны играть без тапа.
        // delayMs — задержка в миллисекундах от момента вызова.
        @JavascriptInterface
        public void scheduleSound(final String filename, final int delayMs) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        if (delayMs > 0) Thread.sleep(delayMs);
                        android.media.MediaPlayer mp = new android.media.MediaPlayer();
                        android.content.res.AssetFileDescriptor afd =
                            activity.getAssets().openFd(filename);
                        mp.setDataSource(
                            afd.getFileDescriptor(),
                            afd.getStartOffset(),
                            afd.getLength()
                        );
                        afd.close();
                        mp.prepare();
                        mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                            public void onCompletion(android.media.MediaPlayer p) {
                                p.release();
                            }
                        });
                        mp.start();
                    } catch (Exception e) {
                        // Файл не найден или MediaPlayer недоступен — тихо игнорируем
                    }
                }
            }).start();
        }

        // Пикер без фильтра по типу — для выбора любых файлов (в т.ч. HTML).
        // Используется при публикации обновления (index.html не является
        // image/* или video/*, поэтому обычный pickStoreFile его не видит).
        @JavascriptInterface
        public void pickAnyFile() {
            activity.runOnUiThread(new Runnable() {
                public void run() {
                    // Пробуем несколько стратегий по очереди — Samsung по-разному
                    // реагирует на разные Intent в зависимости от версии OneUI.
                    boolean started = false;
                    // Стратегия 1: ACTION_OPEN_DOCUMENT с */* и SHOW_ADVANCED флагами
                    if (!started) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            intent.setType("*/*");
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            intent.putExtra("android.provider.extra.SHOW_ADVANCED", true);
                            intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            activity.startActivityForResult(intent, SAF_PICK_STORE_FILE_CODE);
                            started = true;
                        } catch (Exception e) { /* попробуем следующую стратегию */ }
                    }
                    // Стратегия 2: ACTION_GET_CONTENT без chooser-обёртки
                    if (!started) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                            intent.setType("*/*");
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            activity.startActivityForResult(intent, SAF_PICK_STORE_FILE_CODE);
                            started = true;
                        } catch (Exception e) { /* попробуем следующую стратегию */ }
                    }
                    // Стратегия 3: через createChooser как запасной вариант
                    if (!started) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                            intent.setType("text/html");
                            activity.startActivityForResult(
                                Intent.createChooser(intent, "Select index.html"),
                                SAF_PICK_STORE_FILE_CODE
                            );
                        } catch (Exception e) {
                            android.widget.Toast.makeText(activity,
                                "File picker unavailable", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });
        }

        public void pickStoreFile() {
            activity.runOnUiThread(new Runnable() {
				public void run() {
					try {
						Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
						intent.setType("*/*");
						intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
						intent.addCategory(Intent.CATEGORY_OPENABLE);
						intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
									| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
						activity.startActivityForResult(intent, SAF_PICK_STORE_FILE_CODE);
					} catch (Exception e) {
						android.widget.Toast.makeText(activity,
													"Cannot open file picker: " + e.getMessage(),
													android.widget.Toast.LENGTH_LONG).show();
					}
				}
			});
        }

        // Выбор фотографии для аватара аккаунта — только image/*, в отличие
        // от pickStoreFile (который допускает и видео).
        @JavascriptInterface
        public void pickProfilePhoto() {
            activity.runOnUiThread(new Runnable() {
				public void run() {
					try {
						Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
						intent.setType("image/*");
						intent.addCategory(Intent.CATEGORY_OPENABLE);
						intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
									| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
						activity.startActivityForResult(intent, SAF_PICK_PROFILE_PHOTO_CODE);
					} catch (Exception e) {
						android.widget.Toast.makeText(activity,
													"Cannot open file picker: " + e.getMessage(),
													android.widget.Toast.LENGTH_LONG).show();
					}
				}
			});
        }

        // Открывает системный диалог выбора ОДНОГО файла (картинка или видео)
        // для использования как обои рабочего стола.
        @JavascriptInterface
        public void pickWallpaperFile() {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						try {
							Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
							intent.setType("*/*");
							intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
							intent.addCategory(Intent.CATEGORY_OPENABLE);
							intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
											| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
							activity.startActivityForResult(intent, SAF_PICK_WALLPAPER_FILE_CODE);
						} catch (Exception e) {
							android.widget.Toast.makeText(activity,
														  "Cannot open file picker: " + e.getMessage(),
														  android.widget.Toast.LENGTH_LONG).show();
						}
					}
				});
        }

        // Открывает системный диалог голосового ввода (Android RecognizerIntent) —
        // встроен в ОС через Google app на большинстве устройств, не требует
        // собственного UI или сетевого кода с нашей стороны. Результат приходит
        // в onActivityResult → notifyJs("onVoiceInputResult", text).
        @JavascriptInterface
        public void startVoiceInput() {
            activity.runOnUiThread(new Runnable() {
					public void run() {
						try {
							Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
							intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
											android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
							// Без явного EXTRA_LANGUAGE — распознаватель использует текущий
							// язык устройства, что для русско- и англоязычных пользователей
							// работает корректно без доп. настройки с нашей стороны.
							intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Говорите...");
							// Проверяем, что на устройстве вообще есть кто отвечать на этот
							// интент (не на всех устройствах без Google-сервисов есть
							// распознаватель речи) — иначе startActivityForResult бросит
							// ActivityNotFoundException и приложение крашнется.
							if (intent.resolveActivity(activity.getPackageManager()) != null) {
								activity.startActivityForResult(intent, VOICE_INPUT_REQUEST_CODE);
							} else {
								notifyJs("onVoiceInputUnavailable", "");
							}
						} catch (Exception e) {
							notifyJs("onVoiceInputUnavailable", "");
						}
					}
				});
        }


        // Возвращает JSON массив: [{"index":0,"name":"Internal Storage"}, ...]
		 @JavascriptInterface
        public String listRoots() {
            java.util.List<String> uris = getValidTreeUris();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < uris.size(); i++) {
                if (i > 0) sb.append(",");
                String name = "Folder " + (i+1);
                try {
                    Uri treeUri = Uri.parse(uris.get(i));
                    String[] resolved = resolvePath(treeUri, "");
                    if (resolved != null) {
                        Uri rootUri = Uri.parse(resolved[0]);
                        Cursor c = activity.getContentResolver().query(
                            rootUri, new String[]{ COL_NAME }, null, null, null
                        );
                        if (c != null) {
                            try {
                                if (c.moveToFirst()) {
                                    String n = c.getString(c.getColumnIndex(COL_NAME));
                                    if (n != null && !n.isEmpty()) name = n;
                                }
                            } finally { c.close(); }
                        }
                    }
                } catch (Exception e) { /* keep default name */ }
                sb.append("{\"index\":").append(i)
					.append(",\"name\":\"").append(jsonEscape(name)).append("\"}");
            }
            sb.append("]");
            return sb.toString();
        }

        // Удаляет корень по индексу (освобождает разрешение)
        @JavascriptInterface
        public void removeRoot(int index) {
            java.util.List<String> uris = activity.getTreeUriList();
            if (index < 0 || index >= uris.size()) return;
            String uri = uris.get(index);
            activity.removeTreeUri(uri);
        }

        // Возвращает список валидных (всё ещё разрешённых) URI деревьев
        private java.util.List<String> getValidTreeUris() {
            java.util.List<String> raw = activity.getTreeUriList();
            java.util.List<String> valid = new java.util.ArrayList<String>();
            java.util.List<android.content.UriPermission> perms =
                activity.getContentResolver().getPersistedUriPermissions();
            for (String s : raw) {
                try {
                    Uri u = Uri.parse(s);
                    for (android.content.UriPermission p : perms) {
                        if (p.getUri().equals(u) && p.isReadPermission()) {
                            valid.add(s);
                            break;
                        }
                    }
                } catch (Exception e) { /* skip invalid */ }
            }
            return valid;
        }

        // ── Парсинг пути с префиксом индекса дерева: "0:Pictures/photo.jpg" ──
        // Возвращает [treeUriString, relativePath] или null если индекс некорректен.
        // Путь без префикса "N:" интерпретируется как индекс 0 (обратная совместимость).
        private String[] parseTreePath(String path) {
            java.util.List<String> uris = getValidTreeUris();
            if (uris.isEmpty()) return null;

            int colonIdx = path.indexOf(':');
            if (colonIdx > 0 && colonIdx <= 2) {
                try {
                    int idx = Integer.parseInt(path.substring(0, colonIdx));
                    if (idx >= 0 && idx < uris.size()) {
                        return new String[]{ uris.get(idx), path.substring(colonIdx + 1) };
                    }
                } catch (NumberFormatException e) { /* not a prefix, fall through */ }
            }
            // Без префикса — используем первый корень, путь как есть
            return new String[]{ uris.get(0), path };
        }

        // Открывает файл через системное приложение (SAF content:// URI)
        @JavascriptInterface
        public void openFileWith(final String path) {
            final String[] tp = parseTreePath(path);
            if (tp == null) {
                android.widget.Toast.makeText(activity, "No folder selected",
											  android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            final String treeUriStr = tp[0];
            final String relPath = tp[1];
            activity.runOnUiThread(new Runnable() {
					public void run() {
						try {
							Uri treeUri = Uri.parse(treeUriStr);
							String[] resolved = resolvePath(treeUri, relPath);
							if (resolved == null) {
								android.widget.Toast.makeText(activity, "File not found",
															  android.widget.Toast.LENGTH_SHORT).show();
								return;
							}
							Uri fileUri = Uri.parse(resolved[0]);
							String mime = resolved[1];
							if (mime == null || mime.isEmpty() || DIR_MIME.equals(mime)) {
								mime = getMimeFromName(relPath);
							}

							Intent intent = new Intent(Intent.ACTION_VIEW);
							intent.setDataAndType(fileUri, mime);
							intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
							intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
							activity.startActivity(Intent.createChooser(intent, "Open with"));
						} catch (Exception e) {
							android.widget.Toast.makeText(activity,
														  "Cannot open: " + e.getMessage(),
														  android.widget.Toast.LENGTH_LONG).show();
						}
					}
				});
        }

        // ── Поделиться файлом через системный Android share-чузер ────────
        // Используется когда пользователь перетаскивает файл на окно
        // встроенного браузера, открытого на веб-Telegram (или любой другой
        // сайт) — настоящий drag-and-drop с подхватом файла самой веб-страницей
        // здесь невозможен (это два изолированных WebView, между которыми нет
        // способа передать файловый DataTransfer), поэтому вместо имитации
        // используется честный системный механизм: ACTION_SEND с выбором
        // получателя. Если на устройстве установлен Telegram как нативное
        // приложение, он появится в списке и реально получит файл; если нет —
        // пользователь увидит обычный список приложений, способных принять файл
        // (включая "Скопировать в", Bluetooth и т.п.).
        @JavascriptInterface
        public void shareFile(final String path) {
            final String[] tp = parseTreePath(path);
            if (tp == null) {
                android.widget.Toast.makeText(activity, "No folder selected",
											  android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            final String treeUriStr = tp[0];
            final String relPath = tp[1];
            activity.runOnUiThread(new Runnable() {
					public void run() {
						try {
							Uri treeUri = Uri.parse(treeUriStr);
							String[] resolved = resolvePath(treeUri, relPath);
							if (resolved == null) {
								android.widget.Toast.makeText(activity, "File not found",
															  android.widget.Toast.LENGTH_SHORT).show();
								return;
							}
							if (DIR_MIME.equals(resolved[1])) {
								android.widget.Toast.makeText(activity, "Cannot share a folder",
															  android.widget.Toast.LENGTH_SHORT).show();
								return;
							}
							Uri fileUri = Uri.parse(resolved[0]);
							String mime = resolved[1];
							if (mime == null || mime.isEmpty()) {
								mime = getMimeFromName(relPath);
							}

							Intent intent = new Intent(Intent.ACTION_SEND);
							intent.setType(mime);
							intent.putExtra(Intent.EXTRA_STREAM, fileUri);
							intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
							intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
							activity.startActivity(Intent.createChooser(intent, "Share via"));
						} catch (Exception e) {
							android.widget.Toast.makeText(activity,
														  "Cannot share: " + e.getMessage(),
														  android.widget.Toast.LENGTH_LONG).show();
						}
					}
				});
        }

        private String getMimeFromName(String path) {
            String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            String ext = name.contains(".")
                ? name.substring(name.lastIndexOf('.') + 1).toLowerCase()
                : "";
            if (ext.equals("jpg") || ext.equals("jpeg")) return "image/jpeg";
            if (ext.equals("png"))  return "image/png";
            if (ext.equals("gif"))  return "image/gif";
            if (ext.equals("webp")) return "image/webp";
            if (ext.equals("mp3"))  return "audio/mpeg";
            if (ext.equals("wav"))  return "audio/wav";
            if (ext.equals("mp4"))  return "video/mp4";
            if (ext.equals("mkv"))  return "video/x-matroska";
            if (ext.equals("pdf"))  return "application/pdf";
            if (ext.equals("apk"))  return "application/vnd.android.package-archive";
            if (ext.equals("txt") || ext.equals("log")) return "text/plain";
            if (ext.equals("html") || ext.equals("htm")) return "text/html";
            if (ext.equals("json")) return "application/json";
            return "*/*";
        }
    }
}
