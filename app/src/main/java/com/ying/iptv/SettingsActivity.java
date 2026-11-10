package com.ying.iptv;

import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.ying.iptv.data.Prefs;
import com.ying.iptv.data.Source;

import java.util.List;

/** 设置页：直播源管理、刷新、启动行为、关于。 */
public class SettingsActivity extends AppCompatActivity {

    private Prefs prefs;
    private TextView valueSource, valueEdit, valueRefresh, valueRemember, valueAbout;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = Prefs.get(this);

        valueSource = findViewById(R.id.value_source);
        valueEdit = findViewById(R.id.value_edit);
        valueRefresh = findViewById(R.id.value_refresh);
        valueRemember = findViewById(R.id.value_remember);
        valueAbout = findViewById(R.id.value_about);

        findViewById(R.id.row_source).setOnClickListener(v -> chooseSource());
        findViewById(R.id.row_edit).setOnClickListener(v -> editSource(prefs.currentSource()));
        findViewById(R.id.row_refresh).setOnClickListener(v -> {
            prefs.setNeedRefresh(true);
            toast(getString(R.string.source_refreshing));
        });
        findViewById(R.id.row_remember).setOnClickListener(v -> {
            prefs.setRemember(!prefs.isRemember());
            refreshValues();
        });
        findViewById(R.id.row_about).setOnClickListener(v -> showAbout());

        findViewById(R.id.row_source).requestFocus();
        hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshValues();
    }

    private void refreshValues() {
        Source source = prefs.currentSource();
        valueSource.setText(source != null ? source.displayName() : "未配置（点击选择源添加）");
        valueEdit.setText(source != null ? source.url : "");
        valueRefresh.setText("重新下载");
        valueRemember.setText(prefs.isRemember() ? "开" : "关");
        valueAbout.setText("v" + BuildConfig.VERSION_NAME);
    }

    private void chooseSource() {
        final List<Source> sources = prefs.getSources();
        final int current = prefs.getCurrentSource();
        String[] items = new String[sources.size() + 1];
        for (int i = 0; i < sources.size(); i++) {
            String mark = i == current ? "● " : "○ ";
            items[i] = mark + sources.get(i).displayName();
        }
        items[sources.size()] = "＋ " + getString(R.string.add_source);

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Dialog_IPTV)
                .setTitle(R.string.settings_source)
                .setItems(items, (dialog, which) -> {
                    if (which == sources.size()) {
                        editSource(null);
                    } else {
                        prefs.setCurrentSource(which);
                        prefs.setNeedRefresh(true);
                        refreshValues();
                    }
                })
                .setNegativeButton(R.string.cancel, null);

        if (sources.size() > 1) {
            builder.setNeutralButton("删除当前", (dialog, which) -> {
                prefs.removeCurrentSource();
                prefs.setNeedRefresh(true);
                refreshValues();
            });
        }
        builder.show();
    }

    private void editSource(@Nullable Source existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        EditText name = new EditText(this);
        name.setHint(R.string.source_name);
        name.setSingleLine(true);
        name.setTextColor(Color.parseColor("#FFF2F5F9"));
        name.setHintTextColor(Color.parseColor("#FF6B7787"));

        EditText url = new EditText(this);
        url.setHint(R.string.source_url);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setTextColor(Color.parseColor("#FFF2F5F9"));
        url.setHintTextColor(Color.parseColor("#FF6B7787"));

        if (existing != null) {
            name.setText(existing.name);
            url.setText(existing.url);
        }

        box.addView(name);
        box.addView(url);

        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.Dialog_IPTV)
                .setTitle(existing == null ? R.string.add_source : R.string.edit_source)
                .setView(box)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String address = url.getText().toString().trim();
                    if (address.isEmpty()) {
                        url.setError(getString(R.string.url_invalid));
                        return;
                    }
                    String label = name.getText().toString().trim();
                    if (existing == null) prefs.addSource(new Source(label, address));
                    else prefs.updateCurrentSource(new Source(label, address));
                    prefs.setNeedRefresh(true);
                    refreshValues();
                    dialog.dismiss();
                }));
        dialog.show();
        if (existing == null) url.requestFocus();
    }

    private void showAbout() {
        new AlertDialog.Builder(this, R.style.Dialog_IPTV)
                .setTitle(R.string.settings_about)
                .setMessage(getString(R.string.about_text, BuildConfig.VERSION_NAME))
                .setPositiveButton(R.string.confirm, null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
