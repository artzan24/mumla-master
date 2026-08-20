package se.lublin.mumla.preference;

import static java.util.Objects.requireNonNull;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceDialogFragmentCompat;

import se.lublin.mumla.R;

public class KeySelectPreferenceDialogFragment extends PreferenceDialogFragmentCompat implements OnKeyListener {
    private TextView mValueView;
    private int mCurrentValue;

    public static KeySelectPreferenceDialogFragment newInstance(String key) {
        final KeySelectPreferenceDialogFragment fragment = new KeySelectPreferenceDialogFragment();
        final Bundle b = new Bundle(1);
        b.putString(ARG_KEY, key);
        fragment.setArguments(b);
        return fragment;
    }

    @Override
    protected void onPrepareDialogBuilder(@NonNull AlertDialog.Builder builder) {
        super.onPrepareDialogBuilder(builder);

        builder.setNeutralButton(R.string.reset_key, (dialog, which) -> {
            KeySelectDialogPreference preference = (KeySelectDialogPreference) getPreference();
            mCurrentValue = 0;
            // A NeutralButton causes onDialogClosed to be called with positiveResult==false,
            // so we persist manually here.
            if (preference.callChangeListener(mCurrentValue)) {
                requireNonNull(preference.getSharedPreferences())
                        .edit().putInt(preference.getKey(), mCurrentValue).apply();
            }
        });
    }

    @Override
    protected void onBindDialogView(@NonNull View view) {
        super.onBindDialogView(view);

        view.setOnKeyListener(this);
        view.setFocusableInTouchMode(true);
        view.requestFocus();

        mValueView = view.findViewById(R.id.key_select_value_view);
        KeySelectDialogPreference preference = (KeySelectDialogPreference) getPreference();
        mCurrentValue = requireNonNull(preference.getSharedPreferences())
                .getInt(preference.getKey(), 0);
        updateValueView();
    }

    // ==========================================
    // TAMBAHKAN ONSTART INI AGAR TOMBOL DIALOG BISA DIFOKUSI D-PAD
    // ==========================================
    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog instanceof AlertDialog) {
            AlertDialog alertDialog = (AlertDialog) dialog;

            // Ambil tombol OK (Positive)
            Button btnPositive = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (btnPositive != null) {
                btnPositive.setFocusable(true);
                btnPositive.setFocusableInTouchMode(true);
                btnPositive.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() == KeyEvent.ACTION_DOWN &&
                            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                        btnPositive.performClick();
                        return true;
                    }
                    return false;
                });
            }

            // Ambil tombol Cancel (Negative)
            Button btnNegative = alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            if (btnNegative != null) {
                btnNegative.setFocusable(true);
                btnNegative.setFocusableInTouchMode(true);
                btnNegative.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() == KeyEvent.ACTION_DOWN &&
                            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                        btnNegative.performClick();
                        return true;
                    }
                    return false;
                });
            }

            // Ambil tombol Reset Key (Neutral)
            Button btnNeutral = alertDialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            if (btnNeutral != null) {
                btnNeutral.setFocusable(true);
                btnNeutral.setFocusableInTouchMode(true);
                btnNeutral.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() == KeyEvent.ACTION_DOWN &&
                            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                        btnNeutral.performClick();
                        return true;
                    }
                    return false;
                });
            }
        }
    }
    // ==========================================

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }

        // Jika tombol arah bawah (DPAD_DOWN) ditekan dari area tampilan utama,
        // kita izinkan fokus berpindah ke tombol aksi di bawah (Reset/Cancel/OK).
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            Dialog dialog = getDialog();
            if (dialog instanceof AlertDialog) {
                Button btnPositive = ((AlertDialog) dialog).getButton(DialogInterface.BUTTON_POSITIVE);
                if (btnPositive != null) {
                    btnPositive.requestFocus();
                    return true;
                }
            }
        }

        if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                && keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_LEFT
                && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT && keyCode != KeyEvent.KEYCODE_DPAD_CENTER
                && keyCode != KeyEvent.KEYCODE_ENTER) {
            mCurrentValue = keyCode;
            updateValueView();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            dismiss();
            return true;
        }
        return false;
    }

    private void updateValueView() {
        if (mCurrentValue == 0) {
            mValueView.setText(R.string.no_ptt_key);
        } else {
            final String stripPrefix = "KEYCODE_";
            String keyName = KeyEvent.keyCodeToString(mCurrentValue);
            if (keyName.startsWith(stripPrefix)) {
                keyName = keyName.substring(stripPrefix.length());
            }
            mValueView.setText(keyName);
        }
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        if (positiveResult) {
            KeySelectDialogPreference preference = (KeySelectDialogPreference) getPreference();
            if (preference.callChangeListener(mCurrentValue)) {
                requireNonNull(preference.getSharedPreferences())
                        .edit().putInt(preference.getKey(), mCurrentValue).apply();
            }
        }
    }
}