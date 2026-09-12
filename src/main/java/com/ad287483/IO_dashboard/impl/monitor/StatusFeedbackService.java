package com.ad287483.IO_dashboard.impl.monitor;

import com.ad287483.IO_dashboard.impl.client.DashboardClient;
import com.ad287483.IO_dashboard.impl.client.PrimaryInterfaceClient;
import com.ad287483.IO_dashboard.impl.client.RobotStatus;
import com.ur.urcap.api.domain.io.DigitalIO;
import com.ur.urcap.api.domain.io.IO;
import com.ur.urcap.api.domain.io.IOModel;

import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * UR コントローラの状態 (is in remote control, running, robotmode, safetystatus 等) を
 * 定周期 (200ms) で監視し、Ethernet/IP の出力 (汎用ブール/整数出力レジスタ、デジタル出力) へ
 * 常時リアルタイム追従して反映するフィードバックサービス。
 * サービス有効時のみ接続を維持し、無効化時は全出力を0クリアして切断する。
 */
public class StatusFeedbackService {
	private static final Logger LOGGER = Logger.getLogger(StatusFeedbackService.class.getName());
	private static final int FEEDBACK_INTERVAL_MS = 200;

	private final DashboardClient dashboardClient;
	private final PrimaryInterfaceClient primaryClient;
	private final IoMonitorService monitorService;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

	private ScheduledFuture<?> feedbackTask;
	private IOModel ioModel;
	private volatile boolean enabled = true;
	private volatile RobotStatus currentStatus = RobotStatus.disconnected();

	private boolean loggedPrimaryConnected = false;
	private boolean loggedDashboardConnected = false;

	private final Map<StatusOutputAction, IoStatusBinding> statusBindings = new ConcurrentHashMap<StatusOutputAction, IoStatusBinding>();
	private final Map<StatusCodeOutputAction, IoStatusCodeBinding> codeBindings = new ConcurrentHashMap<StatusCodeOutputAction, IoStatusCodeBinding>();
	private final Map<String, Boolean> lastWrittenBoolValues = new ConcurrentHashMap<String, Boolean>();
	private final Map<String, Integer> lastWrittenIntValues = new ConcurrentHashMap<String, Integer>();

	public StatusFeedbackService(DashboardClient dashboardClient, PrimaryInterfaceClient primaryClient, IoMonitorService monitorService) {
		this.dashboardClient = dashboardClient;
		this.primaryClient = primaryClient;
		this.monitorService = monitorService;
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
			loggedDashboardConnected = false;
			loggedPrimaryConnected = false;
			clearAllOutputs();
		}
	}

	public void setStatusBinding(StatusOutputAction action, String ioName) {
		statusBindings.put(action, new IoStatusBinding(action, ioName));
	}

	public IoStatusBinding getStatusBinding(StatusOutputAction action) {
		return statusBindings.get(action);
	}

	public void setCodeBinding(StatusCodeOutputAction action, String ioName) {
		codeBindings.put(action, new IoStatusCodeBinding(action, ioName));
	}

	public IoStatusCodeBinding getCodeBinding(StatusCodeOutputAction action) {
		return codeBindings.get(action);
	}

	public RobotStatus getCurrentStatus() {
		return currentStatus;
	}

	/**
	 * 全ての割り当て済み出力レジスタ（ブールおよび整数）をクリア (0 / false) する。
	 */
	public void clearAllOutputs() {
		for (IoStatusBinding binding : statusBindings.values()) {
			if (binding.isAssigned()) {
				writeBooleanOutput(binding.getIoName(), false);
				lastWrittenBoolValues.put(binding.getIoName(), false);
			}
		}
		for (IoStatusCodeBinding binding : codeBindings.values()) {
			if (binding.isAssigned()) {
				writeIntegerOutput(binding.getIoName(), 0);
				lastWrittenIntValues.put(binding.getIoName(), 0);
			}
		}
		currentStatus = RobotStatus.disconnected();
	}

	/**
	 * 状態フィードバック処理を開始する。
	 */
	public synchronized void start() {
		if (feedbackTask != null && !feedbackTask.isDone()) {
			return;
		}
		feedbackTask = scheduler.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() {
				try {
					if (enabled) {
						ensureConnections();
						updateFeedback();
					}
				} catch (Throwable t) {
					LOGGER.log(Level.WARNING, "Error in status feedback loop: " + t.getMessage(), t);
				}
			}
		}, 100, FEEDBACK_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	/**
	 * 未接続の場合、自動的に接続を試行する (自動接続・再接続マネージャー)。
	 */
	private void ensureConnections() {
		if (!enabled) {
			return;
		}

		// Dashboard 接続の確認
		if (!dashboardClient.isConnected()) {
			if (dashboardClient.connect()) {
				if (!loggedDashboardConnected) {
					if (monitorService != null) monitorService.addLog("接続確立: Dashboard Server (127.0.0.1:29999) - OK");
					loggedDashboardConnected = true;
				}
			}
		} else {
			loggedDashboardConnected = true;
		}

		// Primary 接続の確認
		if (!primaryClient.isConnected()) {
			if (primaryClient.connect()) {
				if (!loggedPrimaryConnected) {
					if (monitorService != null) monitorService.addLog("接続確立: Primary Interface (ポート " + primaryClient.getActivePort() + ") - OK");
					loggedPrimaryConnected = true;
				}
			}
		} else {
			loggedPrimaryConnected = true;
		}
	}

	/**
	 * 状態フィードバック処理を停止する。
	 */
	public synchronized void stop() {
		if (feedbackTask != null) {
			feedbackTask.cancel(true);
			feedbackTask = null;
		}
	}

	public void shutdown() {
		stop();
		scheduler.shutdownNow();
	}

	/**
	 * 毎周期のステータス更新処理。
	 */
	private void updateFeedback() {
		if (!enabled) {
			return;
		}

		// 1. Dashboard Server から最新のロボット状態を取得
		RobotStatus status = dashboardClient.queryRobotStatus();
		this.currentStatus = status;

		// 2. ブール状態出力の更新 (常時追従)
		for (IoStatusBinding binding : statusBindings.values()) {
			if (!binding.isAssigned()) {
				continue;
			}

			boolean currentValue = binding.getAction().evaluate(status);
			String ioName = binding.getIoName();
			Boolean lastVal = lastWrittenBoolValues.get(ioName);

			// 初回または値が変化した場合に書き込み
			if (lastVal == null || lastVal.booleanValue() != currentValue) {
				writeBooleanOutput(ioName, currentValue);
				lastWrittenBoolValues.put(ioName, currentValue);
			}
		}

		// 3. 整数コード出力の更新
		int lastErr = monitorService != null ? monitorService.getLastErrorCode() : 0;
		for (IoStatusCodeBinding binding : codeBindings.values()) {
			if (!binding.isAssigned()) {
				continue;
			}

			int currentVal = binding.getAction().evaluate(status, lastErr);
			String ioName = binding.getIoName();
			Integer lastVal = lastWrittenIntValues.get(ioName);

			if (lastVal == null || lastVal.intValue() != currentVal) {
				writeIntegerOutput(ioName, currentVal);
				lastWrittenIntValues.put(ioName, currentVal);
			}
		}
	}

	/**
	 * ブール出力先（汎用ブール出力レジスタまたはデジタル出力）へ書き込む。
	 */
	public boolean writeBooleanOutput(String ioName, boolean value) {
		if (ioName == null || ioName.trim().isEmpty() || "(未割り当て)".equals(ioName)) {
			return false;
		}

		// 汎用ブール出力レジスタ (例: output_bit_register_64)
		if (ioName.startsWith("output_bit_register_")) {
			try {
				int address = Integer.parseInt(ioName.substring("output_bit_register_".length()).trim());
				return primaryClient.writeOutputBooleanRegister(address, value);
			} catch (NumberFormatException ignored) {}
			return false;
		}

		// デジタル出力 (DigitalIO)
		if (ioModel != null) {
			for (IO io : ioModel.getIOs()) {
				if (ioName.equalsIgnoreCase(io.getName()) || ioName.equalsIgnoreCase(io.getDefaultName())) {
					if (io instanceof DigitalIO && !io.isInput()) {
						return ((DigitalIO) io).setValue(value);
					}
				}
			}
		}
		return false;
	}

	/**
	 * 整数出力先（汎用整数出力レジスタ）へ書き込む。
	 */
	public boolean writeIntegerOutput(String ioName, int value) {
		if (ioName == null || ioName.trim().isEmpty() || "(使用しない)".equals(ioName)) {
			return false;
		}

		if (ioName.startsWith("output_int_register_")) {
			try {
				int address = Integer.parseInt(ioName.substring("output_int_register_".length()).trim());
				return primaryClient.writeOutputIntegerRegister(address, value);
			} catch (NumberFormatException ignored) {}
		}
		return false;
	}

	public Boolean getLastWrittenBool(String ioName) {
		return lastWrittenBoolValues.get(ioName);
	}

	public Integer getLastWrittenInt(String ioName) {
		return lastWrittenIntValues.get(ioName);
	}
}
