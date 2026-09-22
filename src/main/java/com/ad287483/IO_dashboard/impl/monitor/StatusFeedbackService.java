package com.ad287483.IO_dashboard.impl.monitor;

import com.ad287483.IO_dashboard.impl.client.DashboardClient;
import com.ad287483.IO_dashboard.impl.client.PrimaryInterfaceClient;
import com.ad287483.IO_dashboard.impl.client.RobotStatus;
import com.ur.urcap.api.domain.io.DigitalIO;
import com.ur.urcap.api.domain.io.IO;
import com.ur.urcap.api.domain.io.IOModel;

import java.util.HashMap;
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
	private static final int FEEDBACK_INTERVAL_MS = 500;

	private final DashboardClient dashboardClient;
	private final PrimaryInterfaceClient primaryClient;
	private final IoMonitorService monitorService;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

	private ScheduledFuture<?> feedbackTask;
	private IOModel ioModel;
	private final Map<String, DigitalIO> digitalIoCache = new ConcurrentHashMap<String, DigitalIO>();
	private volatile boolean enabled = true;
	private volatile RobotStatus currentStatus = RobotStatus.disconnected();

	private boolean loggedPrimaryConnected = false;
	private boolean loggedDashboardConnected = false;

	private final Map<StatusOutputAction, IoStatusBinding> statusBindings = new ConcurrentHashMap<StatusOutputAction, IoStatusBinding>();
	private final Map<StatusCodeOutputAction, IoStatusCodeBinding> codeBindings = new ConcurrentHashMap<StatusCodeOutputAction, IoStatusCodeBinding>();
	private final Map<String, Boolean> lastWrittenBoolValues = new ConcurrentHashMap<String, Boolean>();
	private final Map<String, Integer> lastWrittenIntValues = new ConcurrentHashMap<String, Integer>();
	private int pollCycleCount = 0;

	public StatusFeedbackService(DashboardClient dashboardClient, PrimaryInterfaceClient primaryClient, IoMonitorService monitorService) {
		this.dashboardClient = dashboardClient;
		this.primaryClient = primaryClient;
		this.monitorService = monitorService;
	}

	public void setIoModel(IOModel ioModel) {
		this.ioModel = ioModel;
		refreshDigitalIoCache();
	}

	public void refreshDigitalIoCache() {
		digitalIoCache.clear();
		if (ioModel != null) {
			for (DigitalIO dio : ioModel.getIOs(DigitalIO.class)) {
				if (!dio.isInput()) {
					if (dio.getName() != null && !dio.getName().isEmpty()) {
						digitalIoCache.put(dio.getName(), dio);
						digitalIoCache.put(dio.getName().toLowerCase(), dio);
					}
					if (dio.getDefaultName() != null && !dio.getDefaultName().isEmpty()) {
						digitalIoCache.put(dio.getDefaultName(), dio);
						digitalIoCache.put(dio.getDefaultName().toLowerCase(), dio);
					}
				}
			}
		}
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
		IoStatusBinding oldBinding = statusBindings.get(action);
		if (oldBinding != null && oldBinding.isAssigned() && !oldBinding.getIoName().equals(ioName)) {
			writeBooleanOutput(oldBinding.getIoName(), false);
			lastWrittenBoolValues.remove(oldBinding.getIoName());
		}
		statusBindings.put(action, new IoStatusBinding(action, ioName));
		if (ioName != null) {
			lastWrittenBoolValues.remove(ioName);
		}
	}

	public IoStatusBinding getStatusBinding(StatusOutputAction action) {
		return statusBindings.get(action);
	}

	public void setCodeBinding(StatusCodeOutputAction action, String ioName) {
		IoStatusCodeBinding oldBinding = codeBindings.get(action);
		if (oldBinding != null && oldBinding.isAssigned() && !oldBinding.getIoName().equals(ioName)) {
			writeIntegerOutput(oldBinding.getIoName(), 0);
			lastWrittenIntValues.remove(oldBinding.getIoName());
		}
		codeBindings.put(action, new IoStatusCodeBinding(action, ioName));
		if (ioName != null) {
			lastWrittenIntValues.remove(ioName);
		}
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
	private boolean isActionAssigned(StatusOutputAction action) {
		IoStatusBinding b = statusBindings.get(action);
		return b != null && b.isAssigned();
	}

	private boolean isCodeActionAssigned(StatusCodeOutputAction action) {
		IoStatusCodeBinding b = codeBindings.get(action);
		return b != null && b.isAssigned();
	}

	private void updateFeedback() {
		if (!enabled) {
			return;
		}

		// 1. Dashboard Server から最新のロボット状態を取得
		// 画面表示（② 出力状態タブ）と実際の出力の整合性を保つため常時全状態を取得
		pollCycleCount++;

		// 割り当て状況から必要な状態項目を判定
		boolean needRemote = isActionAssigned(StatusOutputAction.IS_IN_REMOTE_CONTROL);
		boolean needRunning = isActionAssigned(StatusOutputAction.IS_PROGRAM_RUNNING);
		boolean needMode = isActionAssigned(StatusOutputAction.IS_ROBOT_POWER_ON)
				|| isActionAssigned(StatusOutputAction.IS_ROBOT_READY)
				|| isCodeActionAssigned(StatusCodeOutputAction.ROBOT_MODE_CODE);
		boolean needSafety = isActionAssigned(StatusOutputAction.IS_SAFETY_NORMAL)
				|| isActionAssigned(StatusOutputAction.IS_PROTECTIVE_STOPPED)
				|| isActionAssigned(StatusOutputAction.IS_EMERGENCY_STOPPED)
				|| isCodeActionAssigned(StatusCodeOutputAction.SAFETY_STATUS_CODE);
		boolean needProgState = isCodeActionAssigned(StatusCodeOutputAction.PROGRAM_STATE_CODE);
		if (pollCycleCount % 4 == 0) {
			needRemote = true;
			needRunning = true;
			needMode = true;
			needSafety = true;
			needProgState = true;
		}

		// 1. Dashboard Server から最新のロボット状態を取得 (最適化クエリ)
		RobotStatus status = dashboardClient.queryRobotStatusOptimized(
				needRemote, needRunning, needMode, needSafety, needProgState, currentStatus);
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
				boolean ok = writeBooleanOutput(ioName, currentValue);
				if (ok) {
					lastWrittenBoolValues.put(ioName, currentValue ? Boolean.TRUE : Boolean.FALSE);
				}
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
				boolean ok = writeIntegerOutput(ioName, currentVal);
				if (ok) {
					lastWrittenIntValues.put(ioName, currentVal);
				}
			}
		}
	}

	/**
	 * ブール出力先（汎用ブール出力レジスタまたはデジタル出力）へ書き込む。
	 */
	public boolean writeBooleanOutput(String ioName, boolean value) {
		if (ioName == null || ioName.trim().isEmpty() || IoConstants.UNASSIGNED.equals(ioName)) {
			return false;
		}

		boolean result = false;
		// 汎用ブール出力レジスタ (例: output_bit_register_64)
		if (ioName.startsWith("output_bit_register_")) {
			try {
				int address = Integer.parseInt(ioName.substring("output_bit_register_".length()).trim());
				result = primaryClient.writeOutputBooleanRegister(address, value);
			} catch (NumberFormatException ignored) {}
		} else {
			// デジタル出力 (DigitalIO) - キャッシュからO(1)で即時取得
			DigitalIO dio = digitalIoCache.get(ioName);
			if (dio == null) {
				dio = digitalIoCache.get(ioName.toLowerCase());
			}
			if (dio != null) {
				result = dio.setValue(value);
			}
		}

		if (monitorService != null) {
			monitorService.addLog("Bit出力: " + ioName + " = " + (value ? "1(ON)" : "0(OFF)") + (result ? " [成功]" : " [失敗/未接続]"));
		}
		return result;
	}

	/**
	 * 整数出力先（汎用整数出力レジスタ）へ書き込む。
	 */
	public boolean writeIntegerOutput(String ioName, int value) {
		if (ioName == null || ioName.trim().isEmpty() || IoConstants.NOT_USED.equals(ioName)) {
			return false;
		}

		boolean result = false;
		if (ioName.startsWith("output_int_register_")) {
			try {
				int address = Integer.parseInt(ioName.substring("output_int_register_".length()).trim());
				result = primaryClient.writeOutputIntegerRegister(address, value);
			} catch (NumberFormatException ignored) {}
		}

		if (monitorService != null) {
			monitorService.addLog("Int出力: " + ioName + " = " + value + (result ? " [成功]" : " [失敗/未接続]"));
		}
		return result;
	}

	public Boolean getLastWrittenBool(String ioName) {
		return lastWrittenBoolValues.get(ioName);
	}

	public Integer getLastWrittenInt(String ioName) {
		return lastWrittenIntValues.get(ioName);
	}
}
