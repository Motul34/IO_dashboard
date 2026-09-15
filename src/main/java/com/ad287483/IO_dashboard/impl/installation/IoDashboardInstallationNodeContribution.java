package com.ad287483.IO_dashboard.impl.installation;

import com.ad287483.IO_dashboard.impl.client.DashboardClient;
import com.ad287483.IO_dashboard.impl.client.PrimaryInterfaceClient;
import com.ad287483.IO_dashboard.impl.client.RobotStatus;
import com.ad287483.IO_dashboard.impl.monitor.*;
import com.ur.urcap.api.contribution.InstallationNodeContribution;
import com.ur.urcap.api.contribution.installation.CreationContext;
import com.ur.urcap.api.contribution.installation.InstallationAPIProvider;
import com.ur.urcap.api.domain.data.DataModel;
import com.ur.urcap.api.domain.io.DigitalIO;
import com.ur.urcap.api.domain.io.IO;
import com.ur.urcap.api.domain.io.IOModel;
import com.ur.urcap.api.domain.script.ScriptWriter;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Installation Node のロジック、DataModel 永続化、ライフサイクルおよび UI 更新を統括するコントローラ。
 */
public class IoDashboardInstallationNodeContribution implements InstallationNodeContribution {

	private static final String KEY_ENABLED = "io_dash_enabled";
	private static final String KEY_PROGRAM_PATH = "io_dash_program_path";
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

	private final ScheduledExecutorService uiScheduler = Executors.newSingleThreadScheduledExecutor();
	private ScheduledFuture<?> uiUpdateTask;

	public IoDashboardInstallationNodeContribution(InstallationAPIProvider apiProvider,
	                                             IoDashboardInstallationNodeView view,
	                                             DataModel model,
	                                             CreationContext context,
	                                             DashboardClient dashboardClient,
	                                             PrimaryInterfaceClient primaryClient,
	                                             IoMonitorService monitorService,
	                                             StatusFeedbackService feedbackService) {
		this.apiProvider = apiProvider;
		this.view = view;
		this.model = model;
		this.dashboardClient = dashboardClient;
		this.primaryClient = primaryClient;
		this.monitorService = monitorService;
		this.feedbackService = feedbackService;

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
		new Thread(new Runnable() {
			@Override
			public void run() {
				if (!monitorService.isEnabled()) {
					return;
				}
				if (!dashboardClient.isConnected()) {
					dashboardClient.connect();
				}
				if (!primaryClient.isConnected()) {
					primaryClient.connect();
				}
			}
		}).start();
	}

	private void restoreSettings() {
		boolean enabled = model.get(KEY_ENABLED, true);
		monitorService.setEnabled(enabled);
		feedbackService.setEnabled(enabled);

		// 入力コマンドバインディングの復元
		for (CommandAction action : CommandAction.values()) {
			String savedIo = model.get("cmd_" + action.name(), "(未割り当て)");
			String param = "";
			if (action == CommandAction.LOAD_PROGRAM) {
				param = model.get(KEY_PROGRAM_PATH, "/programs/default.urp");
			}
			monitorService.setCommandBinding(action, savedIo, param);
		}

		// ブール状態出力バインディングの復元
		for (StatusOutputAction action : StatusOutputAction.values()) {
			String savedIo = model.get("status_" + action.name(), "(未割り当て)");
			feedbackService.setStatusBinding(action, savedIo);
		}

		// 整数コード出力バインディングの復元
		for (StatusCodeOutputAction action : StatusCodeOutputAction.values()) {
			String savedIo = model.get("code_" + action.name(), "(使用しない)");
			feedbackService.setCodeBinding(action, savedIo);
		}

		// ハンドシェイク設定の復元
		String busy = model.get(KEY_HANDSHAKE_BUSY, "(使用しない)");
		String done = model.get(KEY_HANDSHAKE_DONE, "(使用しない)");
		String error = model.get(KEY_HANDSHAKE_ERROR, "(使用しない)");
		String errCode = model.get(KEY_HANDSHAKE_ERRCODE, "(使用しない)");
		monitorService.setHandshakeBindings(busy, done, error, errCode);
	}

	@Override
	public void openView() {
		// 1. 利用可能な IO リストを生成して View のコンボボックスに供給
		List<String> inputOptions = buildInputOptions();
		List<String> outputBoolOptions = buildOutputBoolOptions();
		List<String> outputIntOptions = buildOutputIntOptions();

		view.populateInputIoOptions(inputOptions);
		view.populateOutputBoolOptions(outputBoolOptions);
		view.populateOutputIntOptions(outputIntOptions);

		// 2. 現在の保存値を View に反映
		view.setEnabledCheckbox(monitorService.isEnabled());
		view.setProgramPath(model.get(KEY_PROGRAM_PATH, "/programs/default.urp"));

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
				model.get(KEY_HANDSHAKE_BUSY, "(使用しない)"),
				model.get(KEY_HANDSHAKE_DONE, "(使用しない)"),
				model.get(KEY_HANDSHAKE_ERROR, "(使用しない)"),
				model.get(KEY_HANDSHAKE_ERRCODE, "(使用しない)")
		);

		// 3. UI 定期更新タスクの開始 (1000ms 周期で CPU 負荷を最小化)
		monitorService.refreshIoCache();
		feedbackService.refreshDigitalIoCache();

		if (uiUpdateTask != null) {
			uiUpdateTask.cancel(true);
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
		}, 0, 1000, TimeUnit.MILLISECONDS);

		// 4. 未接続の場合は即時自動接続
		triggerAutoConnect();
	}

	@Override
	public void closeView() {
		if (uiUpdateTask != null) {
			uiUpdateTask.cancel(true);
			uiUpdateTask = null;
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

		// ログ更新 (ログタブ表示中のみ再描画を実行し、操作遅延を防止)
		if (view.isLogTabActive()) {
			view.updateLogs(monitorService.getLogEntries());
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

		new Thread(new Runnable() {
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
		}).start();
	}

	public void onCommandIoSelected(CommandAction action, String ioName) {
		model.set("cmd_" + action.name(), ioName);
		String param = action == CommandAction.LOAD_PROGRAM ? view.getProgramPath() : "";
		monitorService.setCommandBinding(action, ioName, param);
	}

	public void onProgramPathChanged(String path) {
		model.set(KEY_PROGRAM_PATH, path);
		IoCommandBinding b = monitorService.getCommandBinding(CommandAction.LOAD_PROGRAM);
		if (b != null) {
			b.setParameter(path);
		}
		monitorService.addLog("ロード対象プログラムパスを更新: " + path);
	}

	public void onStatusIoSelected(StatusOutputAction action, String ioName) {
		model.set("status_" + action.name(), ioName);
		feedbackService.setStatusBinding(action, ioName);
	}

	public void onStatusCodeIoSelected(StatusCodeOutputAction action, String ioName) {
		model.set("code_" + action.name(), ioName);
		feedbackService.setCodeBinding(action, ioName);
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
			b = new IoCommandBinding(action, "手動テスト", view.getProgramPath());
		}
		final IoCommandBinding toExec = b;
		new Thread(new Runnable() {
			@Override
			public void run() {
				monitorService.executeAction(toExec);
			}
		}).start();
	}

	public void testConnection() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				monitorService.addLog("接続テスト開始...");
				dashboardClient.disconnect();
				boolean dOk = dashboardClient.connect();
				primaryClient.disconnect();
				boolean pOk = primaryClient.connect();
				monitorService.addLog("接続結果: Dashboard=" + (dOk ? "OK" : "NG") + ", Primary=" + (pOk ? "OK" : "NG"));
			}
		}).start();
	}

	public void clearLogs() {
		monitorService.clearLogs();
		view.updateLogs(monitorService.getLogEntries());
	}

	// ------------------ IO 候補生成ヘルパー ------------------

	private List<String> buildInputOptions() {
		List<String> list = new ArrayList<String>();
		list.add("(未割り当て)");

		// 汎用ブール入力レジスタ: bit 0 〜 64
		for (int i = 0; i <= 64; i++) {
			list.add("input_bit_register_" + i);
		}

		// デジタル入力
		IOModel ioModel = apiProvider.getInstallationAPI().getIOModel();
		if (ioModel != null) {
			for (DigitalIO dio : ioModel.getIOs(DigitalIO.class)) {
				if (dio.isInput()) {
					list.add(dio.getName() != null && !dio.getName().isEmpty() ? dio.getName() : dio.getDefaultName());
				}
			}
		}
		return list;
	}

	private List<String> buildOutputBoolOptions() {
		List<String> list = new ArrayList<String>();
		list.add("(未割り当て)");

		// 汎用ブール出力レジスタ: bit 0 〜 64
		for (int i = 0; i <= 64; i++) {
			list.add("output_bit_register_" + i);
		}

		// デジタル出力
		IOModel ioModel = apiProvider.getInstallationAPI().getIOModel();
		if (ioModel != null) {
			for (DigitalIO dio : ioModel.getIOs(DigitalIO.class)) {
				if (!dio.isInput()) {
					list.add(dio.getName() != null && !dio.getName().isEmpty() ? dio.getName() : dio.getDefaultName());
				}
			}
		}
		return list;
	}

	private List<String> buildOutputIntOptions() {
		List<String> list = new ArrayList<String>();
		list.add("(使用しない)");

		// 汎用整数出力レジスタ: int 0 〜 47
		for (int i = 0; i <= 47; i++) {
			list.add("output_int_register_" + i);
		}
		return list;
	}
}
