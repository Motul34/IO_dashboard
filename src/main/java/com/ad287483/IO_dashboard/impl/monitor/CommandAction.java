package com.ad287483.IO_dashboard.impl.monitor;

import com.ad287483.IO_dashboard.impl.i18n.I18n;

/**
 * PLC からの入力信号により発火される制御アクション。
 */
public enum CommandAction {
	POWER_ON_AND_RELEASE("cmd.POWER_ON_AND_RELEASE", "power on -> brake release"),
	POWER_ON("cmd.POWER_ON", "power on"),
	BRAKE_RELEASE("cmd.BRAKE_RELEASE", "brake release"),
	POWER_OFF("cmd.POWER_OFF", "power off"),
	PLAY("cmd.PLAY", "play"),
	STOP("cmd.STOP", "stop"),
	PAUSE("cmd.PAUSE", "pause"),
	UNLOCK_PROTECTIVE_STOP("cmd.UNLOCK_PROTECTIVE_STOP", "unlock protective stop / close popup"),
	CLOSE_POPUP("cmd.CLOSE_POPUP", "close popup"),
	RESTART_SAFETY("cmd.RESTART_SAFETY", "restart safety");

	private final String displayNameKey;
	private final String dashboardCommand;

	CommandAction(String displayNameKey, String dashboardCommand) {
		this.displayNameKey = displayNameKey;
		this.dashboardCommand = dashboardCommand;
	}

	public String getDisplayName() {
		return I18n.t(displayNameKey);
	}

	public String getDashboardCommand() {
		return dashboardCommand;
	}
}
