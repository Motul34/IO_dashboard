package com.ad287483.IO_dashboard.impl.installation;

import com.ad287483.IO_dashboard.impl.client.DashboardClient;
import com.ad287483.IO_dashboard.impl.client.PrimaryInterfaceClient;
import com.ad287483.IO_dashboard.impl.client.RobotStatus;
import com.ad287483.IO_dashboard.impl.monitor.*;
import com.ur.urcap.api.contribution.InstallationNodeContribution;
import com.ur.urcap.api.contribution.installation.CreationContext;
import com.ur.urcap.api.contribution.installation.InstallationAPIProvider;
import com.ur.urcap.api.domain.data.DataModel;
import com.ur.urcap.api.domain.io.IOModel;
import com.ur.urcap.api.domain.io.Register;
import com.ur.urcap.api.domain.io.BooleanRegister;
import com.ur.urcap.api.domain.io.IntegerRegister;
import com.ur.urcap.api.domain.script.ScriptWriter;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installation Node のロジック、DataModel 永続化、ライフサイクルおよび UI 更新を統括するコントローラ。
 * スレッドプールの一元化、画面遷移時のIOキャッシュ、ログ更新スキップにより高パフォーマンスを実現。
 */
public class IoDashboardInstallationNodeContribution implements InstallationNodeContribution {

	private static final String KEY_ENABLED = "io_dash_enabled";
	private static final String KEY_HANDSHAKE_BUSY = "io_dash_hs_busy";
	private static final String KEY_HANDSHAKE_DONE = "io_dash_hs_done";
	private static final String KEY_HANDSHAKE_ERROR = "io_dash_hs_error";
	private static final String KEY_HANDSHAKE_ERRCODE = "io_dash_hs_errcode";

	private final InstallationAPIProvider apiProvider;
	private final IoDashboardInstallationNodeView view;
	private final DataModel model;

	private final DashboardClient dashboardClient;
	private final PrimaryInterfaceClient primaryClient;
	private final IoMonitorService monitorService;
	private final StatusFeedbackService feedbackService;

	private final ScheduledExecutorService uiScheduler;
	private final ExecutorService asyncExecutor;
	private final boolean ownsExecutors;
	private final IOModel ioModel;

	// 標準識別名 (例: input_bit_register_0) <-> 表示名 (例: 運転準備) の双方向マッピング
	private final Map<String, String> canonicalToDisplayMap = new HashMap<String, String>();
	private final Map<String, String> displayToCanonicalMap = new HashMap<String, String>();
	private ScheduledFuture<?> uiUpdateTask;

	// UI最適化用キャッシュ
	private List<String> cachedInputOptions = null;
	private List<String> cachedOutputBoolOptions = null;
	private List<String> cachedOutputIntOptions = null;

	private static class CachedRegisterInfo {
		final Register register;
		final String canonicalName;
		final String defaultName;
		CachedRegisterInfo(Register register, String canonicalName, String defaultName) {
			this.register = register;
			this.canonicalName = canonicalName;
			this.defaultName = defaultName;
		}
	}
	private final List<CachedRegisterInfo> cachedRegisters = new ArrayList<CachedRegisterInfo>();
	private final Map<String, String> lastCustomNames = new HashMap<String, String>();

	public String toCanonicalIoName(String displayName) {
		if (displayName == null) return null;
		String canonical = displayToCanonicalMap.get(displayName);
		return canonical != null ? canonical : displayName;
	}

	public String toDisplayIoName(String canonicalName) {
		if (canonicalName == null) return null;
		String display = canonicalToDisplayMap.get(canonicalName);
		return display != null ? display : canonicalName;
	}

	private static int extractRegisterIndex(Register reg) {
		if (reg == null) return -1;
		String defName = reg.getDefaultName();
		if (defName == null || defName.isEmpty()) return -1;
		int start = -1;
		for (int i = 0; i < defName.length(); i++) {
			char c = defName.charAt(i);
			if (c >= '0' && c <= '9') {
				if (start < 0) start = i;
			} else if (start >= 0) {
				try {
					return Integer.parseInt(defName.substring(start, i));
				} catch (NumberFormatException ignored) {
					start = -1;
				}
			}
		}
		if (start >= 0) {
			try {
				return Integer.parseInt(defName.substring(start));
			} catch (NumberFormatException ignored) {}
		}
		return -1;
	}

	private boolean isUserCustomName(String custom, String defName, String canon) {
		if (custom == null || custom.trim().isEmpty()) return false;
		String c = custom.trim();
		if (c.equalsIgnoreCase(defName) || c.equalsIgnoreCase(canon)) return false;
		String lower = c.toLowerCase();
		if (lower.startsWith("gp_bool_") || lower.startsWith("gp_int_")
				|| lower.startsWith("gpbi") || lower.startsWith("gpbo")
				|| lower.startsWith("gpii") || lower.startsWith("gpio")
				|| lower.startsWith("input_bit_") || lower.startsWith("output_bit_")
				|| lower.startsWith("input_int_") || lower.startsWith("output_int_")) {
			return false;
		}
		return true;
	}

	private void initRegisterCache() {
		cachedRegisters.clear();
		if (ioModel == null) return;
		try {
			for (BooleanRegister reg : ioModel.getIOs(BooleanRegister.class)) {
				int index = extractRegisterIndex(reg);
				if (index >= 0) {
					String canon = reg.isInput()
							? (IoConstants.INPUT_BIT_PREFIX + index)
							: (IoConstants.OUTPUT_BIT_PREFIX + index);
					cachedRegisters.add(new CachedRegisterInfo(reg, canon, reg.getDefaultName()));
				}
			}
		} catch (Throwable ignored) {}

		try {
			for (IntegerRegister reg : ioModel.getIOs(IntegerRegister.class)) {
				int index = extractRegisterIndex(reg);
				if (index >= 0) {
					String canon = reg.isInput()
							? ("input_int_register_" + index)
							: (IoConstants.OUTPUT_INT_PREFIX + index);
					cachedRegisters.add(new CachedRegisterInfo(reg, canon, reg.getDefaultName()));
				}
			}
		} catch (Throwable ignored) {}
	}

	/**
	 * IOModel からレジスタのカスタム名を収集し、双方向マッピングテーブルを最新化する。
	 * レジスタのカスタム名に変更があった場合のみ true を返し、未変更なら 0ms (false) で終了する。
	 */
	private boolean refreshNameMappings() {
		Map<String, String> currentCustomNames = new HashMap<String, String>();
		for (CachedRegisterInfo info : cachedRegisters) {
			try {
				String custom = info.register.getName();
				if (isUserCustomName(custom, info.defaultName, info.canonicalName)) {
					currentCustomNames.put(info.canonicalName, custom.trim());
				}
			} catch (Throwable ignored) {}
		}

		// 前回とカスタム名が完全に一致していれば再構築をスキップ (0ms)
		if (!lastCustomNames.isEmpty() && currentCustomNames.equals(lastCustomNames)) {
			return false;
		}

		lastCustomNames.clear();
		lastCustomNames.putAll(currentCustomNames);

		canonicalToDisplayMap.clear();
		displayToCanonicalMap.clear();

		canonicalToDisplayMap.put(IoConstants.UNASSIGNED, IoConstants.UNASSIGNED);
		displayToCanonicalMap.put(IoConstants.UNASSIGNED, IoConstants.UNASSIGNED);
		canonicalToDisplayMap.put(IoConstants.NOT_USED, IoConstants.NOT_USED);
		displayToCanonicalMap.put(IoConstants.NOT_USED, IoConstants.NOT_USED);

		Map<String, String> tempCanonicalToDisplay = new HashMap<String, String>();
		Map<String, Integer> displayUsageCount = new HashMap<String, Integer>();

		for (int i = 0; i <= IoConstants.MAX_INPUT_BIT_INDEX; i++) {
			String canon = IoConstants.INPUT_BIT_PREFIX + i;
			String custom = currentCustomNames.get(canon);
			String disp = (custom != null) ? custom : canon;
			tempCanonicalToDisplay.put(canon, disp);
			Integer c = displayUsageCount.get(disp);
			displayUsageCount.put(disp, c == null ? 1 : c + 1);
		}

		for (int i = 0; i <= IoConstants.MAX_OUTPUT_BIT_INDEX; i++) {
			String canon = IoConstants.OUTPUT_BIT_PREFIX + i;
			String custom = currentCustomNames.get(canon);
			String disp = (custom != null) ? custom : canon;
			tempCanonicalToDisplay.put(canon, disp);
			Integer c = displayUsageCount.get(disp);
			displayUsageCount.put(disp, c == null ? 1 : c + 1);
		}

		for (int i = 0; i <= IoConstants.MAX_OUTPUT_INT_INDEX; i++) {
			String canon = IoConstants.OUTPUT_INT_PREFIX + i;
			String custom = currentCustomNames.get(canon);
			String disp = (custom != null) ? custom : canon;
			tempCanonicalToDisplay.put(canon, disp);
			Integer c = displayUsageCount.get(disp);
			displayUsageCount.put(disp, c == null ? 1 : c + 1);
		}

		for (Map.Entry<String, String> entry : tempCanonicalToDisplay.entrySet()) {
			String canon = entry.getKey();
			String disp = entry.getValue();
			if (displayUsageCount.get(disp) > 1 && !disp.equals(canon)) {
				disp = disp + " (" + canon + ")";
			}
			canonicalToDisplayMap.put(canon, disp);
			displayToCanonicalMap.put(disp, canon);
		}

		return true;
	}
	private long lastCheckedLogSeq = -1;
	private final AtomicBoolean isConnecting = new AtomicBoolean(false);

	public IoDashboardInstallationNodeContribution(InstallationAPIProvider apiProvider,
	                                             IoDashboardInstallationNodeView view,
	                                             DataModel model,
	                                             CreationContext context,
	                                             DashboardClient dashboardClient,
	                                             PrimaryInterfaceClient primaryClient,
	                                             IoMonitorService monitorService,
	                                             StatusFeedbackService feedbackService) {
		this(apiProvider, view, model, context, dashboardClient, primaryClient, monitorService, feedbackService,
				Executors.newSingleThreadScheduledExecutor(), Executors.newCachedThreadPool(), true);
	}

	public IoDashboardInstallationNodeContribution(InstallationAPIProvider apiProvider,
	                                             IoDashboardInstallationNodeView view,
	                                             DataModel model,
	                                             CreationContext context,
	                                             DashboardClient dashboardClient,
	                                             PrimaryInterfaceClient primaryClient,
	                                             IoMonitorService monitorService,
	                                             StatusFeedbackService feedbackService,
	                                             ScheduledExecutorService uiScheduler,
	                                             ExecutorService asyncExecutor) {
		this(apiProvider, view, model, context, dashboardClient, primaryClient, monitorService, feedbackService,
				uiScheduler, asyncExecutor, false);
	}

	private IoDashboardInstallationNodeContribution(InstallationAPIProvider apiProvider,
	                                               IoDashboardInstallationNodeView view,
	                                               DataModel model,
	                                               CreationContext context,
	                                               DashboardClient dashboardClient,
	                                               PrimaryInterfaceClient primaryClient,
	                                               IoMonitorService monitorService,
	                                               StatusFeedbackService feedbackService,
	                                               ScheduledExecutorService uiScheduler,
	                                               ExecutorService asyncExecutor,
	                                               boolean ownsExecutors) {
		this.apiProvider = apiProvider;
		this.view = view;
		this.model = model;
		this.dashboardClient = dashboardClient;
		this.primaryClient = primaryClient;
		this.monitorService = monitorService;
		this.feedbackService = feedbackService;
		this.uiScheduler = uiScheduler;
		this.asyncExecutor = asyncExecutor;
		this.ownsExecutors = ownsExecutors;

		// IOModel を保持し各サービスに設定
		IOModel ioModel = apiProvider.getInstallationAPI().getIOModel();
		this.ioModel = ioModel;
		this.monitorService.setIoModel(ioModel);
		this.feedbackService.setIoModel(ioModel);

		// レジスタキャッシュの初期化と初期マッピング
		initRegisterCache();
		refreshNameMappings();

		// 初期設定の復元
		restoreSettings();

		// バックグラウンドサービスの開始
		this.monitorService.start();
		this.feedbackService.start();

		// 有効時のみ自動接続
		if (monitorService.isEnabled()) {
			triggerAutoConnect();
		}
	}

	private void triggerAutoConnect() {
		if (!monitorService.isEnabled()) {
			return;
		}
		if (dashboardClient.isConnected() && primaryClient.isConnected()) {
			return;
		}
		if (!isConnecting.compareAndSet(false, true)) {
			return;
		}
		asyncExecutor.submit(new Runnable() {
			@Override
			public void run() {
				try {
					if (!monitorService.isEnabled()) {
						return;
					}
					if (!dashboardClient.isConnected()) {
						dashboardClient.connect();
					}
					if (!primaryClient.isConnected()) {
						primaryClient.connect();
					}
				} finally {
					isConnecting.set(false);
				}
			}
		});
	}

	private void restoreSettings() {
		boolean enabled = model.get(KEY_ENABLED, true);
		monitorService.setEnabled(enabled);
		feedbackService.setEnabled(enabled);

		// 入力コマンドバインディングの復元
		for (CommandAction action : CommandAction.values()) {
			String savedIo = model.get("cmd_" + action.name(), IoConstants.UNASSIGNED);
			monitorService.setCommandBinding(action, savedIo, "");
		}

		// ブール状態出力バインディングの復元 (起動時はソケットへの同期書き込みを伴わずに登録)
		for (StatusOutputAction action : StatusOutputAction.values()) {
			String savedIo = model.get("status_" + action.name(), IoConstants.UNASSIGNED);
			feedbackService.initStatusBinding(action, savedIo);
		}

		// 整数コード出力バインディングの復元 (起動時はソケットへの同期書き込みを伴わずに登録)
		for (StatusCodeOutputAction action : StatusCodeOutputAction.values()) {
			String savedIo = model.get("code_" + action.name(), IoConstants.NOT_USED);
			feedbackService.initCodeBinding(action, savedIo);
		}

		// ハンドシェイク設定の復元
		String busy = model.get(KEY_HANDSHAKE_BUSY, IoConstants.NOT_USED);
		String done = model.get(KEY_HANDSHAKE_DONE, IoConstants.NOT_USED);
		String error = model.get(KEY_HANDSHAKE_ERROR, IoConstants.NOT_USED);
		String errCode = model.get(KEY_HANDSHAKE_ERRCODE, IoConstants.NOT_USED);
		monitorService.setHandshakeBindings(busy, done, error, errCode);
	}

	@Override
	public void openView() {
		view.setUpdatingUI(true);
		try {
			// 0. IOModel からカスタム名を収集しマッピングを最新化 (変更がなければ 0ms)
			boolean mappingsChanged = refreshNameMappings();

			// 1. 初回またはIO名に変更があった場合のみ、表示名リストを生成して View のコンボボックスに供給
			if (mappingsChanged || cachedInputOptions == null) {
				cachedInputOptions = buildInputOptions();
				cachedOutputBoolOptions = buildOutputBoolOptions();
				cachedOutputIntOptions = buildOutputIntOptions();
				view.populateInputIoOptions(cachedInputOptions);
				view.populateOutputBoolOptions(cachedOutputBoolOptions);
				view.populateOutputIntOptions(cachedOutputIntOptions);
				monitorService.refreshIoCache();
				feedbackService.refreshDigitalIoCache();
			}

			// 2. 現在の保存値を表示名に変換して View に反映
			view.setEnabledCheckbox(monitorService.isEnabled());

			for (CommandAction action : CommandAction.values()) {
				IoCommandBinding b = monitorService.getCommandBinding(action);
				if (b != null) view.selectCommandIo(action, toDisplayIoName(b.getIoName()));
			}

			for (StatusOutputAction action : StatusOutputAction.values()) {
				IoStatusBinding b = feedbackService.getStatusBinding(action);
				if (b != null) view.selectStatusIo(action, toDisplayIoName(b.getIoName()));
			}

			for (StatusCodeOutputAction action : StatusCodeOutputAction.values()) {
				IoStatusCodeBinding b = feedbackService.getCodeBinding(action);
				if (b != null) view.selectStatusCodeIo(action, toDisplayIoName(b.getIoName()));
			}

			view.selectHandshakeIos(
					toDisplayIoName(model.get(KEY_HANDSHAKE_BUSY, IoConstants.NOT_USED)),
					toDisplayIoName(model.get(KEY_HANDSHAKE_DONE, IoConstants.NOT_USED)),
					toDisplayIoName(model.get(KEY_HANDSHAKE_ERROR, IoConstants.NOT_USED)),
					toDisplayIoName(model.get(KEY_HANDSHAKE_ERRCODE, IoConstants.NOT_USED))
			);
		} finally {
			view.setUpdatingUI(false);
		}

		// 3. 画面表示直後に最新状態を即時描画
		updateUI();

		// 4. UI 定期更新タスクの開始 (初回遅延 1000ms, 周期 1000ms)
		if (uiUpdateTask != null) {
			uiUpdateTask.cancel(true);
			uiUpdateTask = null;
		}
		uiUpdateTask = uiScheduler.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() {
				SwingUtilities.invokeLater(new Runnable() {
					@Override
					public void run() {
						updateUI();
					}
				});
			}
		}, 1000, 1000, TimeUnit.MILLISECONDS);

		// 5. 未接続の場合は即時自動接続
		triggerAutoConnect();
	}

	@Override
	public void closeView() {
		if (uiUpdateTask != null) {
			uiUpdateTask.cancel(true);
			uiUpdateTask = null;
		}
		// view.clearCaches() は呼ばず維持することで、再オープン時の不要な全件再描画を抑止
		lastCheckedLogSeq = -1;

		if (ownsExecutors) {
			uiScheduler.shutdownNow();
			asyncExecutor.shutdownNow();
		}
	}

	@Override
	public void generateScript(ScriptWriter writer) {
		// Installation 設定が適用されたことを示すログを出力
		writer.appendLine("# URCap: IO Dashboard Controller active");
	}

	private void updateUI() {
		// ポップアップ（ドロップダウン）展開中は画面更新をスキップし、描画負荷と操作干渉を完全に防止
		if (view.isAnyPopupVisible()) {
			return;
		}

		if (!monitorService.isEnabled()) {
			view.setConnectionStatus(false, false, 0);
		} else {
			view.setConnectionStatus(dashboardClient.isConnected(), primaryClient.isConnected(), primaryClient.getActivePort());
		}

		// 入力現在値の更新 (UIスレッドでの高負荷な走査を排除し、監視スレッドの最新値を参照)
		for (CommandAction action : CommandAction.values()) {
			IoCommandBinding b = monitorService.getCommandBinding(action);
			if (b != null && b.isAssigned()) {
				boolean val = monitorService.getLastInputState(b.getIoName());
				view.updateInputStatus(action, val);
			} else {
				view.updateInputStatus(action, false);
			}
		}

		// 出力現在値の更新
		RobotStatus status = feedbackService.getCurrentStatus();
		for (StatusOutputAction action : StatusOutputAction.values()) {
			boolean val = action.evaluate(status);
			view.updateOutputStatus(action, val);
		}

		// 整数コード現在値の更新
		int lastErr = monitorService.getLastErrorCode();
		for (StatusCodeOutputAction action : StatusCodeOutputAction.values()) {
			int val = action.evaluate(status, lastErr);
			view.updateCodeStatus(action, val);
		}

		// ログ更新 (ログタブ表示中かつ新規ログがある場合のみ差分反映)
		if (view.isLogTabActive()) {
			long currentSeq = monitorService.getLogSequenceNumber();
			if (currentSeq != lastCheckedLogSeq) {
				lastCheckedLogSeq = currentSeq;
				view.updateLogs(monitorService.getLogEntries());
			}
		}
	}

	// ------------------ イベントハンドラ群 ------------------

	public void onEnableToggled(final boolean enabled) {
		model.set(KEY_ENABLED, enabled);
		monitorService.setEnabled(enabled);
		feedbackService.setEnabled(enabled);

		if (!enabled) {
			// UI表示を直ちに「切断」に切り替え
			view.setConnectionStatus(false, false, 0);
		}

		asyncExecutor.submit(new Runnable() {
			@Override
			public void run() {
				if (!enabled) {
					// 無効化時: 出力レジスタを0クリアし、ソケットを切断
					feedbackService.clearAllOutputs();
					monitorService.clearHandshakeOutputs();
					dashboardClient.disconnect();
					primaryClient.disconnect();
					monitorService.addLog("監視サービスが無効化されました: 全出力を0クリアし通信を切断しました");
					SwingUtilities.invokeLater(new Runnable() {
						@Override
						public void run() {
							view.setConnectionStatus(false, false, 0);
						}
					});
				} else {
					// 有効化時: 再接続を開始
					monitorService.addLog("監視サービスが有効化されました: 自動接続を開始します");
					triggerAutoConnect();
				}
			}
		});
	}

	public void onCommandIoSelected(CommandAction action, String selectedDisplayName) {
		final String canonicalIo = toCanonicalIoName(selectedDisplayName);
		IoCommandBinding old = monitorService.getCommandBinding(action);
		if (old != null && canonicalIo != null && canonicalIo.equals(old.getIoName())) {
			return;
		}
		model.set("cmd_" + action.name(), canonicalIo);
		monitorService.setCommandBinding(action, canonicalIo, "");
	}

	public void onStatusIoSelected(final StatusOutputAction action, String selectedDisplayName) {
		final String canonicalIo = toCanonicalIoName(selectedDisplayName);
		IoStatusBinding old = feedbackService.getStatusBinding(action);
		if (old != null && canonicalIo != null && canonicalIo.equals(old.getIoName())) {
			return;
		}
		model.set("status_" + action.name(), canonicalIo);
		// UIスレッドでのソケット通信ブロックを排除し、完全非同期でレジスタへ反映
		asyncExecutor.submit(new Runnable() {
			@Override
			public void run() {
				feedbackService.setStatusBinding(action, canonicalIo);
				monitorService.addLog("状態出力割付変更: " + action.getDisplayName() + " -> " + canonicalIo);
			}
		});
	}

	public void onStatusCodeIoSelected(final StatusCodeOutputAction action, String selectedDisplayName) {
		final String canonicalIo = toCanonicalIoName(selectedDisplayName);
		IoStatusCodeBinding old = feedbackService.getCodeBinding(action);
		if (old != null && canonicalIo != null && canonicalIo.equals(old.getIoName())) {
			return;
		}
		model.set("code_" + action.name(), canonicalIo);
		// UIスレッドでのソケット通信ブロックを排除し、完全非同期でレジスタへ反映
		asyncExecutor.submit(new Runnable() {
			@Override
			public void run() {
				feedbackService.setCodeBinding(action, canonicalIo);
				monitorService.addLog("コード出力割付変更: " + action.getDisplayName() + " -> " + canonicalIo);
			}
		});
	}

	public void onHandshakeChanged() {
		final String busy = toCanonicalIoName(view.getBusyIo());
		final String done = toCanonicalIoName(view.getDoneIo());
		final String error = toCanonicalIoName(view.getErrorIo());
		final String errCode = toCanonicalIoName(view.getErrorCodeIo());

		String curBusy = model.get(KEY_HANDSHAKE_BUSY, IoConstants.NOT_USED);
		String curDone = model.get(KEY_HANDSHAKE_DONE, IoConstants.NOT_USED);
		String curError = model.get(KEY_HANDSHAKE_ERROR, IoConstants.NOT_USED);
		String curErrCode = model.get(KEY_HANDSHAKE_ERRCODE, IoConstants.NOT_USED);

		if (busy.equals(curBusy) && done.equals(curDone) && error.equals(curError) && errCode.equals(curErrCode)) {
			return;
		}

		model.set(KEY_HANDSHAKE_BUSY, busy);
		model.set(KEY_HANDSHAKE_DONE, done);
		model.set(KEY_HANDSHAKE_ERROR, error);
		model.set(KEY_HANDSHAKE_ERRCODE, errCode);

		asyncExecutor.submit(new Runnable() {
			@Override
			public void run() {
				monitorService.setHandshakeBindings(busy, done, error, errCode);
			}
		});
	}

	public void executeActionManually(CommandAction action) {
		IoCommandBinding b = monitorService.getCommandBinding(action);
		if (b == null) {
			b = new IoCommandBinding(action, "手動テスト", "");
		}
		final IoCommandBinding toExec = b;
		asyncExecutor.submit(new Runnable() {
			@Override
			public void run() {
				monitorService.executeAction(toExec);
			}
		});
	}

	public void testConnection() {
		asyncExecutor.submit(new Runnable() {
			@Override
			public void run() {
				monitorService.addLog("接続テスト開始...");
				dashboardClient.disconnect();
				boolean dOk = dashboardClient.connect();
				primaryClient.disconnect();
				boolean pOk = primaryClient.connect();
				monitorService.addLog("接続結果: Dashboard=" + (dOk ? "OK" : "NG") + ", Primary=" + (pOk ? "OK" : "NG"));
			}
		});
	}

	public void clearLogs() {
		monitorService.clearLogs();
		view.updateLogs(monitorService.getLogEntries());
	}

	// ------------------ IO 候補生成ヘルパー (ビットレジスタのみ) ------------------

	private List<String> buildInputOptions() {
		List<String> list = new ArrayList<String>();
		list.add(IoConstants.UNASSIGNED);
		for (int i = 0; i <= IoConstants.MAX_INPUT_BIT_INDEX; i++) {
			String canon = IoConstants.INPUT_BIT_PREFIX + i;
			list.add(toDisplayIoName(canon));
		}
		return list;
	}

	private List<String> buildOutputBoolOptions() {
		List<String> list = new ArrayList<String>();
		list.add(IoConstants.UNASSIGNED);
		for (int i = 0; i <= IoConstants.MAX_OUTPUT_BIT_INDEX; i++) {
			String canon = IoConstants.OUTPUT_BIT_PREFIX + i;
			list.add(toDisplayIoName(canon));
		}
		return list;
	}

	private List<String> buildOutputIntOptions() {
		List<String> list = new ArrayList<String>();
		list.add(IoConstants.NOT_USED);
		for (int i = 0; i <= IoConstants.MAX_OUTPUT_INT_INDEX; i++) {
			String canon = IoConstants.OUTPUT_INT_PREFIX + i;
			list.add(toDisplayIoName(canon));
		}
		return list;
	}
}
