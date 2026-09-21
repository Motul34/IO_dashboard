package com.ad287483.IO_dashboard.impl.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

public class PrimaryInterfaceClientTest {

	private ServerSocket mockServer;
	private ExecutorService serverExecutor;
	private int mockPort;
	private final BlockingQueue<String> receivedScripts = new LinkedBlockingQueue<String>();

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
						BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
						StringBuilder sb = new StringBuilder();
						String line;
						while ((line = in.readLine()) != null) {
							sb.append(line).append("\n");
							if (line.trim().equals("end")) {
								receivedScripts.put(sb.toString());
								sb.setLength(0);
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
	public void testWriteOutputRegisters() throws Exception {
		PrimaryInterfaceClient client = new PrimaryInterfaceClient("127.0.0.1", mockPort);
		assertTrue(client.connect());

		// 1. ブールレジスタ書き込みテスト
		boolean ok1 = client.writeOutputBooleanRegister(64, true);
		assertTrue(ok1);
		String script1 = receivedScripts.poll(2, java.util.concurrent.TimeUnit.SECONDS);
		assertNotNull(script1);
		assertTrue(script1.contains("write_output_boolean_register(64, True)"));

		// 2. ブールレジスタ False 書き込みテスト
		boolean ok2 = client.writeOutputBooleanRegister(65, false);
		assertTrue(ok2);
		String script2 = receivedScripts.poll(2, java.util.concurrent.TimeUnit.SECONDS);
		assertNotNull(script2);
		assertTrue(script2.contains("write_output_boolean_register(65, False)"));

		// 3. 整数レジスタ書き込みテスト
		boolean ok3 = client.writeOutputIntegerRegister(24, 7);
		assertTrue(ok3);
		String script3 = receivedScripts.poll(2, java.util.concurrent.TimeUnit.SECONDS);
		assertNotNull(script3);
		assertTrue(script3.contains("write_output_integer_register(24, 7)"));

		// 4. 一括バッチ書き込みテスト
		java.util.Map<Integer, Boolean> boolWrites = new java.util.HashMap<Integer, Boolean>();
		boolWrites.put(10, true);
		boolWrites.put(11, false);
		java.util.Map<Integer, Integer> intWrites = new java.util.HashMap<Integer, Integer>();
		intWrites.put(5, 42);

		boolean ok4 = client.writeOutputRegistersBatch(boolWrites, intWrites);
		assertTrue(ok4);
		String script4 = receivedScripts.poll(2, java.util.concurrent.TimeUnit.SECONDS);
		assertNotNull(script4);
		assertTrue(script4.contains("sec writeRegsBatch():"));
		assertTrue(script4.contains("write_output_boolean_register(10, True)"));
		assertTrue(script4.contains("write_output_boolean_register(11, False)"));
		assertTrue(script4.contains("write_output_integer_register(5, 42)"));
		assertTrue(script4.endsWith("end\n"));

		client.disconnect();
		assertFalse(client.isConnected());
	}
}
