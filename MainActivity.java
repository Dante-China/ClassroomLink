package com.mycompany.classsender;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

public class MainActivity extends Activity
{
    private WebView webView;
    private ValueCallback<Uri[]> mFilePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 100;
    private static final String CHANNEL_ID = "classroom_msg_channel";
    private static final String CHANNEL_NAME = "消息通知";
    private static final String SERVICE_CHANNEL_ID = "classroom_service_channel";
    private SharedPreferences sharedPref;
    private static MainActivity instance;
    private View currentFloatView = null;
    private Handler floatHandler = new Handler(Looper.getMainLooper());

    public static class KeepAliveService extends Service
    {
        @Override
        public IBinder onBind(Intent intent) { return null; }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId)
        {
            try
            {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                {
                    NotificationChannel ch = new NotificationChannel(
                        SERVICE_CHANNEL_ID, "发送端运行中",
                        NotificationManager.IMPORTANCE_MIN);
                    ch.setShowBadge(false);
                    ch.setSound(null, null);
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    if (nm != null) nm.createNotificationChannel(ch);
                }
                Intent openIntent = new Intent(this, MainActivity.class);
                openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                int pflag = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pflag |= PendingIntent.FLAG_IMMUTABLE;
                PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, pflag);

                Notification.Builder b;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    b = new Notification.Builder(this, SERVICE_CHANNEL_ID);
                else
                    b = new Notification.Builder(this);

                b.setContentTitle("发送端正在运行")
                 .setContentText("保持在线，接收消息")
                 .setSmallIcon(R.drawable.ic_launcher)
                 .setContentIntent(pi)
                 .setOngoing(true)
                 .setPriority(Notification.PRIORITY_MIN);

                startForeground(8888, b.build());
            }
            catch (Exception e) { e.printStackTrace(); }
            return START_STICKY;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        instance = this;
        sharedPref = getPreferences(MODE_PRIVATE);
        createNotificationChannel();
        startKeepAlive();

        String savedTopic = sharedPref.getString("topic_suffix", "");
        String savedTeacher = sharedPref.getString("teacher_name", "");
        String topic = savedTopic.isEmpty() ? "" : "classroom/msg/" + savedTopic;
        initWebView(topic, savedTopic, savedTeacher);
    }

    private void startKeepAlive()
    {
        try
        {
            Intent svc = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(svc);
            else
                startService(svc);
        }
        catch (Exception e) { e.printStackTrace(); }
    }

    @Override
    protected void onNewIntent(Intent intent)
    {
        super.onNewIntent(intent);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancelAll();
    }

    private void createNotificationChannel()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("接收电脑端发来的消息通知");
            channel.enableVibration(true);
            channel.enableLights(true);
            channel.setShowBadge(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    public void showNativeNotification(String sender, String content, String type)
    {
        try
        {
            String icon = "[消息]";
            if ("image".equals(type)) icon = "[图片]";
            else if ("mix".equals(type)) icon = "[图文]";

            String title = sender + " 发来消息";
            String preview = content == null ? "" : content;
            if (preview.length() > 80) preview = preview.substring(0, 80) + "...";
            if (preview.isEmpty() && "image".equals(type)) preview = "（图片消息）";

            RemoteViews remoteViews = new RemoteViews(getPackageName(), R.layout.notification_layout);
            remoteViews.setTextViewText(R.id.notif_icon, icon);
            remoteViews.setTextViewText(R.id.notif_title, title);
            remoteViews.setTextViewText(R.id.notif_content, preview);

            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                builder = new Notification.Builder(this, CHANNEL_ID);
            else
                builder = new Notification.Builder(this);

            builder.setSmallIcon(R.drawable.ic_launcher)
                .setCustomContentView(remoteViews)
                .setCustomBigContentView(remoteViews)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));

            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null)
            {
                int notifId = (int) (System.currentTimeMillis() % 100000);
                nm.notify(notifId, builder.build());
            }

            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator())
            {
                long[] pattern = {0, 250, 150, 250};
                vibrator.vibrate(pattern, -1);
            }
        }
        catch (Exception e) { e.printStackTrace(); }
    }

    public boolean canDrawOverlays()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            return Settings.canDrawOverlays(this);
        return true;
    }

    public void requestOverlayPermission()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            if (!Settings.canDrawOverlays(this))
            {
                try
                {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
                catch (Exception e) { e.printStackTrace(); }
            }
        }
    }

    public void showFloatingNotice(String sender, String content, String type, String imageBase64)
    {
        if (!canDrawOverlays())
        {
            showNativeNotification(sender, content, type);
            return;
        }
        try
        {
            final WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (currentFloatView != null)
            {
                try { wm.removeView(currentFloatView); } catch (Exception e) {}
                currentFloatView = null;
            }

            LayoutInflater inflater = LayoutInflater.from(this);
            final View floatView = inflater.inflate(R.layout.floating_notice, null);

            String icon = "[消息]";
            if ("image".equals(type)) icon = "[图片]";
            else if ("mix".equals(type)) icon = "[图文]";

            String preview = content == null ? "" : content;
            if (preview.length() > 60) preview = preview.substring(0, 60) + "...";

            TextView iconTv = (TextView) floatView.findViewById(R.id.float_icon);
            TextView titleTv = (TextView) floatView.findViewById(R.id.float_title);
            TextView contentTv = (TextView) floatView.findViewById(R.id.float_content);
            TextView closeTv = (TextView) floatView.findViewById(R.id.float_close);
            ImageView imageView = (ImageView) floatView.findViewById(R.id.float_image);

            iconTv.setText(icon);
            titleTv.setText(sender + " 发来一条消息");

            boolean hasImage = false;
            if (imageBase64 != null && !imageBase64.isEmpty())
            {
                try
                {
                    byte[] imgBytes = android.util.Base64.decode(imageBase64, android.util.Base64.DEFAULT);
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
                    if (bmp != null)
                    {
                        imageView.setImageBitmap(bmp);
                        imageView.setVisibility(View.VISIBLE);
                        hasImage = true;
                    }
                }
                catch (Exception e) { e.printStackTrace(); }
            }

            if (preview.isEmpty())
                contentTv.setText(hasImage ? "（图片消息）" : "（无内容）");
            else
                contentTv.setText(preview);

            closeTv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v)
                {
                    try { wm.removeView(floatView); } catch (Exception e) {}
                    currentFloatView = null;
                }
            });

            floatView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v)
                {
                    try { wm.removeView(floatView); } catch (Exception e) {}
                    currentFloatView = null;
                    Intent intent = new Intent(MainActivity.this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);
                }
            });

            int typeFlag;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                typeFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            else
                typeFlag = WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                typeFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.TRANSLUCENT);

            params.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            params.x = 0;
            params.y = 50;

            wm.addView(floatView, params);
            currentFloatView = floatView;

            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator())
            {
                long[] pattern = {0, 250, 150, 250};
                vibrator.vibrate(pattern, -1);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            showNativeNotification(sender, content, type);
        }
    }

    private String readAssetFile(String filename)
    {
        try
        {
            java.io.InputStream is = getResources().openRawResource(R.raw.mqttjs);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            String content = new String(bos.toByteArray(), "UTF-8");
            if (content.startsWith("\uFEFF")) content = content.substring(1);
            content = content.replace("</script>", "<\\/script>");
            return content;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return "";
        }
    }

    private void initWebView(String topic, String displayName, String teacherName)
    {
        webView = new WebView(this);
        webView.setBackgroundColor(0xFFF4F8FB);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams)
            {
                if (mFilePathCallback != null) mFilePathCallback.onReceiveValue(null);
                mFilePathCallback = filePathCallback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try
                {
                    startActivityForResult(Intent.createChooser(intent, "请选择图片"), FILE_CHOOSER_REQUEST_CODE);
                }
                catch (Exception e) { mFilePathCallback = null; return false; }
                return true;
            }
        });

        String html = buildHtml(topic, displayName, teacherName);
        webView.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null);
        setContentView(webView);
    }

    private String buildHtml(String topic, String displayName, String teacherName)
    {
        return "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no'>" +
            "<title>发送端</title>" +
            "<script>" + readAssetFile("mqtt.min.js") + "</script>" +
            "<style>" +
            "@keyframes fadeUp { from { opacity: 0; transform: translateY(12px); } to { opacity: 1; transform: translateY(0); } } " +
            "@keyframes fadeDown { from { opacity: 0; transform: translateY(-10px); } to { opacity: 1; transform: translateY(0); } } " +
            "@keyframes popIn { 0% { opacity: 0; transform: scale(0.86); } 60% { opacity: 1; transform: scale(1.03); } 100% { transform: scale(1); } } " +
            "@keyframes spin { to { transform: rotate(360deg); } } " +
            "@keyframes dotPulse { 0%,100% { opacity: 1; transform: scale(1); } 50% { opacity: 0.45; transform: scale(0.7); } } " +
            "@keyframes marquee { 0% { transform: translateX(0); } 100% { transform: translateX(-100%); } } " +
            "@keyframes slideDown { from { opacity: 0; transform: translateY(-110%); } to { opacity: 1; transform: translateY(0); } } " +
            "@keyframes slideUp { from { opacity: 1; transform: translateY(0); } to { opacity: 0; transform: translateY(-110%); } } " +
            "* { box-sizing: border-box; -webkit-tap-highlight-color: transparent; } " +
            "button { outline: none; -webkit-tap-highlight-color: transparent; -webkit-appearance: none; user-select: none; font-family: inherit; } " +
            "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif; padding: 0 14px 60px 14px; background: linear-gradient(170deg, #eef7ff 0%, #e6fbf5 55%, #eef4ff 100%); color: #1a2b3c; margin: 0; min-height: 100vh; } " +
            ".loading-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: linear-gradient(170deg, #eef7ff 0%, #e6fbf5 60%, #eef4ff 100%); z-index: 99999; display: flex; flex-direction: column; align-items: center; justify-content: center; transition: opacity 0.35s; } " +
            ".loading-spinner { width: 78px; height: 78px; border-radius: 50%; border: 5px solid transparent; border-top-color: #00c896; border-right-color: #00b4d4; border-bottom-color: #7c3aed; animation: spin 0.9s linear infinite; box-shadow: 0 10px 30px rgba(0, 150, 200, 0.25); } " +
            ".loading-text { margin-top: 30px; font-size: 16px; font-weight: 800; color: #00a884; letter-spacing: 3px; } " +
            ".loading-sub { margin-top: 10px; font-size: 12px; color: #90a4ae; letter-spacing: 1.5px; } " +
            ".notice-container { position: fixed; top: 12px; left: 12px; right: 12px; z-index: 99998; display: flex; flex-direction: column; gap: 10px; pointer-events: none; } " +
            ".notice-card { background: linear-gradient(135deg, #00c896 0%, #00b4d4 100%); color: #fff; border-radius: 20px; padding: 15px 18px; box-shadow: 0 16px 40px rgba(0, 180, 180, 0.4); display: flex; align-items: flex-start; gap: 12px; animation: slideDown 0.4s; pointer-events: auto; cursor: pointer; } " +
            ".notice-card.hide { animation: slideUp 0.28s ease-in forwards; } " +
            ".notice-card .icon { font-size: 26px; flex-shrink: 0; } " +
            ".notice-card .body { flex: 1; min-width: 0; } " +
            ".notice-card .title { font-size: 14px; font-weight: 800; margin-bottom: 4px; } " +
            ".notice-card .content { font-size: 15px; line-height: 1.4; word-break: break-all; } " +
            ".notice-card .close { background: transparent; border: none; color: #fff; opacity: 0.75; font-size: 20px; padding: 0 4px; } " +
            ".status-bar { position: sticky; top: 0; z-index: 100; background: #ffffff; margin: 0 -14px 0 -14px; padding: 12px 14px; display: flex; align-items: center; justify-content: center; gap: 22px; font-size: 12px; font-weight: 700; border-bottom: 1px solid rgba(0, 200, 150, 0.15); } " +
            ".status-item { display: flex; align-items: center; gap: 6px; color: #ef5350; } " +
            ".status-item.online { color: #00a884; } " +
            ".status-dot { display: inline-block; width: 9px; height: 9px; border-radius: 50%; background: #ef5350; } " +
            ".status-item.online .status-dot { background: #00c896; box-shadow: 0 0 10px #00c896; animation: dotPulse 1.6s ease-in-out infinite; } " +
            ".status-text { font-size: 11px; font-weight: 800; padding: 3px 10px; border-radius: 12px; background: #ffebee; color: #c62828; } " +
            ".status-item.online .status-text { background: rgba(0, 200, 150, 0.14); color: #00806a; } " +
            ".marquee-bar { position: sticky; top: 42px; z-index: 99; background: linear-gradient(135deg, #fffbe6 0%, #fff4c2 100%); margin: 0 -14px 14px -14px; padding: 10px 14px; display: flex; align-items: center; overflow: hidden; } " +
            ".marquee-icon { font-size: 18px; margin-right: 10px; } " +
            ".marquee-text { flex: 1; overflow: hidden; white-space: nowrap; height: 20px; } " +
            ".marquee-text span { display: inline-block; padding-left: 100%; color: #b28704; font-size: 13px; font-weight: 700; line-height: 20px; animation: marquee 22s linear infinite; } " +
            ".top-bar { display: flex; align-items: center; justify-content: space-between; background: #fff; padding: 14px 16px; border-radius: 20px; box-shadow: 0 4px 12px rgba(0, 150, 130, 0.06); margin-bottom: 14px; } " +
            ".top-bar > span { font-weight: 800; font-size: 15px; color: #00a884; } " +
            ".top-bar button { padding: 8px 15px; background: linear-gradient(135deg, #00c896, #00b4d4); color: #fff; border: none; border-radius: 11px; font-weight: 800; font-size: 13px; margin-left: 6px; } " +
            ".teacher-badge { display: inline-block; background: linear-gradient(135deg, #00c896 0%, #00b4d4 55%, #7c3aed 130%); color: #fff; padding: 9px 26px; border-radius: 26px; font-size: 15px; font-weight: 800; } " +
            ".card { background: #fff; border-radius: 22px; padding: 18px; margin-bottom: 14px; box-shadow: 0 6px 16px rgba(0, 150, 130, 0.06); } " +
            ".floating-field { position: relative; margin-bottom: 14px; } " +
            ".floating-field input, .floating-field textarea { width: 100%; padding: 22px 14px 8px 14px; font-size: 16px; border: 2px solid #e2ecf2; border-radius: 14px; outline: none; background: #f7fbfd; } " +
            ".floating-field textarea { min-height: 120px; padding-top: 26px; } " +
            ".floating-field label { position: absolute; left: 14px; top: 50%; transform: translateY(-50%); color: #90a4ae; font-size: 16px; pointer-events: none; transition: 0.28s; background: transparent; padding: 0 6px; } " +
            ".floating-field textarea + label { top: 28px; } " +
            ".floating-field input:focus + label, .floating-field input:not(:placeholder-shown) + label { top: 0; left: 10px; font-size: 12px; color: #00c896; font-weight: 800; background: #fff; } " +
            ".floating-field textarea:focus + label, .floating-field textarea:not(:placeholder-shown) + label { top: 0; left: 10px; font-size: 12px; color: #00c896; font-weight: 800; background: #fff; } " +
            ".btn-send { background: linear-gradient(135deg, #00e0b8 0%, #00c896 50%, #00a884 100%); color: #fff; padding: 16px; width: 100%; border: none; border-radius: 16px; font-weight: 800; font-size: 16px; } " +
            ".quick-replies { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; } " +
            ".quick-btn { background: #e9fbf4; color: #00806a; border: none; padding: 10px 15px; border-radius: 20px; font-size: 12px; font-weight: 800; } " +
            ".btn-history-big { display: flex; align-items: center; justify-content: center; gap: 10px; background: linear-gradient(135deg, #a855f7 0%, #7c3aed 100%); color: #fff; padding: 16px; width: 100%; margin-top: 12px; border: none; border-radius: 16px; font-weight: 800; font-size: 15px; } " +
            ".btn-broadcast { display: flex; align-items: center; justify-content: center; gap: 10px; background: linear-gradient(135deg, #fb923c 0%, #ea580c 100%); color: #fff; padding: 16px; width: 100%; margin-top: 10px; border: none; border-radius: 16px; font-weight: 800; font-size: 15px; } " +
            ".feedback { margin-top: 12px; padding: 12px 16px; border-radius: 12px; font-size: 13px; font-weight: 700; display: none; text-align: center; } " +
            ".feedback.show { display: block; } " +
            ".feedback.success { background: rgba(0,200,150,0.14); color: #00806a; } " +
            ".feedback.error { background: #ffebee; color: #c62828; } " +
            ".btn-pick-img { display: flex; align-items: center; justify-content: center; gap: 10px; width: 100%; padding: 20px; background: #e9fbf4; color: #00806a; border: 2px dashed #7dd3c0; border-radius: 16px; font-weight: 800; font-size: 14px; } " +
            ".image-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 10px; } " +
            ".image-grid img { width: 100%; height: 90px; object-fit: cover; border-radius: 12px; } " +
            ".btn-row { display: flex; gap: 10px; margin-top: 8px; } " +
            ".btn-blue, .btn-orange, .btn-cyan { flex: 1; padding: 15px; color: #fff; border: none; border-radius: 14px; font-weight: 800; font-size: 14px; } " +
            ".btn-blue { background: #38bdf8; } .btn-orange { background: #fbbf24; } .btn-cyan { background: #22d3ee; } " +
            ".open-file-row { display: flex; gap: 8px; align-items: flex-end; margin-top: 12px; } " +
            ".open-file-row .floating-field { flex: 1; margin-bottom: 0; } " +
            ".open-file-row button { padding: 13px 20px; background: #a855f7; color: #fff; border: none; border-radius: 12px; font-weight: 800; font-size: 14px; } " +
            ".section-title { font-size: 12px; font-weight: 800; color: #7a8b99; margin: 20px 0 12px 2px; letter-spacing: 1.5px; display: flex; align-items: center; gap: 10px; } " +
            ".section-title::after { content: ''; flex: 1; height: 1px; background: linear-gradient(90deg, rgba(0, 200, 150, 0.35), transparent); } " +
            ".overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(8, 18, 28, 0.55); display: none; align-items: center; justify-content: center; z-index: 9999; padding: 20px; } " +
            ".overlay.show { display: flex; } " +
            ".overlay .content { background: #fff; border-radius: 24px; padding: 24px; width: 100%; max-width: 400px; max-height: 90vh; overflow-y: auto; } " +
            ".overlay h3 { margin: 0 0 8px 0; color: #1a2b3c; font-size: 19px; font-weight: 800; } " +
            ".overlay .subtitle { margin: 0 0 18px 0; color: #7a8b99; font-size: 13px; } " +
            ".overlay .row { margin: 14px 0; } " +
            ".overlay select { width: 100%; padding: 12px; border: 2px solid #e2ecf2; border-radius: 12px; font-size: 14px; outline: none; background: #f7fbfd; } " +
            ".overlay .btn-row2 { display: flex; gap: 10px; margin-top: 22px; } " +
            ".overlay .btn-cancel { flex: 1; padding: 13px; background: #f2f6f9; color: #556670; border: none; border-radius: 12px; font-weight: 800; } " +
            ".overlay .btn-save { flex: 1; padding: 13px; background: #00d4b8; color: #fff; border: none; border-radius: 12px; font-weight: 800; } " +
            ".app-modal .header { background: #38bdf8; } .cloud-modal .header { background: #22d3ee; } " +
            ".history-modal .header { background: #a855f7; } .broadcast-modal .header { background: #fb923c; } " +
            ".history-item { padding: 12px 16px; margin: 8px 0; border-radius: 16px; max-width: 85%; font-size: 14px; position: relative; } " +
            ".history-item.from-me { background: #00c896; color: #fff; margin-left: auto; } " +
            ".history-item.from-pc { background: #f0f6fa; color: #1a2b3c; margin-right: auto; } " +
            ".history-item .sender { font-size: 11px; font-weight: 800; opacity: 0.85; margin-bottom: 5px; } " +
            ".history-item .time { font-size: 10px; opacity: 0.65; margin-top: 5px; text-align: right; } " +
            ".history-empty { text-align: center; color: #90a4ae; padding: 50px 20px; font-size: 14px; } " +
            ".history-toolbar { padding: 10px 12px; border-bottom: 1px solid #eef2f5; display: flex; gap: 8px; } " +
            ".history-toolbar button { flex: 1; padding: 10px; background: #ffe5e8; color: #e53935; border: none; border-radius: 10px; font-weight: 800; font-size: 12px; } " +
            ".broadcast-list { padding: 14px; max-height: 55vh; overflow-y: auto; } " +
            ".broadcast-class-item { padding: 14px; margin: 6px 0; border-radius: 14px; background: #f2f7fa; cursor: pointer; display: flex; align-items: center; gap: 10px; font-size: 15px; font-weight: 700; color: #1a2b3c; } " +
            ".broadcast-class-item.selected { background: #fde8d7; color: #c2410c; } " +
            ".broadcast-class-item .checkbox { width: 22px; height: 22px; border: 2px solid #90a4ae; border-radius: 7px; position: relative; } " +
            ".broadcast-class-item.selected .checkbox { background: #ea580c; border-color: #ea580c; } " +
            ".broadcast-class-item.selected .checkbox::after { content: 'X'; position: absolute; color: #fff; font-size: 15px; font-weight: 800; top: 50%; left: 50%; transform: translate(-50%, -50%); } " +
            "</style></head><body>" +
            "<div class='loading-overlay' id='loadingOverlay'><div class='loading-spinner'></div><div class='loading-text'>正在连接云服务器</div><div class='loading-sub'>请稍候，马上就好</div></div>" +
            "<div class='notice-container' id='noticeContainer'></div>" +
            "<div class='status-bar'><div class='status-item' id='cloudStatus'><span class='status-dot'></span><span>云服务器</span><span class='status-text' id='cloudText'>未连接</span></div><div class='status-item' id='pcStatus'><span class='status-dot'></span><span>电脑端</span><span class='status-text' id='pcText'>未连接</span></div></div>" +
            "<div class='marquee-bar'><span class='marquee-icon'>[广播]</span><div class='marquee-text'><span>温馨提示：如需切换电脑班级或修改电脑端信息，请联系系统管理员。</span></div></div>" +
            "<div class='top-bar'><span>[频道] <span id='channelName'>" + (displayName.isEmpty() ? "未设置" : displayName) + "</span></span><div><button onclick='openSwitchPanel()'>切换</button><button onclick='openSettings()'>设置</button></div></div>" +
            "<div style='text-align:center; margin-bottom:14px;'><span class='teacher-badge' id='teacherBadge'>[管理员] " + (teacherName.isEmpty() ? "未设置" : teacherName) + "</span></div>" +
            "<div class='card'><div class='floating-field'><textarea id='msg' placeholder=' '></textarea><label for='msg'>请输入要发送的文字内容...</label></div><button class='btn-send' onclick='sendMix()'>[发送消息]</button><div class='section-title'>[快捷回复]</div><div class='quick-replies' id='quickRepliesDiv'></div><div class='btn-history-big' onclick='openHistory()'><span style='font-size:18px;'>[记录]</span><span>查看与电脑的消息记录</span></div><div class='feedback' id='msgFeedback'></div></div>" +
            "<div class='card'><div class='section-title'>[图片]（最多6张）</div><input type='file' id='imgFile' accept='image/*' multiple onchange='previewImages()' style='display:none;'><button class='btn-pick-img' onclick='pickImages()'><span style='font-size:22px;'>[图片]</span><span id='pickImgText'>点击选择图片</span></button><div id='imgPreview' class='image-grid'></div></div>" +
            "<div class='card'><div class='section-title'>[远程控制]</div><div class='btn-row'><button class='btn-blue' onclick='requestAppList()'>[打开软件]</button><button class='btn-orange' onclick='requestLog()'>[查看日志]</button></div><div class='btn-row'><button class='btn-cyan' onclick='requestWpsCloud()'>[WPS云文档]</button></div><div class='open-file-row'><div class='floating-field'><input type='text' id='fileNameInput' placeholder=' '><label for='fileNameInput'>输入文件名，如：课件</label></div><button onclick='openFile()'>打开</button></div><div class='feedback' id='fileFeedback'></div><button class='btn-broadcast' onclick='openPowerPanel()' style='margin-top:14px; background:#ef4444;'>[电源] 电源按钮</button><button class='btn-broadcast' onclick='openBroadcastPanel()' style='margin-top:14px;'>[广播] 全体开会（多班发送）</button></div>" +
            "<div class='overlay' id='initOverlay'><div class='content'><h3>[欢迎使用发送端]</h3><p class='subtitle'>首次使用请填写以下信息，之后可在设置中修改</p><div class='floating-field'><input type='text' id='initTeacherName' placeholder=' '><label for='initTeacherName'>请输入您的姓名（例如：张三）</label></div><div class='floating-field'><input type='text' id='initChannelName' placeholder=' ' value='Classroom1'><label for='initChannelName'>请输入专属通道名称</label></div><div class='btn-row2'><button class='btn-cancel' onclick='exitApp()'>退出</button><button class='btn-save' onclick='doInit()'>进入</button></div></div></div>" +
            "<div class='overlay' id='settingsPanel'><div class='content'><h3>[常用设置]</h3><div class='floating-field'><input type='text' id='setTeacherName' placeholder=' '><label for='setTeacherName'>修改老师姓名</label></div><div class='row'><label style='display:block;margin-bottom:6px;font-size:13px;color:#7f8c8d;font-weight:700;'>自动关闭时间：</label><select id='setAutoClose'><option value='0'>手动关闭</option><option value='5'>5秒后自动关闭</option><option value='10'>10秒后自动关闭</option><option value='15'>15秒后自动关闭</option><option value='30'>30秒后自动关闭</option></select></div><div class='row'><label style='display:block;margin-bottom:6px;font-size:13px;color:#7f8c8d;font-weight:700;'>默认显示模式：</label><select id='setDisplayMode'><option value='fullscreen'>全屏弹窗</option><option value='mini'>缩小化（左上角小窗）</option></select></div><div class='row'><label style='display:flex;align-items:center;font-size:14px;color:#2c3e50;font-weight:700;cursor:pointer;'><input type='checkbox' id='setReadAloud' style='width:18px;height:18px;margin-right:10px;cursor:pointer;'>朗读文字</label></div><div class='row'><label style='display:block;margin-bottom:6px;font-size:13px;color:#7f8c8d;font-weight:700;'>语音包：</label><select id='setVoice'><option value='zh-CN-XiaoxiaoNeural'>晓晓（女声·最自然）</option><option value='zh-CN-XiaoyiNeural'>晓伊（女声·可爱）</option><option value='zh-CN-YunxiNeural'>云希（男声·温柔）</option><option value='zh-CN-YunyangNeural'>云扬（男声·新闻）</option><option value='zh-CN-YunjianNeural'>云健（男声·激情）</option></select></div><div class='row'><label style='display:flex;align-items:center;font-size:14px;color:#2c3e50;font-weight:700;cursor:pointer;'><input type='checkbox' id='setReceivePcMsg' style='width:18px;height:18px;margin-right:10px;cursor:pointer;'>接收电脑端消息</label></div><div class='row'><label style='display:flex;align-items:center;font-size:14px;color:#2c3e50;font-weight:700;cursor:pointer;'><input type='checkbox' id='setFloatingNotice' style='width:18px;height:18px;margin-right:10px;cursor:pointer;'>使用悬浮窗（在任意界面显示卡片）</label></div><div class='row'><button onclick='requestOverlayPermission()' style='width:100%;padding:13px;background:#ff5722;color:#fff;border:none;border-radius:12px;font-weight:800;cursor:pointer;'>授予悬浮窗权限</button></div><div class='btn-row2'><button class='btn-cancel' onclick='closeSettings()'>取消</button><button class='btn-save' onclick='saveSettings()'>保存</button></div></div></div>" +
            "<div class='overlay' id='switchPanel'><div class='content'><h3>[切换通道]</h3><p class='subtitle'>输入要切换到的电脑专属名称</p><div class='floating-field'><input type='text' id='switchInput' placeholder=' '><label for='switchInput'>请输入新的专属通道名称</label></div><div class='btn-row2'><button class='btn-cancel' onclick='closeSwitchPanel()'>取消</button><button class='btn-save' onclick='doSwitch()'>切换</button></div></div></div>" +
            "<div class='overlay app-modal' id='appModal'><div class='content' style='padding:0; max-height:80vh; display:flex; flex-direction:column; overflow:hidden;'><div class='header' style='padding:16px 18px; color:#fff; font-weight:800; display:flex; justify-content:space-between; align-items:center; border-radius:24px 24px 0 0;'><span>选择要打开的软件</span><button onclick='closeAppModal()' style='background:transparent; color:#fff; border:none; font-size:22px; font-weight:800; cursor:pointer;'>X</button></div><div style='padding:10px;'><input type='text' id='appSearch' placeholder='搜索软件名称...' oninput='renderAppList()' style='width:100%; padding:12px 14px; border:2px solid #e2ecf2; border-radius:12px; font-size:14px; outline:none; box-sizing:border-box; background:#f7fbfd;'></div><div class='list' id='appListDiv' style='flex:1; overflow-y:auto; max-height:55vh;'></div></div></div>" +
            "<div class='overlay cloud-modal' id='cloudModal'><div class='content' style='padding:0; max-height:80vh; display:flex; flex-direction:column; overflow:hidden;'><div class='header' style='padding:16px 18px; color:#fff; font-weight:800; display:flex; justify-content:space-between; align-items:center; border-radius:24px 24px 0 0;'><span>WPS 云文档</span><button onclick='closeCloudModal()' style='background:transparent; color:#fff; border:none; font-size:22px; font-weight:800; cursor:pointer;'>X</button></div><div style='padding:10px;'><input type='text' id='cloudSearch' placeholder='搜索文档名称...' oninput='renderCloudList()' style='width:100%; padding:12px 14px; border:2px solid #e2ecf2; border-radius:12px; font-size:14px; outline:none; box-sizing:border-box; background:#f7fbfd;'></div><div class='list' id='cloudListDiv' style='flex:1; overflow-y:auto; max-height:50vh;'></div></div></div>" +
            "<div class='overlay history-modal' id='historyModal'><div class='content' style='padding:0; max-height:85vh; display:flex; flex-direction:column; overflow:hidden;'><div class='header' style='padding:16px 18px; color:#fff; font-weight:800; display:flex; justify-content:space-between; align-items:center; border-radius:24px 24px 0 0;'><span>与电脑端的消息记录</span><button onclick='closeHistory()' style='background:transparent; color:#fff; border:none; font-size:22px; font-weight:800; cursor:pointer;'>X</button></div><div class='history-toolbar'><button onclick='clearHistory()'>清空记录</button></div><div class='history-list' id='historyListDiv' style='flex:1; overflow-y:auto; max-height:65vh; padding:14px;'></div></div></div>" +
            "<div class='overlay broadcast-modal' id='powerModal'><div class='content' style='padding:0; max-height:80vh; display:flex; flex-direction:column; overflow:hidden;'><div class='header' style='padding:16px 18px; color:#fff; font-weight:800; display:flex; justify-content:space-between; align-items:center; border-radius:24px 24px 0 0;'><span>电源控制</span><button onclick='closePowerPanel()' style='background:transparent; color:#fff; border:none; font-size:22px; font-weight:800; cursor:pointer;'>X</button></div><div style='padding:20px;'><p style='font-size:13px; color:#7a8b99; margin-bottom:20px; text-align:center;'>危险操作，请谨慎使用<br>点击后需输入 <b>确认</b> 两个字才会执行</p><button onclick='sendPowerCmd(\"SHUTDOWN\")' style='width:100%; padding:16px; background:#ef4444; color:#fff; border:none; border-radius:14px; font-weight:800; font-size:16px; margin-bottom:12px;'>关闭教室电脑</button><button onclick='sendPowerCmd(\"RESTART\")' style='width:100%; padding:16px; background:#f97316; color:#fff; border:none; border-radius:14px; font-weight:800; font-size:16px;'>重启教室电脑</button></div></div></div>" +
            "<div class='overlay broadcast-modal' id='broadcastModal'><div class='content' style='padding:0; max-height:85vh; display:flex; flex-direction:column; overflow:hidden;'><div class='header' style='padding:16px 18px; color:#fff; font-weight:800; display:flex; justify-content:space-between; align-items:center; border-radius:24px 24px 0 0;'><span>全体开会</span><button onclick='closeBroadcastPanel()' style='background:transparent; color:#fff; border:none; font-size:22px; font-weight:800; cursor:pointer;'>X</button></div><div style='padding:14px 16px 6px 16px;'><p style='font-size:13px; color:#7a8b99; margin:0 0 10px 0;'>选择要发送到的班级（可多选）：</p><button onclick='toggleAllClasses()' style='width:100%; padding:11px; background:#f2f6f9; color:#556670; border:none; border-radius:11px; font-weight:800; font-size:13px;'>全选 / 取消全选</button></div><div class='broadcast-list' id='broadcastListDiv'></div><div style='padding:14px 16px 20px 16px; border-top:1px solid #eef2f5;'><div class='floating-field'><textarea id='broadcastText' placeholder=' ' style='min-height:80px;'></textarea><label for='broadcastText'>输入要发送的内容</label></div><button onclick='doBroadcast()' class='btn-send' style='margin-top:4px;'>发送到选中班级</button></div></div></div>" +
            "<div class='overlay broadcast-modal' id='addClassModal'><div class='content'><h3>添加班级</h3><p class='subtitle'>班级名是显示的，专属名是电脑端的通道名</p><div class='floating-field'><input type='text' id='newClassName' placeholder=' '><label for='newClassName'>班级名，如：2025级3班</label></div><div class='floating-field'><input type='text' id='newClassTopic' placeholder=' '><label for='newClassTopic'>专属名，如：cy3（电脑端的通道名）</label></div><div class='btn-row2'><button class='btn-cancel' onclick='closeAddClassPanel()'>取消</button><button class='btn-save' onclick='confirmAddClass()'>添加</button></div></div></div>" +
            "<div class='overlay' id='appAlertModal'><div class='content'><h3 id='appAlertTitle'>提示</h3><p class='subtitle' id='appAlertMsg'></p><div class='btn-row2'><button class='btn-save' onclick='closeAppAlert()' style='flex:1;'>知道了</button></div></div></div>" +
            "<div class='overlay' id='appConfirmModal'><div class='content'><h3 id='appConfirmTitle'>确认操作</h3><p class='subtitle' id='appConfirmMsg'></p><div class='btn-row2'><button class='btn-cancel' onclick='closeAppConfirm(false)'>取消</button><button class='btn-save' onclick='closeAppConfirm(true)'>确定</button></div></div></div>" +
            "<div class='overlay' id='appPromptModal'><div class='content'><h3 id='appPromptTitle'>输入</h3><p class='subtitle' id='appPromptMsg'></p><div class='floating-field'><input type='text' id='appPromptInput' placeholder=' '><label for='appPromptInput' id='appPromptLabel'>请输入</label></div><div class='btn-row2'><button class='btn-cancel' onclick='closeAppPrompt(null)'>取消</button><button class='btn-save' onclick='confirmAppPrompt()'>确定</button></div></div></div>" +
            "<script>" +
            "var _appAlertCb = null; var _appConfirmCb = null; var _appPromptCb = null;" +
            "function appAlert(msg, cb) { document.getElementById('appAlertMsg').textContent = String(msg); document.getElementById('appAlertModal').classList.add('show'); _appAlertCb = cb || null; }" +
            "function closeAppAlert() { document.getElementById('appAlertModal').classList.remove('show'); var cb = _appAlertCb; _appAlertCb = null; if (cb) setTimeout(cb, 120); }" +
            "window.alert = function(msg) { appAlert(msg); };" +
            "function appConfirm(msg, onOk, onCancel) { document.getElementById('appConfirmMsg').textContent = String(msg); document.getElementById('appConfirmModal').classList.add('show'); _appConfirmCb = { ok: onOk || null, cancel: onCancel || null }; }" +
            "function closeAppConfirm(result) { document.getElementById('appConfirmModal').classList.remove('show'); var cb = _appConfirmCb; _appConfirmCb = null; if (!cb) return; if (result && cb.ok) setTimeout(cb.ok, 120); else if (!result && cb.cancel) setTimeout(cb.cancel, 120); }" +
            "function appPrompt(title, msg, placeholder, defaultValue, onOk) { document.getElementById('appPromptTitle').textContent = title || '输入'; document.getElementById('appPromptMsg').textContent = msg || ''; document.getElementById('appPromptLabel').textContent = placeholder || '请输入'; document.getElementById('appPromptInput').value = defaultValue || ''; document.getElementById('appPromptModal').classList.add('show'); _appPromptCb = onOk || null; setTimeout(function() { var el = document.getElementById('appPromptInput'); if (el) el.focus(); }, 180); }" +
            "function closeAppPrompt(result) { document.getElementById('appPromptModal').classList.remove('show'); var cb = _appPromptCb; _appPromptCb = null; if (cb && result !== null) setTimeout(function(){ cb(result); }, 120); }" +
            "function confirmAppPrompt() { var v = document.getElementById('appPromptInput').value; closeAppPrompt(v); }" +
            "function requestOverlayPermission() { if (window.AndroidBridge && AndroidBridge.requestOverlayPermission) { try { AndroidBridge.requestOverlayPermission(); appAlert('请在弹出的设置页面中，找到本应用，打开【允许显示在其他应用上层】权限'); } catch(e) { appAlert('无法请求权限: ' + e.message); } } else { appAlert('当前环境不支持'); } }" +
            "var topic = '" + topic + "'; var toPhoneTopic = '" + topic + "/toPhone'; var readTopic = '" + topic + "/read'; var teacherName = '" + teacherName + "'; var currentChannel = '" + displayName + "';" +
            "var appList = []; var cloudList = []; var pendingAppList = false; var pendingCloudList = false; var pendingLog = false; var fileDialogOpen = false; var lastPcHeartbeat = 0; var onlineClasses = {};" +
            "var autoCloseSec = parseInt(localStorage.getItem('set_auto_close') || '0'); var displayMode = localStorage.getItem('set_display_mode') || 'fullscreen'; var readAloud = localStorage.getItem('set_read_aloud') === 'true'; var selectedVoice = localStorage.getItem('set_voice') || 'zh-CN-XiaoxiaoNeural';" +
            "var receivePcMsgRaw = localStorage.getItem('set_receive_pc_msg'); var receivePcMsg = (receivePcMsgRaw === null) ? true : (receivePcMsgRaw === 'true'); var useFloatingRaw = localStorage.getItem('set_floating_notice'); var useFloating = (useFloatingRaw === null) ? true : (useFloatingRaw === 'true');" +
            "var chatHistory = []; try { chatHistory = JSON.parse(localStorage.getItem('chat_history') || '[]'); } catch(e) { chatHistory = []; } var client = null; var phoneHeartbeatTimer = null;" +
            "function applyAutoTheme() { var hour = new Date().getHours(); if (hour >= 19 || hour < 7) document.body.classList.add('dark-mode'); else document.body.classList.remove('dark-mode'); } applyAutoTheme(); setInterval(applyAutoTheme, 60000);" +
            "function setCloudStatus(online) { var el = document.getElementById('cloudStatus'); var txt = document.getElementById('cloudText'); if (!el) return; if (online) { el.classList.add('online'); if (txt) txt.innerText = '已连接'; } else { el.classList.remove('online'); if (txt) txt.innerText = '未连接'; } }" +
            "function setPcStatus(online) { var el = document.getElementById('pcStatus'); var txt = document.getElementById('pcText'); if (!el) return; if (online) { el.classList.add('online'); if (txt) txt.innerText = '已连接'; } else { el.classList.remove('online'); if (txt) txt.innerText = '未连接'; } }" +
            "function saveHistory() { try { if (chatHistory.length > 200) chatHistory = chatHistory.slice(-200); localStorage.setItem('chat_history', JSON.stringify(chatHistory)); } catch(e) {} }" +
            "function addHistory(sender, content, type, fromMe) { var now = new Date(); var timeStr = ('0' + now.getHours()).slice(-2) + ':' + ('0' + now.getMinutes()).slice(-2); chatHistory.push({ sender: sender, content: content, type: type, fromMe: fromMe, time: timeStr }); saveHistory(); }" +
            "function renderHistory() { var list = document.getElementById('historyListDiv'); if (!list) return; list.innerHTML = ''; if (chatHistory.length === 0) { list.innerHTML = '<div class=\"history-empty\">暂无消息记录<br>发送或接收消息后会显示在这里</div>'; return; } for (var i = 0; i < chatHistory.length; i++) { var h = chatHistory[i]; var item = document.createElement('div'); item.className = h.fromMe ? 'history-item from-me' : 'history-item from-pc'; var icon = '[消息]'; if (h.type === 'image') icon = '[图片]'; else if (h.type === 'mix') icon = '[图文]'; var contentText = h.content || ''; if (h.type === 'image' && !contentText) contentText = '（图片消息）'; item.innerHTML = '<div class=\"sender\">' + (h.fromMe ? '我' : h.sender) + ' ' + icon + '</div>' + '<div class=\"content-text\"></div>' + '<div class=\"time\">' + h.time + '</div>'; item.querySelector('.content-text').textContent = contentText; list.appendChild(item); } list.scrollTop = list.scrollHeight; }" +
            "function openHistory() { renderHistory(); document.getElementById('historyModal').classList.add('show'); sendReadReceipt(); }" +
            "function closeHistory() { document.getElementById('historyModal').classList.remove('show'); }" +
            "function clearHistory() { appConfirm('确定要清空所有聊天记录吗？', function() { chatHistory = []; saveHistory(); renderHistory(); }); }" +
            "function sendReadReceipt() { if (!client || !client.connected) return; var now = new Date(); var timeStr = ('0' + now.getHours()).slice(-2) + ':' + ('0' + now.getMinutes()).slice(-2); client.publish(readTopic, JSON.stringify({ time: timeStr, name: currentChannel })); }" +
            "function startPhoneHeartbeat() { if (phoneHeartbeatTimer) clearInterval(phoneHeartbeatTimer); var sendHb = function() { if (!client || !client.connected) return; client.publish('classroom/phone_heartbeat/' + currentChannel, JSON.stringify({ name: currentChannel, time: Date.now() })); }; sendHb(); phoneHeartbeatTimer = setInterval(sendHb, 5000); }" +
            "function showNotice(sender, content, type) { var container = document.getElementById('noticeContainer'); if (!container) return; var card = document.createElement('div'); card.className = 'notice-card'; var icon = '[消息]'; if (type === 'image') icon = '[图片]'; else if (type === 'mix') icon = '[图文]'; var preview = content || ''; if (preview.length > 100) preview = preview.substring(0, 100) + '...'; card.innerHTML = '<span class=\"icon\">' + icon + '</span><div class=\"body\"><div class=\"title\"></div><div class=\"content\"></div></div><button class=\"close\">X</button>'; card.querySelector('.title').textContent = sender + ' 发来消息'; card.querySelector('.content').textContent = preview || '（图片消息）'; var dismiss = function() { card.classList.add('hide'); setTimeout(function() { if (card.parentNode) card.parentNode.removeChild(card); }, 320); }; card.querySelector('.close').onclick = function(e) { e.stopPropagation(); dismiss(); }; card.onclick = dismiss; container.appendChild(card); setTimeout(dismiss, 8000); }" +
            "function showKickedToast() { var old = document.getElementById('kickedToast'); if (old) old.remove(); var t = document.createElement('div'); t.id = 'kickedToast'; t.style.cssText = 'position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);background:#f44336;color:#fff;padding:24px 40px;border-radius:18px;font-size:18px;font-weight:800;z-index:999999;text-align:center;'; t.innerHTML = '[通知]<br>您已被管理员踢出'; document.body.appendChild(t); setTimeout(function(){ if (t.parentNode) t.parentNode.removeChild(t); }, 1800); }" +
            "function showFeedback(id, msg, isSuccess) { var el = document.getElementById(id); if (!el) return; el.innerText = msg; el.className = 'feedback show ' + (isSuccess ? 'success' : 'error'); clearTimeout(el._timer); el._timer = setTimeout(function() { el.classList.remove('show'); }, 3000); }" +
            "function setQuick(text) { var msgBox = document.getElementById('msg'); if (msgBox) { msgBox.value = text; msgBox.focus(); } }" +
            "function getQuickReplies() { try { return JSON.parse(localStorage.getItem('quick_replies') || '[]'); } catch(e) { return []; } }" +
            "function setQuickReplies(list) { localStorage.setItem('quick_replies', JSON.stringify(list)); }" +
            "function renderQuickReplies() { var div = document.getElementById('quickRepliesDiv'); if (!div) return; div.innerHTML = ''; var list = getQuickReplies(); for (var i = 0; i < list.length; i++) { (function(idx) { var btn = document.createElement('button'); btn.className = 'quick-btn'; btn.textContent = list[idx]; btn.onclick = function() { setQuick(list[idx]); }; var del = document.createElement('span'); del.textContent = ' X'; del.style.cssText = 'color:#ef4444;font-weight:800;cursor:pointer;margin-left:4px;'; del.onclick = function(e) { e.stopPropagation(); appConfirm('删除快捷回复「' + list[idx] + '」？', function() { var l = getQuickReplies(); l.splice(idx, 1); setQuickReplies(l); renderQuickReplies(); }); }; btn.appendChild(del); div.appendChild(btn); })(i); } var addBtn = document.createElement('button'); addBtn.className = 'quick-btn'; addBtn.textContent = '[+] 添加'; addBtn.style.cssText = 'background:#00d4b4;color:#fff;'; addBtn.onclick = function() { appPrompt('添加快捷回复', '输入内容后点击确定', '请输入快捷回复内容', '', function(t) { if (!t) return; t = t.trim(); if (!t) return; var list = getQuickReplies(); if (list.indexOf(t) >= 0) { appAlert('该快捷回复已存在'); return; } list.push(t); setQuickReplies(list); renderQuickReplies(); }); }; div.appendChild(addBtn); }" +
            "function pickImages() { document.getElementById('imgFile').click(); }" +
            "function openPowerPanel() { document.getElementById('powerModal').classList.add('show'); } function closePowerPanel() { document.getElementById('powerModal').classList.remove('show'); }" +
            "function sendPowerCmd(cmd) { if (!topic) { appAlert('请先完成初始化'); return; } var action = (cmd === 'SHUTDOWN' ? '关机' : '重启'); appPrompt('危险操作', '即将对教室电脑执行【' + action + '】，请输入“确认”两个字继续', '请输入：确认', '', function(v) { if (v !== '确认') { if (v !== null) appAlert('已取消'); return; } client.publish(topic, 'CMD:' + cmd); appAlert('指令已发送，10秒后电脑将' + action); closePowerPanel(); }); }" +
            "var savedClasses = localStorage.getItem('broadcast_classes'); if (!savedClasses) { localStorage.setItem('broadcast_classes', '[]'); }" +
            "function getClasses() { try { var raw = JSON.parse(localStorage.getItem('broadcast_classes') || '[]'); var out = []; for (var i = 0; i < raw.length; i++) { var x = raw[i]; if (typeof x === 'string') out.push({ name: x, topic: x }); else if (x && x.name && x.topic) out.push({ name: x.name, topic: x.topic }); } return out; } catch(e) { return []; } }" +
            "function setClasses(list) { localStorage.setItem('broadcast_classes', JSON.stringify(list)); } var selectedBroadcastClasses = [];" +
            "function openBroadcastPanel() { selectedBroadcastClasses = []; renderBroadcastList(); document.getElementById('broadcastText').value = document.getElementById('msg').value || ''; document.getElementById('broadcastModal').classList.add('show'); }" +
            "function closeBroadcastPanel() { document.getElementById('broadcastModal').classList.remove('show'); }" +
            "function renderBroadcastList() { var list = document.getElementById('broadcastListDiv'); if (!list) return; list.innerHTML = ''; var classes = getClasses(); for (var i = 0; i < classes.length; i++) { var cls = classes[i]; var selected = selectedBroadcastClasses.indexOf(cls.name) >= 0; var item = document.createElement('div'); item.className = 'broadcast-class-item' + (selected ? ' selected' : ''); item.innerHTML = '<div class=\"checkbox\"></div><span style=\"flex:1;\">' + cls.name + '</span><span class=\"del-btn\" style=\"color:#ef4444;font-size:16px;padding:0 6px;font-weight:800;\">X</span>'; item.querySelector('.del-btn').onclick = (function(n) { return function(e) { e.stopPropagation(); deleteClass(n); }; })(cls.name); item.setAttribute('data-class', cls.name); item.onclick = function() { var c = this.getAttribute('data-class'); var idx = selectedBroadcastClasses.indexOf(c); if (idx >= 0) selectedBroadcastClasses.splice(idx, 1); else selectedBroadcastClasses.push(c); renderBroadcastList(); }; list.appendChild(item); } var addBtn = document.createElement('div'); addBtn.className = 'broadcast-class-item'; addBtn.style.borderStyle = 'dashed'; addBtn.style.justifyContent = 'center'; addBtn.style.color = '#00a884'; addBtn.innerHTML = '[+] 添加新班级'; addBtn.onclick = function() { document.getElementById('newClassName').value = ''; document.getElementById('newClassTopic').value = ''; document.getElementById('addClassModal').classList.add('show'); }; list.appendChild(addBtn); }" +
            "function toggleAllClasses() { var classes = getClasses(); if (selectedBroadcastClasses.length === classes.length) selectedBroadcastClasses = []; else { selectedBroadcastClasses = []; for (var i = 0; i < classes.length; i++) selectedBroadcastClasses.push(classes[i].name); } renderBroadcastList(); }" +
            "function openAddClassPanel() { document.getElementById('addClassModal').classList.add('show'); } function closeAddClassPanel() { document.getElementById('addClassModal').classList.remove('show'); }" +
            "function confirmAddClass() { var name = document.getElementById('newClassName').value.trim(); var topicName = document.getElementById('newClassTopic').value.trim(); if (!name) { appAlert('请输入班级名'); return; } if (!topicName) { appAlert('请输入专属名'); return; } var classes = getClasses(); for (var i = 0; i < classes.length; i++) { if (classes[i].name === name) { appAlert('该班级名已存在'); return; } } classes.push({ name: name, topic: topicName }); setClasses(classes); document.getElementById('newClassName').value = ''; document.getElementById('newClassTopic').value = ''; closeAddClassPanel(); renderBroadcastList(); }" +
            "function deleteClass(name) { appConfirm('确定删除班级「' + name + '」吗？', function() { var classes = getClasses(); var out = []; for (var i = 0; i < classes.length; i++) { if (classes[i].name !== name) out.push(classes[i]); } setClasses(out); var sIdx = selectedBroadcastClasses.indexOf(name); if (sIdx >= 0) selectedBroadcastClasses.splice(sIdx, 1); renderBroadcastList(); }); }" +
            "function doBroadcast() { var text = document.getElementById('broadcastText').value.trim(); if (!text) { appAlert('请输入要发送的内容'); return; } if (selectedBroadcastClasses.length === 0) { appAlert('请至少选择一个班级'); return; } if (!client || !client.connected) { appAlert('云服务器未连接'); return; } var classes = getClasses(); var map = {}; for (var i = 0; i < classes.length; i++) map[classes[i].name] = classes[i].topic; var sentNames = []; for (var j = 0; j < selectedBroadcastClasses.length; j++) { var n = selectedBroadcastClasses[j]; var tp = map[n] || n; client.publish('classroom/msg/' + tp, 'TEXT:' + text); sentNames.push(n); } appAlert('已发送到：' + sentNames.join('、')); document.getElementById('msg').value = ''; closeBroadcastPanel(); }" +
            "function doInit() { var t = document.getElementById('initTeacherName').value.trim(); var c = document.getElementById('initChannelName').value.trim(); if (!t) { appAlert('请输入您的姓名'); return; } if (!c) c = 'Classroom1'; teacherName = t; currentChannel = c; topic = 'classroom/msg/' + c; toPhoneTopic = topic + '/toPhone'; readTopic = topic + '/read'; if (window.AndroidBridge) { AndroidBridge.saveTeacherName(t); AndroidBridge.saveNewTopic(c); } document.getElementById('teacherBadge').innerText = '[管理员] ' + t; document.getElementById('channelName').innerText = c; document.getElementById('initOverlay').classList.remove('show'); if (client && client.connected) { client.subscribe(topic); client.subscribe(toPhoneTopic); } localStorage.setItem('first_launch_done', 'true'); setTimeout(function() { openSettings(); }, 800); }" +
            "function exitApp() { if (window.AndroidBridge) AndroidBridge.exitApp(); }" +
            "function sendMix() { if (!topic) { appAlert('请先完成初始化'); return; } var text = document.getElementById('msg').value; var files = document.getElementById('imgFile').files; if(!text && files.length === 0) { showFeedback('msgFeedback', '请输入文字或选择图片', false); return; } var imagesData = []; var loadedCount = 0; if (files.length === 0) { sendPayload(text, imagesData); } else { for (var i = 0; i < files.length; i++) { var reader = new FileReader(); reader.onload = function(e) { var base64Data = e.target.result.split(',')[1]; imagesData.push(base64Data); loadedCount++; if(loadedCount === files.length) { sendPayload(text, imagesData); } }; reader.readAsDataURL(files[i]); } } }" +
            "function sendPayload(text, images) { var payload = JSON.stringify({ text: text, images: images, sender: teacherName, mode: displayMode, auto_close: autoCloseSec, read_aloud: readAloud, voice: selectedVoice }); client.publish(topic, 'MIX:' + payload); var htype = images.length > 0 ? (text ? 'mix' : 'image') : 'text'; addHistory(teacherName, text || '', htype, true); document.getElementById('msg').value = ''; document.getElementById('imgFile').value = ''; document.getElementById('imgPreview').innerHTML = ''; var _t = document.getElementById('pickImgText'); if (_t) _t.textContent = '点击选择图片'; var extra = ''; if (readAloud && text) extra = '，将朗读'; showFeedback('msgFeedback', '消息发送成功！' + (images.length > 0 ? '（含' + images.length + '张图片）' : '') + extra, true); }" +
            "function previewImages() { var preview = document.getElementById('imgPreview'); preview.innerHTML = ''; var files = document.getElementById('imgFile').files; if (files.length > 6) { appAlert('最多只能选6张图片！'); document.getElementById('imgFile').value = ''; return; } var txt = document.getElementById('pickImgText'); if (txt) { if (files.length > 0) txt.textContent = '已选 ' + files.length + ' 张，点击可重新选择'; else txt.textContent = '点击选择图片'; } for (var i = 0; i < files.length; i++) { var reader = new FileReader(); reader.onload = function(e) { var img = document.createElement('img'); img.src = e.target.result; preview.appendChild(img); }; reader.readAsDataURL(files[i]); } }" +
            "function requestAppList() { if (!topic) { appAlert('请先完成初始化'); return; } pendingAppList = true; client.publish(topic, 'CMD:GET_APP_LIST'); }" +
            "function showAppListDialog() { if (appList.length === 0) { appAlert('电脑端未返回任何软件'); return; } document.getElementById('appSearch').value = ''; renderAppList(); document.getElementById('appModal').classList.add('show'); } function closeAppModal() { document.getElementById('appModal').classList.remove('show'); }" +
            "function renderAppList() { var list = document.getElementById('appListDiv'); list.innerHTML = ''; var filter = document.getElementById('appSearch').value.toLowerCase(); var shown = 0; for (var i = 0; i < appList.length; i++) { var app = appList[i]; if (filter && app.name.toLowerCase().indexOf(filter) < 0) continue; var item = document.createElement('div'); item.className = 'item'; item.textContent = app.name; item.onclick = (function(idx) { return function() { openAppByIndex(idx); }; })(i); list.appendChild(item); shown++; } if (shown === 0) { var empty = document.createElement('div'); empty.className = 'item'; empty.style.color = '#95a5a6'; empty.textContent = '没有匹配的软件'; list.appendChild(empty); } }" +
            "function openAppByIndex(idx) { if (idx < 0 || idx >= appList.length) return; client.publish(topic, 'CMD:OPEN_APP:' + appList[idx].path); closeAppModal(); }" +
            "function requestWpsCloud() { if (!topic) { appAlert('请先完成初始化'); return; } pendingCloudList = true; client.publish(topic, 'CMD:GET_WPS_CLOUD_LIST'); }" +
            "function showCloudDialog() { if (cloudList.length === 0) { appAlert('未找到 WPS 云文档缓存'); return; } document.getElementById('cloudSearch').value = ''; renderCloudList(); document.getElementById('cloudModal').classList.add('show'); } function closeCloudModal() { document.getElementById('cloudModal').classList.remove('show'); }" +
            "function renderCloudList() { var list = document.getElementById('cloudListDiv'); list.innerHTML = ''; var filter = document.getElementById('cloudSearch').value.toLowerCase(); var shown = 0; for (var i = 0; i < cloudList.length; i++) { var f = cloudList[i]; if (filter && f.name.toLowerCase().indexOf(filter) < 0) continue; var item = document.createElement('div'); item.className = 'item'; item.textContent = '[文档] ' + f.name; item.onclick = (function(idx) { return function() { openCloudByIndex(idx); }; })(i); list.appendChild(item); shown++; } if (shown === 0) { var empty = document.createElement('div'); empty.className = 'item'; empty.style.color = '#95a5a6'; empty.textContent = '没有匹配的文档'; list.appendChild(empty); } }" +
            "function openCloudByIndex(idx) { if (idx < 0 || idx >= cloudList.length) return; client.publish(topic, 'CMD:OPEN_FILE_FULL:' + cloudList[idx].path); closeCloudModal(); showFeedback('fileFeedback', '已打开：' + cloudList[idx].name, true); }" +
            "function openFile() { if (!topic) { appAlert('请先完成初始化'); return; } var fileName = document.getElementById('fileNameInput').value.trim(); if (!fileName) { showFeedback('fileFeedback', '请输入文件名', false); return; } client.publish(topic, 'CMD:OPEN_FILE:' + fileName); showFeedback('fileFeedback', '已发送打开指令：' + fileName, true); }" +
            "function showFileListDialog(files) { if (fileDialogOpen) return; fileDialogOpen = true; var msg = '找到多个同名文件，请输入序号：\\n\\n'; for (var i = 0; i < files.length; i++) { msg += (i+1) + '. ' + files[i].name + '\\n'; } appPrompt('选择文件', msg, '请输入序号', '1', function(v) { fileDialogOpen = false; if (!v) return; var idx = parseInt(v) - 1; if (idx < 0 || idx >= files.length) { showFeedback('fileFeedback', '无效选择', false); return; } client.publish(topic, 'CMD:OPEN_FILE_FULL:' + files[idx].path); showFeedback('fileFeedback', '已打开：' + files[idx].name, true); }); }" +
            "function requestLog() { if (!topic) { appAlert('请先完成初始化'); return; } pendingLog = true; client.publish(topic, 'CMD:GET_LOG'); showLogDialog('正在加载日志...'); }" +
            "function showLogDialog(logContent) { var existing = document.getElementById('logOverlay'); if (existing) existing.remove(); var panel = document.createElement('div'); panel.id = 'logOverlay'; panel.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.95);z-index:99999;padding:20px;overflow-y:auto;color:#0f0;font-family:monospace;font-size:12px;white-space:pre-wrap;'; panel.innerHTML = '<div style=\"display:flex;justify-content:space-between;margin-bottom:15px;\"><span style=\"color:#4caf50;font-weight:800;font-size:16px;\">[日志] 设备日志</span><button onclick=\"document.getElementById(\\'logOverlay\\').remove()\" style=\"background:#f44336;color:#fff;border:none;padding:8px 16px;border-radius:8px;font-weight:800;cursor:pointer;\">X 关闭</button></div>'; var content = document.createElement('div'); content.textContent = logContent; panel.appendChild(content); document.body.appendChild(panel); }" +
            "function openSwitchPanel() { document.getElementById('switchInput').value = ''; document.getElementById('switchPanel').classList.add('show'); } function closeSwitchPanel() { document.getElementById('switchPanel').classList.remove('show'); }" +
            "function doSwitch() { var newName = document.getElementById('switchInput').value.trim(); if (!newName) { appAlert('请输入通道名称'); return; } if (client && topic) client.unsubscribe(topic); if (client && toPhoneTopic) client.unsubscribe(toPhoneTopic); currentChannel = newName; topic = 'classroom/msg/' + newName; toPhoneTopic = topic + '/toPhone'; readTopic = topic + '/read'; lastPcHeartbeat = 0; setPcStatus(false); document.getElementById('channelName').innerText = newName; if (window.AndroidBridge) { AndroidBridge.saveNewTopic(newName); } if (client && client.connected) { client.subscribe(topic); client.subscribe(toPhoneTopic); } startPhoneHeartbeat(); closeSwitchPanel(); }" +
            "function openSettings() { document.getElementById('setTeacherName').value = teacherName; document.getElementById('setAutoClose').value = autoCloseSec; document.getElementById('setDisplayMode').value = displayMode; document.getElementById('setReadAloud').checked = readAloud; document.getElementById('setVoice').value = selectedVoice; document.getElementById('setReceivePcMsg').checked = receivePcMsg; document.getElementById('setFloatingNotice').checked = useFloating; document.getElementById('settingsPanel').classList.add('show'); } function closeSettings() { document.getElementById('settingsPanel').classList.remove('show'); }" +
            "function saveSettings() { var newTeacher = document.getElementById('setTeacherName').value.trim(); if (newTeacher && newTeacher !== teacherName) { teacherName = newTeacher; document.getElementById('teacherBadge').innerText = '[管理员] ' + teacherName; if (window.AndroidBridge) { AndroidBridge.saveTeacherName(newTeacher); } } autoCloseSec = parseInt(document.getElementById('setAutoClose').value); displayMode = document.getElementById('setDisplayMode').value; readAloud = document.getElementById('setReadAloud').checked; selectedVoice = document.getElementById('setVoice').value; receivePcMsg = document.getElementById('setReceivePcMsg').checked; useFloating = document.getElementById('setFloatingNotice').checked; localStorage.setItem('set_auto_close', autoCloseSec); localStorage.setItem('set_display_mode', displayMode); localStorage.setItem('set_read_aloud', readAloud); localStorage.setItem('set_voice', selectedVoice); localStorage.setItem('set_receive_pc_msg', receivePcMsg); localStorage.setItem('set_floating_notice', useFloating); closeSettings(); }" +
            "function onMqttLoaded() { if (client) return; if (typeof mqtt === 'undefined') { setTimeout(onMqttLoaded, 300); return; } " +
            // ⚠️ 这里已经替换成了你的 EMQX 信息
            "  var options = { clientId: 'android_' + Math.random().toString(16).substr(2, 8), username: 'Dante-chain', password: '123456', clean: true, connectTimeout: 5000, reconnectPeriod: 2000 };" +
            "  client = mqtt.connect('wss://t12acec1.ala.cn-hangzhou.emqxsl.cn:8084/mqtt', options);" +
            "  client.on('connect', function() { setCloudStatus(true); client.subscribe('classroom/heartbeat'); if (topic) client.subscribe(topic); if (toPhoneTopic) client.subscribe(toPhoneTopic); startPhoneHeartbeat(); var lo = document.getElementById('loadingOverlay'); if (lo) { lo.classList.add('hide'); setTimeout(function() { if (lo && lo.parentNode) lo.parentNode.removeChild(lo); }, 500); } });" +
            "  client.on('reconnect', function() { setCloudStatus(false); }); client.on('error', function(err) { setCloudStatus(false); });" +
            "  client.on('message', function(t, message) { var payload = message.toString(); if (t === 'classroom/heartbeat') { try { var data = JSON.parse(payload); if (data.name === currentChannel) { lastPcHeartbeat = Date.now(); setPcStatus(true); } } catch(e) {} return; } if (t === toPhoneTopic) { try { var d = JSON.parse(payload); var s = d.sender || '电脑端'; var c = d.content || ''; var ty = d.type || 'text'; var fi = ''; if (d.images && d.images.length > 0) fi = d.images[0]; addHistory(s, c, ty, false); if (receivePcMsg) { showNotice(s, c, ty); if (useFloating && window.AndroidBridge && AndroidBridge.showFloatingNotice) { try { AndroidBridge.showFloatingNotice(s, c, ty, fi); } catch(e) { try { AndroidBridge.showNotification(s, c, ty); } catch(e2) {} } } else if (window.AndroidBridge && AndroidBridge.showNotification) { try { AndroidBridge.showNotification(s, c, ty); } catch(e) {} } } } catch(e) {} return; } if (payload.startsWith('CMD:APP_LIST:')) { if (!pendingAppList) return; pendingAppList = false; try { appList = JSON.parse(payload.substring(13)); showAppListDialog(); } catch(e) {} } else if (payload.startsWith('CMD:WPS_CLOUD_LIST:')) { if (!pendingCloudList) return; pendingCloudList = false; try { cloudList = JSON.parse(payload.substring(19)); showCloudDialog(); } catch(e) {} } else if (payload === 'CMD:WPS_CLOUD_EMPTY') { pendingCloudList = false; appAlert('未找到 WPS 云文档缓存'); } else if (payload.startsWith('CMD:FILE_LIST:')) { try { showFileListDialog(JSON.parse(payload.substring(14))); } catch(e) {} } else if (payload.startsWith('CMD:FILE_NOT_FOUND:')) { showFeedback('fileFeedback', '未找到该文件：' + payload.substring(19), false); } else if (payload.startsWith('CMD:LOG_DATA:')) { if (!pendingLog) return; pendingLog = false; showLogDialog(payload.substring(13)); } else if (payload === 'CMD:KICKED') { showKickedToast(); } });" +
            "  setInterval(function() { if (lastPcHeartbeat > 0 && (Date.now() - lastPcHeartbeat) > 12000) setPcStatus(false); }, 2000);" +
            "}" +
            "renderQuickReplies(); if (typeof mqtt !== 'undefined') { onMqttLoaded(); }" +
            "</script></body></html>";
    }

    public class WebAppInterface {
        Context mContext;
        WebAppInterface(Context c) { mContext = c; }
        @JavascriptInterface public void saveNewTopic(String newTopic) { if (newTopic != null && !newTopic.isEmpty()) { sharedPref.edit().putString("topic_suffix", newTopic).apply(); } }
        @JavascriptInterface public void saveTeacherName(String newName) { if (newName != null && !newName.isEmpty()) { sharedPref.edit().putString("teacher_name", newName).apply(); } }
        @JavascriptInterface public void exitApp() { finish(); }
        @JavascriptInterface public void showNotification(String sender, String content, String type) { showNativeNotification(sender, content, type); }
        @JavascriptInterface public void showFloatingNotice(String sender, String content, String type, String imageBase64) { MainActivity.this.showFloatingNotice(sender, content, type, imageBase64); }
        @JavascriptInterface public void requestOverlayPermission() { MainActivity.this.requestOverlayPermission(); }
        @JavascriptInterface public boolean canDrawOverlays() { return MainActivity.this.canDrawOverlays(); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (mFilePathCallback == null) { super.onActivityResult(requestCode, resultCode, data); return; }
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK) {
                if (data == null) { results = null; }
                else {
                    String dataString = data.getDataString();
                    if (dataString != null) { results = new Uri[]{Uri.parse(dataString)}; }
                    else if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            mFilePathCallback.onReceiveValue(results);
            mFilePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }
}