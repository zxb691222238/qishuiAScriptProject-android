package cn.qishui.helper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure decision engine: only a verified page transition confirms a UI round. */
public final class FlowEngine {
    public static final String TARGET = "com.luna.music";
    public static final double[] TOP = {.45, .015, 1, .23}, MIDDLE = {.07, .23, .93, .76}, LIVE = {.05, .40, .95, .88}; 
    public static final double[] BOTTOM_NAV = {0, .85, 1, 1}, TOP_BAR = {0, .01, 1, .20};
    static final String REWARD_BTN = "^(?:领取奖励|立即领取|确认领取|开心收下|再看一个|再看1个|继续看视频|去看看)$";
    static final String COUNTDOWN = "\\d+秒(?:后)?(?:可)?(?:领取|领|获得|获取|关闭)";
    static final String COMPLETE = "^(?:领取成功|奖励已领取|奖励已到账|已获得奖励)[×xX✕✖]?$";
    static final String CLOSE = "^(?:关闭|×|✕|✖|x|X)$";
    static final Pattern LIVE_PROMPT = Pattern.compile("(?:(?:\\d{1,3}|[一二三四五六七八九十几]+)(?:秒|[sS])后(?:将|自动)?|即将(?:自动)?)(?:进入|前往|跳转(?:到|至)?)直播[间間]");

    public static String compact(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC).replaceAll("[\\s\\u00a0]+", "");
    }
    /** Known labels sometimes include accessibility action/role annotations. */
    public static String accessibilityLabel(String raw) {
        Matcher label = Pattern.compile("^(领取成功|奖励已领取|奖励已到账|已获得奖励|关闭|关注|更多直播|说点什么|领取奖励|立即领取|确认领取|继续观看|开心收下|再看一个|继续看视频)(?:,关闭)?,(?:按钮|输入框)$").matcher(compact(raw));
        return label.matches() ? label.group(1) : raw;
    }
    public static final class Settings {
        public final int maxRounds, maxMinutes;
        public final boolean dryRun;
        public Settings(int rounds, int minutes, boolean dry) {
            if (rounds < 1 || rounds > 50 || minutes < 1 || minutes > 60) throw new IllegalArgumentException("请输入 1～50 轮、1～60 分钟");
            maxRounds = rounds; maxMinutes = minutes; dryRun = dry;
        }
    }
    public static final class Token {
        public final String text;
        public final double x, y, left, top, right, bottom;
        public Token(String text, double left, double top, double right, double bottom) {
            this.text = text; this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            x = (left + right) / 2; y = (top + bottom) / 2;
        }
        public Token renamed(String text) { return new Token(text, left, top, right, bottom); }
        public boolean inside(Screen s, double[] r) {
            return s.width > 0 && s.height > 0 && x >= 0 && y >= 0 && x < s.width && y < s.height
                && x / s.width >= r[0] && y / s.height >= r[1] && x / s.width <= r[2] && y / s.height <= r[3];
        }
    }
    public static final class Screen {
        public final int width, height;
        public final String packageName;
        public final List<Token> tokens;
        public Screen(int width, int height, String pkg, List<Token> tokens) {
            this.width = width; this.height = height; packageName = pkg; this.tokens = new ArrayList<>(tokens);
        }
        public Token find(String regex, double[] region) {
            Pattern pattern = Pattern.compile(regex);
            List<Token> hits = new ArrayList<>();
            for (Token t : tokens) if (t.inside(this, region) && pattern.matcher(compact(t.text)).find()) {
                boolean duplicate = false;
                for (Token prior : hits) if (Math.abs(t.x - prior.x) < Math.max(8, width * .015)
                    && Math.abs(t.y - prior.y) < Math.max(8, height * .007)) duplicate = true;
                if (!duplicate) hits.add(t);
            }
            return hits.size() == 1 ? hits.get(0) : null;
        }
        public boolean has(String regex, double[] region) {
            Pattern pattern = Pattern.compile(regex);
            for (Token t : tokens) if (t.inside(this, region) && pattern.matcher(compact(t.text)).find()) return true;
            return false;
        }
    }
    public static final class Decision {
        public final String kind, reason, key;
        public final Token target;
        Decision(String kind, String reason, Token token, String key) { this.kind = kind; this.reason = reason; target = token; this.key = key; }
        static Decision waitFor(String text) { return new Decision("wait", text, null, ""); }
        static Decision tap(String text, Token t, String key) { return new Decision("tap", text, t, key); }
    }
    public static Token findLiveCancel(Screen s) {
        if (s.width <= 0 || s.height <= 0) return null;
        List<Token> candidates = new ArrayList<>();
        for (Token anchor : s.tokens) {
            if (!anchor.inside(s, LIVE)) continue;
            String text = compact(anchor.text);
            Matcher match = LIVE_PROMPT.matcher(text);
            if (!match.find()) continue;
            boolean separate = false;
            for (Token t : s.tokens) {
                double gap = t.left - anchor.right;
                if (t.inside(s, LIVE) && compact(t.text).equals("取消") && t.x > anchor.x
                    && gap >= -s.width * .04 && gap <= s.width * .28
                    && Math.abs(t.y - anchor.y) <= Math.max(s.height * .025, anchor.bottom - anchor.top)) {
                    candidates.add(t); separate = true;
                }
            }
            if (separate || !text.substring(match.end()).matches("[|丨:：·•—\\-]*取消[!。]?")) continue;
            if (anchor.bottom <= anchor.top || anchor.bottom - anchor.top > s.height * .075 || anchor.right <= anchor.left) continue;
            int units = 0;
            for (char c : text.toCharArray()) units += c > 255 ? 2 : 1;
            double cancelWidth = (anchor.right - anchor.left) * 4 / Math.max(units, 4);
            candidates.add(new Token("取消", anchor.right - cancelWidth, anchor.top, anchor.right, anchor.bottom));
        }
        List<Token> unique = new ArrayList<>();
        for (Token t : candidates) {
            boolean dup = false;
            for (Token p : unique) if (Math.abs(t.x - p.x) < Math.max(8, s.width * .025) && Math.abs(t.y - p.y) < Math.max(8, s.height * .012)) dup = true;
            if (!dup) unique.add(t);
        }
        return unique.size() == 1 ? unique.get(0).renamed("取消") : null;
    }
    public static Token findLiveExit(Screen s) {
        if (s.width <= 0 || s.height <= 0 || s.has(COUNTDOWN, TOP) || s.has(COMPLETE, TOP) || findLiveCancel(s) != null) return null;
        int markers = 0;
        if (s.has("^更多直播[>›]?$", new double[]{.55, .04, 1, .20})) markers++;
        if (s.has("^\\+?关注$", new double[]{0, .015, .65, .16})) markers++;
        if (s.has("^(?:说点什么|聊一聊|发个弹幕|发送弹幕)[.。…]*$", new double[]{0, .75, 1, .98})) markers++;
        if (markers < 2) return null;
        Token close = s.find("^(?:退出直播间|关闭直播间|关闭|×|✕|✖|[1lI|]?[xX])$", new double[]{.82, .015, 1, .14});
        return close == null ? null : close.renamed("退出直播间");
    } 
    public static boolean isHomePage(Screen s) {
        if (s.width <= 0 || s.height <= 0) return false;
        int hits = 0;
        if (s.has("^发现$", BOTTOM_NAV)) hits++;
        if (s.has("^音乐$", BOTTOM_NAV)) hits++;
        if (s.has("^我的$", BOTTOM_NAV)) hits++;
        if (s.has("模式探索", TOP_BAR)) hits++;
        return hits >= 2;
    }
    public final Settings settings;
    public String phase = "WAIT_AD", stopReason = "";
    public int rounds, liveExitAttempts, clickFailures;
    private double phaseSince, lastClick = -100, lastCancel = -100, lastExit = -100;
    private boolean returnPending;
    public FlowEngine(Settings settings) { this.settings = settings; }
    private void phase(String p, double now) { phase = p; phaseSince = now; }
    public Decision stop(String reason) { phase = "STOPPED"; stopReason = reason; return new Decision("stop", reason, null, ""); }
    private Decision confirmed(double now) {
        rounds++;
        if (rounds >= settings.maxRounds) return stop("已完成设置的 " + rounds + " 轮流程，请在汽水核对免费听权益");
        phase("WATCHING", now); return Decision.waitFor("已进入下一轮广告，继续等待");
    }
    public Decision tick(Screen s, double now) {
        if (phase.equals("STOPPED")) return new Decision("stop", stopReason, null, "");
        if (now >= settings.maxMinutes * 60) return stop("达到本次运行时间上限");
        if (!TARGET.equals(s.packageName)) return new Decision("pause", "已离开汽水音乐，请返回后继续", null, "");
        if (s.width <= 0 || s.height <= 0) return stop("无法读取屏幕尺寸");
        if (s.has("(?:今日|今天).{0,12}(?:次数|额度).{0,8}(?:用完|上限)|暂无广告可看|今日暂无广告", MIDDLE)) return stop("页面提示今日额度用完或暂无广告");
        if (s.has("^(?:今日免费听已解锁|已解锁今日免费听|已获得(?:24小时|1天|一天)免费听(?:权益)?)[！!。]?$", MIDDLE)) return stop("页面显示已获得今日免费听，请在汽水核对有效期");
        Token cancel = findLiveCancel(s);
        if (cancel != null) return now - lastCancel < .8 ? Decision.waitFor("等待取消直播跳转生效") : Decision.tap("取消自动进入直播间", cancel, "cancel_live");
        Token exit = findLiveExit(s);
        if (exit != null) {
            if (now - lastExit < 1) return Decision.waitFor("等待直播间关闭");
            if (liveExitAttempts >= 3) return stop("直播间关闭后页面未变化，请检查当前页面");
            return Decision.tap("退出直播间，返回广告", exit, "exit_live");
        }
        boolean countdown = s.has(COUNTDOWN, TOP);
        Token complete = s.find(COMPLETE, TOP), reward = s.find(REWARD_BTN, MIDDLE);
        if (now - lastClick < 1.3) return Decision.waitFor("等待页面响应");
        if (returnPending && (countdown || complete != null || reward != null)) {
            returnPending = false; liveExitAttempts = 0;
            if (phase.equals("VERIFY") && (countdown || complete != null)) return confirmed(now);
            if (phase.equals("WAIT_REWARD") && complete != null && reward == null) {
                Token close = s.find(CLOSE, TOP);
                return Decision.tap("已返回完成的广告，继续领奖", close == null ? complete : close, "complete");
            }
        }
        if (phase.equals("VERIFY") || phase.equals("WAIT_REWARD")) {
            if (countdown) return confirmed(now);
            if (now - phaseSince > 45) return stop("点击后未确认页面变化，请检查当前页面");
        }
        if (phase.equals("VERIFY")) return Decision.waitFor("正在确认领奖后的页面变化");
        if (phase.equals("WAIT_REWARD")) return reward != null ? Decision.tap("点击领取奖励", reward, "reward") : Decision.waitFor("等待奖励弹窗");
        if (countdown) {
            if (!phase.equals("WATCHING")) phase("WATCHING", now);
            if (now - phaseSince > 150) return stop("广告等待超时，请检查当前页面");
            return Decision.waitFor("广告倒计时中，等待正常播放完成");
        }
        if (complete != null) {
            Token close = s.find(CLOSE, TOP);
            return Decision.tap("广告显示完成，关闭完成页", close == null ? complete : close, "complete");
        } 
        if ((phase.equals("WATCHING") || phase.equals("VERIFY")) && !countdown && complete == null && reward == null && !isHomePage(s)) {
            Token landingClose = s.find(CLOSE, TOP);
            if (landingClose != null && s.has("立即下载|立即体验|开通会员|续费会员|立即开通|免费试用|下载体验", MIDDLE)) {
                return Decision.tap("关闭广告/会员落地页", landingClose, "close_landing");
            }
        }
        Token resume = s.find("^继续观看$", MIDDLE);
        if (phase.equals("WATCHING") && resume != null && s.has("放弃奖励|坚持退出|还差|继续观看.*(?:奖励|秒)", MIDDLE)) return Decision.tap("恢复尚未完成的广告", resume, "resume");
        if (now - phaseSince > 150) return stop(phase.equals("WAIT_AD") ? "未检测到广告，请进入第一条广告后重新开始" : "未识别到完成提示，请检查页面");
        return Decision.waitFor(phase.equals("WAIT_AD") ? "请手动进入第一条奖励广告" : "正在等待广告完成提示");
    }
    public void actionResult(Decision d, boolean ok, double now) {
        if (!d.kind.equals("tap")) return;
        lastClick = now;
        if (d.key.equals("cancel_live")) lastCancel = now; 
        if (d.key.equals("exit_live")) { lastExit = now; liveExitAttempts++; }
        if (!ok) { if (++clickFailures >= 3) stop("连续三次点击失败，请检查无障碍服务"); return; }
        clickFailures = 0;
        if (d.key.equals("cancel_live")) return; 
        if (d.key.equals("close_landing")) return;
        if (d.key.equals("exit_live")) { returnPending = true; return; }
        switch (d.key) {
            case "complete": phase("WAIT_REWARD", now); break;
            case "reward": phase("VERIFY", now); break;
            default: phase("WATCHING", now);
        }
    }
}
