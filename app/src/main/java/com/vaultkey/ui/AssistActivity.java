package com.vaultkey.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Db;
import com.vaultkey.data.Session;
import com.vaultkey.service.FillAssistService;
import com.vaultkey.util.Ui;
import java.util.List;

/** 辅助填充：从通知进入，选择账号后由无障碍服务填入 */
public final class AssistActivity extends BaseActivity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Session.key() == null) {
            startActivity(new android.content.Intent(this, UnlockActivity.class));
            finish();
            return;
        }
        body.setPadding(0, Ui.dp(this, 8), 0, 0);
        TextView t = title("填充到：" + FillAssistService.targetLabel(this), 18);
        t.setPadding(0, 0, 0, Ui.dp(this, 12));
        body.addView(t);
        List<Db.Entry> all = Db.get(this).list(null, false, false, null, 0);
        for (Db.Entry e : all) {
            TextView row = new TextView(this);
            row.setText((e.title.isEmpty() ? e.user : e.title) + "\n" + e.user);
            row.setTextColor(Ui.attr(this, R.attr.textColor));
            row.setTextSize(15);
            row.setBackground(Ui.glass(this, 16, R.attr.cardColor, R.attr.strokeColor));
            row.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 8);
            row.setLayoutParams(lp);
            Ui.press(row);
            row.setOnClickListener(v -> {
                FillAssistService.doFill(this, e);
                finish();
            });
            body.addView(row);
        }
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有保存的账号");
            empty.setTextColor(Ui.attr(this, R.attr.textColor2));
            empty.setGravity(Gravity.CENTER);
            body.addView(empty);
        }
    }
}
