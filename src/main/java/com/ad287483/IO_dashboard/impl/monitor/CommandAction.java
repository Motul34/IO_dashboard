package com.ad287483.IO_dashboard.impl.monitor;

/**
 * PLC からの入力信号により発火される制御アクション。
 */
public enum CommandAction {
	POWER_ON_AND_RELEASE("一括起動 (電源ON & ブレーキ解除)", "power on -> brake release"),
	POWER_ON("電源ON (Power On)", "power on"),
	BRAKE_RELEASE("ブレーキ解除 (Brake Release)", "brake release"),
	POWER_OFF("電源OFF (Power Off)", "power off"),
	PLAY("プログラム再生 (Play)", "play"),
	STOP("プログラム停止 (Stop)", "stop"),
	PAUSE("プログラム一時停止 (Pause)", "pause"),
	LOAD_PROGRAM("プログラムロード (Load Program)", "load <program>"),
	UNLOCK_PROTECTIVE_STOP("保護停止解除 & ポップアップ消去", "unlock protective stop / close popup"),
	CLOSE_POPUP("ポップアップ消去 (Close Popup)", "close popup"),
	RESTART_SAFETY("セーフティリスタート (Restart Safety)", "restart safety");

	private final String displayName;
	private final String dashboardCommand;

	CommandAction(String displayName, String dashboardCommand) {
		this.displayName = displayName;
		this.dashboardCommand = dashboardCommand;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getDashboardCommand() {
		return dashboardCommand;
	}
}
