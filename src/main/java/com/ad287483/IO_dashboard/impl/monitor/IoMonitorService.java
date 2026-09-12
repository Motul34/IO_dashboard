package com.ad287483.IO_dashboard.impl.monitor;

import com.ad287483.IO_dashboard.impl.client.DashboardClient;
import com.ad287483.IO_dashboard.impl.client.PrimaryInterfaceClient;
import com.ur.urcap.api.domain.io.DigitalIO;
import com.ur.urcap.api.domain.io.IO;
import com.ur.urcap.api.domain.io.IOModel;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PLC から送信されるブール信号（汎用ブール入力レジスタ、デジタル入力等）を定周期監視し、
 * OFF -> ON の立ち上がりエッジを検出して Dashboard Server コマンドを実行するサービス。
 */
public class IoMonitorService {
	private static final Logger LOGGER = Logger.getLogger(IoMonitorService.class.getName());
	private static final int POLLING_INTERVAL_MS = 50;
	private static final int MAX_LOG_ENTRIES = 50;

	private final DashboardClient dashboardClient;
	private final PrimaryInterfaceClient primaryClient;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private final ExecutorService commandExecutor = Executors.newCachedThreadPool();

	private ScheduledFuture<?> pollingTask;
	private IOModel ioModel;

	private boolean enabled = true;
	private final Map<CommandAction, IoCommandBinding> commandBindings = new ConcurrentHashMap<CommandAction, IoCommandBinding>();
	private final Map<String, Boolean> previousIoStates = new ConcurrentHashMap<String, Boolean>();
	private final List<String> logEntries = new CopyOnWriteArrayList<String>();

	// コマンド実行ハンドシェイク用
	private String busyIoName;
	private String doneIoName;
	private String errorIoName;
	private String errorCodeIoName;
	private volatile int lastErrorCode = 0;
	private volatile boolean isExecutingCommand = false;

	public IoMonitorService(DashboardClient dashboardClient, PrimaryInterfaceClient primaryClient) {
		this.dashboardClient = dashboardClient;
		this.primaryClient = primaryClient;
	}

	public void setIoModel(IOModel ioModel) {
		this.ioModel = ioModel;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		if (!enabled) {
			clearHandshakeOutputs();
		}
		addLog(enabled ? "監視サービスを開始しました (ENABLED)" : "監視サービスを停止しました (DISABLED)");
	}

	public void clearHandshakeOutputs() {
		setHandshakeOutput(busyIoName, false);
		setHandshakeOutput(doneIoName, false);
		setHandshakeOutput(errorIoName, false);
		updateErrorCodeOutput(0);
		lastErrorCode = 0;
		isExecutingCommand = false;
	}

	public void setCommandBinding(CommandAction action, String ioName, String parameter) {
		commandBindings.put(action, new IoCommandBinding(action, ioName, parameter));
	}

	public IoCommandBinding getCommandBinding(CommandAction action) {
		return commandBindings.get(action);
	}

	public void setHandshakeBindings(String busyIo, String doneIo, String errorIo, String errorCodeIo) {
		this.busyIoName = busyIo;
		this.doneIoName = doneIo;
		this.errorIoName = errorIo;
		this.errorCodeIoName = errorCodeIo;
	}

	public int getLastErrorCode() {
		return lastErrorCode;
	}

	public List<String> getLogEntries() {
		return new ArrayList<String>(logEntries);
	}

	public void clearLogs() {
		logEntries.clear();
	}

	public void addLog(String message) {
		String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
		String entry = "[" + timestamp + "] " + message;
		LOGGER.info(entry);
		logEntries.add(entry);
		while (logEntries.size() > MAX_LOG_ENTRIES) {
			logEntries.remove(0);
		}
	}

	/**
	 * 監視ポーリングを開始する。
	 */
	public synchronized void start() {
		if (pollingTask != null && !pollingTask.isDone()) {
			return;
		}
		pollingTask = scheduler.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() {
				try {
					pollInputs();
				} catch (Throwable t) {
					LOGGER.log(Level.WARNING, "Error in IO monitor polling: " + t.getMessage(), t);
				}
			}
		}, 100, POLLING_INTERVAL_MS, TimeUnit.MILLISECONDS);
		addLog("IOポーリングスレッド稼働中 (周期: " + POLLING_INTERVAL_MS + "ms)");
	}

	/**
	 * 監視ポーリングを停止する。
	 */
	public synchronized void stop() {
		if (pollingTask != null) {
			pollingTask.cancel(true);
			pollingTask = null;
		}
	}

	/**
	 * 全リソースを解放する。
	 */
	public void shutdown() {
		stop();
		scheduler.shutdownNow();
		commandExecutor.shutdownNow();
	}

	/**
	 * 毎周期の入力チェック処理。
	 */
	private void pollInputs() {
		if (!enabled || ioModel == null) {
			return;
		}

		for (IoCommandBinding binding : commandBindings.values()) {
			if (!binding.isAssigned()) {
				continue;
			}

			String ioName = binding.getIoName();
			boolean currentState = readIoValue(ioName);
			Boolean prevState = previousIoStates.get(ioName);

			// 初回検知時は現在値を保存するのみ
			if (prevState == null) {
				previousIoStates.put(ioName, currentState);
				continue;
			}

			// OFF -> ON の立ち上がりエッジ (Rising Edge) 検知
			if (!prevState && currentState) {
				previousIoStates.put(ioName, true);
				triggerAction(binding);
			} else {
				previousIoStates.put(ioName, currentState);
			}
		}
	}

	/**
	 * 指定した名前のIOの現在ブール値を読み出す。
	 */
	public boolean readIoValue(String ioName) {
		if (ioModel == null || ioName == null || ioName.trim().isEmpty()) {
			return false;
		}

		for (IO io : ioModel.getIOs()) {
			if (ioName.equalsIgnoreCase(io.getName()) || ioName.equalsIgnoreCase(io.getDefaultName())) {
				if (io instanceof DigitalIO) {
					return ((DigitalIO) io).getValue();
				}
				String valStr = io.getValueStr();
				return "true".equalsIgnoreCase(valStr) || "1".equals(valStr) || "high".equalsIgnoreCase(valStr);
			}
		}
		return false;
	}

	/**
	 * エッジ検知時のコマンド実行ハンドラ。
	 */
	private void triggerAction(final IoCommandBinding binding) {
		if (isExecutingCommand) {
			addLog("警告: 他のコマンド実行中のため、トリガー要求 [" + binding.getAction().getDisplayName() + "] はスキップされました");
			return;
		}

		commandExecutor.submit(new Runnable() {
			@Override
			public void run() {
				executeAction(binding);
			}
		});
	}

	/**
	 * コマンドの実際の実行とハンドシェイク信号制御。
	 */
	public void executeAction(IoCommandBinding binding) {
		isExecutingCommand = true;
		setHandshakeOutput(busyIoName, true);
		setHandshakeOutput(doneIoName, false);
		setHandshakeOutput(errorIoName, false);

		CommandAction action = binding.getAction();
		addLog("トリガー検知: " + binding.getIoName() + " (OFF → ON) : [" + action.getDisplayName() + "]");

		boolean success = false;
		String response = "";
		int errCode = 0;

		try {
			switch (action) {
				case POWER_ON_AND_RELEASE:
					response = dashboardClient.powerOnAndRelease();
					success = !response.toLowerCase().contains("failed") && !response.toLowerCase().contains("timeout");
					if (!success) errCode = 3;
					break;

				case POWER_ON:
					response = dashboardClient.sendCommand("power on");
					success = response.toLowerCase().contains("powering on");
					if (!success) errCode = 3;
					break;

				case BRAKE_RELEASE:
					response = dashboardClient.sendCommand("brake release");
					success = response.toLowerCase().contains("brake releasing");
					if (!success) errCode = 3;
					break;

				case POWER_OFF:
					response = dashboardClient.sendCommand("power off");
					success = response.toLowerCase().contains("powering off");
					if (!success) errCode = 3;
					break;

				case PLAY:
					response = dashboardClient.sendCommand("play");
					success = response.toLowerCase().contains("starting program");
					if (!success) errCode = 3;
					break;

				case STOP:
					response = dashboardClient.sendCommand("stop");
					success = response.toLowerCase().contains("stopped");
					if (!success) errCode = 3;
					break;

				case PAUSE:
					response = dashboardClient.sendCommand("pause");
					success = response.toLowerCase().contains("pausing program");
					if (!success) errCode = 3;
					break;

				case LOAD_PROGRAM:
					String progPath = binding.getParameter();
					if (progPath == null || progPath.trim().isEmpty()) {
						response = "Error: Program path is empty";
						errCode = 1;
						success = false;
					} else {
						response = dashboardClient.sendCommand("load " + progPath.trim());
						success = response.toLowerCase().contains("loading program");
						if (!success) {
							errCode = response.toLowerCase().contains("file not found") ? 1 : 2;
						}
					}
					break;

				case UNLOCK_PROTECTIVE_STOP:
					response = dashboardClient.sendCommand("unlock protective stop");
					dashboardClient.sendCommand("close safety popup");
					dashboardClient.sendCommand("close popup");
					success = response.toLowerCase().contains("protective stop releasing");
					if (!success) errCode = 3;
					break;

				case CLOSE_POPUP:
					dashboardClient.sendCommand("close safety popup");
					response = dashboardClient.sendCommand("close popup");
					success = true;
					break;

				case RESTART_SAFETY:
					response = dashboardClient.sendCommand("restart safety");
					success = response.toLowerCase().contains("restarting safety");
					if (!success) errCode = 3;
					break;
			}

			addLog("Dashboard 応答: \"" + response + "\" (" + (success ? "成功" : "失敗") + ")");
		} catch (IOException e) {
			response = "通信エラー: " + e.getMessage();
			errCode = 4;
			success = false;
			addLog(response);
		} catch (InterruptedException e) {
			response = "中断されました";
			errCode = 3;
			success = false;
			addLog(response);
		} finally {
			lastErrorCode = errCode;
			updateErrorCodeOutput(lastErrorCode);

			setHandshakeOutput(busyIoName, false);
			if (success) {
				setHandshakeOutput(doneIoName, true);
				setHandshakeOutput(errorIoName, false);
			} else {
				setHandshakeOutput(doneIoName, false);
				setHandshakeOutput(errorIoName, true);
			}
			isExecutingCommand = false;

			// 完了フラグ・エラーフラグを一定時間後に自動クリア
			scheduler.schedule(new Runnable() {
				@Override
				public void run() {
					setHandshakeOutput(doneIoName, false);
				}
			}, 1500, TimeUnit.MILLISECONDS);
		}
	}

	private void setHandshakeOutput(String ioName, boolean value) {
		if (ioName == null || ioName.trim().isEmpty() || "(使用しない)".equals(ioName)) {
			return;
		}

		// 汎用ブール出力レジスタ (例: output_bit_register_70)
		if (ioName.startsWith("output_bit_register_")) {
			try {
				int address = Integer.parseInt(ioName.substring("output_bit_register_".length()).trim());
				primaryClient.writeOutputBooleanRegister(address, value);
			} catch (NumberFormatException ignored) {}
			return;
		}

		// デジタル出力
		if (ioModel != null) {
			for (IO io : ioModel.getIOs()) {
				if (ioName.equalsIgnoreCase(io.getName()) || ioName.equalsIgnoreCase(io.getDefaultName())) {
					if (io instanceof DigitalIO && !io.isInput()) {
						((DigitalIO) io).setValue(value);
						return;
					}
				}
			}
		}
	}

	private void updateErrorCodeOutput(int code) {
		if (errorCodeIoName == null || errorCodeIoName.trim().isEmpty() || "(使用しない)".equals(errorCodeIoName)) {
			return;
		}
		if (errorCodeIoName.startsWith("output_int_register_")) {
			try {
				int address = Integer.parseInt(errorCodeIoName.substring("output_int_register_".length()).trim());
				primaryClient.writeOutputIntegerRegister(address, code);
			} catch (NumberFormatException ignored) {}
		}
	}
}
