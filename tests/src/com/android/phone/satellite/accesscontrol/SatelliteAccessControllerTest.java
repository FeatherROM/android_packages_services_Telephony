/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone.satellite.accesscontrol;

import static android.telephony.satellite.SatelliteManager.KEY_SATELLITE_COMMUNICATION_ALLOWED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_LOCATION_NOT_AVAILABLE;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_MODEM_ERROR;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_SUCCESS;

import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.EVENT_CONFIG_DATA_UPDATED;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.GOOGLE_US_SAN_SAT_S2_FILE_NAME;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.DropBoxManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ResultReceiver;
import android.telecom.TelecomManager;
import android.telephony.satellite.SatelliteManager;
import android.testing.TestableLooper;
import android.util.Log;
import android.util.Pair;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyCountryDetector;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.satellite.SatelliteConfig;
import com.android.internal.telephony.satellite.SatelliteConfigParser;
import com.android.internal.telephony.satellite.SatelliteController;
import com.android.internal.telephony.satellite.SatelliteModemInterface;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Unit test for {@link SatelliteAccessController} */
@RunWith(AndroidJUnit4.class)
public class SatelliteAccessControllerTest {
    private static final String TAG = "SatelliteAccessControllerTest";
    private static final String[] TEST_SATELLITE_COUNTRY_CODES = {"US", "CA", "UK"};
    private static final String TEST_SATELLITE_S2_FILE = "sat_s2_file.dat";
    private static final boolean TEST_SATELLITE_ALLOW = true;
    private static final int TEST_LOCATION_FRESH_DURATION_SECONDS = 10;
    private static final long TEST_LOCATION_FRESH_DURATION_NANOS =
            TimeUnit.SECONDS.toNanos(TEST_LOCATION_FRESH_DURATION_SECONDS);
    private static final long TIMEOUT = 500;
    private static final List<String> EMPTY_STRING_LIST = new ArrayList<>();
    private static final List<String> LOCATION_PROVIDERS =
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER);
    private static final int SUB_ID = 0;

    @Mock
    private LocationManager mMockLocationManager;
    @Mock
    private TelecomManager mMockTelecomManager;
    @Mock
    private TelephonyCountryDetector mMockCountryDetector;
    @Mock
    private SatelliteController mMockSatelliteController;
    @Mock
    private SatelliteModemInterface mMockSatelliteModemInterface;
    @Mock
    private DropBoxManager mMockDropBoxManager;
    @Mock
    private Context mMockContext;
    @Mock
    private Phone mMockPhone;
    @Mock
    private Phone mMockPhone2;
    @Mock
    private FeatureFlags mMockFeatureFlags;
    @Mock
    private Resources mMockResources;
    @Mock
    private SatelliteOnDeviceAccessController mMockSatelliteOnDeviceAccessController;
    @Mock
    Location mMockLocation0;
    @Mock
    Location mMockLocation1;
    @Mock
    File mMockSatS2File;
    @Mock
    SharedPreferences mMockSharedPreferences;
    @Mock
    private SharedPreferences.Editor mMockSharedPreferencesEditor;
    @Mock
    private Map<SatelliteOnDeviceAccessController.LocationToken, Boolean>
            mMockCachedAccessRestrictionMap;

    private Looper mLooper;
    private TestableLooper mTestableLooper;
    private Phone[] mPhones;
    private TestSatelliteAccessController mSatelliteAccessControllerUT;

    @Captor
    private ArgumentCaptor<CancellationSignal> mLocationRequestCancellationSignalCaptor;
    @Captor
    private ArgumentCaptor<Consumer<Location>> mLocationRequestConsumerCaptor;
    @Captor
    private ArgumentCaptor<Handler> mConfigUpdateHandlerCaptor;
    @Captor
    private ArgumentCaptor<Integer> mConfigUpdateIntCaptor;
    @Captor
    private ArgumentCaptor<Object> mConfigUpdateObjectCaptor;
    private boolean mQueriedSatelliteAllowed = false;
    private int mQueriedSatelliteAllowedResultCode = SATELLITE_RESULT_SUCCESS;
    private Semaphore mSatelliteAllowedSemaphore = new Semaphore(0);
    private ResultReceiver mSatelliteAllowedReceiver = new ResultReceiver(null) {
        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            mQueriedSatelliteAllowedResultCode = resultCode;
            if (resultCode == SATELLITE_RESULT_SUCCESS) {
                if (resultData.containsKey(KEY_SATELLITE_COMMUNICATION_ALLOWED)) {
                    mQueriedSatelliteAllowed = resultData.getBoolean(
                            KEY_SATELLITE_COMMUNICATION_ALLOWED);
                } else {
                    logd("KEY_SATELLITE_COMMUNICATION_ALLOWED does not exist.");
                    mQueriedSatelliteAllowed = false;
                }
            } else {
                logd("mSatelliteAllowedReceiver: resultCode=" + resultCode);
                mQueriedSatelliteAllowed = false;
            }
            try {
                mSatelliteAllowedSemaphore.release();
            } catch (Exception ex) {
                fail("mSatelliteAllowedReceiver: Got exception in releasing semaphore, ex=" + ex);
            }
        }
    };

    @Before
    public void setUp() throws Exception {
        logd("setUp");
        MockitoAnnotations.initMocks(this);

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        HandlerThread handlerThread = new HandlerThread("SatelliteAccessControllerTest");
        handlerThread.start();
        mLooper = handlerThread.getLooper();
        mTestableLooper = new TestableLooper(mLooper);
        when(mMockContext.getSystemServiceName(LocationManager.class)).thenReturn(
                Context.LOCATION_SERVICE);
        when(mMockContext.getSystemServiceName(TelecomManager.class)).thenReturn(
                Context.TELECOM_SERVICE);
        when(mMockContext.getSystemServiceName(DropBoxManager.class)).thenReturn(
                Context.DROPBOX_SERVICE);
        when(mMockContext.getSystemService(LocationManager.class)).thenReturn(
                mMockLocationManager);
        when(mMockContext.getSystemService(TelecomManager.class)).thenReturn(
                mMockTelecomManager);
        when(mMockContext.getSystemService(DropBoxManager.class)).thenReturn(
                mMockDropBoxManager);
        mPhones = new Phone[]{mMockPhone, mMockPhone2};
        replaceInstance(PhoneFactory.class, "sPhones", null, mPhones);
        replaceInstance(SatelliteController.class, "sInstance", null,
                mMockSatelliteController);
        replaceInstance(SatelliteModemInterface.class, "sInstance", null,
                mMockSatelliteModemInterface);
        replaceInstance(TelephonyCountryDetector.class, "sInstance", null,
                mMockCountryDetector);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getStringArray(
                com.android.internal.R.array.config_oem_enabled_satellite_country_codes))
                .thenReturn(TEST_SATELLITE_COUNTRY_CODES);
        when(mMockResources.getBoolean(
                com.android.internal.R.bool.config_oem_enabled_satellite_access_allow))
                .thenReturn(TEST_SATELLITE_ALLOW);
        when(mMockResources.getString(
                com.android.internal.R.string.config_oem_enabled_satellite_s2cell_file))
                .thenReturn(TEST_SATELLITE_S2_FILE);
        when(mMockResources.getInteger(com.android.internal.R.integer
                .config_oem_enabled_satellite_location_fresh_duration))
                .thenReturn(TEST_LOCATION_FRESH_DURATION_SECONDS);

        when(mMockLocationManager.getProviders(true)).thenReturn(LOCATION_PROVIDERS);
        when(mMockLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                .thenReturn(mMockLocation0);
        when(mMockLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
                .thenReturn(mMockLocation1);
        when(mMockLocation0.getLatitude()).thenReturn(0.0);
        when(mMockLocation0.getLongitude()).thenReturn(0.0);
        when(mMockLocation1.getLatitude()).thenReturn(1.0);
        when(mMockLocation1.getLongitude()).thenReturn(1.0);
        when(mMockSatelliteOnDeviceAccessController.isSatCommunicationAllowedAtLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class))).thenReturn(true);

        when(mMockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(
                mMockSharedPreferences);
        when(mMockSharedPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        when(mMockSharedPreferences.getStringSet(anyString(), any()))
                .thenReturn(Set.of(TEST_SATELLITE_COUNTRY_CODES));
        doReturn(mMockSharedPreferencesEditor).when(mMockSharedPreferences).edit();
        doReturn(mMockSharedPreferencesEditor).when(mMockSharedPreferencesEditor)
                .putBoolean(anyString(), anyBoolean());
        doReturn(mMockSharedPreferencesEditor).when(mMockSharedPreferencesEditor)
                .putStringSet(anyString(), any());

        when(mMockFeatureFlags.satellitePersistentLogging()).thenReturn(true);

        mSatelliteAccessControllerUT = new TestSatelliteAccessController(mMockContext,
                mMockFeatureFlags, mLooper, mMockLocationManager, mMockTelecomManager,
                mMockSatelliteOnDeviceAccessController, mMockSatS2File);
        mTestableLooper.processAllMessages();
    }

    @After
    public void tearDown() throws Exception {
        logd("tearDown");
        if (mTestableLooper != null) {
            mTestableLooper.destroy();
            mTestableLooper = null;
        }

        if (mLooper != null) {
            mLooper.quit();
            mLooper = null;
        }
    }

    @Test
    public void testGetInstance() {
        SatelliteAccessController inst1 =
                SatelliteAccessController.getOrCreateInstance(mMockContext, mMockFeatureFlags);
        SatelliteAccessController inst2 =
                SatelliteAccessController.getOrCreateInstance(mMockContext, mMockFeatureFlags);
        assertEquals(inst1, inst2);
    }

    @Test
    public void testRequestIsSatelliteCommunicationAllowedForCurrentLocation() throws Exception {
        // OEM-enabled satellite is not supported
        when(mMockFeatureFlags.oemEnabledSatelliteFlag()).thenReturn(false);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                SUB_ID, mSatelliteAllowedReceiver);
        mTestableLooper.processAllMessages();
        assertTrue(waitForRequestIsSatelliteAllowedForCurrentLocationResult(
                mSatelliteAllowedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_REQUEST_NOT_SUPPORTED, mQueriedSatelliteAllowedResultCode);

        // OEM-enabled satellite is supported
        when(mMockFeatureFlags.oemEnabledSatelliteFlag()).thenReturn(true);

        // Satellite is not supported
        setUpResponseForRequestIsSatelliteSupported(false, SATELLITE_RESULT_SUCCESS);
        clearAllInvocations();
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                SUB_ID, mSatelliteAllowedReceiver);
        mTestableLooper.processAllMessages();
        assertTrue(waitForRequestIsSatelliteAllowedForCurrentLocationResult(
                mSatelliteAllowedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_SUCCESS, mQueriedSatelliteAllowedResultCode);
        assertFalse(mQueriedSatelliteAllowed);

        // Failed to query whether satellite is supported or not
        setUpResponseForRequestIsSatelliteSupported(false, SATELLITE_RESULT_MODEM_ERROR);
        clearAllInvocations();
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                SUB_ID, mSatelliteAllowedReceiver);
        mTestableLooper.processAllMessages();
        assertTrue(waitForRequestIsSatelliteAllowedForCurrentLocationResult(
                mSatelliteAllowedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_MODEM_ERROR, mQueriedSatelliteAllowedResultCode);

        // Network country codes are not available. TelecomManager.isInEmergencyCall() returns true.
        // On-device access controller will be used. Last known location is available and fresh.
        clearAllInvocations();
        setUpResponseForRequestIsSatelliteSupported(true, SATELLITE_RESULT_SUCCESS);
        setUpResponseForRequestIsSatelliteProvisioned(true, SATELLITE_RESULT_SUCCESS);
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(EMPTY_STRING_LIST);
        when(mMockTelecomManager.isInEmergencyCall()).thenReturn(true);
        mSatelliteAccessControllerUT.elapsedRealtimeNanos = TEST_LOCATION_FRESH_DURATION_NANOS + 1;
        when(mMockLocation0.getElapsedRealtimeNanos()).thenReturn(2L);
        when(mMockLocation1.getElapsedRealtimeNanos()).thenReturn(0L);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                SUB_ID, mSatelliteAllowedReceiver);
        mTestableLooper.processAllMessages();
        assertTrue(
                mSatelliteAccessControllerUT.isKeepOnDeviceAccessControllerResourcesTimerStarted());
        verify(mMockSatelliteOnDeviceAccessController).isSatCommunicationAllowedAtLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class));
        assertTrue(waitForRequestIsSatelliteAllowedForCurrentLocationResult(
                mSatelliteAllowedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_SUCCESS, mQueriedSatelliteAllowedResultCode);
        assertTrue(mQueriedSatelliteAllowed);

        // Move time forward and verify resources are cleaned up
        clearAllInvocations();
        mTestableLooper.moveTimeForward(mSatelliteAccessControllerUT
                .getKeepOnDeviceAccessControllerResourcesTimeoutMillis());
        mTestableLooper.processAllMessages();
        assertFalse(
                mSatelliteAccessControllerUT.isKeepOnDeviceAccessControllerResourcesTimerStarted());
        assertTrue(mSatelliteAccessControllerUT.isSatelliteOnDeviceAccessControllerReset());
        verify(mMockSatelliteOnDeviceAccessController).close();

        // Restore SatelliteOnDeviceAccessController for next verification
        mSatelliteAccessControllerUT.setSatelliteOnDeviceAccessController(
                mMockSatelliteOnDeviceAccessController);

        // Network country codes are not available. TelecomManager.isInEmergencyCall() returns
        // false. Phone0 is in ECM. On-device access controller will be used. Last known location is
        // not fresh.
        clearAllInvocations();
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(EMPTY_STRING_LIST);
        when(mMockTelecomManager.isInEmergencyCall()).thenReturn(false);
        when(mMockPhone.isInEcm()).thenReturn(true);
        mSatelliteAccessControllerUT.elapsedRealtimeNanos = TEST_LOCATION_FRESH_DURATION_NANOS + 1;
        when(mMockLocation0.getElapsedRealtimeNanos()).thenReturn(0L);
        when(mMockLocation1.getElapsedRealtimeNanos()).thenReturn(0L);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                SUB_ID, mSatelliteAllowedReceiver);
        mTestableLooper.processAllMessages();
        assertFalse(
                mSatelliteAccessControllerUT.isKeepOnDeviceAccessControllerResourcesTimerStarted());
        verify(mMockLocationManager).getCurrentLocation(eq(LocationManager.GPS_PROVIDER),
                any(LocationRequest.class), mLocationRequestCancellationSignalCaptor.capture(),
                any(Executor.class), mLocationRequestConsumerCaptor.capture());
        assertTrue(mSatelliteAccessControllerUT.isWaitForCurrentLocationTimerStarted());
        sendLocationRequestResult(mMockLocation0);
        assertFalse(mSatelliteAccessControllerUT.isWaitForCurrentLocationTimerStarted());
        // The LocationToken should be already in the cache
        verify(mMockSatelliteOnDeviceAccessController, never()).isSatCommunicationAllowedAtLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class));
        assertTrue(waitForRequestIsSatelliteAllowedForCurrentLocationResult(
                mSatelliteAllowedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_SUCCESS, mQueriedSatelliteAllowedResultCode);
        assertTrue(mQueriedSatelliteAllowed);

        // Timed out to wait for current location. No cached allowed state.
        clearAllInvocations();
        mSatelliteAccessControllerUT.setIsSatelliteCommunicationAllowedForCurrentLocationCache(
                "cache_clear_and_not_allowed");
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(EMPTY_STRING_LIST);
        when(mMockTelecomManager.isInEmergencyCall()).thenReturn(false);
        when(mMockPhone.isInEcm()).thenReturn(true);
        mSatelliteAccessControllerUT.elapsedRealtimeNanos = TEST_LOCATION_FRESH_DURATION_NANOS + 1;
        when(mMockLocation0.getElapsedRealtimeNanos()).thenReturn(0L);
        when(mMockLocation1.getElapsedRealtimeNanos()).thenReturn(0L);
        when(mMockCountryDetector.getCachedLocationCountryIsoInfo()).thenReturn(new Pair<>("", 0L));
        when(mMockCountryDetector.getCachedNetworkCountryIsoInfo()).thenReturn(new HashMap<>());
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                SUB_ID, mSatelliteAllowedReceiver);
        mTestableLooper.processAllMessages();
        assertFalse(
                mSatelliteAccessControllerUT.isKeepOnDeviceAccessControllerResourcesTimerStarted());
        verify(mMockLocationManager).getCurrentLocation(anyString(), any(LocationRequest.class),
                any(CancellationSignal.class), any(Executor.class), any(Consumer.class));
        assertTrue(mSatelliteAccessControllerUT.isWaitForCurrentLocationTimerStarted());
        // Timed out
        mTestableLooper.moveTimeForward(
                mSatelliteAccessControllerUT.getWaitForCurrentLocationTimeoutMillis());
        mTestableLooper.processAllMessages();
        assertFalse(mSatelliteAccessControllerUT.isWaitForCurrentLocationTimerStarted());
        verify(mMockSatelliteOnDeviceAccessController, never()).isSatCommunicationAllowedAtLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class));
        assertTrue(waitForRequestIsSatelliteAllowedForCurrentLocationResult(
                mSatelliteAllowedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_LOCATION_NOT_AVAILABLE, mQueriedSatelliteAllowedResultCode);

        // Network country codes are not available. TelecomManager.isInEmergencyCall() returns
        // false. No phone is in ECM. Last known location is not fresh. Cached country codes should
        // be used for verifying satellite allow. No cached country codes are available.
        clearAllInvocations();
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(EMPTY_STRING_LIST);
        when(mMockCountryDetector.getCachedLocationCountryIsoInfo()).thenReturn(new Pair<>("", 0L));
        when(mMockCountryDetector.getCachedNetworkCountryIsoInfo()).thenReturn(new HashMap<>());
        when(mMockTelecomManager.isInEmergencyCall()).thenReturn(false);
        when(mMockPhone.isInEcm()).thenReturn(false);
        when(mMockPhone2.isInEcm()).thenReturn(false);
        mSatelliteAccessControllerUT.elapsedRealtimeNanos = TEST_LOCATION_FRESH_DURATION_NANOS + 1;
        when(mMockLocation0.getElapsedRealtimeNanos()).thenReturn(0L);
        when(mMockLocation1.getElapsedRealtimeNanos()).thenReturn(0L);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                SUB_ID, mSatelliteAllowedReceiver);
        mTestableLooper.processAllMessages();
        verify(mMockLocationManager, never()).getCurrentLocation(anyString(),
                any(LocationRequest.class), any(CancellationSignal.class), any(Executor.class),
                any(Consumer.class));
        verify(mMockSatelliteOnDeviceAccessController, never()).isSatCommunicationAllowedAtLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class));
        assertTrue(waitForRequestIsSatelliteAllowedForCurrentLocationResult(
                mSatelliteAllowedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_SUCCESS, mQueriedSatelliteAllowedResultCode);
        assertFalse(mQueriedSatelliteAllowed);
    }

    @Test
    public void testUpdateSatelliteConfigData() throws Exception {
        verify(mMockSatelliteController).registerForConfigUpdateChanged(
                mConfigUpdateHandlerCaptor.capture(), mConfigUpdateIntCaptor.capture(),
                mConfigUpdateObjectCaptor.capture());

        assertSame(mConfigUpdateHandlerCaptor.getValue(), mSatelliteAccessControllerUT);
        assertSame(mConfigUpdateIntCaptor.getValue(), EVENT_CONFIG_DATA_UPDATED);
        assertSame(mConfigUpdateObjectCaptor.getValue(), mMockContext);

        replaceInstance(SatelliteAccessController.class, "mCachedAccessRestrictionMap",
                mSatelliteAccessControllerUT, mMockCachedAccessRestrictionMap);

        // These APIs are executed during loadRemoteConfigs
        verify(mMockSharedPreferences, times(1)).getStringSet(anyString(), any());
        verify(mMockSharedPreferences, times(1)).getBoolean(anyString(), anyBoolean());

        // satelliteConfig is null
        SatelliteConfigParser spyConfigParser =
                spy(new SatelliteConfigParser("test".getBytes()));
        doReturn(spyConfigParser).when(mMockSatelliteController).getSatelliteConfigParser();
        assertNull(spyConfigParser.getConfig());

        sendConfigUpdateChangedEvent(mMockContext);
        verify(mMockSharedPreferences, never()).edit();
        verify(mMockCachedAccessRestrictionMap, never()).clear();

        // satelliteConfig has invalid country codes
        SatelliteConfig mockConfig = mock(SatelliteConfig.class);
        doReturn(List.of("USA", "JAP")).when(mockConfig).getDeviceSatelliteCountryCodes();
        doReturn(mockConfig).when(mMockSatelliteController).getSatelliteConfig();
        doReturn(false).when(mockConfig).isSatelliteDataForAllowedRegion();

        sendConfigUpdateChangedEvent(mMockContext);
        verify(mMockSharedPreferences, never()).edit();
        verify(mMockCachedAccessRestrictionMap, never()).clear();

        // satelliteConfig does not have is_allow_access_control data
        doReturn(List.of(TEST_SATELLITE_COUNTRY_CODES))
                .when(mockConfig).getDeviceSatelliteCountryCodes();
        doReturn(null).when(mockConfig).isSatelliteDataForAllowedRegion();

        sendConfigUpdateChangedEvent(mMockContext);
        verify(mMockSharedPreferences, never()).edit();
        verify(mMockCachedAccessRestrictionMap, never()).clear();

        // satelliteConfig doesn't have S2CellFile
        File mockFile = mock(File.class);
        doReturn(false).when(mockFile).exists();
        doReturn(List.of(TEST_SATELLITE_COUNTRY_CODES))
                .when(mockConfig).getDeviceSatelliteCountryCodes();
        doReturn(true).when(mockConfig).isSatelliteDataForAllowedRegion();
        doReturn(mockFile).when(mockConfig).getSatelliteS2CellFile(mMockContext);

        sendConfigUpdateChangedEvent(mMockContext);
        verify(mMockSharedPreferences, never()).edit();
        verify(mMockCachedAccessRestrictionMap, never()).clear();

        // satelliteConfig has valid data
        doReturn(mockConfig).when(mMockSatelliteController).getSatelliteConfig();
        File testS2File = mSatelliteAccessControllerUT
                .getTestSatelliteS2File(GOOGLE_US_SAN_SAT_S2_FILE_NAME);
        doReturn(List.of(TEST_SATELLITE_COUNTRY_CODES))
                .when(mockConfig).getDeviceSatelliteCountryCodes();
        doReturn(true).when(mockConfig).isSatelliteDataForAllowedRegion();
        doReturn(testS2File).when(mockConfig).getSatelliteS2CellFile(mMockContext);

        sendConfigUpdateChangedEvent(mMockContext);
        verify(mMockSharedPreferences, times(2)).edit();
        verify(mMockCachedAccessRestrictionMap, times(1)).clear();
    }

    private void sendConfigUpdateChangedEvent(Context context) {
        Message msg = mSatelliteAccessControllerUT.obtainMessage(EVENT_CONFIG_DATA_UPDATED);
        msg.obj = new AsyncResult(context, SATELLITE_RESULT_SUCCESS, null);
        msg.sendToTarget();
        mTestableLooper.processAllMessages();
    }

    private void clearAllInvocations() {
        clearInvocations(mMockSatelliteController);
        clearInvocations(mMockSatelliteOnDeviceAccessController);
        clearInvocations(mMockLocationManager);
        clearInvocations(mMockCountryDetector);
    }

    private void verifyCountryDetectorApisCalled() {
        verify(mMockCountryDetector).getCurrentNetworkCountryIso();
        verify(mMockCountryDetector).getCachedLocationCountryIsoInfo();
        verify(mMockCountryDetector).getCachedLocationCountryIsoInfo();
    }

    private boolean waitForRequestIsSatelliteAllowedForCurrentLocationResult(Semaphore semaphore,
            int expectedNumberOfEvents) {
        for (int i = 0; i < expectedNumberOfEvents; i++) {
            try {
                if (!semaphore.tryAcquire(TIMEOUT, TimeUnit.MILLISECONDS)) {
                    logd("Timeout to receive "
                            + "requestIsCommunicationAllowedForCurrentLocation()"
                            + " callback");
                    return false;
                }
            } catch (Exception ex) {
                logd("waitForRequestIsSatelliteSupportedResult: Got exception=" + ex);
                return false;
            }
        }
        return true;
    }

    private void sendLocationRequestResult(Location location) {
        mLocationRequestConsumerCaptor.getValue().accept(location);
        mTestableLooper.processAllMessages();
    }

    private void setUpResponseForRequestIsSatelliteSupported(
            boolean isSatelliteSupported, @SatelliteManager.SatelliteResult int error) {
        doAnswer(invocation -> {
            ResultReceiver resultReceiver = invocation.getArgument(1);
            if (error == SATELLITE_RESULT_SUCCESS) {
                Bundle bundle = new Bundle();
                bundle.putBoolean(SatelliteManager.KEY_SATELLITE_SUPPORTED, isSatelliteSupported);
                resultReceiver.send(error, bundle);
            } else {
                resultReceiver.send(error, Bundle.EMPTY);
            }
            return null;
        }).when(mMockSatelliteController).requestIsSatelliteSupported(anyInt(),
                any(ResultReceiver.class));
    }

    private void setUpResponseForRequestIsSatelliteProvisioned(
            boolean isSatelliteProvisioned, @SatelliteManager.SatelliteResult int error) {
        doAnswer(invocation -> {
            ResultReceiver resultReceiver = invocation.getArgument(1);
            if (error == SATELLITE_RESULT_SUCCESS) {
                Bundle bundle = new Bundle();
                bundle.putBoolean(SatelliteManager.KEY_SATELLITE_PROVISIONED,
                        isSatelliteProvisioned);
                resultReceiver.send(error, bundle);
            } else {
                resultReceiver.send(error, Bundle.EMPTY);
            }
            return null;
        }).when(mMockSatelliteController).requestIsSatelliteProvisioned(anyInt(),
                any(ResultReceiver.class));
    }

    @SafeVarargs
    private static <E> List<E> listOf(E... values) {
        return Arrays.asList(values);
    }

    private static void logd(String message) {
        Log.d(TAG, message);
    }

    private static void replaceInstance(final Class c,
            final String instanceName, final Object obj, final Object newValue) throws Exception {
        Field field = c.getDeclaredField(instanceName);
        field.setAccessible(true);
        field.set(obj, newValue);
    }

    private static class TestSatelliteAccessController extends SatelliteAccessController {
        public long elapsedRealtimeNanos = 0;

        /**
         * Create a SatelliteAccessController instance.
         *
         * @param context                           The context associated with the
         *                                          {@link SatelliteAccessController} instance.
         * @param featureFlags                      The FeatureFlags that are supported.
         * @param looper                            The Looper to run the SatelliteAccessController
         *                                          on.
         * @param locationManager                   The LocationManager for querying current
         *                                          location of the
         *                                          device.
         * @param satelliteOnDeviceAccessController The on-device satellite access controller
         *                                          instance.
         */
        protected TestSatelliteAccessController(Context context, FeatureFlags featureFlags,
                Looper looper, LocationManager locationManager, TelecomManager telecomManager,
                SatelliteOnDeviceAccessController satelliteOnDeviceAccessController,
                File s2CellFile) {
            super(context, featureFlags, looper, locationManager, telecomManager,
                    satelliteOnDeviceAccessController, s2CellFile);
        }

        @Override
        protected long getElapsedRealtimeNanos() {
            return elapsedRealtimeNanos;
        }

        public boolean isKeepOnDeviceAccessControllerResourcesTimerStarted() {
            return hasMessages(EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT);
        }

        public boolean isSatelliteOnDeviceAccessControllerReset() {
            synchronized (mLock) {
                return (mSatelliteOnDeviceAccessController == null);
            }
        }

        public void setSatelliteOnDeviceAccessController(
                @Nullable SatelliteOnDeviceAccessController accessController) {
            synchronized (mLock) {
                mSatelliteOnDeviceAccessController = accessController;
            }
        }

        public long getKeepOnDeviceAccessControllerResourcesTimeoutMillis() {
            return KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT_MILLIS;
        }

        public long getWaitForCurrentLocationTimeoutMillis() {
            return WAIT_FOR_CURRENT_LOCATION_TIMEOUT_MILLIS;
        }

        public boolean isWaitForCurrentLocationTimerStarted() {
            return hasMessages(EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT);
        }
    }
}
