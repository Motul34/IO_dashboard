package com.ad287483.IO_dashboard.impl.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * UR コントローラ内部の Dashboard Server (TCP 127.0.0.1:29999) と通信を行うクライアント。
 */
public class DashboardClient {
	private static final Logger LOGGER = Logger.getLogger(DashboardClient.class.getName());
	private static final String DEFAULT_HOST = "127.0.0.1";
	private static final int DEFAULT_PORT = 29999;
	private static final int CONNECT_TIMEOUT_MS = 2000;
	private static final int READ_TIMEOUT_MS = 3000;

	private final String host;
	private final int port;
	private Socket socket;
	private BufferedReader reader;
	private PrintWriter writer;

	public DashboardClient() {
		this(DEFAULT_HOST, DEFAULT_PORT);
	}

	public DashboardClient(String host, int port) {
		this.host = host;
		this.port = port;
	}

	/**
	 * Dashboard Server へ接続する。
	 * 接続直後のウェルカムメッセージ (例: "Connected: Universal Robots Dashboard Server") を読み捨てる。
	 */
	public synchronized boolean connect() {
		if (isConnected()) {
			return true;
		}
		disconnect();

		try {
			socket = new Socket();
			socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
			socket.setSoTimeout(READ_TIMEOUT_MS);
			reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
			writer = new PrintWriter(socket.getOutputStream(), true);

			// 接続ウェルカム行を読み出す
			String welcome = reader.readLine();
			LOGGER.info("Connected to Dashboard Server: " + welcome);
			return true;
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Failed to connect to Dashboard Server on " + host + ":" + port + " - " + e.getMessage());
			disconnect();
			return false;
		}
	}

	/**
	 * 切断する。
	 */
	public synchronized void disconnect() {
		if (writer != null) {
			try { writer.close(); } catch (Exception ignored) {}
			writer = null;
		}
		if (reader != null) {
			try { reader.close(); } catch (Exception ignored) {}
			reader = null;
		}
		if (socket != null) {
			try { socket.close(); } catch (Exception ignored) {}
			socket = null;
		}
	}

	/**
	 * 接続中かどうか。
	 */
	public synchronized boolean isConnected() {
		return socket != null && socket.isConnected() && !socket.isClosed();
	}

	/**
	 * Dashboard Server へコマンドを1行送信し、応答を1行受信する。
	 * 切断時は自動で1回再接続を試みる。
	 */
	public synchronized String sendCommand(String command) throws IOException {
		if (command == null || command.trim().isEmpty()) {
			return "";
		}
		if (!isConnected()) {
			if (!connect()) {
				throw new IOException("Cannot connect to Dashboard Server (" + host + ":" + port + ")");
			}
		}

		try {
			writer.print(command.trim() + "\n");
			writer.flush();
			String response = reader.readLine();
			if (response == null) {
				disconnect();
				throw new IOException("Dashboard Server closed the connection unexpectedly.");
			}
			return response.trim();
		} catch (IOException e) {
			disconnect();
			// 1回だけ再接続して再試行
			if (connect()) {
				writer.print(command.trim() + "\n");
				writer.flush();
				String response = reader.readLine();
				if (response != null) {
					return response.trim();
				}
			}
			throw e;
		}
	}

	/**
	 * ロボットの各状態（リモート中、再生中、ロボットモード、セーフティステータス、プログラム状態）を一括照会する。
	 */
	public synchronized RobotStatus queryRobotStatus() {
		if (!isConnected()) {
			if (!connect()) {
				return RobotStatus.disconnected();
			}
		}

		try {
			// 1. is in remote control
			String remoteRes = sendCommand("is in remote control");
			boolean inRemote = "true".equalsIgnoreCase(remoteRes);

			// 2. running
			String runningRes = sendCommand("running");
			boolean running = runningRes != null && runningRes.toLowerCase().contains("true");

			// 3. robotmode
			String modeRes = sendCommand("robotmode");
			String mode = "UNKNOWN";
			if (modeRes != null) {
				if (modeRes.startsWith("Robotmode: ")) {
					mode = modeRes.substring("Robotmode: ".length()).trim();
				} else {
					mode = modeRes.trim();
				}
			}

			// 4. safetystatus
			String safetyRes = sendCommand("safetystatus");
			String safety = "UNKNOWN";
			if (safetyRes != null) {
				if (safetyRes.startsWith("Safetystatus: ")) {
					safety = safetyRes.substring("Safetystatus: ".length()).trim();
				} else {
					safety = safetyRes.trim();
				}
			}

			// 5. programState
			String progStateRes = sendCommand("programState");
			String progState = "UNKNOWN";
			if (progStateRes != null) {
				String[] parts = progStateRes.split(" ");
				progState = parts[0].trim();
			}

			return new RobotStatus(inRemote, running, mode, safety, progState);
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Error querying robot status: " + e.getMessage());
			return RobotStatus.disconnected();
		}
	}

	/**
	 * 一括起動シーケンス：power on 送信後、IDLEになるのを待機して brake release を送信する。
	 */
	public synchronized String powerOnAndRelease() throws IOException, InterruptedException {
		String res1 = sendCommand("power on");
		if (res1.toLowerCase().contains("failed")) {
			return res1;
		}

		// ロボットが IDLE になるまで待機 (最大15秒)
		long startTime = System.currentTimeMillis();
		boolean readyForBrake = false;
		while (System.currentTimeMillis() - startTime < 15000) {
			Thread.sleep(500);
			String modeRes = sendCommand("robotmode");
			if (modeRes != null && (modeRes.contains("IDLE") || modeRes.contains("RUNNING"))) {
				readyForBrake = true;
				break;
			}
		}

		if (!readyForBrake) {
			return "Timeout waiting for robot power on (IDLE)";
		}

		// ブレーキ解除
		return sendCommand("brake release");
	}
}
