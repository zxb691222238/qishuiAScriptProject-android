package cn.qishui.helper;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class HelperService extends AccessibilityService {
    public static volatile HelperService instance;
    final Handler handler = new Handler(Looper.getMainLooper());
    private TextRecognizer recognizer;
    private WindowManager wm;
    private Button bubble;
    private View menu;
    private FlowEngine engine;
    private boolean active, busy, targetSeen;
    private long generation, activeSince, elapsedSaved, lastScan, lastCapture, captureFailures;
    private long lastBringBack; private int bringBackAttempts;
    private String status = "待开始", message = "无障碍服务已就绪，请进入第一条奖励广告", lastPublished = "";
    private Bitmap evidence;
    private int lastWidth, lastHeight;

    @Override protected void onServiceConnected() {
        instance = this;
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        showBubble(); publish(message, "待开始");
        handler.post(loop);
    }
    public static final class PauseReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            HelperService s = instance;
            if (s != null) s.pause("已通过通知暂停");
        }
    }
    public String statusText() { return status + " · 已完成 " + (engine == null ? 0 : engine.rounds) + " 轮\n" + message; }
    public boolean isActive() { return active; }
    public void startTask(FlowEngine.Settings settings) {
        AppData.save(this, settings); closeMenu(); generation++; active = true; busy = false;
        engine = new FlowEngine(settings); elapsedSaved = 0; activeSince = SystemClock.elapsedRealtime(); targetSeen = false; bringBackAttempts = 0; lastBringBack = 0;
        publish("请进入第一条奖励广告，点悬浮按钮可暂停", settings.dryRun ? "仅识别" : "运行中");
        }
    public void pause(String reason) {
        if (active) elapsedSaved += SystemClock.elapsedRealtime() - activeSince;
        active = false; generation++; busy = false; restoreBubble(); publish(reason, "已暂停");
    }
    public void resumeTask() {
        if (engine == null || engine.phase.equals("STOPPED")) { publish("请点击重新开始创建任务", "待开始"); return; }
        closeMenu();
        if (!active) { activeSince = SystemClock.elapsedRealtime(); active = true; generation++; busy = false; targetSeen = false; }
        publish("已继续，请保持汽水音乐在前台", engine.settings.dryRun ? "仅识别" : "运行中");
    }
    public void stopTask() {
        pause("已停止当前任务");
        if (engine != null) engine.stop("已停止当前任务");
        publish("任务已停止，可重新开始", "已停止");
    }
    private double elapsed() { return (elapsedSaved + (active ? SystemClock.elapsedRealtime() - activeSince : 0)) / 1000.0; }
    private final Runnable loop = new Runnable() {
        @Override public void run() {
            try { if (active && !busy) scan(); }
            catch (Exception e) { pause("运行异常：" + e.getClass().getSimpleName() + "，请重新自检"); }
            handler.postDelayed(this, 400);
        }
    };
    private boolean screenOn() { return ((PowerManager) getSystemService(POWER_SERVICE)).isInteractive(); }
    private String foreground() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        return root.getPackageName() == null ? "" : root.getPackageName().toString();
    }
    private boolean isUserExitPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals(getPackageName())) return true;
        String lower = pkg.toLowerCase();
        return lower.contains("launcher") || lower.equals("com.android.systemui");
    }
    private boolean bringTargetToFront() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(FlowEngine.TARGET);
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(launch);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
    private FlowEngine.Screen nativeScreen() {
        Rect display = wm.getCurrentWindowMetrics().getBounds();
        int width = display.width(), height = display.height();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
        List<FlowEngine.Token> tokens = new ArrayList<>();
        if (!FlowEngine.TARGET.equals(pkg)) return new FlowEngine.Screen(width, height, pkg, tokens);
        ArrayDeque<AccessibilityNodeInfo> stack = new ArrayDeque<>(); stack.push(root);
        int visited = 0;
        while (!stack.isEmpty() && visited++ < 1800) {
            AccessibilityNodeInfo node = stack.pop();
            if (node.isVisibleToUser()) {
                Rect rect = new Rect(); node.getBoundsInScreen(rect);
                if (!rect.isEmpty()) {
                    CharSequence text = node.getText(), description = node.getContentDescription();
                    if (text != null && text.length() > 0) tokens.add(token(FlowEngine.accessibilityLabel(text.toString()), rect));
                    if (description != null && description.length() > 0) {
                        String value = FlowEngine.compact(FlowEngine.accessibilityLabel(description.toString()));
                        if (!value.equals("关闭") || node.isClickable()) tokens.add(token(value, rect));
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) stack.push(child); }
        }
        return new FlowEngine.Screen(width, height, pkg, tokens);
    }
    private static FlowEngine.Token token(String text, Rect r) { return new FlowEngine.Token(text, r.left, r.top, r.right, r.bottom); }
    private void scan() {
        if (!screenOn()) { pause("屏幕已熄灭，请亮屏后继续"); return; }
        FlowEngine.Screen nativeResult = nativeScreen();
        if (!FlowEngine.TARGET.equals(nativeResult.packageName)) {
            if (!targetSeen && SystemClock.elapsedRealtime() - activeSince < 30000) return;
            if (targetSeen && engine != null && !engine.settings.dryRun
                && !isUserExitPackage(nativeResult.packageName) && bringBackAttempts < 5) {
                long nowMs = SystemClock.elapsedRealtime();
                if (nowMs - lastBringBack >= 2500) {
                    lastBringBack = nowMs;
                    bringBackAttempts++;
                    bringTargetToFront();
                    publish("检测到跳转外部应用，自动拉回汽水音乐（" + bringBackAttempts + "/5）", "运行中");
                }
                return;
            }
            pause("已离开汽水音乐，请返回广告后继续"); return;
        }
        bringBackAttempts = 0;
        targetSeen = true;
        if (FlowEngine.findLiveCancel(nativeResult) != null || FlowEngine.findLiveExit(nativeResult) != null
            || nativeResult.find(FlowEngine.COMPLETE, FlowEngine.TOP) != null) { process(nativeResult); return; }
        if (elapsed() >= engine.settings.maxMinutes * 60) { engine.stop("达到运行时间上限"); finish("达到运行时间上限"); return; }
        if (SystemClock.elapsedRealtime() - lastCapture < 550) return;
        busy = true;
        long expected = generation;
        observe(null, result -> {
            if (!valid(expected)) return;
            busy = false;
            if (result != null) process(result);
        });
    }
    private boolean valid(long expected) { return instance == this && generation == expected; }
    private void process(FlowEngine.Screen result) {
        if (!active || engine == null) return;
        if (!FlowEngine.TARGET.equals(foreground())) { pause("页面已切换，请返回汽水后继续"); return; }
        FlowEngine.Screen latest = nativeScreen();
        if (FlowEngine.findLiveExit(latest) != null || FlowEngine.findLiveCancel(latest) != null) result = latest;
        FlowEngine.Decision decision = engine.tick(result, elapsed());
        if (decision.kind.equals("stop")) { finish(decision.reason); return; }
        if (decision.kind.equals("pause")) { pause(decision.reason); return; }
        if (!decision.kind.equals("tap")) { publish(decision.reason, engine.settings.dryRun ? "仅识别" : "运行中"); return; }
        if (engine.settings.dryRun) { publish("只识别，准备操作：" + decision.reason, "仅识别"); return; }
        busy = true;
        long expected = generation;
        if (decision.key.equals("cancel_live") || decision.key.equals("exit_live")) {
            FlowEngine.Screen fresh = nativeScreen();
            FlowEngine.Token target = decision.key.equals("cancel_live") ? FlowEngine.findLiveCancel(fresh) : FlowEngine.findLiveExit(fresh);
            if (target != null) { gesture(decision, target, expected); return; }
            observe(decision.target, screen -> {
                if (!valid(expected) || !active) return;
                FlowEngine.Token updated = screen == null ? null : decision.key.equals("cancel_live") ? FlowEngine.findLiveCancel(screen) : FlowEngine.findLiveExit(screen);
                if (updated == null) { busy = false; publish("页面已变化，重新识别", "运行中"); return; }
                gesture(decision, updated, expected);
            });
        } else gesture(decision, decision.target, expected);
    }
    private void gesture(FlowEngine.Decision decision, FlowEngine.Token target, long expected) {
        if (!valid(expected) || !active || !screenOn() || !FlowEngine.TARGET.equals(foreground())) { busy = false; return; }
        Rect size = wm.getCurrentWindowMetrics().getBounds();
        if (target == null || target.x < 0 || target.y < 0 || target.x >= size.width() || target.y >= size.height()) { busy = false; return; }
           float tx = (float) Math.max(1, Math.min(size.width() - 2, target.x + (Math.random() - 0.5) * 8.0));
        float ty = (float) Math.max(1, Math.min(size.height() - 2, target.y + (Math.random() - 0.5) * 8.0));
        Path path = new Path(); path.moveTo(tx, ty);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, 75)).build();
        GestureResultCallback callback = new GestureResultCallback() {
            private boolean finished;
            private void done(boolean success) {
                if (finished) return; finished = true;
                if (!valid(expected) || !active) return;
                engine.actionResult(decision, success, elapsed()); busy = false;
                logAction(decision, target, success);
                if (engine.phase.equals("STOPPED")) finish(engine.stopReason);
                else publish(success ? decision.reason : "点击未完成，等待重新识别", "运行中");
            }
            @Override public void onCompleted(GestureDescription g) { done(true); }
            @Override public void onCancelled(GestureDescription g) { done(false); }
        };
        if (!dispatchGesture(gesture, callback, handler)) callback.onCancelled(gesture);
    }
    private void finish(String reason) { pause(reason); publish(reason, "已停止"); }
    /** Capture is restricted to the target foreground. No projection app or network service is needed. */
    private void observe(FlowEngine.Token revalidate, Consumer<FlowEngine.Screen> completion) {
        long expected = generation;
        if (!screenOn() || !FlowEngine.TARGET.equals(foreground())) { completion.accept(null); return; }
        if (bubble != null) bubble.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> {
            if (!valid(expected)) { restoreBubble(); return; }
            if (!FlowEngine.TARGET.equals(foreground())) { restoreBubble(); completion.accept(null); return; }
            lastCapture = SystemClock.elapsedRealtime();
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    restoreBubble();
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    Bitmap image = null;
                    try {
                        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hardware != null) { image = hardware.copy(Bitmap.Config.ARGB_8888, false); hardware.recycle(); }
                    } finally { buffer.close(); }
                    if (image == null) { completion.accept(null); return; }
                    if (!valid(expected) || !FlowEngine.TARGET.equals(foreground())) { image.recycle(); completion.accept(null); return; }
                    captureFailures = 0; lastWidth = image.getWidth(); lastHeight = image.getHeight();
                    if (evidence != null) evidence.recycle(); evidence = image;
                    int w = image.getWidth(), h = image.getHeight();
                    boolean cancelOnly = revalidate != null && revalidate.text.equals("取消");
                    boolean fullOnly = revalidate != null && !cancelOnly;
                    Rect band = new Rect((int) (w * .05), (int) (h * .60), (int) (w * .95), (int) (h * .83));
                    if (cancelOnly && revalidate.y >= 0 && revalidate.y < h) { band.top = Math.max((int) (h * .40), (int) (revalidate.y - h * .05)); band.bottom = Math.min((int) (h * .88), (int) (revalidate.y + h * .05)); }
                    if (band.top >= band.bottom) { completion.accept(null); return; }
                    Bitmap stable = image;
                    if (fullOnly) recognize(stable, new Rect(0, 0, w, h), expected, completion);
                    else recognize(stable, band, expected, partial -> {
                        if (!valid(expected)) return;
                        if (partial == null || FlowEngine.findLiveCancel(partial) != null || cancelOnly) completion.accept(partial);
                        else recognize(stable, new Rect(0, 0, w, h), expected, completion);
                    });
                }
                @Override public void onFailure(int errorCode) {
                    restoreBubble();
                    if (!valid(expected)) return;
                    if (++captureFailures >= 3) pause("无法读取屏幕（" + errorCode + "），请检查无障碍服务或受保护的页面");
                    completion.accept(null);
                }
            });
        }, 35);
    }
    private void recognize(Bitmap original, Rect crop, long expected, Consumer<FlowEngine.Screen> completion) {
        if (!valid(expected) || original.isRecycled()) return;
        Bitmap region = Bitmap.createBitmap(original, crop.left, crop.top, crop.width(), crop.height());
        Bitmap clipped = region.copy(Bitmap.Config.ARGB_8888, false);
        if (region != original) region.recycle();
        double factor = Math.min(1.0, 1600.0 / Math.max(clipped.getWidth(), clipped.getHeight()));
        Bitmap input = factor < 1 ? Bitmap.createScaledBitmap(clipped, Math.max(1, (int) Math.round(clipped.getWidth() * factor)), Math.max(1, (int) Math.round(clipped.getHeight() * factor)), true) : clipped;
        double sx = crop.width() / (double) input.getWidth(), sy = crop.height() / (double) input.getHeight();
        long began = SystemClock.elapsedRealtime();
        recognizer.process(InputImage.fromBitmap(input, 0)).addOnSuccessListener(text -> {
            lastScan = SystemClock.elapsedRealtime() - began;
            if (!valid(expected)) return;
            FlowEngine.Screen nativeResult = nativeScreen();
            if (!FlowEngine.TARGET.equals(nativeResult.packageName)) { completion.accept(null); return; }
            List<FlowEngine.Token> tokens = new ArrayList<>(nativeResult.tokens);
            for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
                addOcr(tokens, line.getText(), line.getBoundingBox(), crop, sx, sy);
                for (Text.Element element : line.getElements()) {
                    String value = FlowEngine.compact(element.getText());
                    if (value.matches("取消|关闭|[×xX✕✖]|领取奖励|立即领取|确认领取")) addOcr(tokens, value, element.getBoundingBox(), crop, sx, sy);
                }
            }
            completion.accept(new FlowEngine.Screen(original.getWidth(), original.getHeight(), nativeResult.packageName, tokens));
        }).addOnFailureListener(error -> {
            if (valid(expected)) { pause("文字识别异常，请重新自检：" + error.getClass().getSimpleName()); completion.accept(null); }
        }).addOnCompleteListener(task -> {
            if (input != original && !input.isRecycled()) input.recycle();
            if (clipped != original && clipped != input && !clipped.isRecycled()) clipped.recycle();
        });
    }
    private static void addOcr(List<FlowEngine.Token> output, String text, Rect box, Rect crop, double sx, double sy) {
        if (box != null && !box.isEmpty()) output.add(new FlowEngine.Token(text, crop.left + box.left * sx, crop.top + box.top * sy, crop.left + box.right * sx, crop.top + box.bottom * sy));
    }
    public void selfCheck() {
        pause("正在自检，仅识别不点击"); closeMenu(); busy = true;
        long expected = generation;
        handler.postDelayed(() -> observe(new FlowEngine.Token("自检", 0, 0, 1, 1), screen -> {
            if (!valid(expected)) return;
            busy = false;
            if (screen == null) { publish("请先打开汽水广告，再从悬浮菜单执行自检", "自检未完成"); return; }
            try {
                JSONObject result = snapshot(); JSONArray tokens = new JSONArray();
                for (FlowEngine.Token t : screen.tokens) tokens.put(tokenJson(t));
                result.put("width", screen.width).put("height", screen.height).put("tokens", tokens);
                result.put("live_cancel", FlowEngine.findLiveCancel(screen) != null).put("live_exit", FlowEngine.findLiveExit(screen) != null);
                AppData.write(this, "last_scan.json", result, false); saveEvidence("last-scan.jpg");
            } catch (Exception ignored) { }
            publish("自检完成，识别到 " + screen.tokens.size() + " 处文字；日志保存在本机", "已暂停");
        }), 300);
    }
    private JSONObject snapshot() {
        JSONObject object = new JSONObject();
        try {
            FlowEngine.Settings s = engine == null ? AppData.load(this) : engine.settings;
            object.put("time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).put("version", BuildConfig.VERSION_NAME)
                .put("status", status).put("message", message).put("active", active).put("rounds", engine == null ? 0 : engine.rounds)
                .put("phase", engine == null ? "NONE" : engine.phase).put("elapsed_seconds", elapsed()).put("ocr_ms", lastScan)
                .put("max_rounds", s.maxRounds).put("max_minutes", s.maxMinutes).put("dry_run", s.dryRun);
        } catch (Exception ignored) { }
        return object;
    }
    private static JSONObject tokenJson(FlowEngine.Token t) throws Exception {
        return new JSONObject().put("text", t.text).put("x", t.x).put("y", t.y).put("rect", new JSONArray(new double[]{t.left, t.top, t.right, t.bottom}));
    }
    private void logAction(FlowEngine.Decision d, FlowEngine.Token t, boolean ok) {
        try { AppData.write(this, "actions.jsonl", snapshot().put("key", d.key).put("dispatched", ok).put("target", tokenJson(t)), true); }
        catch (Exception ignored) { }
    }
    private void saveEvidence(String name) {
        if (evidence == null || evidence.isRecycled()) return;
        try (FileOutputStream out = new FileOutputStream(new File(AppData.directory(this), name))) { evidence.compress(Bitmap.CompressFormat.JPEG, 82, out); }
        catch (Exception ignored) { }
    }
    private void publish(String text, String state) {
        message = text; status = state;
        String key = state + text + (engine == null ? 0 : engine.rounds);
        if (!key.equals(lastPublished)) {
            lastPublished = key; AppData.write(this, "run.jsonl", snapshot(), true);
            android.util.Log.i("QishuiHelper", statusText());
        }
        AppData.write(this, "status.json", snapshot(), false);
        if (bubble != null) bubble.setText(active ? (engine.settings.dryRun ? "识别" : "运行") : "助手");
        updateNotification();
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private void showBubble() {
        bubble = new Button(this); bubble.setText("助手"); bubble.setTextColor(Color.WHITE); bubble.setTextSize(13); bubble.setPadding(0, 0, 0, 0);
        bubble.setBackground(background(0xE6245FDC, 26)); bubble.setContentDescription("汽水助手，点击打开暂停和控制菜单，可拖动");
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(dp(52), dp(52), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT; params.x = dp(6); params.y = (int) (wm.getCurrentWindowMetrics().getBounds().height() * .43);
        bubble.setOnClickListener(v -> showMenu());
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float x, y; int ox, oy; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { x = e.getRawX(); y = e.getRawY(); ox = params.x; oy = params.y; moved = false; return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    if (Math.abs(e.getRawX() - x) + Math.abs(e.getRawY() - y) > dp(8)) moved = true;
                    if (moved) { Rect bounds = wm.getCurrentWindowMetrics().getBounds(); params.x = Math.max(0, Math.min(bounds.width() - dp(52), ox + (int) (e.getRawX() - x))); params.y = Math.max(0, Math.min(bounds.height() - dp(60), oy + (int) (e.getRawY() - y))); wm.updateViewLayout(bubble, params); }
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP) { if (!moved) v.performClick(); return true; }
                return false;
            }
        });
        wm.addView(bubble, params);
    }
    private void restoreBubble() { if (bubble != null) bubble.setVisibility(View.VISIBLE); }
    private void showMenu() {
        if (menu != null) { closeMenu(); return; }
        pause("控制菜单已打开，操作已暂停");
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(16), dp(20), dp(16)); box.setBackground(background(Color.WHITE, 18));
        TextView title = new TextView(this); title.setText(statusText()); title.setTextColor(0xFF173456); title.setTextSize(17); box.addView(title);
        addMenuButton(box, "继续任务", this::resumeTask);
        addMenuButton(box, "重新开始", () -> startTask(AppData.load(this)));
        addMenuButton(box, "屏幕自检", this::selfCheck);
        addMenuButton(box, "停止任务", () -> { stopTask(); closeMenu(); });
        addMenuButton(box, "设置与使用说明", () -> { closeMenu(); Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); });
        addMenuButton(box, "收起菜单", this::closeMenu);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams((int) (wm.getCurrentWindowMetrics().getBounds().width() * .88), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_DIM_BEHIND, android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.CENTER; p.dimAmount = .2f; menu = box; wm.addView(menu, p);
    }
    private void addMenuButton(LinearLayout box, String text, Runnable action) { Button b = new Button(this); b.setText(text); b.setOnClickListener(v -> action.run()); box.addView(b); }
    private void closeMenu() { if (menu != null) { wm.removeView(menu); menu = null; } }
    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("task", "任务状态", NotificationManager.IMPORTANCE_LOW));
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getBroadcast(this, 1, new Intent(this, PauseReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(1, new Notification.Builder(this, "task").setSmallIcon(R.drawable.ic_app).setContentTitle("汽水助手 · " + status)
            .setContentText(message).setContentIntent(open).setOngoing(active).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null, "暂停", pause).build()).build());
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN && event.getAction() == KeyEvent.ACTION_DOWN && active) pause("已通过音量减键暂停");
        return false;
    }
    @Override public void onInterrupt() { pause("无障碍服务中断，请检查系统设置"); }
    @Override public void onDestroy() {
        pause("无障碍服务已关闭"); instance = null; handler.removeCallbacksAndMessages(null);
        closeMenu(); if (bubble != null) { wm.removeView(bubble); bubble = null; }
        if (recognizer != null) recognizer.close();
        if (evidence != null && !evidence.isRecycled()) evidence.recycle();
        getSystemService(NotificationManager.class).cancel(1); super.onDestroy();
    }
}
