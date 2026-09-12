package com.ad287483.IO_dashboard.impl.monitor;

import com.ad287483.IO_dashboard.impl.client.RobotStatus;

/**
 * UR から PLC へ常時フィードバック出力するブール状態項目。
 */
public enum StatusOutputAction {
	IS_IN_REMOTE_CONTROL("Is in Remote Control (リモート中)", "ロボットがリモート制御モードであるか") {
		@Override
		public boolean evaluate(RobotStatus status) {
			return status.isInRemoteControl();
		}
	},
	IS_PROGRAM_RUNNING("Is Program Running (再生中)", "プログラムが再生中であるか") {
		@Override
		public boolean evaluate(RobotStatus status) {
			return status.isProgramRunning();
		}
	},
	IS_ROBOT_POWER_ON("Is Robot Power On (電源ON)", "ロボットアームの電源が入っているか") {
		@Override
		public boolean evaluate(RobotStatus status) {
			return status.isPowerOn();
		}
	},
	IS_ROBOT_READY("Is Robot Ready / Idle (運転可能)", "ブレーキ解除済みで動作可能な状態か") {
		@Override
		public boolean evaluate(RobotStatus status) {
			return status.isReady();
		}
	},
	IS_SAFETY_NORMAL("Is Safety Normal (正常)", "セーフティが正常動作中か") {
		@Override
		public boolean evaluate(RobotStatus status) {
			return status.isSafetyNormal();
		}
	},
	IS_PROTECTIVE_STOPPED("Is Protective Stopped (保護停止中)", "保護停止が発生しているか") {
		@Override
		public boolean evaluate(RobotStatus status) {
			return status.isProtectiveStopped();
		}
	},
	IS_EMERGENCY_STOPPED("Is Emergency Stopped (非常停止中)", "非常停止が押下されているか") {
		@Override
		public boolean evaluate(RobotStatus status) {
			return status.isEmergencyStopped();
		}
	};

	private final String displayName;
	private final String description;

	StatusOutputAction(String displayName, String description) {
		this.displayName = displayName;
		this.description = description;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getDescription() {
		return description;
	}

	public abstract boolean evaluate(RobotStatus status);
}
