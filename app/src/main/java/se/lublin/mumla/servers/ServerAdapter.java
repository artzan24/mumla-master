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

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.appcompat.widget.PopupMenu;

import java.util.List;

import se.lublin.humla.model.Server;
import se.lublin.mumla.R;

/**
 * Created by andrew on 05/05/14.
 */
public abstract class ServerAdapter<E extends Server> extends ArrayAdapter<E> {
    private int mViewResource;

    public ServerAdapter(Context context, int viewResource, List<E> servers) {
        super(context, 0, servers);
        mViewResource = viewResource;
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).getId();
    }

    static class ViewHolder {
        EditText etUsername;
        EditText etPassword;
        ImageView btnTogglePass;
        Button btnConnect;
    }

    @Override
    public View getView(int position, View v, ViewGroup parent) {
        View view = v;
        ViewHolder holder;

        if (view == null) {
            LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            view = inflater.inflate(mViewResource, parent, false);

            holder = new ViewHolder();
            holder.etUsername = view.findViewById(R.id.server_row_edit_username);
            holder.etPassword = view.findViewById(R.id.server_row_edit_password);
            holder.btnTogglePass = view.findViewById(R.id.btn_row_toggle_password);
            holder.btnConnect = view.findViewById(R.id.btn_row_connect);

            view.setTag(holder);
        } else {
            holder = (ViewHolder) view.getTag();
        }

        final E server = getItem(position);

        // Muat data SharedPreferences setiap kali row disiapkan/dirender agar sinkron dengan item data saat ini
        SharedPreferences prefsLogin = getContext().getSharedPreferences("RoipLoginPrefs", Context.MODE_PRIVATE);
        SharedPreferences prefsSession = getContext().getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);

        String savedUsername = prefsLogin.getString("saved_username", server != null ? server.getUsername() : "");
        String savedPassword = prefsSession.getString("saved_ci4_password", prefsLogin.getString("saved_password", ""));

        if (holder.etUsername != null) {
            // Isi teks hanya jika field kosong agar tidak mengganggu pengetikan aktif pengguna
            if (holder.etUsername.getText().toString().isEmpty()) {
                holder.etUsername.setText(savedUsername);
            }
            // Langsung berikan fokus ke kolom username saat pertama kali muncul
            holder.etUsername.requestFocus();
        }
        if (holder.etPassword != null) {
            if (holder.etPassword.getText().toString().isEmpty()) {
                holder.etPassword.setText(savedPassword);
            }
            holder.etPassword.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        }

        // Sinkronisasi klik pada wadah (layout) pembungkus password agar langsung fokus ke EditText password di dalamnya
        if (holder.etPassword != null) {
            View passwordContainer = (View) holder.etPassword.getParent();
            if (passwordContainer != null) {
                passwordContainer.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        holder.etPassword.requestFocus();
                        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.showSoftInput(holder.etPassword, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                        }
                    }
                });
            }
        }

        // Fitur Tombol Mata (Show/Hide Password) dengan dukungan Klik dan Keypad (DPAD Center / Enter)
        if (holder.btnTogglePass != null && holder.etPassword != null) {
            final EditText passwordField = holder.etPassword;
            final ImageView togglePass = holder.btnTogglePass;

            // Hapus listener sebelumnya agar tidak menumpuk saat recycler/list mendaur ulang view
            togglePass.setOnClickListener(null);
            togglePass.setOnKeyListener(null);

            final boolean[] isPasswordVisible = {false};
            View.OnClickListener toggleClickListener = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int cursorPos = passwordField.getSelectionStart();
                    if (isPasswordVisible[0]) {
                        // Sembunyikan password kembali
                        passwordField.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
                        togglePass.setImageResource(R.drawable.ic_visibility);
                        isPasswordVisible[0] = false;
                    } else {
                        // Tampilkan password (buka mata)
                        passwordField.setTransformationMethod(null);
                        togglePass.setImageResource(R.drawable.ic_visibility_off);
                        isPasswordVisible[0] = true;
                    }
                    passwordField.setSelection(cursorPos);
                }
            };

            togglePass.setOnClickListener(toggleClickListener);

            // Dukungan penekanan tombol OK/Enter pada keypad fisik saat ikon mata disorot (fokus)
            togglePass.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                            togglePass.performClick();
                            return true;
                        }
                    }
                    return false;
                }
            });
        }

        // Aksi saat tombol Connect ditekan
        if (holder.btnConnect != null) {
            final EditText usernameField = holder.etUsername;
            final EditText passwordField = holder.etPassword;

            // Hapus listener lama untuk mencegah duplikasi eksekusi aksi klik
            holder.btnConnect.setOnClickListener(null);

            holder.btnConnect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String usernameStr = usernameField != null ? usernameField.getText().toString().trim() : "";
                    String passwordStr = passwordField != null ? passwordField.getText().toString().trim() : "";

                    if (usernameStr.isEmpty()) {
                        if (usernameField != null) {
                            usernameField.setError("Username / NRP harus diisi!");
                            usernameField.requestFocus();
                        }
                        return;
                    }

                    // Simpan data otomatis ke SharedPreferences agar sinkron dengan MumlaActivity dan sesi login berikutnya
                    Context appCtx = getContext().getApplicationContext();

                    appCtx.getSharedPreferences("RoipLoginPrefs", Context.MODE_PRIVATE).edit()
                            .putString("saved_username", usernameStr)
                            .putString("saved_password", passwordStr)
                            .apply();

                    appCtx.getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE).edit()
                            .putString("saved_username", usernameStr)
                            .putString("saved_ci4_password", passwordStr)
                            .apply();

                    // Perbarui data server sebelum terhubung
                    if (server != null) {
                        server.setUsername(usernameStr);
                        server.setPassword(passwordStr);
                    }

                    // Panggil fungsi klik server bawaan adapter untuk melanjutkan proses koneksi ke MumlaActivity
                    onServerConnectClick(server);
                }
            });
        }

        return view;
    }

    // Method bantuan untuk menangani aksi koneksi yang dapat diimplementasikan di Fragment/Activity
    public void onServerConnectClick(Server server) {
        // Bisa dioverride atau disesuaikan dengan fungsi koneksi utama Mumla Activity Anda
    }

    private void onServerOptionsClick(final Server server, View optionsButton) {
        PopupMenu popupMenu = new PopupMenu(getContext(), optionsButton);
        popupMenu.inflate(getPopupMenuResource());
        popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                return onPopupItemClick(server, menuItem);
            }
        });
        popupMenu.show();
    }

    public abstract int getPopupMenuResource();
    public abstract boolean onPopupItemClick(Server server, MenuItem menuItem);
}