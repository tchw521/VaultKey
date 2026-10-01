package com.vaultkey.service;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.util.Log;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;
import com.vaultkey.R;
import com.vaultkey.data.Db;
import com.vaultkey.data.Prefs;
import com.vaultkey.data.Session;
import com.vaultkey.util.Icons;
import com.vaultkey.util.Match;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 系统自动填充：填充 + 保存，Bitwarden 风格 */
public final class VaultAutofillService extends AutofillService {
    private static final String TAG = "VaultAutofill";

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancel, FillCallback callback) {
        try {
            if (Prefs.getB("autofill_off", false)) { callback.onSuccess(null); return; }
            List<FillContext> ctxs = request.getFillContexts();
            if (ctxs == null || ctxs.isEmpty()) { callback.onSuccess(null); return; }
            android.app.assist.AssistStructure s = ctxs.get(ctxs.size() - 1).getStructure();
            if (s == null) { callback.onSuccess(null); return; }
            String pkg = s.getActivityComponent() == null ? null : s.getActivityComponent().getPackageName();
            if (pkg != null && pkg.equals(getPackageName())) { callback.onSuccess(null); return; }

            Parser p = new Parser();
            p.parse(s);
            p.settle();
            if (p.user == null && p.pass == null) { callback.onSuccess(null); return; }
            if (p.pass == null && !p.loginLike) { callback.onSuccess(null); return; }

            ArrayList<AutofillId> ids = new ArrayList<>();
            if (p.user != null) ids.add(p.user);
            if (p.pass != null) ids.add(p.pass);

            boolean locked = Session.key() == null || Session.expired(Prefs.getI("lock_ms", 180000));
            if (locked) {
                Intent i = new Intent(this, com.vaultkey.ui.UnlockActivity.class);
                i.putExtra("auth", true).putExtra("pkg", pkg).putExtra("domain", p.domain)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent pi = PendingIntent.getActivity(this, 99, i, piFlags());
                AutofillId[] arr = ids.toArray(new AutofillId[0]);
                android.widget.RemoteViews rv = new RemoteViews(getPackageName(), R.layout.autofill_item);
                rv.setTextViewText(R.id.af_title, "解锁密盒以填充");
                rv.setTextViewText(R.id.af_sub, pkg == null ? "" : pkg);
                FillResponse.Builder b = new FillResponse.Builder();
                b.setAuthentication(arr, pi.getIntentSender(), rv);
                callback.onSuccess(b.build());
                return;
            }

            List<Match.Hit> hits = Match.find(this, p.domain, pkg);

            /* 悬浮窗联动：识别到登录界面且悬浮窗已开启时，同时把小窗调出来。
               这样即使当前 App 的输入框无法被自动填充（游戏 / Flutter / 自定义 WebView），
               用户也能直接从悬浮窗复制，不用切回密盒。 */
            if (FloatService.enabled() && FloatService.canDraw(this) && FloatService.autoPopup()) {
                FloatService.show(this, pkg, p.domain);
            }

            FillResponse.Builder rb = new FillResponse.Builder();
            if (!hits.isEmpty()) {
                for (Match.Hit hh : hits) {
                    Db.Entry e = hh.e;
                    RemoteViews rv = new RemoteViews(getPackageName(), R.layout.autofill_item);
                    rv.setTextViewText(R.id.af_title, e.title.isEmpty() ? e.user : e.title);
                    rv.setTextViewText(R.id.af_sub, e.user.isEmpty() ? e.url : e.user);
                    Dataset.Builder ds = new Dataset.Builder(rv);
                    if (p.user != null && !e.user.isEmpty()) ds.setValue(p.user, AutofillValue.forText(e.user));
                    if (p.pass != null && !e.pass.isEmpty()) ds.setValue(p.pass, AutofillValue.forText(e.pass));
                    rb.addDataset(ds.build());
                }
            }
            /* 打开密盒搜索 */
            Intent open = new Intent(this, com.vaultkey.ui.MainActivity.class)
                    .putExtra("search", p.domain == null ? (pkg == null ? "" : pkg) : p.domain)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent opi = PendingIntent.getActivity(this, 98, open, piFlags());
            RemoteViews orv = new RemoteViews(getPackageName(), R.layout.autofill_item);
            orv.setTextViewText(R.id.af_title, "在密盒中搜索");
            orv.setTextViewText(R.id.af_sub, p.domain == null ? (pkg == null ? "" : pkg) : p.domain);
            Dataset.Builder ods = new Dataset.Builder(orv);
            if (p.user != null && p.pass != null) {
                ods.setValue(p.user, AutofillValue.forText(""));
                ods.setValue(p.pass, AutofillValue.forText(""));
            }
            ods.setAuthentication(opi.getIntentSender());
            rb.addDataset(ods.build());

            /* 登录成功后询问是否保存 */
            if (p.pass != null) {
                try {
                    SaveInfo.Builder sb = new SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD,
                            new AutofillId[]{p.pass});
                    if (p.user != null) sb.setOptionalIds(new AutofillId[]{p.user});
                    rb.setSaveInfo(sb.build());
                } catch (Exception ignored) { }
            }
            callback.onSuccess(rb.build());
        } catch (Exception e) {
            Log.w(TAG, "fill", e);
            try { callback.onSuccess(null); } catch (Exception ignored) { }
        }
    }

    private static int piFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        try {
            if (Prefs.getB("autofill_off", false)) { callback.onSuccess(); return; }
            List<FillContext> ctxs = request.getFillContexts();
            if (ctxs == null || ctxs.isEmpty()) { callback.onSuccess(); return; }
            android.app.assist.AssistStructure s = ctxs.get(ctxs.size() - 1).getStructure();
            if (s == null) { callback.onSuccess(); return; }
            Parser p = new Parser();
            p.parse(s);
            p.settle();
            p.readValues(s);
            String pkg = s.getActivityComponent() == null ? "" : s.getActivityComponent().getPackageName();
            if (p.passValue == null || p.passValue.length() < 3) { callback.onSuccess(); return; }
            if (!p.loginLike && p.userValue == null) { callback.onSuccess(); return; }
            if (!Prefs.getB("offer_save", true)) { callback.onSuccess(); return; }
            /* 已存在相同账号就不需要再问 */
            if (Session.key() != null) {
                String host = p.domain;
                for (Db.Entry e : Db.get(this).list(null, false, false, null, 0)) {
                    boolean same = (e.pkg != null && e.pkg.equals(pkg)) ||
                            (host != null && !host.isEmpty() && host.equals(Icons.hostOf(e.url)));
                    if (same && e.user.equals(p.userValue)) { callback.onSuccess(); return; }
                }
            }
            AutofillSave.pending = new AutofillSave.Data(p.userValue, p.passValue, p.domain, pkg);
            Intent i = new Intent(this, Session.key() == null ? com.vaultkey.ui.UnlockActivity.class : com.vaultkey.ui.SaveActivity.class);
            i.putExtra("auth", true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            callback.onSuccess();
        } catch (Exception e) {
            Log.w(TAG, "save", e);
            try { callback.onSuccess(); } catch (Exception ignored) { }
        }
    }

    public static final class AutofillSave {
        public static final class Data {
            public String user, pass, domain, pkg;
            Data(String u, String p, String d, String k) { user = u; pass = p; domain = d; pkg = k; }
        }
        public static Data pending;
        public static boolean has() { return pending != null; }
        public static Data take() { Data d = pending; pending = null; return d; }
    }

    /* ---------------- 界面解析 ---------------- */

    static final class Parser {
        AutofillId user, pass;
        String domain;
        String userValue, passValue;

        private boolean pass1;

        void parse(android.app.assist.AssistStructure s) {
            int n = s.getWindowNodeCount();
            /* 第一遍：只确认域名与「是否像登录页」，这样第二遍判字段时结论已完整 */
            pass1 = true;
            for (int i = 0; i < n; i++) {
                android.app.assist.AssistStructure.WindowNode w = s.getWindowNodeAt(i);
                if (w != null) walk(w.getRootViewNode());
            }
            /* 第二遍：正式归类用户名 / 密码 / 验证码字段 */
            pass1 = false;
            for (int i = 0; i < n; i++) {
                android.app.assist.AssistStructure.WindowNode w = s.getWindowNodeAt(i);
                if (w != null) walk(w.getRootViewNode());
            }
        }

        void readValues(android.app.assist.AssistStructure s) {
            int n = s.getWindowNodeCount();
            for (int i = 0; i < n; i++) {
                android.app.assist.AssistStructure.WindowNode w = s.getWindowNodeAt(i);
                if (w != null) readValue(w.getRootViewNode());
            }
        }

        private void readValue(android.app.assist.AssistStructure.ViewNode v) {
            if (v == null) return;
            if (v.getAutofillId() != null) {
                android.view.autofill.AutofillValue val = v.getAutofillValue();
                if (val != null && val.isText()) {
                    if (v.getAutofillId().equals(pass)) passValue = val.getTextValue() == null ? null : val.getTextValue().toString();
                    else if (v.getAutofillId().equals(user)) userValue = val.getTextValue() == null ? null : val.getTextValue().toString();
                }
            }
            for (int i = 0; i < v.getChildCount(); i++) readValue(v.getChildAt(i));
        }

        /** 字段候选：打分后排序，挑最可信的当 用户名 / 密码 */
        private final List<Fld> users = new ArrayList<>();
        private final List<Fld> passes = new ArrayList<>();
        private final List<Fld> otps = new ArrayList<>();

        private static final class Fld {
            AutofillId id;
            int score;
            Fld(AutofillId id, int score) { this.id = id; this.score = score; }
        }

        /** 页面里是否出现「登录/注册」类按钮 —— 用来确认这是登录页，而非聊天、搜索页 */
        boolean loginLike;

        private static final int USER_MIN = 55;

        /** 严格模式：默认开。关掉后任何输入框都会尝试填充（个别 App 识别不出时可关） */
        boolean strict = Prefs.getB("af_strict", true);

        private void walk(android.app.assist.AssistStructure.ViewNode v) {
            if (v == null) return;
            if (v.getWebDomain() != null && domain == null) {
                String d = v.getWebDomain().toString();
                if (!d.isEmpty()) domain = Icons.hostOf(d);
            }
            String cls = v.getClassName() == null ? "" : v.getClassName();
            boolean editable = cls.contains("EditText");
            boolean web = cls.contains("WebView");
            if (!editable && !web) {
                String txt = textOf(v);
                if (!txt.isEmpty() && looksLogin(txt)) loginLike = true;
                for (int i = 0; i < v.getChildCount(); i++) walk(v.getChildAt(i));
                return;
            }
            if (pass1) {
                /* 第一遍：输入框也可能带「登录」字样（如按钮式输入），顺带看一眼 */
                String ph0 = v.getHint() == null ? "" : v.getHint().toString().toLowerCase();
                if (looksLogin(ph0)) loginLike = true;
                for (int i = 0; i < v.getChildCount(); i++) walk(v.getChildAt(i));
                return;
            }

            String id = v.getIdEntry() == null ? "" : v.getIdEntry().toLowerCase();
            String hint = "";
            String[] hints = v.getAutofillHints();
            if (hints != null) for (String hh : hints) hint += " " + (hh == null ? "" : hh.toLowerCase());
            String ph = v.getHint() == null ? "" : v.getHint().toString().toLowerCase();
            int it = v.getInputType();

            boolean pwdType = isPasswordType(it);
            boolean otpLike = id.contains("captcha") || id.contains("verify") || id.contains("code")
                    || id.contains("sms") || id.contains("otp") || hint.contains("otp")
                    || ph.contains("验证码") || ph.contains("校验码") || id.contains("验证码");

            int sc = score(id, hint, ph, pwdType);
            if (sc <= 0) return;
            /* 黑名单只对弱信号生效：真密码框（>=80）即使 id 里带 input/et_ 也必须保留，
               否则 et_password 这类命名会被误排除 */
            if (sc < 80 && blacklist(id, hint, ph)) return;

            /* 网页表单：有域名时放宽判定，浏览器里 type=password 才是最可靠信号 */
            if (web && domain != null && !domain.isEmpty() && pwdType) sc = Math.max(sc, 100);

            /* 语义归类优先于分数阈值：
               只按分数切会出错 —— et_user（账号框）分数可能高于 et_password 的阈值，
               被误当成密码框。所以「是不是密码框」看关键词不看总分。 */
            boolean passKey = id.contains("password") || id.contains("passwd") || id.contains("pwd")
                    || id.contains("pass") || hint.contains("password") || ph.contains("密码");
            boolean userKey = hint.contains("username") || hint.contains("email") || hint.contains("phone")
                    || id.contains("user") || id.contains("email") || id.contains("mail")
                    || id.contains("account") || id.contains("login") || id.contains("mobile")
                    || id.contains("phone") || id.contains("tel")
                    || ph.contains("账号") || ph.contains("帐号") || ph.contains("用户名")
                    || ph.contains("邮箱") || ph.contains("手机");

            if (otpLike && !pwdType) { otps.add(new Fld(v.getAutofillId(), sc)); return; }
            if (pwdType || (passKey && !userKey)) passes.add(new Fld(v.getAutofillId(), sc));
            else if (userKey) users.add(new Fld(v.getAutofillId(), sc));
            else if (!strict) {
                /* 宽松模式：任何可编辑框都当候选（老行为，个别 App 识别不出时才用） */
                if (passes.isEmpty() && users.isEmpty()) users.add(new Fld(v.getAutofillId(), sc));
            } else if (passes.isEmpty() && users.isEmpty()) {
                /* 弱信号：只有页面确实像登录页时才收下，避免聊天框、搜索框被误认 */
                if (loginLike) users.add(new Fld(v.getAutofillId(), sc));
            }

            for (int i = 0; i < v.getChildCount(); i++) walk(v.getChildAt(i));
        }

        /** 收敛出最终要填充的 用户名 / 密码 字段 */
        void settle() {
            Collections.sort(passes, new Comparator<Fld>() {
                @Override public int compare(Fld a, Fld b) { return b.score - a.score; }
            });
            Collections.sort(users, new Comparator<Fld>() {
                @Override public int compare(Fld a, Fld b) { return b.score - a.score; }
            });
            if (!passes.isEmpty()) pass = passes.get(0).id;
            if (!users.isEmpty()) user = users.get(0).id;
            /* 只认出密码、没认出用户名：不填充（多半是修改密码、支付密码之类的单框） */
            if (pass != null && user == null && !loginLike) pass = null;
            /* 只认出用户名：分数太低说明不明确，宁可不填 */
            if (pass == null && user != null && users.get(0).score < USER_MIN) user = null;
        }

        private static String textOf(android.app.assist.AssistStructure.ViewNode v) {
            try {
                CharSequence t = v.getText();
                if (t == null) return "";
                String s = t.toString().trim().toLowerCase();
                return s.length() > 40 ? "" : s;   // 太长的多半是正文，不是按钮
            } catch (Exception e) { return ""; }
        }

        private static boolean looksLogin(String t) {
            return t.contains("登录") || t.contains("登陆") || t.contains("登入") || t.contains("注册")
                    || t.contains("登陆") || t.contains("sign in") || t.contains("signin")
                    || t.contains("log in") || t.contains("login") || t.contains("submit")
                    || t.contains("下一步") && t.length() < 8;
        }

        private static boolean isPasswordType(int it) {
            int var = it & android.text.InputType.TYPE_MASK_VARIATION;
            return var == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || var == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    || var == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || var == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }

        /**
         * 这些输入框永远不是账号密码。
         * 注意：不能放 "input"、"et_"、"text" 这类通用前缀 —— 国内 App 的登录框
         * 常叫 et_password / input_username，放进去会把真登录框误排除。
         * 聊天框、搜索框靠「打分=0」自然落选，不需要靠黑名单。
         */
        private static boolean blacklist(String id, String hint, String ph) {
            String[] bad = {
                    "chat", "message", "editor", "comment", "reply", "emoji",
                    "search", "keyword", "query", "publish", "feedback", "suggest",
                    "remark", "nickname", "nick", "subject", "signature", "memo"
            };
            String all = id + " " + hint + " " + ph;
            for (String b : bad) if (all.contains(b)) return true;
            if (ph.contains("搜索") || ph.contains("聊天") || ph.contains("说点什么")
                    || ph.contains("请输入内容") || ph.contains("评论") || ph.contains("留言")) return true;
            return false;
        }

        /**
         * 打分：>=80 视为密码框，55~79 视为用户名框，其余为弱信号。
         * 只有弱信号的页面不会触发填充，避免聊天框、搜索框被误认。
         */
        private static int score(String id, String hint, String ph, boolean pwdType) {
            int sc = 0;
            if (pwdType) sc += 100;                                    // inputType 是密码 → 最强信号
            if (hint.contains("password")) sc += 90;
            if (hint.contains("username") || hint.contains("email") || hint.contains("phone")) sc += 65;
            if (id.contains("password") || id.contains("passwd") || id.contains("pwd")) sc += 80;
            if (id.contains("pass")) sc += 70;
            if (id.contains("user") || id.contains("email") || id.contains("mail")) sc += 60;
            if (id.contains("account") || id.contains("login") || id.contains("mobile")
                    || id.contains("phone") || id.contains("tel")) sc += 58;
            if (id.contains("name") || id.contains("id")) sc += 30;    // 弱：可能是昵称、任意 ID
            if (ph.contains("密码")) sc += 80;
            if (ph.contains("账号") || ph.contains("用户名") || ph.contains("邮箱")
                    || ph.contains("手机") || ph.contains("帐号")) sc += 60;
            return sc;
        }
    }
}
