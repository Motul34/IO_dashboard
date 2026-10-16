package com.ad287483.IO_dashboard.impl.monitor;

import com.ad287483.IO_dashboard.impl.client.RobotStatus;
import com.ad287483.IO_dashboard.impl.i18n.I18n;

/**
 * UR から PLC へ出力する多値状態の整数コード項目。
 */
public enum StatusCodeOutputAction {
	ROBOT_MODE_CODE("code.ROBOT_MODE_CODE.name", "code.ROBOT_MODE_CODE.desc") {
		@Override
		public int evaluate(RobotStatus status, int lastErrorCode) {
			return status.getRobotModeCode();
		}
	},
	SAFETY_STATUS_CODE("code.SAFETY_STATUS_CODE.name", "code.SAFETY_STATUS_CODE.desc") {
		@Override
		public int evaluate(RobotStatus status, int lastErrorCode) {
			return status.getSafetyStatusCode();
		}
	},
	PROGRAM_STATE_CODE("code.PROGRAM_STATE_CODE.name", "code.PROGRAM_STATE_CODE.desc") {
		@Override
		public int evaluate(RobotStatus status, int lastErrorCode) {
			return status.getProgramStateCode();
		}
	},
	LAST_ERROR_CODE("code.LAST_ERROR_CODE.name", "code.LAST_ERROR_CODE.desc") {
		@Override
		public int evaluate(RobotStatus status, int lastErrorCode) {
			return lastErrorCode;
		}
	};

	private final String displayNameKey;
	private final String descriptionKey;

	StatusCodeOutputAction(String displayNameKey, String descriptionKey) {
		this.displayNameKey = displayNameKey;
		this.descriptionKey = descriptionKey;
	}

	public String getDisplayName() {
		return I18n.t(displayNameKey);
	}

	public String getDescription() {
		return I18n.t(descriptionKey);
	}

	public abstract int evaluate(RobotStatus status, int lastErrorCode);
}
