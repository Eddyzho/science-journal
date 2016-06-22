/*
 *  Copyright 2016 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.google.android.apps.forscience.whistlepunk.devicemanager;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.google.android.apps.forscience.javalib.Success;
import com.google.android.apps.forscience.whistlepunk.AppSingleton;
import com.google.android.apps.forscience.whistlepunk.DataController;
import com.google.android.apps.forscience.whistlepunk.LoggingConsumer;
import com.google.android.apps.forscience.whistlepunk.PreferenceProgressCategory;
import com.google.android.apps.forscience.whistlepunk.R;
import com.google.android.apps.forscience.whistlepunk.WhistlePunkApplication;
import com.google.android.apps.forscience.whistlepunk.metadata.BleSensorSpec;
import com.google.android.apps.forscience.whistlepunk.metadata.ExternalSensorSpec;
import com.squareup.leakcanary.RefWatcher;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Searches for Bluetooth LE devices that are supported.
 */
public class ManageDevicesFragment extends PreferenceFragment {

    private static final String TAG = "ManageDevices";

    // STOPSHIP: set this to false.
    private static final boolean LOCAL_LOGD = true;

    private static final int MSG_STOP_SCANNING = 10001;
    private static final long SCAN_TIME_MS = TimeUnit.SECONDS.toMillis(10);

    /**
     * Boolean extra set on the preference when the device in question is paired.
     */
    private static final String EXTRA_KEY_PAIRED = "paired";

    /**
     * String extra set to say the type of external sensor
     */
    private static final String EXTRA_KEY_TYPE = "type";

    private static final String EXTRA_KEY_ID = "id";
    private static final String PREF_KEY_PAIRED_DEVICES = "paired_devices";
    private static final String PREF_KEY_AVAILABLE_DEVICES = "available_devices";

    private PreferenceCategory mPairedDevices;
    private PreferenceProgressCategory mAvailableDevices;
    private Menu mMainMenu;

    private boolean mScanning;
    private Handler mHandler;
    private DataController mDataController;

    private String mExperimentId;
    private Map<String, ExternalSensorDiscoverer> mDiscoverers = new HashMap<>();

    public ManageDevicesFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                if (msg.what == MSG_STOP_SCANNING) {
                    stopScanning();
                    return true;
                }
                return false;
            }
        });
        mDataController = AppSingleton.getInstance(getActivity()).getDataController();
        addPreferencesFromResource(R.xml.external_devices);
        mPairedDevices = new PreferenceProgressCategory(getActivity());
        mPairedDevices.setKey(PREF_KEY_PAIRED_DEVICES);
        mPairedDevices.setTitle(R.string.external_devices_paired);
        mPairedDevices.setOrder(0);
        mPairedDevices.setSelectable(false);
        mAvailableDevices = new PreferenceProgressCategory(getActivity());
        mAvailableDevices.setKey(PREF_KEY_AVAILABLE_DEVICES);
        mAvailableDevices.setTitle(R.string.external_devices_available);
        mAvailableDevices.setOrder(1);
        mAvailableDevices.setSelectable(false);
        getPreferenceScreen().addPreference(mAvailableDevices);
        setHasOptionsMenu(true);

        mDiscoverers.put(BleSensorSpec.TYPE, new NativeBleDiscoverer(this, getActivity()));
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshAfterLoad();
    }

    @Override
    public void onPause() {
        stopScanning();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        for (ExternalSensorDiscoverer discoverer : mDiscoverers.values()) {
            discoverer.onDestroy();
        }
        mDiscoverers.clear();
        mMainMenu = null;
        super.onDestroy();

        // Make sure we don't leak this fragment.
        RefWatcher watcher = WhistlePunkApplication.getRefWatcher(getActivity());
        watcher.watch(this);
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference.getKey() != null) {
            if (!preference.getExtras().getBoolean(EXTRA_KEY_PAIRED)) {
                addExternalSensorIfNecessary(preference);
            } else {
                showDeviceOptions(preference);
            }
            return true;
        }
        return false;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_manage_devices, menu);
        super.onCreateOptionsMenu(menu, inflater);
        mMainMenu = menu;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_refresh) {
            refresh();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void refreshAfterLoad() {
        mExperimentId = getArguments().getString(ManageDevicesActivity.EXTRA_EXPERIMENT_ID);
        refresh();
    }

    private void refresh() {
        stopScanning();
        mDataController.getExternalSensorsByExperiment(mExperimentId,
                new LoggingConsumer<Map<String, ExternalSensorSpec>>(TAG, "Load external sensors") {
                    @Override
                    public void success(Map<String, ExternalSensorSpec> sensors) {
                        if (getActivity() == null) {
                            return;
                        }

                        mPairedDevices.removeAll();
                        // Need to add paired devices header before adding specific prefs
                        boolean hasPairedDevicePref = getPreferenceScreen().findPreference(
                                PREF_KEY_PAIRED_DEVICES) != null;
                        if (sensors.size() == 0 && hasPairedDevicePref) {
                            getPreferenceScreen().removePreference(mPairedDevices);
                        } else if (sensors.size() > 0 && !hasPairedDevicePref) {
                            getPreferenceScreen().addPreference(mPairedDevices);
                        }
                        for (Map.Entry<String, ExternalSensorSpec> entry : sensors.entrySet()) {
                            ExternalSensorSpec sensor = entry.getValue();
                            Preference device = makePreference(sensor.getName(),
                                    sensor.getAddress(), sensor.getType(), true, getActivity());
                            device.getExtras().putString(EXTRA_KEY_ID, entry.getKey());
                            device.setWidgetLayoutResource(R.layout.preference_external_device);
                            updateSummary(device, sensor);
                            mPairedDevices.addPreference(device);
                            Preference availablePref = mAvailableDevices.findPreference(
                                    sensor.getAddress());
                            if (availablePref != null) {
                                mAvailableDevices.removePreference(availablePref);
                            }
                        }
                        if (canScan()) {
                            scanForDevices();
                        }
                    }
                });
    }

    private boolean canScan() {
        for (ExternalSensorDiscoverer discoverer : mDiscoverers.values()) {
            if (discoverer.canScan()) {
                // TODO: is the behavior we actually want?
                return true;
            }
        }
        return false;
    }

    public void startScanningInDiscoverers() {
        ExternalSensorDiscoverer.SensorPrefCallbacks
                scanCallbacks = new ExternalSensorDiscoverer.SensorPrefCallbacks() {
            @Override
            public boolean isSensorAlreadyKnown(String key) {
                return getDevicePreference(mPairedDevices, key) != null
                        || getDevicePreference(mAvailableDevices, key) != null;
            }

            @Override
            public void addAvailableSensorPreference(Preference newPref) {
                mAvailableDevices.addPreference(newPref);
            }
        };

        for (ExternalSensorDiscoverer discoverer : mDiscoverers.values()) {
            discoverer.startScanning(scanCallbacks);
        }
    }

    @NonNull
    public static Preference makePreference(String name, String address, String type,
            boolean paired, Context context) {
        Preference device = new Preference(context);
        device.setTitle(name);
        device.setKey(address);
        device.getExtras().putBoolean(EXTRA_KEY_PAIRED, paired);
        device.getExtras().putString(EXTRA_KEY_TYPE, type);
        return device;
    }

    private void scanForDevices() {
        if (!mScanning) {
            mScanning = true;
            mHandler.removeMessages(MSG_STOP_SCANNING);
            startScanningInDiscoverers();
            mHandler.sendEmptyMessageDelayed(MSG_STOP_SCANNING, SCAN_TIME_MS);
            setScanningUi(true);
        }
    }

    private void stopScanning() {
        if (mScanning) {
            mScanning = false;
            stopScanningInDiscoverers();
            mHandler.removeMessages(MSG_STOP_SCANNING);
            setScanningUi(false);
        }
    }

    private void stopScanningInDiscoverers() {
        for (ExternalSensorDiscoverer discoverer : mDiscoverers.values()) {
            discoverer.stopScanning();
        }
    }

    private void setScanningUi(boolean scanning) {
        mAvailableDevices.setProgress(scanning);
        if (mMainMenu != null) {
            MenuItem refresh = mMainMenu.findItem(R.id.action_refresh);
            refresh.setEnabled(!scanning);
            if (getActivity() != null) {
                refresh.getIcon().setAlpha(getActivity().getResources().getInteger(
                        scanning ? R.integer.icon_inactive_alpha : R.integer.icon_active_alpha));
            }
        }
    }

    private Preference getDevicePreference(PreferenceCategory category, String address) {
        for (int index = 0; index < category.getPreferenceCount(); ++index) {
            Preference device = category.getPreference(index);
            if (device.getKey().equals(address)) {
                return device;
            }
        }
        return null;
    }

    private void addExternalSensorIfNecessary(Preference preference) {
        preference.setEnabled(false);
        preference.setSummary(R.string.external_devices_pairing);
        // TODO: probably shouldn't finish in these cases, instead go into
        // sensor editing.

        String sensorType = preference.getExtras().getString(EXTRA_KEY_TYPE);
        ExternalSensorDiscoverer discoverer = mDiscoverers.get(sensorType);
        final ExternalSensorSpec sensor = discoverer.extractSensorSpec(preference);
        mDataController.addOrGetExternalSensor(sensor,
                new LoggingConsumer<String>(TAG, "ensure sensor") {
                    @Override
                    public void success(final String sensorId) {
                        mDataController.addSensorToExperiment(mExperimentId, sensorId,
                                new LoggingConsumer<Success>(TAG, "add sensor to experiment") {
                                    @Override
                                    public void success(Success value) {
                                        if (LOCAL_LOGD) {
                                            Log.d(TAG, "Added sensor to experiment " + mExperimentId);
                                        }
                                        reloadAppearancesAndShowOptions(sensor, sensorId);
                                    }
                                });
                    }
                });
    }

    private void reloadAppearancesAndShowOptions(final ExternalSensorSpec sensor,
            final String sensorId) {
        AppSingleton.getInstance(getActivity()).getSensorAppearanceProvider()
                .loadAppearances(new LoggingConsumer<Success>(TAG, "Load appearance") {
                    @Override
                    public void success(Success value) {
                        refresh();
                        showDeviceOptions(mExperimentId, sensor.getAddress(), sensorId);
                    }
                });
    }

    private void updateSummary(Preference preference, ExternalSensorSpec sensor) {
        preference.setSummary(sensor.getSensorAppearance().getNameResource());
    }

    private void showDeviceOptions(Preference preference) {
        // TODO: use a SettingsController subclass once it's fragmentized.
        showDeviceOptions(mExperimentId, preference.getKey(),
                preference.getExtras().getString(EXTRA_KEY_ID));
    }

    private void showDeviceOptions(String experimentId, String address, String sensorId) {
        DeviceOptionsDialog dialog = DeviceOptionsDialog.newInstance(experimentId, address,
                sensorId);
        dialog.show(getFragmentManager(), "edit_device");
    }
}