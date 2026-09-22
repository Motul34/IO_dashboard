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
import com.ur.urcap.api.domain.script.ScriptWriter;

import javax.swing.*;
import java.util.ArrayList;
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
	private ScheduledFuture<?> uiUpdateTask;

	// UI最適化用キャッシュ
	private List<String> cachedInputOptions = null;
	private List<String> cachedOutputBoolOptions = null;
	private List<String> cachedOutputIntOptions = null;
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

		// IOModel を各サービスに設定
		IOModel ioModel = apiProvider.getInstallationAPI().getIOModel();
		this.monitorService.setIoModel(ioModel);
		this.feedbackService.setIoModel(ioModel);

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

		// ブール状態出力バインディングの復元
		for (StatusOutputAction action : StatusOutputAction.values()) {
			String savedIo = model.get("status_" + action.name(), IoConstants.UNASSIGNED);
			feedbackService.setStatusBinding(action, savedIo);
		}

		// 整数コード出力バインディングの復元
		for (StatusCodeOutputAction action : StatusCodeOutputAction.values()) {
			String savedIo = model.get("code_" + action.name(), IoConstants.NOT_USED);
			feedbackService.setCodeBinding(action, savedIo);
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
			// 1. 利用可能な IO リストを生成して差分がある場合のみ View のコンボボックスに供給
			List<String> inputOptions = buildInputOptions();
			List<String> outputBoolOptions = buildOutputBoolOptions();
			List<String> outputIntOptions = buildOutputIntOptions();

			boolean optionsChanged = (cachedInputOptions == null
					|| !inputOptions.equals(cachedInputOptions)
					|| !outputBoolOptions.equals(cachedOutputBoolOptions)
					|| !outputIntOptions.equals(cachedOutputIntOptions));

			if (optionsChanged) {
				cachedInputOptions = inputOptions;
				cachedOutputBoolOptions = outputBoolOptions;
				cachedOutputIntOptions = outputIntOptions;
				view.populateInputIoOptions(inputOptions);
				view.populateOutputBoolOptions(outputBoolOptions);
				view.populateOutputIntOptions(outputIntOptions);
				monitorService.refreshIoCache();
				feedbackService.refreshDigitalIoCache();
			}

			// 2. 現在の保存値を View に反映
			view.setEnabledCheckbox(monitorService.isEnabled());

			for (CommandAction action : CommandAction.values()) {
				IoCommandBinding b = monitorService.getCommandBinding(action);
				if (b != null) view.selectCommandIo(action, b.getIoName());
			}

			for (StatusOutputAction action : StatusOutputAction.values()) {
				IoStatusBinding b = feedbackService.getStatusBinding(action);
				if (b != null) view.selectStatusIo(action, b.getIoName());
			}

			for (StatusCodeOutputAction action : StatusCodeOutputAction.values()) {
				IoStatusCodeBinding b = feedbackService.getCodeBinding(action);
				if (b != null) view.selectStatusCodeIo(action, b.getIoName());
			}

			view.selectHandshakeIos(
					model.get(KEY_HANDSHAKE_BUSY, IoConstants.NOT_USED),
					model.get(KEY_HANDSHAKE_DONE, IoConstants.NOT_USED),
					model.get(KEY_HANDSHAKE_ERROR, IoConstants.NOT_USED),
					model.get(KEY_HANDSHAKE_ERRCODE, IoConstants.NOT_USED)
			);
		} finally {
			view.setUpdatingUI(false);
		}

		// 3. UI 定期更新タスクの開始 (2000ms 周期で CPU 負荷を最小化)
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
		}, 0, 2000, TimeUnit.MILLISECONDS);

		// 4. 未接続の場合は即時自動接続
		triggerAutoConnect();
	}

	@Override
	public void closeView() {
		if (uiUpdateTask != null) {
			uiUpdateTask.cancel(true);
			uiUpdateTask = null;
		}
		view.clearCaches();
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

	public void onCommandIoSelected(CommandAction action, String ioName) {
		model.set("cmd_" + action.name(), ioName);
		monitorService.setCommandBinding(action, ioName, "");
	}

	public void onStatusIoSelected(StatusOutputAction action, String ioName) {
		model.set("status_" + action.name(), ioName);
		feedbackService.setStatusBinding(action, ioName);
		monitorService.addLog("状態出力割付変更: " + action.getDisplayName() + " -> " + ioName);
	}

	public void onStatusCodeIoSelected(StatusCodeOutputAction action, String ioName) {
		model.set("code_" + action.name(), ioName);
		feedbackService.setCodeBinding(action, ioName);
		monitorService.addLog("コード出力割付変更: " + action.getDisplayName() + " -> " + ioName);
	}

	public void onHandshakeChanged() {
		String busy = view.getBusyIo();
		String done = view.getDoneIo();
		String error = view.getErrorIo();
		String errCode = view.getErrorCodeIo();

		model.set(KEY_HANDSHAKE_BUSY, busy);
		model.set(KEY_HANDSHAKE_DONE, done);
		model.set(KEY_HANDSHAKE_ERROR, error);
		model.set(KEY_HANDSHAKE_ERRCODE, errCode);

		monitorService.setHandshakeBindings(busy, done, error, errCode);
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

		// 汎用ブール入力レジスタのみ: bit 0 〜 64
		for (int i = 0; i <= IoConstants.MAX_INPUT_BIT_INDEX; i++) {
			list.add(IoConstants.INPUT_BIT_PREFIX + i);
		}
		return list;
	}

	private List<String> buildOutputBoolOptions() {
		List<String> list = new ArrayList<String>();
		list.add(IoConstants.UNASSIGNED);

		// 汎用ブール出力レジスタのみ: bit 0 〜 64
		for (int i = 0; i <= IoConstants.MAX_OUTPUT_BIT_INDEX; i++) {
			list.add(IoConstants.OUTPUT_BIT_PREFIX + i);
		}
		return list;
	}

	private List<String> buildOutputIntOptions() {
		List<String> list = new ArrayList<String>();
		list.add(IoConstants.NOT_USED);

		// 汎用整数出力レジスタのみ: int 0 〜 47
		for (int i = 0; i <= IoConstants.MAX_OUTPUT_INT_INDEX; i++) {
			list.add(IoConstants.OUTPUT_INT_PREFIX + i);
		}
		return list;
	}
}
