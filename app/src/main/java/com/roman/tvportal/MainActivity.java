package com.roman.tvportal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.CaptivePortal;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Браузер для входа в сеть провайдера (captive portal) на Android TV.
 * - сам проверяет, есть ли интернет, и открывает страницу авторизации;
 * - управление пультом: виртуальный курсор (стрелки + OK);
 * - может хранить логин/пароль и подставлять их в форму.
 */
public class MainActivity extends Activity {

    private static final String CHECK_URL = "http://connectivitycheck.gstatic.com/generate_204";
    private static final String EXTRA_PORTAL_URL = "android.net.extra.CAPTIVE_PORTAL_URL";

    private static final String P_PORTAL = "portal_url";
    private static final String P_LOGIN = "login";
    private static final String P_PASS = "password";
    private static final String P_AUTOFILL = "autofill";
    private static final String P_CURSOR = "cursor";
    private static final String P_MODE = "mode";
    private static final String P_AUTOSUBMIT = "autosubmit";
    private static final String MODE_VOUCHER = "voucher";
    private static final String MODE_MEMBER = "member";
    // Страница входа провайдера (Nobel, hotspot "wlfl")
    private static final String DEFAULT_PORTAL = "http://login3.net/login";

    private WebView web;
    private CursorView cursor;
    private TextView statusText, urlText;
    private ProgressBar progress;
    private Button btnCheck, btnCursor;
    private SharedPreferences prefs;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private boolean cursorMode = true;
    private boolean online = false;
    private boolean checking = false;
    private String detectedPortalUrl = null;

    // Данные из системного уведомления "Войдите в сеть" (Android 6+)
    private Network portalNetwork = null;
    private Object captivePortal = null;

    private final Runnable recheck = new Runnable() {
        @Override public void run() { checkInternet(false); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE);

        web = findViewById(R.id.web);
        cursor = findViewById(R.id.cursor);
        statusText = findViewById(R.id.status);
        urlText = findViewById(R.id.url);
        progress = findViewById(R.id.progress);
        btnCheck = findViewById(R.id.btnCheck);
        btnCursor = findViewById(R.id.btnCursor);

        btnCheck.setOnClickListener(v -> checkInternet(true));
        findViewById(R.id.btnLogin).setOnClickListener(v -> openLoginPage());
        findViewById(R.id.btnFill).setOnClickListener(v -> fillCredentials(true));
        findViewById(R.id.btnBack).setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); });
        findViewById(R.id.btnReload).setOnClickListener(v -> web.reload());
        findViewById(R.id.btnUrl).setOnClickListener(v -> showUrlDialog());
        findViewById(R.id.btnCreds).setOnClickListener(v -> showCredentialsDialog());
        btnCursor.setOnClickListener(v -> setCursorMode(!cursorMode));

        setupWebView();
        setCursorMode(prefs.getBoolean(P_CURSOR, true));
        btnCheck.requestFocus();

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        bg.shutdownNow();
        if (portalNetwork != null && Build.VERSION.SDK_INT >= 23) {
            try {
                ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).bindProcessToNetwork(null);
            } catch (Exception ignored) { }
        }
        web.destroy();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- intent

    private void handleIntent(Intent i) {
        String portalUrl = null;
        if (i != null && "android.net.conn.CAPTIVE_PORTAL".equals(i.getAction())
                && Build.VERSION.SDK_INT >= 23) {
            try {
                portalNetwork = i.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK);
                captivePortal = i.getParcelableExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL);
                portalUrl = i.getStringExtra(EXTRA_PORTAL_URL);
                if (portalNetwork != null) {
                    ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                    cm.bindProcessToNetwork(portalNetwork);
                }
            } catch (Exception ignored) { }
        }
        if (portalUrl != null && portalUrl.length() > 0) {
            setStatus("Требуется вход в сеть", 0xFFFFD54F);
            web.loadUrl(portalUrl);
        } else {
            checkInternet(true);
        }
    }

    // ---------------------------------------------------------------- web view

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false; // всё открываем внутри приложения
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                urlText.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                urlText.setText(url);
                if (prefs.getBoolean(P_AUTOFILL, true)) fillCredentials(false);
                // после каждой загрузки страницы проверяем, появился ли интернет
                ui.removeCallbacks(recheck);
                ui.postDelayed(recheck, 1500);
            }

            @Override
            public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {
                // Страницы входа провайдеров часто имеют самоподписанный сертификат
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Небезопасный сертификат")
                        .setMessage("Сайт " + error.getUrl() + " использует недоверенный сертификат. "
                                + "Для страниц входа провайдера это бывает нормально. Продолжить?")
                        .setPositiveButton("Продолжить", (d, w) -> handler.proceed())
                        .setNegativeButton("Отмена", (d, w) -> handler.cancel())
                        .setOnCancelListener(d -> handler.cancel())
                        .show();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? View.VISIBLE : View.INVISIBLE);
            }
        });
    }

    private void openLoginPage() {
        String saved = prefs.getString(P_PORTAL, "");
        if (saved.length() > 0) {
            web.loadUrl(saved);
        } else if (detectedPortalUrl != null) {
            web.loadUrl(detectedPortalUrl);
        } else {
            // запрос на http-адрес: провайдер перенаправит на свою страницу входа
            web.loadUrl(online ? DEFAULT_PORTAL : CHECK_URL);
        }
        focusPage();
    }

    private void focusPage() {
        web.requestFocus();
    }

    // ---------------------------------------------------------------- connectivity

    private void checkInternet(final boolean openPortalIfNeeded) {
        if (checking) return;
        checking = true;
        if (openPortalIfNeeded) setStatus("Проверяю подключение…", 0xFFFFD54F);
        bg.execute(() -> {
            int code = -1;
            String location = null;
            HttpURLConnection c = null;
            try {
                URL u = new URL(CHECK_URL);
                if (portalNetwork != null && Build.VERSION.SDK_INT >= 21) {
                    c = (HttpURLConnection) portalNetwork.openConnection(u);
                } else {
                    c = (HttpURLConnection) u.openConnection();
                }
                c.setInstanceFollowRedirects(false);
                c.setUseCaches(false);
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                code = c.getResponseCode();
                location = c.getHeaderField("Location");
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
            final int fCode = code;
            final String fLoc = location;
            ui.post(() -> onCheckResult(fCode, fLoc, openPortalIfNeeded));
        });
    }

    private void onCheckResult(int code, String location, boolean openPortalIfNeeded) {
        checking = false;
        boolean wasOnline = online;
        online = (code == 204);
        if (online) {
            setStatus("✓ Интернет подключён", 0xFF66E07A);
            if (captivePortal != null && Build.VERSION.SDK_INT >= 23) {
                try { ((CaptivePortal) captivePortal).reportCaptivePortalDismissed(); } catch (Exception ignored) { }
                captivePortal = null;
            }
            if (openPortalIfNeeded && web.getUrl() == null) {
                web.loadDataWithBaseURL(null,
                        "<html><body style='background:#15171b;color:#ddd;font-family:sans-serif;"
                        + "text-align:center;padding-top:12%'><h1 style='color:#66e07a'>✓ Интернет подключён</h1>"
                        + "<p>Вход в сеть не требуется. Если провайдер всё же просит авторизацию — "
                        + "нажмите «Страница входа» или «Адрес…».</p></body></html>",
                        "text/html", "utf-8", null);
            }
            if (!wasOnline && !openPortalIfNeeded) {
                Toast.makeText(this, "Готово! Интернет подключён — приложение можно закрыть.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (code >= 300 && code < 400 && location != null) {
            detectedPortalUrl = location;
            setStatus("Требуется вход в сеть", 0xFFFFD54F);
        } else if (code == 200) {
            // некоторые провайдеры отдают страницу входа сразу (без редиректа)
            detectedPortalUrl = DEFAULT_PORTAL;
            setStatus("Требуется вход в сеть", 0xFFFFD54F);
        } else if (code == -1) {
            setStatus("✗ Нет связи с сетью — проверьте Wi‑Fi/кабель", 0xFFFF6B6B);
        } else {
            setStatus("Нет интернета (код " + code + ")", 0xFFFF6B6B);
        }

        if (openPortalIfNeeded && code != -1) openLoginPage();
    }

    private void setStatus(String text, int color) {
        statusText.setText(text);
        statusText.setTextColor(color);
    }

    // ---------------------------------------------------------------- credentials

    private long lastAutoSubmit = 0;

    /**
     * Заполняет форму входа. Поддерживает страницу провайдера с вкладками
     * «Voucher» (одно поле Code) и «Member» (логин + пароль), а также обычные формы.
     */
    private void fillCredentials(final boolean manual) {
        String mode = prefs.getString(P_MODE, MODE_VOUCHER);
        String login = prefs.getString(P_LOGIN, "");
        String pass = prefs.getString(P_PASS, "");
        boolean voucher = MODE_VOUCHER.equals(mode);
        if ((voucher && login.length() == 0) || (!voucher && login.length() == 0 && pass.length() == 0)) {
            if (manual) {
                Toast.makeText(this, "Сначала сохраните код или логин/пароль в «Мои данные…»",
                        Toast.LENGTH_LONG).show();
                showCredentialsDialog();
            }
            return;
        }
        // Автонажатие «Login» — не чаще раза в 20 секунд, чтобы не зациклиться при неверном коде
        long now = SystemClock.elapsedRealtime();
        boolean submit = manual || (prefs.getBoolean(P_AUTOSUBMIT, true) && !online
                && now - lastAutoSubmit > 20000);

        String js = "(function(mode,u,p,submit){"
                + "function vis(e){return !!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);}"
                + "function set(e,v){try{var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');"
                + "d.set.call(e,v);}catch(x){e.value=v;}"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));}"
                + "function txt(e){return ((e.innerText||e.value||e.textContent||'')+'').trim().toLowerCase();}"
                + "function clickable(){return document.querySelectorAll('button,a,input[type=button],input[type=submit],[onclick],[role=button]');}"
                + "function tab(name){var c=clickable();for(var i=0;i<c.length;i++){if(txt(c[i])==name&&vis(c[i])){c[i].click();return true;}}return false;}"
                + "var TXT=['text','email','tel','number',''];"
                + "function isTxt(e){return TXT.indexOf((e.getAttribute('type')||'').toLowerCase())>=0;}"
                + "if(mode=='voucher')tab('voucher');else tab('member');"
                + "var ins=document.querySelectorAll('input'),i,pw=null,us=null,filled=false;"
                + "if(mode=='voucher'){"
                + "  for(i=0;i<ins.length;i++){if(vis(ins[i])&&isTxt(ins[i])){us=ins[i];break;}}"
                + "  if(!us)for(i=0;i<ins.length;i++){if(vis(ins[i])&&ins[i].type=='password'){us=ins[i];break;}}"
                + "  if(!us)return 'nofield';"
                + "  set(us,u);filled=true;"
                // у ваучера пароль обычно равен коду (скрытое поле password)
                + "  for(i=0;i<ins.length;i++){if(ins[i]!==us&&ins[i].type=='password'||(ins[i].name||'').toLowerCase()=='password'&&ins[i]!==us){set(ins[i],u);}}"
                + "}else{"
                + "  for(i=0;i<ins.length;i++){if(ins[i].type=='password'&&vis(ins[i])){pw=ins[i];break;}}"
                + "  for(i=0;i<ins.length;i++){if(ins[i]===pw)break;if(vis(ins[i])&&isTxt(ins[i]))us=ins[i];}"
                + "  if(!pw&&!us)return 'nofield';"
                + "  if(us&&u)set(us,u);if(pw&&p)set(pw,p);filled=true;"
                + "}"
                + "if(filled&&submit){setTimeout(function(){"
                + "  var c=clickable(),k;"
                + "  for(k=0;k<c.length;k++){var t=txt(c[k]);if(vis(c[k])&&(t=='login'||t=='log in'||t=='войти'||t=='вход'||t=='connect'||t=='sign in')){c[k].click();return;}}"
                + "  var f=us&&us.form||pw&&pw.form;"
                + "  if(f){var sb=f.querySelector('[type=submit]');if(sb){sb.click();}else if(f.requestSubmit){f.requestSubmit();}else{f.submit();}}"
                + "},400);return 'submitted';}"
                + "return 'ok';"
                + "})(" + JSONObject.quote(voucher ? "voucher" : "member") + ","
                + JSONObject.quote(login) + "," + JSONObject.quote(pass) + "," + submit + ")";
        web.evaluateJavascript(js, result -> {
            if (result != null && result.contains("submitted")) lastAutoSubmit = SystemClock.elapsedRealtime();
            if (!manual) return;
            if (result != null && result.contains("submitted")) {
                Toast.makeText(this, "Данные введены, нажимаю Login…", Toast.LENGTH_SHORT).show();
                ui.removeCallbacks(recheck);
                ui.postDelayed(recheck, 4000);
            } else if (result != null && result.contains("ok")) {
                Toast.makeText(this, "Данные вставлены. Нажмите Login на странице.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "На этой странице не найдено поле для ввода.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showCredentialsDialog() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * d);
        box.setPadding(pad, pad / 2, pad, 0);

        final RadioGroup modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.HORIZONTAL);
        final RadioButton rbVoucher = new RadioButton(this);
        rbVoucher.setId(View.generateViewId());
        rbVoucher.setText("Voucher (код)");
        final RadioButton rbMember = new RadioButton(this);
        rbMember.setId(View.generateViewId());
        rbMember.setText("Member (логин + пароль)");
        modeGroup.addView(rbVoucher);
        modeGroup.addView(rbMember);

        final EditText login = new EditText(this);
        login.setSingleLine(true);
        login.setText(prefs.getString(P_LOGIN, ""));

        final EditText pass = new EditText(this);
        pass.setHint("Пароль");
        pass.setSingleLine(true);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setText(prefs.getString(P_PASS, ""));

        final CheckBox show = new CheckBox(this);
        show.setText("Показать пароль");
        show.setOnCheckedChangeListener((b, on) -> {
            pass.setInputType(InputType.TYPE_CLASS_TEXT | (on
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            pass.setSelection(pass.getText().length());
        });

        modeGroup.setOnCheckedChangeListener((g, id) -> {
            boolean v = id == rbVoucher.getId();
            login.setHint(v ? "Код ваучера (Code)" : "Логин (Member)");
            pass.setVisibility(v ? View.GONE : View.VISIBLE);
            show.setVisibility(v ? View.GONE : View.VISIBLE);
        });
        modeGroup.check(MODE_VOUCHER.equals(prefs.getString(P_MODE, MODE_VOUCHER))
                ? rbVoucher.getId() : rbMember.getId());

        final CheckBox auto = new CheckBox(this);
        auto.setText("Подставлять автоматически на странице входа");
        auto.setChecked(prefs.getBoolean(P_AUTOFILL, true));

        final CheckBox autoSubmit = new CheckBox(this);
        autoSubmit.setText("Сразу нажимать «Login»");
        autoSubmit.setChecked(prefs.getBoolean(P_AUTOSUBMIT, true));

        TextView note = new TextView(this);
        note.setText("Данные хранятся только на этом устройстве.");
        note.setTextSize(12);
        note.setAlpha(0.7f);

        box.addView(modeGroup);
        box.addView(login);
        box.addView(pass);
        box.addView(show);
        box.addView(auto);
        box.addView(autoSubmit);
        box.addView(note);

        new AlertDialog.Builder(this)
                .setTitle("Данные для входа (Nobel / wlfl)")
                .setView(box)
                .setPositiveButton("Сохранить и войти", (dlg, w) -> {
                    prefs.edit()
                            .putString(P_MODE, modeGroup.getCheckedRadioButtonId() == rbVoucher.getId()
                                    ? MODE_VOUCHER : MODE_MEMBER)
                            .putString(P_LOGIN, login.getText().toString().trim())
                            .putString(P_PASS, pass.getText().toString())
                            .putBoolean(P_AUTOFILL, auto.isChecked())
                            .putBoolean(P_AUTOSUBMIT, autoSubmit.isChecked())
                            .apply();
                    lastAutoSubmit = 0;
                    fillCredentials(true);
                })
                .setNeutralButton("Удалить", (dlg, w) -> prefs.edit()
                        .remove(P_LOGIN).remove(P_PASS).apply())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showUrlDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        String saved = prefs.getString(P_PORTAL, "");
        input.setText(saved.length() > 0 ? saved : DEFAULT_PORTAL);
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("Адрес страницы")
                .setMessage("Можно указать адрес страницы входа провайдера (например, http://192.168.1.1) "
                        + "и сохранить его — тогда кнопка «Страница входа» будет открывать его сразу.")
                .setView(input)
                .setPositiveButton("Открыть", (d, w) -> {
                    web.loadUrl(normalize(input.getText().toString()));
                    focusPage();
                })
                .setNeutralButton("Сохранить как страницу входа", (d, w) -> {
                    String u = input.getText().toString().trim();
                    prefs.edit().putString(P_PORTAL, u.length() > 0 ? normalize(u) : "").apply();
                    if (u.length() > 0) {
                        web.loadUrl(normalize(u));
                        focusPage();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static String normalize(String u) {
        u = u.trim();
        if (u.length() == 0) return CHECK_URL;
        if (!u.contains("://")) u = "http://" + u;
        return u;
    }

    // ---------------------------------------------------------------- remote control / cursor

    private void setCursorMode(boolean on) {
        cursorMode = on;
        prefs.edit().putBoolean(P_CURSOR, on).apply();
        cursor.setVisibility(on ? View.VISIBLE : View.GONE);
        btnCursor.setText(on ? "Курсор: ВКЛ" : "Курсор: ВЫКЛ");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        // Кнопка "Меню" на пульте — включить/выключить курсор
        if (k == KeyEvent.KEYCODE_MENU) {
            if (!down) setCursorMode(!cursorMode);
            return true;
        }

        if (cursorMode && web.hasFocus()) {
            cursor.setActive(true);
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) moveCursor(k, e.getRepeatCount());
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    if (down && e.getRepeatCount() == 0) clickAtCursor();
                    return true;
                default:
                    break;
            }
        } else {
            cursor.setActive(false);
        }
        return super.dispatchKeyEvent(e);
    }

    private void moveCursor(int key, int repeat) {
        float d = getResources().getDisplayMetrics().density;
        float step = d * (6 + Math.min(repeat, 25) * 2.2f); // ускорение при удержании
        float x = cursor.getCursorX(), y = cursor.getCursorY();
        int w = cursor.getWidth(), h = cursor.getHeight();
        float edge = 4 * d;

        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                x -= step;
                if (x < edge) { x = edge; scrollPage(-(int) step, 0); }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                x += step;
                if (x > w - edge) { x = w - edge; scrollPage((int) step, 0); }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (y <= edge && repeat == 0 && !web.canScrollVertically(-1)) {
                    // уже наверху страницы — переходим на панель кнопок
                    btnCheck.requestFocus();
                    cursor.setActive(false);
                    return;
                }
                y -= step;
                if (y < edge) { y = edge; scrollPage(0, -(int) (step * 1.5f)); }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                y += step;
                if (y > h - edge) { y = h - edge; scrollPage(0, (int) (step * 1.5f)); }
                break;
        }
        cursor.setCursor(x, y);
    }

    private void scrollPage(int dx, int dy) {
        if ((dy < 0 && web.canScrollVertically(-1)) || (dy > 0 && web.canScrollVertically(1))
                || (dx < 0 && web.canScrollHorizontally(-1)) || (dx > 0 && web.canScrollHorizontally(1))) {
            web.scrollBy(dx, dy);
        } else {
            // запасной вариант для страниц со своим скроллом
            float dens = getResources().getDisplayMetrics().density;
            web.evaluateJavascript("window.scrollBy(" + (int) (dx / dens) + "," + (int) (dy / dens) + ")", null);
        }
    }

    private void clickAtCursor() {
        float x = cursor.getCursorX(), y = cursor.getCursorY();
        long t = SystemClock.uptimeMillis();
        MotionEvent downEv = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        downEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        MotionEvent upEv = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
        upEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(downEv);
        web.dispatchTouchEvent(upEv);
        downEv.recycle();
        upEv.recycle();
    }

    @Override
    public void onBackPressed() {
        if (!web.hasFocus()) {
            focusPage();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
