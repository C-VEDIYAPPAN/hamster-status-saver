package com.statusvault.app;

import android.app.KeyguardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Covers the app until the user passes fingerprint / face / screen-lock. */
public class LockActivity extends BaseActivity {

    private static final int REQ_CONFIRM = 1;
    private CancellationSignal cancel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Palette pal = new Palette(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(32), dp(32), dp(32), dp(32));
        root.setBackgroundColor(getColor(R.color.bg));
        root.setFitsSystemWindows(true);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        root.addView(logo, new LinearLayout.LayoutParams(dp(88), dp(88)));

        ImageView lock = new ImageView(this);
        lock.setImageResource(R.drawable.ic_lock);
        lock.setImageTintList(ColorStateList.valueOf(pal.primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
        lp.topMargin = dp(28);
        root.addView(lock, lp);

        TextView title = new TextView(this);
        title.setText("Status Vault is locked");
        title.setTextSize(20);
        title.setTextColor(getColor(R.color.on_surface));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(12), 0, dp(32));
        root.addView(title);

        Button unlock = new Button(this);
        unlock.setText("Unlock");
        unlock.setAllCaps(false);
        unlock.setTextSize(16);
        unlock.setTextColor(pal.onPrimary);
        unlock.setBackground(pal.rounded(pal.primary, 24));
        unlock.setStateListAnimator(null);
        unlock.setPadding(dp(40), 0, dp(40), 0);
        unlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                authenticate();
            }
        });
        root.addView(unlock, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)));

        setContentView(root);
        authenticate();
    }

    @SuppressWarnings("deprecation")
    private void authenticate() {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km == null || !km.isDeviceSecure()) { // no screen lock set — nothing to check against
            unlocked();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(this)
                    .setTitle("Unlock Status Vault")
                    .setSubtitle("Use your fingerprint, face or screen lock");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                b.setDeviceCredentialAllowed(true);
            }
            cancel = new CancellationSignal();
            b.build().authenticate(cancel, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    unlocked();
                }
                // Errors / cancel: stay on this screen; the Unlock button retries.
            });
        } else {
            Intent i = km.createConfirmDeviceCredentialIntent("Unlock Status Vault", "Confirm your screen lock");
            if (i == null) {
                unlocked();
                return;
            }
            StatusVaultApp.get().suppressNextLockCheck();
            startActivityForResult(i, REQ_CONFIRM);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CONFIRM && resultCode == RESULT_OK) unlocked();
    }

    private void unlocked() {
        StatusVaultApp.get().markUnlocked();
        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Leaving the lock screen must not reveal the app.
        finishAffinity();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cancel != null) cancel.cancel();
    }
}
