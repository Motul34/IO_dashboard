package com.ad287483.IO_dashboard.impl.installation;

import com.ad287483.IO_dashboard.impl.client.DashboardClient;
import com.ad287483.IO_dashboard.impl.client.PrimaryInterfaceClient;
import com.ad287483.IO_dashboard.impl.i18n.I18n;
import com.ad287483.IO_dashboard.impl.monitor.IoMonitorService;
import com.ad287483.IO_dashboard.impl.monitor.StatusFeedbackService;
import com.ad287483.IO_dashboard.impl.style.Style;
import com.ad287483.IO_dashboard.impl.style.V5Style;
import com.ur.urcap.api.contribution.ViewAPIProvider;
import com.ur.urcap.api.contribution.installation.ContributionConfiguration;
import com.ur.urcap.api.contribution.installation.CreationContext;
import com.ur.urcap.api.contribution.installation.InstallationAPIProvider;
import com.ur.urcap.api.contribution.installation.swing.SwingInstallationNodeService;
import com.ur.urcap.api.domain.data.DataModel;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * PolyScope に IO Dashboard の Installation Node を提供するサービスファクトリ。
 * スレッドプールを一元管理し、タブ遷移等に伴うリソースリークを防止する。
 */
public class IoDashboardInstallationNodeService implements SwingInstallationNodeService<IoDashboardInstallationNodeContribution, IoDashboardInstallationNodeView> {

	private final DashboardClient dashboardClient = new DashboardClient();
	private final PrimaryInterfaceClient primaryClient = new PrimaryInterfaceClient();
	private final IoMonitorService monitorService = new IoMonitorService(dashboardClient, primaryClient);
	private final StatusFeedbackService feedbackService = new StatusFeedbackService(dashboardClient, primaryClient, monitorService);

	// シングルトンとして一元管理するスレッドプール
	private final ScheduledExecutorService uiScheduler = Executors.newSingleThreadScheduledExecutor();
	private final ExecutorService asyncExecutor = Executors.newCachedThreadPool();

	@Override
	public void configureContribution(ContributionConfiguration configuration) {
	}

	@Override
	public String getTitle(Locale locale) {
		if (locale != null) {
			I18n.setLocale(locale);
		}
		return "IO Dashboard";
	}

	@Override
	public IoDashboardInstallationNodeView createView(ViewAPIProvider apiProvider) {
		try {
			Locale locale = apiProvider.getSystemAPI().getSystemSettings().getLocalization().getLocale();
			I18n.setLocale(locale);
		} catch (Throwable ignored) {}
		Style style = new V5Style();
		return new IoDashboardInstallationNodeView(style);
	}

	@Override
	public IoDashboardInstallationNodeContribution createInstallationNode(
			InstallationAPIProvider apiProvider,
			IoDashboardInstallationNodeView view,
			DataModel model,
			CreationContext context) {
		try {
			Locale locale = apiProvider.getSystemAPI().getSystemSettings().getLocalization().getLocale();
			I18n.setLocale(locale);
		} catch (Throwable ignored) {}
		return new IoDashboardInstallationNodeContribution(
				apiProvider,
				view,
				model,
				context,
				dashboardClient,
				primaryClient,
				monitorService,
				feedbackService,
				uiScheduler,
				asyncExecutor
		);
	}

	public void shutdown() {
		monitorService.shutdown();
		feedbackService.shutdown();
		dashboardClient.disconnect();
		primaryClient.disconnect();
		uiScheduler.shutdownNow();
		asyncExecutor.shutdownNow();
	}
}
