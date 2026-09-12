package com.ad287483.IO_dashboard.impl.monitor;

import com.ad287483.IO_dashboard.impl.client.RobotStatus;

/**
 * UR から PLC へ出力する多値状態の整数コード項目。
 */
public enum StatusCodeOutputAction {
	ROBOT_MODE_CODE("Robot Mode Code (整数)", "0:切断, 1:電源OFF, 3:電源ON, 4:IDLE, 7:RUNNING 等") {
		@Override
		public int evaluate(RobotStatus status, int lastErrorCode) {
			return status.getRobotModeCode();
		}
	},
	SAFETY_STATUS_CODE("Safety Status Code (整数)", "1:正常, 2:縮退, 3:保護停止, 5:非常停止 等") {
		@Override
		public int evaluate(RobotStatus status, int lastErrorCode) {
			return status.getSafetyStatusCode();
		}
	},
	PROGRAM_STATE_CODE("Program State Code (整数)", "0:NONE, 1:STOPPED, 2:PLAYING, 3:PAUSED") {
		@Override
		public int evaluate(RobotStatus status, int lastErrorCode) {
			return status.getProgramStateCode();
		}
	},
	LAST_ERROR_CODE("Last Error Code (直近エラーコード)", "0:正常, 1:ファイルなし, 2:セーフティ未確認, 3:実行拒否, 4:通信エラー") {
		@Override
		public int evaluate(RobotStatus status, int lastErrorCode) {
			return lastErrorCode;
		}
	};

	private final String displayName;
	private final String description;

	StatusCodeOutputAction(String displayName, String description) {
		this.displayName = displayName;
		this.description = description;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getDescription() {
		return description;
	}

	public abstract int evaluate(RobotStatus status, int lastErrorCode);
}
