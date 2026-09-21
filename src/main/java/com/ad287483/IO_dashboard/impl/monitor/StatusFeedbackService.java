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
	private final Map<StatusOutputAction, Integer> boolRegisterAddresses = new ConcurrentHashMap<StatusOutputAction, Integer>();
	private final Map<StatusCodeOutputAction, Integer> intRegisterAddresses = new ConcurrentHashMap<StatusCodeOutputAction, Integer>();
	private final Map<String, Boolean> lastWrittenBoolValues = new ConcurrentHashMap<String, Boolean>();
	private final Map<String, Integer> lastWrittenIntValues = new ConcurrentHashMap<String, Integer>();

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
		statusBindings.put(action, new IoStatusBinding(action, ioName));
		if (ioName != null && ioName.startsWith("output_bit_register_")) {
			try {
				int addr = Integer.parseInt(ioName.substring("output_bit_register_".length()).trim());
				boolRegisterAddresses.put(action, addr);
			} catch (NumberFormatException e) {
				boolRegisterAddresses.remove(action);
			}
		} else {
			boolRegisterAddresses.remove(action);
		}
	}

	public IoStatusBinding getStatusBinding(StatusOutputAction action) {
		return statusBindings.get(action);
	}

	public void setCodeBinding(StatusCodeOutputAction action, String ioName) {
		codeBindings.put(action, new IoStatusCodeBinding(action, ioName));
		if (ioName != null && ioName.startsWith("output_int_register_")) {
			try {
				int addr = Integer.parseInt(ioName.substring("output_int_register_".length()).trim());
				intRegisterAddresses.put(action, addr);
			} catch (NumberFormatException e) {
				intRegisterAddresses.remove(action);
			}
		} else {
			intRegisterAddresses.remove(action);
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
	 * 汎用レジスタは一括バッチ送信でクリアし、通信オーバーヘッドを最小化する。
	 */
	public void clearAllOutputs() {
		Map<Integer, Boolean> batchBoolWrites = new HashMap<Integer, Boolean>();
		Map<Integer, Integer> batchIntWrites = new HashMap<Integer, Integer>();

		for (IoStatusBinding binding : statusBindings.values()) {
			if (binding.isAssigned()) {
				String ioName = binding.getIoName();
				Integer addr = boolRegisterAddresses.get(binding.getAction());
				if (addr != null) {
					batchBoolWrites.put(addr, false);
				} else {
					writeBooleanOutput(ioName, false);
				}
				lastWrittenBoolValues.put(ioName, Boolean.FALSE);
			}
		}
		for (IoStatusCodeBinding binding : codeBindings.values()) {
			if (binding.isAssigned()) {
				String ioName = binding.getIoName();
				Integer addr = intRegisterAddresses.get(binding.getAction());
				if (addr != null) {
					batchIntWrites.put(addr, 0);
				} else {
					writeIntegerOutput(ioName, 0);
				}
				lastWrittenIntValues.put(ioName, 0);
			}
		}

		if (!batchBoolWrites.isEmpty() || !batchIntWrites.isEmpty()) {
			primaryClient.writeOutputRegistersBatch(batchBoolWrites, batchIntWrites);
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

		// 実際に割り当てられているステータス項目のみを判別
		boolean needRemote = isStatusActionNeeded(StatusOutputAction.IS_IN_REMOTE_CONTROL);
		boolean needRunning = isStatusActionNeeded(StatusOutputAction.IS_PROGRAM_RUNNING);
		boolean needMode = isStatusActionNeeded(StatusOutputAction.IS_ROBOT_POWER_ON)
				|| isStatusActionNeeded(StatusOutputAction.IS_ROBOT_READY)
				|| isCodeActionNeeded(StatusCodeOutputAction.ROBOT_MODE_CODE);
		boolean needSafety = isStatusActionNeeded(StatusOutputAction.IS_SAFETY_NORMAL)
				|| isStatusActionNeeded(StatusOutputAction.IS_PROTECTIVE_STOPPED)
				|| isStatusActionNeeded(StatusOutputAction.IS_EMERGENCY_STOPPED)
				|| isCodeActionNeeded(StatusCodeOutputAction.SAFETY_STATUS_CODE);
		boolean needProgState = isCodeActionNeeded(StatusCodeOutputAction.PROGRAM_STATE_CODE);

		// 1. Dashboard Server から必要な項目のみを軽量取得 (未割り当ての項目は問い合わせを完全スキップ)
		RobotStatus status = dashboardClient.queryRobotStatusOptimized(
				needRemote, needRunning, needMode, needSafety, needProgState, currentStatus);
		this.currentStatus = status;

		Map<Integer, Boolean> batchBoolWrites = new HashMap<Integer, Boolean>();
		Map<Integer, Integer> batchIntWrites = new HashMap<Integer, Integer>();

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
				Integer addr = boolRegisterAddresses.get(binding.getAction());
				if (addr != null) {
					batchBoolWrites.put(addr, currentValue);
				} else {
					// デジタル出力等
					writeBooleanOutput(ioName, currentValue);
				}
				lastWrittenBoolValues.put(ioName, currentValue ? Boolean.TRUE : Boolean.FALSE);
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
				Integer addr = intRegisterAddresses.get(binding.getAction());
				if (addr != null) {
					batchIntWrites.put(addr, currentVal);
				} else {
					writeIntegerOutput(ioName, currentVal);
				}
				lastWrittenIntValues.put(ioName, currentVal);
			}
		}

		// 汎用レジスタの書き込みを 1 つの URScript パケットで一括送信
		if (!batchBoolWrites.isEmpty() || !batchIntWrites.isEmpty()) {
			primaryClient.writeOutputRegistersBatch(batchBoolWrites, batchIntWrites);
		}
	}

	private boolean isStatusActionNeeded(StatusOutputAction action) {
		IoStatusBinding b = statusBindings.get(action);
		return b != null && b.isAssigned();
	}

	private boolean isCodeActionNeeded(StatusCodeOutputAction action) {
		IoStatusCodeBinding b = codeBindings.get(action);
		return b != null && b.isAssigned();
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

		// デジタル出力 (DigitalIO) - キャッシュからO(1)で即時取得
		DigitalIO dio = digitalIoCache.get(ioName);
		if (dio == null) {
			dio = digitalIoCache.get(ioName.toLowerCase());
		}
		if (dio != null) {
			return dio.setValue(value);
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
