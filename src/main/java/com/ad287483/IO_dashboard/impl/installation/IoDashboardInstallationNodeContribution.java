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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installation Node ??????DataModel ?????????????? UI ??????????????
 * ??????????????????IO?????????????????????????????
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

	private final ScheduledExecutorService uiScheduler;
	private final ExecutorService asyncExecutor;
	private final boolean ownsExecutors;
	private ScheduledFuture<?> uiUpdateTask;

	// UI?????????
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

		// IOModel ?????????
		IOModel ioModel = apiProvider.getInstallationAPI().getIOModel();
		this.monitorService.setIoModel(ioModel);
		this.feedbackService.setIoModel(ioModel);

		// ???????
		restoreSettings();

		// ???????????????
		this.monitorService.start();
		this.feedbackService.start();

		// ?????????
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

		// ????????????????
		for (CommandAction action : CommandAction.values()) {
			String savedIo = model.get("cmd_" + action.name(), "(?????)");
			String param = "";
			if (action == CommandAction.LOAD_PROGRAM) {
				param = model.get(KEY_PROGRAM_PATH, "/programs/default.urp");
			}
			monitorService.setCommandBinding(action, savedIo, param);
		}

		// ?????????????????
		for (StatusOutputAction action : StatusOutputAction.values()) {
			String savedIo = model.get("status_" + action.name(), "(?????)");
			feedbackService.setStatusBinding(action, savedIo);
		}

		// ?????????????????
		for (StatusCodeOutputAction action : StatusCodeOutputAction.values()) {
			String savedIo = model.get("code_" + action.name(), "(?????)");
			feedbackService.setCodeBinding(action, savedIo);
		}

		// ????????????
		String busy = model.get(KEY_HANDSHAKE_BUSY, "(?????)");
		String done = model.get(KEY_HANDSHAKE_DONE, "(?????)");
		String error = model.get(KEY_HANDSHAKE_ERROR, "(?????)");
		String errCode = model.get(KEY_HANDSHAKE_ERRCODE, "(?????)");
		monitorService.setHandshakeBindings(busy, done, error, errCode);
	}

	@Override
	public void openView() {
		view.setUpdatingUI(true);
		try {
			// 1. ????? IO ????????????????? View ???????????
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

			// 2. ??????? View ???
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
					model.get(KEY_HANDSHAKE_BUSY, "(?????)"),
					model.get(KEY_HANDSHAKE_DONE, "(?????)"),
					model.get(KEY_HANDSHAKE_ERROR, "(?????)"),
					model.get(KEY_HANDSHAKE_ERRCODE, "(?????)")
			);
		} finally {
			view.setUpdatingUI(false);
		}

		// 3. UI ?????????? (2000ms ??? CPU ??????)
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

		// 4. ?????????????
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
		// Installation ??????????????????
		writer.appendLine("# URCap: IO Dashboard Controller active");
	}

	private void updateUI() {
		if (!monitorService.isEnabled()) {
			view.setConnectionStatus(false, false, 0);
		} else {
			view.setConnectionStatus(dashboardClient.isConnected(), primaryClient.isConnected(), primaryClient.getActivePort());
		}

		// ???????? (UI??????????????????????????????)
		for (CommandAction action : CommandAction.values()) {
			IoCommandBinding b = monitorService.getCommandBinding(action);
			if (b != null && b.isAssigned()) {
				boolean val = monitorService.getLastInputState(b.getIoName());
				view.updateInputStatus(action, val);
			} else {
				view.updateInputStatus(action, false);
			}
		}

		// ????????
		RobotStatus status = feedbackService.getCurrentStatus();
		for (StatusOutputAction action : StatusOutputAction.values()) {
			boolean val = action.evaluate(status);
			view.updateOutputStatus(action, val);
		}

		// ???????????
		int lastErr = monitorService.getLastErrorCode();
		for (StatusCodeOutputAction action : StatusCodeOutputAction.values()) {
			int val = action.evaluate(status, lastErr);
			view.updateCodeStatus(action, val);
		}

		// ???? (????????????????????????)
		if (view.isLogTabActive()) {
			long currentSeq = monitorService.getLogSequenceNumber();
			if (currentSeq != lastCheckedLogSeq) {
				lastCheckedLogSeq = currentSeq;
				view.updateLogs(monitorService.getLogEntries());
			}
		}
	}

	// ------------------ ????????? ------------------

	public void onEnableToggled(final boolean enabled) {
		model.set(KEY_ENABLED, enabled);
		monitorService.setEnabled(enabled);
		feedbackService.setEnabled(enabled);

		if (!enabled) {
			// UI???????????????
			view.setConnectionStatus(false, false, 0);
		}

		asyncExecutor.submit(new Runnable() {
			@Override
			public void run() {
				if (!enabled) {
					// ????: ???????0????????????
					feedbackService.clearAllOutputs();
					monitorService.clearHandshakeOutputs();
					dashboardClient.disconnect();
					primaryClient.disconnect();
					monitorService.addLog("???????????????: ????0?????????????");
					SwingUtilities.invokeLater(new Runnable() {
						@Override
						public void run() {
							view.setConnectionStatus(false, false, 0);
						}
					});
				} else {
					// ????: ??????
					monitorService.addLog("???????????????: ??????????");
					triggerAutoConnect();
				}
			}
		});
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
		monitorService.addLog("???????????????: " + path);
	}

	public void onStatusIoSelected(StatusOutputAction action, String ioName) {
		model.set("status_" + action.name(), ioName);
		feedbackService.setStatusBinding(action, ioName);
		monitorService.addLog("????????: " + action.getDisplayName() + " -> " + ioName);
	}

	public void onStatusCodeIoSelected(StatusCodeOutputAction action, String ioName) {
		model.set("code_" + action.name(), ioName);
		feedbackService.setCodeBinding(action, ioName);
		monitorService.addLog("?????????: " + action.getDisplayName() + " -> " + ioName);
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
			b = new IoCommandBinding(action, "?????", view.getProgramPath());
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
				monitorService.addLog("???????...");
				dashboardClient.disconnect();
				boolean dOk = dashboardClient.connect();
				primaryClient.disconnect();
				boolean pOk = primaryClient.connect();
				monitorService.addLog("????: Dashboard=" + (dOk ? "OK" : "NG") + ", Primary=" + (pOk ? "OK" : "NG"));
			}
		});
	}

	public void clearLogs() {
		monitorService.clearLogs();
		view.updateLogs(monitorService.getLogEntries());
	}

	// ------------------ IO ???????? ------------------

	private List<String> buildInputOptions() {
		List<String> list = new ArrayList<String>();
		list.add("(?????)");

		// ???????????: bit 0 ? 64
		for (int i = 0; i <= 64; i++) {
			list.add("input_bit_register_" + i);
		}

		// ??????
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
		list.add("(?????)");

		// ???????????: bit 0 ? 64
		for (int i = 0; i <= 64; i++) {
			list.add("output_bit_register_" + i);
		}

		// ??????
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
		list.add("(?????)");

		// ??????????: int 0 ? 47
		for (int i = 0; i <= 47; i++) {
			list.add("output_int_register_" + i);
		}
		return list;
	}
}
