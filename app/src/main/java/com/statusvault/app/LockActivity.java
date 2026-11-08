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

/**
 * Covers the app until the user passes fingerprint / face / screen lock.
 * The prompt is only requested once this screen is actually in front (newer Android cancels
 * prompts asked for any earlier), and falls back to the phone's PIN screen if it can't be shown.
 */
public class LockActivity extends BaseActivity {

    private static final int REQ_CONFIRM = 1;
    private CancellationSignal cancel;
    private boolean authInProgress;
    private boolean autoPromptDone;
    private TextView hint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StatusVaultApp.get().setLockShowing(true);
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
        title.setPadding(0, dp(12), 0, dp(6));
        root.addView(title);

        hint = new TextView(this);
        hint.setText("Use your fingerprint, face or screen lock");
        hint.setTextSize(14);
        hint.setTextColor(getColor(R.color.on_surface_variant));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, dp(28));
        root.addView(hint);

        Button unlock = new Button(this);
        unlock.setText("Unlock");
        unlock.setAllCaps(false);
        unlock.setTextSize(16);
        unlock.setTextColor(pal.onPrimary);
        unlock.setBackground(pal.gradient(26));
        unlock.setStateListAnimator(null);
        unlock.setPadding(dp(44), 0, dp(44), 0);
        unlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                authenticate();
            }
        });
        root.addView(unlock, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)));

        setContentView(root);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Ask once, automatically, as soon as the lock screen is really in front.
        if (hasFocus && !autoPromptDone) {
            autoPromptDone = true;
            authenticate();
        }
    }

    @SuppressWarnings("deprecation")
    private void authenticate() {
        if (authInProgress) return;
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km == null || !km.isDeviceSecure()) { // no screen lock set — nothing to check against
            unlocked();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
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
                authInProgress = true;
                b.build().authenticate(cancel, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        authInProgress = false;
                        unlocked();
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        authInProgress = false;
                        boolean userCancelled = code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                                || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED;
                        if (userCancelled) {
                            hint.setText("Tap Unlock to try again");
                        } else {
                            useScreenLock(); // the prompt can't run here — use the phone's PIN screen
                        }
                    }
                });
                return;
            } catch (RuntimeException e) {
                authInProgress = false; // this phone rejected the prompt setup — fall back below
            }
        }
        useScreenLock();
    }

    /** The phone's own PIN / pattern / password screen. */
    @SuppressWarnings("deprecation")
    private void useScreenLock() {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        Intent i = km != null ? km.createConfirmDeviceCredentialIntent("Unlock Status Vault", "Confirm your screen lock") : null;
        if (i == null) {
            unlocked();
            return;
        }
        authInProgress = true;
        StatusVaultApp.get().suppressNextLockCheck();
        try {
            startActivityForResult(i, REQ_CONFIRM);
        } catch (RuntimeException e) {
            authInProgress = false;
            unlocked(); // never leave the user stuck on a screen they can't get past
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CONFIRM) return;
        authInProgress = false;
        if (resultCode == RESULT_OK) unlocked();
        else hint.setText("Tap Unlock to try again");
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
        StatusVaultApp.get().setLockShowing(false);
        if (cancel != null) cancel.cancel();
    }
}
