package com.ad287483.IO_dashboard.impl.monitor;

import com.ad287483.IO_dashboard.impl.client.DashboardClient;
import com.ad287483.IO_dashboard.impl.client.PrimaryInterfaceClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class StatusFeedbackServiceTest {

	private ServerSocket mockPrimaryServer;
	private Thread primaryServerThread;
	private final BlockingQueue<String> receivedScripts = new LinkedBlockingQueue<String>();

	private ServerSocket mockDashboardServer;
	private Thread dashboardServerThread;

	private PrimaryInterfaceClient primaryClient;
	private DashboardClient dashboardClient;
	private IoMonitorService monitorService;
	private StatusFeedbackService feedbackService;

	@BeforeEach
	public void setUp() throws Exception {
		// 1. Mock Primary Server
		mockPrimaryServer = new ServerSocket(0);
		final int primaryPort = mockPrimaryServer.getLocalPort();
		primaryServerThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					while (!mockPrimaryServer.isClosed()) {
						Socket client = mockPrimaryServer.accept();
						BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
						String line;
						StringBuilder sb = new StringBuilder();
						while ((line = in.readLine()) != null) {
							sb.append(line).append("\n");
							if (line.trim().equals("end")) {
								receivedScripts.put(sb.toString());
								sb.setLength(0);
							}
						}
					}
				} catch (Exception ignored) {}
			}
		});
		primaryServerThread.setDaemon(true);
		primaryServerThread.start();

		// 2. Mock Dashboard Server
		mockDashboardServer = new ServerSocket(0);
		final int dashboardPort = mockDashboardServer.getLocalPort();
		dashboardServerThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					while (!mockDashboardServer.isClosed()) {
						Socket client = mockDashboardServer.accept();
						java.io.PrintWriter out = new java.io.PrintWriter(client.getOutputStream(), true);
						out.println("Connected: Universal Robots Dashboard Server");
						BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
						String line;
						while ((line = in.readLine()) != null) {
							String cmd = line.trim();
							if ("is in remote control".equalsIgnoreCase(cmd)) {
								out.println("true");
							} else if ("running".equalsIgnoreCase(cmd)) {
								out.println("Robot is running: false");
							} else if ("robotmode".equalsIgnoreCase(cmd)) {
								out.println("Robotmode: RUNNING");
							} else if ("safetystatus".equalsIgnoreCase(cmd)) {
								out.println("Safetystatus: NORMAL");
							} else if ("programState".equalsIgnoreCase(cmd)) {
								out.println("STOPPED");
							} else {
								out.println("Unknown command: " + cmd);
							}
						}
					}
				} catch (IOException ignored) {}
			}
		});
		dashboardServerThread.setDaemon(true);
		dashboardServerThread.start();

		primaryClient = new PrimaryInterfaceClient("127.0.0.1", primaryPort);
		dashboardClient = new DashboardClient("127.0.0.1", dashboardPort);
		monitorService = new IoMonitorService(dashboardClient, primaryClient);
		feedbackService = new StatusFeedbackService(dashboardClient, primaryClient, monitorService);
	}

	@AfterEach
	public void tearDown() throws Exception {
		if (feedbackService != null) feedbackService.shutdown();
		if (monitorService != null) monitorService.shutdown();
		if (primaryClient != null) primaryClient.disconnect();
		if (dashboardClient != null) dashboardClient.disconnect();
		if (mockPrimaryServer != null) mockPrimaryServer.close();
		if (mockDashboardServer != null) mockDashboardServer.close();
	}

	@Test
	public void testWriteBooleanOutputAndLogging() throws Exception {
		assertTrue(primaryClient.connect());

		// 1. 日本語ロケールでのレジスタ書き込み＆ログテスト
		com.ad287483.IO_dashboard.impl.i18n.I18n.setLocale(java.util.Locale.JAPANESE);
		boolean ok = feedbackService.writeBooleanOutput("output_bit_register_10", true);
		assertTrue(ok);

		// スクリプトが届いたか確認
		String script = receivedScripts.poll(2, TimeUnit.SECONDS);
		assertNotNull(script);
		assertTrue(script.contains("write_output_boolean_register(10, True)"));

		// 日本語ログに記録されたか確認
		boolean foundJaLog = false;
		for (String entry : monitorService.getLogEntries()) {
			if (entry.contains("Bit出力: output_bit_register_10 = 1(ON) [成功]")) {
				foundJaLog = true;
				break;
			}
		}
		assertTrue(foundJaLog, "日本語ログにBit出力成功が記録されていること");

		// 2. 英語ロケールでのレジスタ書き込み＆ログテスト
		com.ad287483.IO_dashboard.impl.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
		boolean okEn = feedbackService.writeBooleanOutput("output_bit_register_11", false);
		assertTrue(okEn);
		String scriptEn = receivedScripts.poll(2, TimeUnit.SECONDS);
		assertNotNull(scriptEn);
		assertTrue(scriptEn.contains("write_output_boolean_register(11, False)"));

		boolean foundEnLog = false;
		for (String entry : monitorService.getLogEntries()) {
			if (entry.contains("Bit Output: output_bit_register_11 = 0(OFF) [OK]")) {
				foundEnLog = true;
				break;
			}
		}
		assertTrue(foundEnLog, "英語ログにBit Output成功が記録されていること");
	}

	@Test
	public void testStatusBindingChangeClearsOldRegister() throws Exception {
		assertTrue(primaryClient.connect());

		// 最初のアサイン
		feedbackService.setStatusBinding(StatusOutputAction.IS_ROBOT_POWER_ON, "output_bit_register_5");
		assertEquals("output_bit_register_5", feedbackService.getStatusBinding(StatusOutputAction.IS_ROBOT_POWER_ON).getIoName());

		// 別レジスタへ変更
		feedbackService.setStatusBinding(StatusOutputAction.IS_ROBOT_POWER_ON, "output_bit_register_6");

		// 古いレジスタ(5)がクリア(False)送信されたか確認
		String script = receivedScripts.poll(2, TimeUnit.SECONDS);
		assertNotNull(script);
		assertTrue(script.contains("write_output_boolean_register(5, False)"));
		assertEquals("output_bit_register_6", feedbackService.getStatusBinding(StatusOutputAction.IS_ROBOT_POWER_ON).getIoName());
	}
}
