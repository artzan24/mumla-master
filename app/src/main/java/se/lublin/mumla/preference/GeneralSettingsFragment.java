package se.lublin.mumla.preference;

import static java.util.Objects.requireNonNull;

import android.os.Bundle;

import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;

import info.guardianproject.netcipher.proxy.OrbotHelper;
import se.lublin.mumla.R;

public class GeneralSettingsFragment extends MumlaPreferenceFragment {
    private static final String USE_TOR_KEY = "useTor";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_general, rootKey);

        Preference useOrbotPreference = getPreferenceScreen().findPreference(USE_TOR_KEY);
        requireNonNull(useOrbotPreference).setEnabled(OrbotHelper.isOrbotInstalled(requireContext()));

        // --- TAMBAHKAN DUA BARIS INI ---
        // 1. Paksa matikan centangnya agar reset ke false
        CheckBoxPreference pinnedModePref = findPreference("startUpInPinnedMode");
        if (pinnedModePref != null) {
            pinnedModePref.setChecked(false);
            // 2. Sembunyikan menunya agar tidak terlihat user
            pinnedModePref.setVisible(false);
        }
        // -----------------------------
    }
}
