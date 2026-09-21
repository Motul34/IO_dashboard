package com.ad287483.IO_dashboard.impl.client;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * UR コントローラの Primary/Secondary Client Interface (TCP 127.0.0.1:30002 / 30001) を通じて、
 * プログラム停止中であっても汎用出力レジスタ (write_output_boolean_register 等) を即時更新するクライアント。
 * UR 起動時に自動で接続を確立し、切断時も自動で再接続を試みる。
 */
public class PrimaryInterfaceClient {
	private static final Logger LOGGER = Logger.getLogger(PrimaryInterfaceClient.class.getName());
	private static final String DEFAULT_HOST = "127.0.0.1";
	private static final int SECONDARY_PORT = 30002;
	private static final int PRIMARY_PORT = 30001;
	private static final int CONNECT_TIMEOUT_MS = 1500;

	private final String host;
	private int activePort = SECONDARY_PORT;
	private Socket socket;
	private PrintWriter writer;
	private long lastConnectAttemptTime = 0;
	private Thread drainThread;

	public PrimaryInterfaceClient() {
		this(DEFAULT_HOST);
	}

	public PrimaryInterfaceClient(String host) {
		this.host = host;
	}

	public PrimaryInterfaceClient(String host, int port) {
		this.host = host;
		this.activePort = port;
	}

	/**
	 * 自動接続処理。
	 * まず 30002 (Secondary) を試み、失敗した場合は 30001 (Primary) を試行する。
	 */
	public synchronized boolean connect() {
		if (isConnected()) {
			return true;
		}
		disconnect();

		// 頻繁すぎる接続試行を抑制 (最低1秒間隔)
		long now = System.currentTimeMillis();
		if (now - lastConnectAttemptTime < 1000) {
			return false;
		}
		lastConnectAttemptTime = now;

		int[] candidatePorts = (activePort == SECONDARY_PORT)
				? new int[]{SECONDARY_PORT, PRIMARY_PORT}
				: new int[]{activePort, SECONDARY_PORT, PRIMARY_PORT};

		for (int port : candidatePorts) {
			try {
				Socket s = new Socket();
				s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
				writer = new PrintWriter(s.getOutputStream(), true);
				socket = s;
				activePort = port;
				LOGGER.info("Connected to Primary/Secondary Interface on " + host + ":" + port);
				startDrainThread(s);
				return true;
			} catch (IOException ignored) {
			}
		}

		disconnect();
		return false;
	}

	public synchronized void disconnect() {
		if (drainThread != null) {
			drainThread.interrupt();
			drainThread = null;
		}
		if (writer != null) {
			try { writer.close(); } catch (Exception ignored) {}
			writer = null;
		}
		if (socket != null) {
			try { socket.close(); } catch (Exception ignored) {}
			socket = null;
		}
	}

	/**
	 * UR コントローラからブロードキャストされる状態パケットを
	 * 空読みして破棄するバックグラウンドスレッドを開始する。
	 * TCP 受信バッファの溢れ (ZeroWindow) を防止する。
	 */
	private void startDrainThread(final Socket s) {
		if (drainThread != null) {
			drainThread.interrupt();
		}
		drainThread = new Thread(new Runnable() {
			@Override
			public void run() {
				byte[] buf = new byte[4096];
				try {
					java.io.InputStream in = s.getInputStream();
					while (!Thread.currentThread().isInterrupted()) {
						int read = in.read(buf);
						if (read < 0) break;
					}
				} catch (IOException e) {
					// ソケットクローズ時は正常終了
				}
			}
		});
		drainThread.setDaemon(true);
		drainThread.setName("UR-PrimaryDrain");
		drainThread.start();
	}

	public synchronized boolean isConnected() {
		return socket != null && socket.isConnected() && !socket.isClosed();
	}

	public int getActivePort() {
		return activePort;
	}

	/**
	 * URScript を送信する。セカンダリタスクとして即時実行される。
	 */
	public synchronized boolean sendScript(String script) {
		if (!isConnected()) {
			if (!connect()) {
				return false;
			}
		}

		try {
			writer.print(script);
			writer.flush();
			return true;
		} catch (Exception e) {
			LOGGER.log(Level.WARNING, "Error sending script to Primary Interface: " + e.getMessage());
			disconnect();
			// 1回再試行
			if (connect()) {
				try {
					writer.print(script);
					writer.flush();
					return true;
				} catch (Exception ignored) {}
			}
			return false;
		}
	}

	/**
	 * 汎用ブール出力レジスタ (output_bit_register_X) に値を書き込む。
	 * Ethernet/IP の T->O (Target to Originator) アセンブリに即時反映される。
	 */
	public synchronized boolean writeOutputBooleanRegister(int address, boolean value) {
		String valStr = value ? "True" : "False";
		String script = "sec writeBoolReg():\n"
				+ "  write_output_boolean_register(" + address + ", " + valStr + ")\n"
				+ "end\n";
		return sendScript(script);
	}

	/**
	 * 汎用整数出力レジスタ (output_int_register_X) に値を書き込む。
	 */
	public synchronized boolean writeOutputIntegerRegister(int address, int value) {
		String script = "sec writeIntReg():\n"
				+ "  write_output_integer_register(" + address + ", " + value + ")\n"
				+ "end\n";
		return sendScript(script);
	}

	/**
	 * 汎用ブール出力レジスタおよび整数出力レジスタを一括更新する。
	 * 複数のレジスタ書き込みを 1 つの URScript セカンダリプログラムにまとめて送信し、通信負荷を最小化する。
	 */
	public synchronized boolean writeOutputRegistersBatch(java.util.Map<Integer, Boolean> boolWrites, java.util.Map<Integer, Integer> intWrites) {
		if ((boolWrites == null || boolWrites.isEmpty()) && (intWrites == null || intWrites.isEmpty())) {
			return true;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("sec writeRegsBatch():\n");
		if (boolWrites != null) {
			for (java.util.Map.Entry<Integer, Boolean> entry : boolWrites.entrySet()) {
				sb.append("  write_output_boolean_register(").append(entry.getKey()).append(", ")
				  .append(entry.getValue() ? "True" : "False").append(")\n");
			}
		}
		if (intWrites != null) {
			for (java.util.Map.Entry<Integer, Integer> entry : intWrites.entrySet()) {
				sb.append("  write_output_integer_register(").append(entry.getKey()).append(", ")
				  .append(entry.getValue()).append(")\n");
			}
		}
		sb.append("end\n");
		return sendScript(sb.toString());
	}
}
