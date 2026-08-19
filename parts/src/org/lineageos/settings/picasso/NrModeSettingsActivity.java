/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.picasso;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.qti.extphone.Client;
import com.qti.extphone.ExtPhoneCallbackBase;
import com.qti.extphone.ExtTelephonyManager;
import com.qti.extphone.NrConfig;
import com.qti.extphone.ServiceCallback;
import com.qti.extphone.Status;
import com.qti.extphone.Token;

public final class NrModeSettingsActivity extends CollapsingToolbarBaseActivity {
    private static final String TAG_NR_MODE = "nr_mode";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.nr_mode_title);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new NrModeSettingsFragment(), TAG_NR_MODE)
                    .commit();
        }
    }

    public static final class NrModeSettingsFragment extends PreferenceFragmentCompat {
        private static final String TAG = "PicassoNrMode";
        private static final int INVALID_NR_CONFIG = -1;

        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private final SparseArray<ListPreference> mPreferences = new SparseArray<>();
        private final SparseIntArray mPendingConfigs = new SparseIntArray();

        private PreferenceCategory mCategory;
        private Preference mStatusPreference;
        private SubscriptionManager mSubscriptionManager;
        private TelephonyManager mTelephonyManager;
        private ExtTelephonyManager mExtTelephonyManager;
        private Client mClient;
        private boolean mStarted;

        private final ExtPhoneCallbackBase mExtPhoneCallback = new ExtPhoneCallbackBase() {
            @Override
            public void onSetNrConfig(int slotId, Token token, Status status)
                    throws RemoteException {
                mMainHandler.post(() -> handleSetNrConfig(slotId, status));
            }

            @Override
            public void onNrConfigStatus(int slotId, Token token, Status status,
                    NrConfig nrConfig) throws RemoteException {
                int value = nrConfig == null ? INVALID_NR_CONFIG : nrConfig.get();
                mMainHandler.post(() -> handleNrConfigStatus(slotId, status, value));
            }
        };

        private final ServiceCallback mServiceCallback = new ServiceCallback() {
            @Override
            public void onConnected() {
                mMainHandler.post(NrModeSettingsFragment.this::handleServiceConnected);
            }

            @Override
            public void onDisconnected() {
                mMainHandler.post(NrModeSettingsFragment.this::handleServiceDisconnected);
            }
        };

        private final SubscriptionManager.OnSubscriptionsChangedListener
                mSubscriptionsChangedListener =
                new SubscriptionManager.OnSubscriptionsChangedListener() {
                    @Override
                    public void onSubscriptionsChanged() {
                        mMainHandler.post(() -> {
                            if (mStarted && mClient != null) {
                                refreshSubscriptions();
                            }
                        });
                    }
                };

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            PreferenceScreen screen = getPreferenceManager()
                    .createPreferenceScreen(requireContext());
            setPreferenceScreen(screen);

            mCategory = new PreferenceCategory(requireContext());
            mCategory.setTitle(R.string.nr_mode_category);
            screen.addPreference(mCategory);

            mStatusPreference = new Preference(requireContext());
            mStatusPreference.setSelectable(false);
            mStatusPreference.setSummary(R.string.nr_mode_connecting);
            screen.addPreference(mStatusPreference);

            mSubscriptionManager = requireContext().getSystemService(SubscriptionManager.class);
            mTelephonyManager = requireContext().getSystemService(TelephonyManager.class);
            mExtTelephonyManager = ExtTelephonyManager.getInstance(requireContext());
        }

        @Override
        public void onStart() {
            super.onStart();
            mStarted = true;
            mStatusPreference.setVisible(true);
            mStatusPreference.setSummary(R.string.nr_mode_connecting);
            mSubscriptionManager.addOnSubscriptionsChangedListener(
                    requireContext().getMainExecutor(), mSubscriptionsChangedListener);

            if (!mExtTelephonyManager.connectService(mServiceCallback)) {
                mExtTelephonyManager.disconnectService(mServiceCallback);
                showServiceError(R.string.nr_mode_service_unavailable);
            }
        }

        @Override
        public void onStop() {
            mStarted = false;
            mSubscriptionManager.removeOnSubscriptionsChangedListener(
                    mSubscriptionsChangedListener);

            if (mExtTelephonyManager.isServiceConnected() && mClient != null) {
                mExtTelephonyManager.unRegisterCallback(mExtPhoneCallback);
            }
            mClient = null;
            mExtTelephonyManager.disconnectService(mServiceCallback);
            super.onStop();
        }

        @Override
        public void onResume() {
            super.onResume();
            requireActivity().setTitle(R.string.nr_mode_title);
        }

        private void handleServiceConnected() {
            if (!mStarted) {
                return;
            }

            mClient = mExtTelephonyManager.registerCallback(
                    requireContext().getPackageName(), mExtPhoneCallback);
            if (mClient == null) {
                showServiceError(R.string.nr_mode_registration_failed);
                return;
            }
            refreshSubscriptions();
        }

        private void handleServiceDisconnected() {
            mClient = null;
            if (mStarted) {
                showServiceError(R.string.nr_mode_service_unavailable);
            }
        }

        private void refreshSubscriptions() {
            mCategory.removeAll();
            mPreferences.clear();
            mPendingConfigs.clear();

            int activeSimCount = 0;
            int modemCount = mTelephonyManager.getActiveModemCount();
            for (int slotId = 0; slotId < modemCount; slotId++) {
                SubscriptionInfo info = mSubscriptionManager
                        .getActiveSubscriptionInfoForSimSlotIndex(slotId);
                if (info == null) {
                    continue;
                }

                ListPreference preference = new ListPreference(requireContext());
                preference.setKey("nr_mode_slot_" + slotId);
                preference.setPersistent(false);
                preference.setTitle(getSimTitle(slotId, info));
                preference.setDialogTitle(R.string.nr_mode_dialog_title);
                preference.setEntries(R.array.nr_mode_entries);
                preference.setEntryValues(R.array.nr_mode_values);
                preference.setSummary(R.string.nr_mode_querying);
                preference.setEnabled(false);
                final int targetSlot = slotId;
                preference.setOnPreferenceChangeListener((unused, newValue) -> {
                    setNrConfig(targetSlot, Integer.parseInt((String) newValue));
                    return false;
                });

                mCategory.addPreference(preference);
                mPreferences.put(slotId, preference);
                activeSimCount++;
            }

            if (activeSimCount == 0) {
                mStatusPreference.setVisible(true);
                mStatusPreference.setSummary(R.string.nr_mode_no_sim);
                return;
            }

            mStatusPreference.setVisible(false);
            for (int i = 0; i < mPreferences.size(); i++) {
                queryNrConfig(mPreferences.keyAt(i));
            }
        }

        private CharSequence getSimTitle(int slotId, SubscriptionInfo info) {
            CharSequence displayName = info.getDisplayName();
            if (TextUtils.isEmpty(displayName)) {
                return getString(R.string.nr_mode_sim_title_no_name, slotId + 1);
            }
            return getString(R.string.nr_mode_sim_title, slotId + 1, displayName);
        }

        private void queryNrConfig(int slotId) {
            ListPreference preference = mPreferences.get(slotId);
            if (preference == null || mClient == null) {
                return;
            }

            preference.setEnabled(false);
            preference.setSummary(R.string.nr_mode_querying);
            Token token = mExtTelephonyManager.queryNrConfig(slotId, mClient);
            if (token == null) {
                showRequestError(preference);
            }
        }

        private void setNrConfig(int slotId, int value) {
            ListPreference preference = mPreferences.get(slotId);
            if (preference == null || mClient == null) {
                return;
            }

            preference.setEnabled(false);
            preference.setSummary(R.string.nr_mode_applying);
            mPendingConfigs.put(slotId, value);
            Token token = mExtTelephonyManager.setNrConfig(
                    slotId, new NrConfig(value), mClient);
            if (token == null) {
                mPendingConfigs.delete(slotId);
                showRequestError(preference);
            }
        }

        private void handleSetNrConfig(int slotId, Status status) {
            if (!mStarted) {
                return;
            }

            ListPreference preference = mPreferences.get(slotId);
            if (preference == null) {
                return;
            }

            if (isSuccess(status)) {
                int value = mPendingConfigs.get(slotId, INVALID_NR_CONFIG);
                if (value != INVALID_NR_CONFIG) {
                    updatePreference(preference, value);
                }
                Toast.makeText(requireContext(),
                        getString(R.string.nr_mode_changed, slotId + 1),
                        Toast.LENGTH_SHORT).show();
                mPendingConfigs.delete(slotId);
                queryNrConfig(slotId);
            } else {
                mPendingConfigs.delete(slotId);
                showRequestError(preference);
            }
        }

        private void handleNrConfigStatus(int slotId, Status status, int value) {
            if (!mStarted) {
                return;
            }

            ListPreference preference = mPreferences.get(slotId);
            if (preference == null) {
                return;
            }

            if (isSuccess(status) && value != INVALID_NR_CONFIG) {
                updatePreference(preference, value);
            } else {
                showRequestError(preference);
            }
        }

        private void updatePreference(ListPreference preference, int value) {
            String stringValue = Integer.toString(value);
            int index = preference.findIndexOfValue(stringValue);
            preference.setValue(stringValue);
            preference.setEnabled(true);
            if (index >= 0) {
                preference.setSummary(preference.getEntries()[index]);
            } else {
                preference.setSummary(getString(R.string.nr_mode_unknown, value));
            }
        }

        private void showServiceError(int messageResId) {
            mStatusPreference.setVisible(true);
            mStatusPreference.setSummary(messageResId);
            for (int i = 0; i < mPreferences.size(); i++) {
                mPreferences.valueAt(i).setEnabled(false);
            }
        }

        private void showRequestError(ListPreference preference) {
            Log.e(TAG, "QTI NR configuration request failed for " + preference.getKey());
            preference.setEnabled(true);
            preference.setSummary(R.string.nr_mode_request_failed);
        }

        private static boolean isSuccess(Status status) {
            return status != null && status.get() == Status.SUCCESS;
        }
    }
}
