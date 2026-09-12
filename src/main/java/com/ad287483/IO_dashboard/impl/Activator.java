package com.ad287483.IO_dashboard.impl;

import com.ad287483.IO_dashboard.impl.installation.IoDashboardInstallationNodeService;
import com.ur.urcap.api.contribution.installation.swing.SwingInstallationNodeService;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * OSGi BundleActivator for IO_dashboard URCap.
 */
public class Activator implements BundleActivator {

	private IoDashboardInstallationNodeService nodeService;

	@Override
	public void start(BundleContext bundleContext) throws Exception {
		nodeService = new IoDashboardInstallationNodeService();
		bundleContext.registerService(SwingInstallationNodeService.class, nodeService, null);
		System.out.println("IO_dashboard URCap bundle started successfully.");
	}

	@Override
	public void stop(BundleContext bundleContext) throws Exception {
		if (nodeService != null) {
			nodeService.shutdown();
			nodeService = null;
		}
		System.out.println("IO_dashboard URCap bundle stopped.");
	}
}
