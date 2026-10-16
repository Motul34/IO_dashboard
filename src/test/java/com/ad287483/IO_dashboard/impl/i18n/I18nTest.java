package com.ad287483.IO_dashboard.impl.i18n;

import com.ad287483.IO_dashboard.impl.monitor.CommandAction;
import com.ad287483.IO_dashboard.impl.monitor.IoCommandBinding;
import com.ad287483.IO_dashboard.impl.monitor.IoConstants;
import com.ad287483.IO_dashboard.impl.monitor.IoStatusBinding;
import com.ad287483.IO_dashboard.impl.monitor.IoStatusCodeBinding;
import com.ad287483.IO_dashboard.impl.monitor.StatusCodeOutputAction;
import com.ad287483.IO_dashboard.impl.monitor.StatusOutputAction;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

public class I18nTest {

	private static final String[] ALL_KEYS = {
			"header.enable_service",
			"header.dashboard_checking",
			"header.dashboard_connected",
			"header.dashboard_disconnected",
			"header.primary_checking",
			"header.primary_connected",
			"header.primary_disconnected",
			"header.reconnect_btn",
			"tab.input",
			"tab.output_bool",
			"tab.output_code",
			"tab.log",
			"input.desc",
			"input.run_btn",
			"output_bool.desc",
			"output_code.header_codes",
			"output_code.header_handshake",
			"handshake.busy",
			"handshake.done",
			"handshake.error",
			"handshake.error_code",
			"log.title",
			"log.clear_btn",
			"io.unassigned",
			"io.not_used",
			"combo.prototype_custom_name",
			"log.service_disabled_detail",
			"log.service_enabled_detail",
			"log.status_binding_changed",
			"log.code_binding_changed",
			"log.manual_test",
			"log.conn_test_start",
			"log.conn_test_result",
			"log.service_started",
			"log.service_stopped",
			"log.polling_started",
			"log.warn_command_busy",
			"log.trigger_detected",
			"log.dashboard_response_success",
			"log.dashboard_response_failed",
			"log.comm_error",
			"log.interrupted",
			"log.connected_dashboard",
			"log.connected_primary",
			"log.bit_output_ok",
			"log.bit_output_fail",
			"log.int_output_ok",
			"log.int_output_fail"
	};

	@Test
	public void testEnglishLocalization() {
		I18n.setLocale(Locale.ENGLISH);
		assertFalse(I18n.isJapanese());

		assertEquals("Enable IO Dashboard Monitoring Service", I18n.t("header.enable_service"));
		assertEquals("(Unassigned)", I18n.t("io.unassigned"));
		assertEquals("(Not Used)", I18n.t("io.not_used"));
		assertEquals("Primary (30002): Connected", I18n.t("header.primary_connected", 30002));
		assertEquals("Power On & Brake Release", CommandAction.POWER_ON_AND_RELEASE.getDisplayName());
		assertEquals("Is in Remote Control", StatusOutputAction.IS_IN_REMOTE_CONTROL.getDisplayName());
		assertEquals("Robot is in Remote Control mode", StatusOutputAction.IS_IN_REMOTE_CONTROL.getDescription());
		assertEquals("Robot Mode Code (Int)", StatusCodeOutputAction.ROBOT_MODE_CODE.getDisplayName());

		verifyAllKeysPresent();
	}

	@Test
	public void testJapaneseLocalizationStandardAndPolyScopeJp() {
		// 1. 標準の "ja" ロケール
		I18n.setLocale(Locale.JAPANESE);
		assertTrue(I18n.isJapanese());
		assertEquals("IO Dashboard 監視サービスを有効にする", I18n.t("header.enable_service"));
		assertEquals("(未割り当て)", I18n.t("io.unassigned"));
		assertEquals("(使用しない)", I18n.t("io.not_used"));
		assertEquals("一括起動 (電源ON & ブレーキ解除)", CommandAction.POWER_ON_AND_RELEASE.getDisplayName());
		verifyAllKeysPresent();

		// 2. PolyScope 固有の "jp" ロケール
		I18n.setLocale(new Locale("jp"));
		assertTrue(I18n.isJapanese());
		assertEquals("IO Dashboard 監視サービスを有効にする", I18n.t("header.enable_service"));
		assertEquals("Primary (30002): 接続中", I18n.t("header.primary_connected", 30002));
		assertEquals("プログラム再生 (Play)", CommandAction.PLAY.getDisplayName());
		verifyAllKeysPresent();
	}

	@Test
	public void testFallbackToEnglishForOtherLocales() {
		I18n.setLocale(Locale.GERMAN);
		assertFalse(I18n.isJapanese());
		assertEquals("Enable IO Dashboard Monitoring Service", I18n.t("header.enable_service"));
		assertEquals("Play Program", CommandAction.PLAY.getDisplayName());
	}

	@Test
	public void testUnassignedAndNotUsedCompatibility() {
		IoCommandBinding bJa = new IoCommandBinding(CommandAction.PLAY, "(未割り当て)", "");
		IoCommandBinding bEn = new IoCommandBinding(CommandAction.PLAY, "(Unassigned)", "");
		assertFalse(bJa.isAssigned());
		assertFalse(bEn.isAssigned());

		IoStatusBinding sJa = new IoStatusBinding(StatusOutputAction.IS_ROBOT_READY, "(未割り当て)");
		IoStatusBinding sEn = new IoStatusBinding(StatusOutputAction.IS_ROBOT_READY, "(Unassigned)");
		assertFalse(sJa.isAssigned());
		assertFalse(sEn.isAssigned());

		IoStatusCodeBinding cJa = new IoStatusCodeBinding(StatusCodeOutputAction.ROBOT_MODE_CODE, "(使用しない)");
		IoStatusCodeBinding cEn = new IoStatusCodeBinding(StatusCodeOutputAction.ROBOT_MODE_CODE, "(Not Used)");
		assertFalse(cJa.isAssigned());
		assertFalse(cEn.isAssigned());
	}

	private void verifyAllKeysPresent() {
		for (String key : ALL_KEYS) {
			String val = I18n.t(key);
			assertNotNull(val);
			assertFalse(val.isEmpty(), "Empty value for key: " + key);
			assertFalse(val.startsWith("!"), "Missing resource key: " + key);
		}
		for (CommandAction a : CommandAction.values()) {
			assertFalse(a.getDisplayName().startsWith("!"));
		}
		for (StatusOutputAction a : StatusOutputAction.values()) {
			assertFalse(a.getDisplayName().startsWith("!"));
			assertFalse(a.getDescription().startsWith("!"));
		}
		for (StatusCodeOutputAction a : StatusCodeOutputAction.values()) {
			assertFalse(a.getDisplayName().startsWith("!"));
			assertFalse(a.getDescription().startsWith("!"));
		}
	}
}
