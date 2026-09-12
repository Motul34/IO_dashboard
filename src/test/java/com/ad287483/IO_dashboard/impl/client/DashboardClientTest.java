package com.ad287483.IO_dashboard.impl.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

public class DashboardClientTest {

	private ServerSocket mockServer;
	private ExecutorService serverExecutor;
	private int mockPort;

	@BeforeEach
	public void setup() throws Exception {
		mockServer = new ServerSocket(0);
		mockPort = mockServer.getLocalPort();
		serverExecutor = Executors.newSingleThreadExecutor();

		serverExecutor.submit(new Runnable() {
			@Override
			public void run() {
				try {
					while (!mockServer.isClosed()) {
						Socket client = mockServer.accept();
						PrintWriter out = new PrintWriter(client.getOutputStream(), true);
						BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));

						// ウェルカムメッセージ送信
						out.println("Connected: Universal Robots Dashboard Server");

						String line;
						while ((line = in.readLine()) != null) {
							line = line.trim();
							if ("is in remote control".equalsIgnoreCase(line)) {
								out.println("true");
							} else if ("running".equalsIgnoreCase(line)) {
								out.println("Program running: false");
							} else if ("robotmode".equalsIgnoreCase(line)) {
								out.println("Robotmode: IDLE");
							} else if ("safetystatus".equalsIgnoreCase(line)) {
								out.println("Safetystatus: NORMAL");
							} else if ("programState".equalsIgnoreCase(line)) {
								out.println("STOPPED");
							} else if ("power on".equalsIgnoreCase(line)) {
								out.println("Powering on");
							} else if ("brake release".equalsIgnoreCase(line)) {
								out.println("Brake releasing");
							} else if ("play".equalsIgnoreCase(line)) {
								out.println("Starting program");
							} else if ("stop".equalsIgnoreCase(line)) {
								out.println("Stopped");
							} else if (line.startsWith("load ")) {
								out.println("Loading program: " + line.substring(5));
							} else {
								out.println("Echo: " + line);
							}
						}
					}
				} catch (Exception ignored) {
				}
			}
		});
	}

	@AfterEach
	public void tearDown() throws Exception {
		if (mockServer != null) {
			mockServer.close();
		}
		if (serverExecutor != null) {
			serverExecutor.shutdownNow();
		}
	}

	@Test
	public void testConnectionAndCommands() throws Exception {
		DashboardClient client = new DashboardClient("127.0.0.1", mockPort);
		assertTrue(client.connect());
		assertTrue(client.isConnected());

		// コマンド送信テスト
		String resPlay = client.sendCommand("play");
		assertEquals("Starting program", resPlay);

		String resStop = client.sendCommand("stop");
		assertEquals("Stopped", resStop);

		String resLoad = client.sendCommand("load test.urp");
		assertEquals("Loading program: test.urp", resLoad);

		// ロボット状態一括クエリテスト
		RobotStatus status = client.queryRobotStatus();
		assertNotNull(status);
		assertTrue(status.isInRemoteControl());
		assertFalse(status.isProgramRunning());
		assertEquals("IDLE", status.getRobotMode());
		assertEquals("NORMAL", status.getSafetyStatus());
		assertTrue(status.isReady());
		assertTrue(status.isSafetyNormal());

		client.disconnect();
		assertFalse(client.isConnected());
	}
}
