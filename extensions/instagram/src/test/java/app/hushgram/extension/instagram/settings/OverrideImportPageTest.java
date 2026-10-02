/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.preference.TwoStatePreference;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.instagram.misc.OverrideExchange;
import app.hushgram.extension.instagram.misc.OverrideImportTest;
import app.hushgram.extension.instagram.misc.OverrideImportTest.NativeTable;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = OverrideImportTest.NativeTable.class)
@SuppressWarnings("deprecation")
public class OverrideImportPageTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private static final Uri DOCUMENT = Uri.parse("content://override-documents/import.json");
    private ActivityController<OverrideDocumentsTest.HostActivity> host;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        NativeTable.reset();
        clearFeedback();
        OverrideDocumentsTest.HostActivity.noPicker = false;
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        OverrideImportTest.install();
    }

    @After public void close() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        if (host != null) host.close();
        PatchFamily.inBuildForTests = null;
        Settings.ALLOW_OVERRIDE_IMPORT.resetToDefault();
        clearFeedback();
    }

    private static void clearFeedback() {
        HushgramPreferenceFragment.overrideImportFeedback = null;
        HushgramPreferenceFragment.overrideRestoreFeedback = null;
        HushgramPreferenceFragment.overrideExportFeedback = null;
        HushgramPreferenceFragment.overrideValidationFeedback = null;
    }

    private void openHost() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.DEVELOPER_OPTIONS);
        host = Robolectric.buildActivity(OverrideDocumentsTest.HostActivity.class).setup();
        NativeTable.file = OverrideImportTest.store(host.get(), "mobileconfig");
        SettingsDialog dialog = new SettingsDialog();
        dialog.show(host.get().getFragmentManager(), SettingsEntry.DIALOG_TAG);
        host.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
    }

    private void result(ShadowActivity.IntentForResult picked, int status, Uri uri) throws Exception {
        Shadows.shadowOf(host.get()).receiveResult(picked.intent, status, uri == null ? null : new Intent().setData(uri));
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    private void click(String key) {
        Preference row = page.findPreference(key);
        assertTrue(key, row.isEnabled());
        row.getOnPreferenceClickListener().onPreferenceClick(row);
    }

    private byte[] changed() throws Exception {
        JSONObject file = new JSONObject(new String(OverrideExchange.export(OverrideExchange.capture(host.get())), StandardCharsets.UTF_8));
        file.getJSONObject("overrides").put("123:config", new JSONArray().put("0: enabled: false").put("1: limit: 5"));
        NativeTable.captures = 0;
        return file.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test public void importAndRestoreRowsExistOnlyWhileTheirSwitchIsOn() throws Exception {
        openHost();
        TwoStatePreference allow = (TwoStatePreference) page.findPreference(Settings.ALLOW_OVERRIDE_IMPORT.key);
        assertFalse(allow.isChecked());
        assertNull(page.findPreference("hushgram_import_overrides"));
        assertNull(page.findPreference("hushgram_restore_overrides"));
        allow.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(Settings.ALLOW_OVERRIDE_IMPORT.get());
        assertNotNull(page.findPreference("hushgram_import_overrides"));
        assertNotNull(page.findPreference("hushgram_restore_overrides"));
        page.searchSettings("restore previous");
        assertNotNull(page.getPreferenceScreen().findPreference("hushgram_restore_overrides"));
        page.searchSettings("");
        allow.setChecked(false);
        ShadowLooper.idleMainLooper();
        assertNull(page.findPreference("hushgram_import_overrides"));
        assertNull(page.findPreference("hushgram_restore_overrides"));
        assertEquals(0, NativeTable.captures);
    }

    @Test public void anImportFromThePickerAppliesAndRestorePutsTheSavedCopyBack() throws Exception {
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
        openHost();
        byte[] file = changed();
        Shadows.shadowOf(host.get().getContentResolver()).registerInputStream(DOCUMENT, new ByteArrayInputStream(file));
        click("hushgram_import_overrides");
        ShadowActivity.IntentForResult picked = Shadows.shadowOf(host.get()).getNextStartedActivityForResult();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picked.intent.getAction());
        assertEquals("application/json", picked.intent.getType());
        result(picked, Activity.RESULT_OK, DOCUMENT);
        assertEquals("Imported 1 override changes. Restart Instagram to apply them.", HushgramPreferenceFragment.overrideImportFeedback);
        assertTrue(new String(Files.readAllBytes(NativeTable.file.toPath()), StandardCharsets.UTF_8).contains("0: enabled: false"));
        assertEquals(1, NativeTable.writes);

        click("hushgram_restore_overrides");
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertEquals("Previous overrides restored. Restart Instagram to apply them.", HushgramPreferenceFragment.overrideRestoreFeedback);
        assertTrue(new String(Files.readAllBytes(NativeTable.file.toPath()), StandardCharsets.UTF_8).contains("0: enabled: true"));
        assertTrue(page.findPreference("hushgram_import_overrides").isEnabled());
        assertTrue(page.findPreference("hushgram_restore_overrides").isEnabled());
    }

    @Test public void cancelledMalformedAndUnsavedRequestsLeaveTheNativeStoreByteIdentical() throws Exception {
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
        openHost();
        byte[] before = Files.readAllBytes(NativeTable.file.toPath());
        click("hushgram_import_overrides");
        result(Shadows.shadowOf(host.get()).getNextStartedActivityForResult(), Activity.RESULT_CANCELED, null);
        assertEquals(0, NativeTable.captures);
        click("hushgram_import_overrides");
        result(Shadows.shadowOf(host.get()).getNextStartedActivityForResult(), Activity.RESULT_OK, Uri.parse("file:///unused.json"));
        assertEquals(0, NativeTable.captures);
        Shadows.shadowOf(host.get().getContentResolver()).registerInputStream(DOCUMENT,
                new ByteArrayInputStream("{\"project\":\"HushGram-overrides\"}".getBytes(StandardCharsets.UTF_8)));
        click("hushgram_import_overrides");
        result(Shadows.shadowOf(host.get()).getNextStartedActivityForResult(), Activity.RESULT_OK, DOCUMENT);
        assertTrue(HushgramPreferenceFragment.overrideImportFeedback.startsWith("Couldn't import overrides."));
        click("hushgram_restore_overrides");
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertTrue(HushgramPreferenceFragment.overrideRestoreFeedback.startsWith("Couldn't restore overrides."));
        assertArrayEquals(before, Files.readAllBytes(NativeTable.file.toPath()));
        assertEquals(0, NativeTable.writes);
        assertEquals(0, NativeTable.tableCalls);
    }
}
