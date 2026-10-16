package com.ad287483.IO_dashboard.impl.monitor;

import com.ad287483.IO_dashboard.impl.client.RobotStatus;
import com.ad287483.IO_dashboard.impl.i18n.I18n;

/**
 * UR から PLC へ常時フィードバック出力するブール状態項目。
 */
public enum StatusOutputAction {
	IS_IN_REMOTE_CONTROL("status.IS_IN_REMOTE_CONTROL.name", "status.IS_IN_REMOTE_CONTROL.desc") {
		@Override
		public boolean evaluate(RobotStatus status) {
			return status.isInRemoteControl();
		}
	},
	IS_PROGRAM_RUNNING("status.IS_PROGRAM_RUNNING.name", "status.IS_PROGRAM_RUNNING.desc") {
		@Override
		public boolean evaluate(RobotStatus status) {
			return status.isProgramRunning();
		}
	},
	IS_ROBOT_POWER_ON("status.IS_ROBOT_POWER_ON.name", "status.IS_ROBOT_POWER_ON.desc") {
		@Override
		public boolean evaluate(RobotStatus status) {
			return status.isPowerOn();
		}
	},
	IS_ROBOT_READY("status.IS_ROBOT_READY.name", "status.IS_ROBOT_READY.desc") {
		@Override
		public boolean evaluate(RobotStatus status) {
			return status.isReady();
		}
	},
	IS_SAFETY_NORMAL("status.IS_SAFETY_NORMAL.name", "status.IS_SAFETY_NORMAL.desc") {
		@Override
		public boolean evaluate(RobotStatus status) {
			return status.isSafetyNormal();
		}
	},
	IS_PROTECTIVE_STOPPED("status.IS_PROTECTIVE_STOPPED.name", "status.IS_PROTECTIVE_STOPPED.desc") {
		@Override
		public boolean evaluate(RobotStatus status) {
			return status.isProtectiveStopped();
		}
	},
	IS_EMERGENCY_STOPPED("status.IS_EMERGENCY_STOPPED.name", "status.IS_EMERGENCY_STOPPED.desc") {
		@Override
		public boolean evaluate(RobotStatus status) {
			return status.isEmergencyStopped();
		}
	};

	private final String displayNameKey;
	private final String descriptionKey;

	StatusOutputAction(String displayNameKey, String descriptionKey) {
		this.displayNameKey = displayNameKey;
		this.descriptionKey = descriptionKey;
	}

	public String getDisplayName() {
		return I18n.t(displayNameKey);
	}

	public String getDescription() {
		return I18n.t(descriptionKey);
	}

	public abstract boolean evaluate(RobotStatus status);
}
