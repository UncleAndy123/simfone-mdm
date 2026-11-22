/*
 * SimFone MDM
 * Licensed under the Apache License, Version 2.0.
 */

package com.simfone.mdm.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.simfone.mdm.BuildConfig;
import com.simfone.mdm.R;
import com.simfone.mdm.core.AgentController;
import com.simfone.mdm.helper.ConfigUpdater;
import com.simfone.mdm.helper.CryptoHelper;
import com.simfone.mdm.helper.SettingsHelper;
import com.simfone.mdm.json.ServerConfig;
import com.simfone.mdm.policy.PolicyEnforcer;
import com.simfone.mdm.policy.PolicyLedger;
import com.simfone.mdm.policy.ReleaseManager;
import com.simfone.mdm.security.FallbackManifest;
import com.simfone.mdm.security.OfflineCode;
import com.simfone.mdm.server.ServerServiceKeeper;
import com.simfone.mdm.util.RemoteLogger;
import com.simfone.mdm.util.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Hidden D-pad settings, opened by the dialer code *#*#7463#*#* (S-I-M-F).
 *
 * Built for keypad phones: one focusable column, visible focus highlight, OK selects,
 * BACK goes back. No touch-only widgets, no overflow menus, no XML layouts.
 *
 * Access:
 *  - PIN = the admin password from the server config (MD5 compared, as upstream Headwind).
 *    There is NO default password: without a config, only a signed offline code opens it.
 *  - Release / park / reactivate / temporary unlock / server change always need an offline code
 *    for that specific action, even after the PIN.
 */
public class SettingsActivity extends AppCompatActivity {
    private static final long UNLOCK_DURATION_MS = TimeUnit.MINUTES.toMillis(15);

    private TextView header;
    private TextView body;
    private EditText input;
    private RecyclerView list;
    private final Adapter adapter = new Adapter();

    /** Back stack of pages. */
    private final List<Runnable> pages = new ArrayList<>();

    private static final class Item {
        final String label;
        final Runnable action;
        Item(String label, Runnable action) { this.label = label; this.action = action; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);  // no screenshots of codes

        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.BLACK);

        header = text(20, true);
        body = text(15, false);
        input = new EditText(this);
        input.setTextColor(Color.WHITE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        input.setSingleLine(true);
        input.setBackgroundResource(R.drawable.item_focus);
        input.setPadding(pad, pad, pad, pad);
        input.setVisibility(View.GONE);

        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setItemAnimator(null);

        root.addView(header);
        root.addView(body);
        root.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        push(this::showLogin);
    }

    // -------------------------------------------------------------------------------- pages

    private void showLogin() {
        ServerConfig config = SettingsHelper.getInstance(this).getConfig();
        boolean pinAvailable = config != null && config.getPassword() != null && !config.getPassword().isEmpty();
        List<Item> items = new ArrayList<>();
        if (pinAvailable) {
            page(getString(R.string.settings_title), getString(R.string.settings_enter_pin), true, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            items.add(new Item(getString(R.string.settings_ok), () -> {
                String pin = input.getText().toString();
                if (CryptoHelper.getMD5String(pin).equalsIgnoreCase(SettingsHelper.getInstance(this).getConfig().getPassword())) {
                    replace(this::showMenu);
                } else {
                    RemoteLogger.log(this, com.simfone.mdm.Const.LOG_WARN, "Wrong PIN in hidden settings");
                    body.setText(R.string.settings_wrong_pin);
                    input.setText("");
                }
            }));
        } else {
            page(getString(R.string.settings_title), getString(R.string.settings_no_pin), false, 0);
        }
        items.add(new Item(getString(R.string.settings_use_code), () ->
                push(() -> showCode(OfflineCode.ACTION_SETTINGS, () -> replaceAll(this::showMenu)))));
        items.add(new Item(getString(R.string.settings_close), this::finish));
        adapter.set(items);
    }

    private void showMenu() {
        page(getString(R.string.settings_title), statusLine(), false, 0);
        List<Item> items = new ArrayList<>();
        items.add(new Item(getString(R.string.settings_status), () -> push(this::showStatus)));
        String state = ReleaseManager.getState(this);
        if (ReleaseManager.STATE_PARKED.equals(state)) {
            items.add(new Item(getString(R.string.settings_reactivate), () -> push(() ->
                    showCode(OfflineCode.ACTION_REACTIVATE, () -> replaceAll(this::showServer)))));
        } else {
            items.add(new Item(getString(R.string.settings_sync_now), () -> {
                ConfigUpdater.forceConfigUpdate(getApplicationContext());
                body.setText(R.string.settings_sync_started);
            }));
        }
        items.add(new Item(getString(R.string.settings_server), () -> push(() ->
                showCode(OfflineCode.ACTION_SETTINGS, () -> replace(this::showServer)))));
        items.add(new Item(getString(R.string.settings_wifi), () -> {
            try {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (Exception e) {
                body.setText(e.getMessage());
            }
        }));
        items.add(new Item(getString(R.string.settings_send_logs), () -> {
            RemoteLogger.resetState();
            RemoteLogger.sendLogsToServer(getApplicationContext());
            body.setText(R.string.settings_logs_sent);
        }));
        if (PolicyEnforcer.temporaryUnlockRemainingMs(this) > 0) {
            items.add(new Item(getString(R.string.settings_relock_now), () -> {
                new Thread(() -> PolicyEnforcer.endTemporaryUnlock(getApplicationContext())).start();
                replace(this::showMenu);
            }));
        } else {
            items.add(new Item(getString(R.string.settings_temp_unlock), () -> push(() ->
                    showCode(OfflineCode.ACTION_UNLOCK, () -> {
                        new Thread(() -> PolicyEnforcer.beginTemporaryUnlock(getApplicationContext(), UNLOCK_DURATION_MS)).start();
                        replaceAll(this::showMenu);
                    }))));
        }
        if (FallbackManifest.isConfigured()) {
            items.add(new Item(getString(R.string.settings_check_manifest), () -> {
                body.setText(R.string.settings_working);
                new Thread(() -> {
                    String r;
                    try {
                        r = FallbackManifest.checkNow(getApplicationContext(), true);
                    } catch (Exception e) {
                        r = e.getMessage();
                    }
                    final String result = r;
                    runOnUiThread(() -> body.setText(result));
                }).start();
            }));
        }
        if (!ReleaseManager.STATE_PARKED.equals(state)) {
            items.add(new Item(getString(R.string.settings_park), () -> push(() ->
                    showCode(OfflineCode.ACTION_PARK, () -> runAndClose(() ->
                            ReleaseManager.park(getApplicationContext(), "offline code"))))));
        }
        items.add(new Item(getString(R.string.settings_release), () -> push(() ->
                showCode(OfflineCode.ACTION_RELEASE, () -> runAndClose(() ->
                        ReleaseManager.release(getApplicationContext(), "offline code"))))));
        items.add(new Item(getString(R.string.settings_close), this::finish));
        adapter.set(items);
    }

    private void showStatus() {
        SettingsHelper s = SettingsHelper.getInstance(this);
        ServerConfig c = s.getConfig();
        long last = FallbackManifest.lastServerContact(this);
        StringBuilder b = new StringBuilder();
        b.append("SimFone MDM ").append(BuildConfig.VERSION_NAME).append('\n');
        b.append("State: ").append(ReleaseManager.getState(this)).append('\n');
        b.append("Device Owner: ").append(Utils.isDeviceOwner(this)).append('\n');
        b.append("Device ID: ").append(s.getDeviceId()).append('\n');
        b.append("Server: ").append(s.getBaseUrl());
        if (!s.getServerProject().isEmpty()) b.append('/').append(s.getServerProject());
        b.append('\n');
        b.append("Config: ").append(c != null && c.getDescription() != null ? c.getDescription() : (c != null ? "loaded" : "none")).append('\n');
        b.append("Last sync: ").append(last == 0 ? "never" : DateFormat.format("yyyy-MM-dd HH:mm", last)).append('\n');
        b.append("Policies: ").append(PolicyLedger.get(this).summary()).append('\n');
        b.append("Offline key: ").append(OfflineCode.hasKey(this) ? "installed" : "MISSING").append('\n');
        long unlock = PolicyEnforcer.temporaryUnlockRemainingMs(this);
        if (unlock > 0) b.append("Unlocked for ").append(unlock / 60000 + 1).append(" min\n");
        page(getString(R.string.settings_status), b.toString(), false, 0);
        List<Item> items = new ArrayList<>();
        items.add(new Item(getString(R.string.settings_back), this::pop));
        adapter.set(items);
    }

    private void showServer() {
        SettingsHelper s = SettingsHelper.getInstance(this);
        page(getString(R.string.settings_server), getString(R.string.settings_server_hint), true, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(s.getBaseUrl());
        List<Item> items = new ArrayList<>();
        items.add(new Item(getString(R.string.settings_save_server), () -> {
            String url = input.getText().toString().trim();
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                body.setText(R.string.settings_bad_url);
                return;
            }
            s.setBaseUrl(url);
            s.setSecondaryBaseUrl(url);
            ServerServiceKeeper.resetServices();
            RemoteLogger.log(this, com.simfone.mdm.Const.LOG_WARN, "Server changed in hidden settings");
            body.setText(R.string.settings_saved);
        }));
        items.add(new Item(getString(R.string.settings_device_id) + ": " + s.getDeviceId(), () -> {
            String id = input.getText().toString().trim();
            if (id.isEmpty() || id.contains("/")) return;
            s.setDeviceId(id);
            body.setText(R.string.settings_saved);
        }));
        if (ReleaseManager.STATE_PARKED.equals(ReleaseManager.getState(this))) {
            items.add(new Item(getString(R.string.settings_reactivate_now), () -> {
                ReleaseManager.reactivate(getApplicationContext());
                AgentController.start(getApplicationContext(), "reactivated");
                replaceAll(this::showMenu);
            }));
        } else {
            items.add(new Item(getString(R.string.settings_reenroll), () -> {
                ServerServiceKeeper.resetServices();
                ConfigUpdater.forceConfigUpdate(getApplicationContext());
                body.setText(R.string.settings_sync_started);
            }));
        }
        items.add(new Item(getString(R.string.settings_back), this::pop));
        adapter.set(items);
    }

    /** Challenge / response page for one action. */
    private void showCode(String action, Runnable onSuccess) {
        if (!OfflineCode.hasKey(this)) {
            page(getString(R.string.settings_code_title), getString(R.string.settings_no_key), false, 0);
            List<Item> items = new ArrayList<>();
            items.add(new Item(getString(R.string.settings_back), this::pop));
            adapter.set(items);
            return;
        }
        long lock = OfflineCode.lockoutRemainingMs(this);
        String text;
        if (lock > 0) {
            text = getString(R.string.settings_locked_out, lock / 60000 + 1);
        } else {
            text = getString(R.string.settings_code_prompt,
                    SettingsHelper.getInstance(this).getDeviceId(), action, OfflineCode.getChallenge(this));
        }
        page(getString(R.string.settings_code_title), text, lock == 0, InputType.TYPE_CLASS_NUMBER);
        List<Item> items = new ArrayList<>();
        if (lock == 0) {
            items.add(new Item(getString(R.string.settings_ok), () -> {
                if (OfflineCode.verify(this, action, input.getText().toString())) {
                    onSuccess.run();
                } else {
                    input.setText("");
                    replace(() -> showCode(action, onSuccess));
                    body.append("\n" + getString(R.string.settings_wrong_code, OfflineCode.failures(this)));
                }
            }));
        }
        items.add(new Item(getString(R.string.settings_back), this::pop));
        adapter.set(items);
    }

    private void runAndClose(Runnable work) {
        page(getString(R.string.settings_title), getString(R.string.settings_working), false, 0);
        adapter.set(new ArrayList<>());
        new Thread(() -> {
            work.run();
            runOnUiThread(this::finish);
        }).start();
    }

    private String statusLine() {
        String state = ReleaseManager.getState(this);
        long unlock = PolicyEnforcer.temporaryUnlockRemainingMs(this);
        return SettingsHelper.getInstance(this).getDeviceId() + " - " + state
                + (unlock > 0 ? " - unlocked " + (unlock / 60000 + 1) + " min" : "");
    }

    // -------------------------------------------------------------------------------- navigation

    private void push(Runnable page) {
        pages.add(page);
        page.run();
    }

    private void replace(Runnable page) {
        if (!pages.isEmpty()) pages.remove(pages.size() - 1);
        push(page);
    }

    private void replaceAll(Runnable page) {
        pages.clear();
        push(page);
    }

    private void pop() {
        if (pages.size() <= 1) {
            finish();
            return;
        }
        pages.remove(pages.size() - 1);
        pages.get(pages.size() - 1).run();
    }

    @Override
    public void onBackPressed() {
        pop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            pop();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the screen ends the session: the PIN/code must be entered again
        if (!isChangingConfigurations()) finish();
    }

    // -------------------------------------------------------------------------------- views

    private void page(String title, String text, boolean withInput, int inputType) {
        header.setText(title);
        body.setText(text);
        input.setText("");
        if (withInput) {
            input.setInputType(inputType);
            input.setVisibility(View.VISIBLE);
            input.requestFocus();
        } else {
            input.setVisibility(View.GONE);
        }
    }

    private TextView text(int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private List<Item> items = new ArrayList<>();

        void set(List<Item> items) {
            this.items = items;
            notifyDataSetChanged();
            if (input.getVisibility() != View.VISIBLE) {
                list.post(() -> {
                    RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(0);
                    if (h != null) h.itemView.requestFocus();
                });
            }
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView label;
            Holder(TextView v) { super(v); label = v; }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView v = text(18, false);
            int p = dp(14);
            v.setPadding(p, p, p, p);
            v.setFocusable(true);
            v.setFocusableInTouchMode(true);
            v.setClickable(true);
            v.setBackgroundResource(R.drawable.item_focus);
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Item item = items.get(position);
            holder.label.setText(item.label);
            holder.label.setOnClickListener(v -> item.action.run());
            holder.label.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    item.action.run();
                    return true;
                }
                return event.getAction() == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
