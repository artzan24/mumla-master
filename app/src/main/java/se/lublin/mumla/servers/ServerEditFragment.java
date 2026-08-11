/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.servers;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import se.lublin.humla.model.Server;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;

public class ServerEditFragment extends DialogFragment {
    private static final String ARGUMENT_SERVER = "server";
    private static final String ARGUMENT_ACTION = "action";
    private static final String ARGUMENT_IGNORE_TITLE = "ignore_title";

    private EditText mNameEdit;
    private EditText mHostEdit;
    private EditText mPortEdit;
    private EditText mUsernameEdit;
    private EditText mPasswordEdit;
    private ImageView mTogglePasswordBtn; // Tombol tanda mata

    private ServerEditListener mListener;

    /**
     * Creates a new {@link ServerEditFragment} dialog. Results will be delivered to the parent
     * activity via {@link ServerEditListener}.
     * @param server Optional, if set will populate the fragment with data from the server.
     * @param action The action the fragment is performing (i.e. Add, Edit)
     * @param ignoreTitle If true, don't show fields related to the server title (useful for quick
     *                    connect dialogs)
     */
    public static DialogFragment createServerEditDialog(Context context, Server server,
                                                        Action action,
                                                        boolean ignoreTitle) {
        Bundle args = new Bundle();
        args.putParcelable(ARGUMENT_SERVER, server);
        args.putInt(ARGUMENT_ACTION, action.ordinal());
        args.putBoolean(ARGUMENT_IGNORE_TITLE, ignoreTitle);
        return (DialogFragment) Fragment.instantiate(context, ServerEditFragment.class.getName(), args);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            mListener = (ServerEditListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement ServerEditListener!");
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // Override positive button to not automatically dismiss on press.
        // We can't accomplish this with AlertDialog.Builder.
        ((AlertDialog)getDialog()).getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (validate()) {
                    String inputCi4Pass = mPasswordEdit.getText().toString().trim();
                    android.content.SharedPreferences prefs = requireActivity().getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
                    prefs.edit().putString("saved_ci4_password", inputCi4Pass).apply();
                    Server server = createServer();
                    mListener.onServerEdited(getAction(), server);
                    dismiss();
                }
            }
        });
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Settings settings = Settings.getInstance(getActivity());

        String actionName;
        switch (getAction()) {
            case ADD_ACTION:
                actionName = getString(R.string.add);
                break;
            case EDIT_ACTION:
                actionName = getString(android.R.string.ok);
                break;
            case CONNECT_ACTION:
                actionName = getString(R.string.connect);
                break;
            default:
                throw new RuntimeException("Unknown action " + getAction());
        }

        LayoutInflater inflater = LayoutInflater.from(getActivity());
        View view = inflater.inflate(R.layout.dialog_server_edit, null, false);

        TextView titleLabel = view.findViewById(R.id.server_edit_name_title);
        mNameEdit = view.findViewById(R.id.server_edit_name);
        mHostEdit = view.findViewById(R.id.server_edit_host);
        mPortEdit = view.findViewById(R.id.server_edit_port);
        mUsernameEdit = view.findViewById(R.id.server_edit_username);
        mUsernameEdit.setHint(settings.getDefaultUsername());

        mPasswordEdit = view.findViewById(R.id.server_edit_password);
        mTogglePasswordBtn = view.findViewById(R.id.btn_toggle_password);

        mPortEdit.setText("50000");
        mPortEdit.setVisibility(View.GONE);
        mNameEdit.setText("Polda Bali");
        mNameEdit.setVisibility(View.GONE);

        titleLabel.setVisibility(View.GONE);

        View portLabel = view.findViewById(R.id.server_edit_port_title); // Sesuaikan ID jika ada
        if (portLabel != null) {
            portLabel.setVisibility(View.GONE);
        }

        // Logika Tombol Tanda Mata (Toggle Password Visibility)
        // Logika Tombol Tanda Mata (Toggle Password Visibility)
        final boolean[] isPasswordVisible = {false};
        if (mTogglePasswordBtn != null && mPasswordEdit != null) {
            mTogglePasswordBtn.setOnClickListener(v -> {
                if (isPasswordVisible[0]) {
                    // Sembunyikan password (kembalikan ke mode titik-titik)
                    mPasswordEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    mTogglePasswordBtn.setImageResource(R.drawable.ic_visibility);
                } else {
                    // Tampilkan password secara teks biasa (terlihat)
                    mPasswordEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                    mTogglePasswordBtn.setImageResource(R.drawable.ic_visibility_off);
                }
                isPasswordVisible[0] = !isPasswordVisible[0];
                // Pindahkan kursor ke bagian akhir teks
                mPasswordEdit.setSelection(mPasswordEdit.getText().length());
            });
        }

        Server oldServer = getServer();
        if (oldServer != null) {
            mNameEdit.setText(oldServer.getName());
            mHostEdit.setText(oldServer.getHost());
            if (oldServer.getPort() != 0) {
                mPortEdit.setText(String.valueOf(oldServer.getPort()));
            }
            mUsernameEdit.setText(oldServer.getUsername());
            // AMBIL PASSWORD DATABASE YANG SEBELUMNYA TERSIMPAN OTOMATIS
            android.content.SharedPreferences prefs = requireActivity().getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
            String savedPassword = prefs.getString("saved_ci4_password", "");
            mPasswordEdit.setText(savedPassword);
        }

        if (shouldIgnoreTitle()) {
            titleLabel.setVisibility(View.GONE);
            mNameEdit.setVisibility(View.GONE);
        }

        return new MaterialAlertDialogBuilder(requireActivity())
                .setPositiveButton(actionName, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setView(view)
                .create();
    }

    public Server createServer() {
        String name = (mNameEdit).getText().toString().trim();
        String host = (mHostEdit).getText().toString().trim();

        int port = 50000;
        try {
            port = Integer.parseInt((mPortEdit).getText().toString());
        } catch (final NumberFormatException ex) {
            port = 0;
        }

        String username = (mUsernameEdit).getText().toString().trim();

        // 1. Password untuk koneksi core Mumble (Ditetapkan tetap/hardcode sesuai server)
        String passwordMumble = "PoldaBali241081";

        // 2. (Opsional) Jika Anda perlu menyimpan inputan password CI4 ke dalam objek server
        // atau membawanya ke proses selanjutnya, bisa diambil dari mPasswordEdit:
        String passwordCi4Input = mPasswordEdit != null ? mPasswordEdit.getText().toString().trim() : "";

        if (username.equals(""))
            username = mUsernameEdit.getHint().toString();

        long id;
        if (getServer() != null) {
            id = getServer().getId();
        } else {
            id = -1;
        }

        // Mengembalikan objek Server dengan password Mumble "PoldaBali241081"
        return new Server(id, name, host, port, username, passwordMumble);
    }

    /**
     * Checks all fields in this ServerEditFragment for validity.
     * If an invalid field is found, an error is shown and false is returned.
     * @return true if the inputted values are valid, false otherwise.
     */
    public boolean validate() {
        if (mHostEdit.getText().length() == 0) {
            mHostEdit.setError(getString(R.string.invalid_host));
            return false;
        } else if (mPortEdit.getText().length() > 0) {
            try {
                int port = Integer.parseInt(mPortEdit.getText().toString());
                if (port < 1 || port > 65535) {
                    mPortEdit.setError(getString(R.string.invalid_port_range));
                    return false;
                }
            } catch (NumberFormatException nfe) {
                mPortEdit.setError(getString(R.string.invalid_port_range));
                return false;
            }
        }
        return true;
    }

    private Server getServer() {
        return getArguments().getParcelable(ARGUMENT_SERVER);
    }

    private Action getAction() {
        return Action.values()[getArguments().getInt(ARGUMENT_ACTION)];
    }

    private boolean shouldIgnoreTitle() {
        return getArguments().getBoolean(ARGUMENT_IGNORE_TITLE);
    }

    public interface ServerEditListener {
        void onServerEdited(Action action, Server server);
    }

    public enum Action {
        CONNECT_ACTION,
        EDIT_ACTION,
        ADD_ACTION
    }
}