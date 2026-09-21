package com.ad287483.IO_dashboard.impl.client;

/**
 * Dashboard Server から取得したロボット状態を保持・判定するデータクラス。
 */
public class RobotStatus {
	private final boolean inRemoteControl;
	private final boolean programRunning;
	private final String robotMode;
	private final String safetyStatus;
	private final String programState;
	private final boolean powerOn;
	private final boolean ready;
	private final boolean safetyNormal;
	private final boolean protectiveStopped;
	private final boolean emergencyStopped;
	private final int robotModeCode;
	private final int safetyStatusCode;
	private final int programStateCode;

	public RobotStatus(boolean inRemoteControl, boolean programRunning, String robotMode, String safetyStatus, String programState) {
		this.inRemoteControl = inRemoteControl;
		this.programRunning = programRunning;
		this.robotMode = robotMode != null ? robotMode.trim() : "UNKNOWN";
		this.safetyStatus = safetyStatus != null ? safetyStatus.trim() : "UNKNOWN";
		this.programState = programState != null ? programState.trim() : "UNKNOWN";

		String m = this.robotMode.toUpperCase();
		String s = this.safetyStatus.toUpperCase();
		String p = this.programState.toUpperCase();

		this.powerOn = m.contains("POWER_ON") || m.contains("IDLE") || m.contains("RUNNING");
		this.ready = m.contains("IDLE") || m.contains("RUNNING");
		this.safetyNormal = s.contains("NORMAL") || s.contains("REDUCED");
		this.protectiveStopped = s.contains("PROTECTIVE_STOP");
		this.emergencyStopped = s.contains("EMERGENCY_STOP");

		if (m.contains("DISCONNECTED") || m.contains("NO_CONTROLLER")) {
			this.robotModeCode = 0;
		} else if (m.contains("POWER_OFF")) {
			this.robotModeCode = 1;
		} else if (m.contains("BOOTING")) {
			this.robotModeCode = 2;
		} else if (m.contains("POWER_ON")) {
			this.robotModeCode = 3;
		} else if (m.contains("IDLE")) {
			this.robotModeCode = 4;
		} else if (m.contains("BACKDRIVE")) {
			this.robotModeCode = 5;
		} else if (m.contains("CONFIRM_SAFETY")) {
			this.robotModeCode = 6;
		} else if (m.contains("RUNNING")) {
			this.robotModeCode = 7;
		} else {
			this.robotModeCode = -1;
		}

		if (s.contains("NORMAL")) {
			this.safetyStatusCode = 1;
		} else if (s.contains("REDUCED")) {
			this.safetyStatusCode = 2;
		} else if (s.contains("PROTECTIVE_STOP")) {
			this.safetyStatusCode = 3;
		} else if (s.contains("SAFEGUARD_STOP")) {
			this.safetyStatusCode = 4;
		} else if (s.contains("EMERGENCY_STOP")) {
			this.safetyStatusCode = 5;
		} else if (s.contains("VIOLATION") || s.contains("FAULT")) {
			this.safetyStatusCode = 6;
		} else {
			this.safetyStatusCode = 0;
		}

		if (p.contains("STOPPED")) {
			this.programStateCode = 1;
		} else if (p.contains("PLAYING")) {
			this.programStateCode = 2;
		} else if (p.contains("PAUSED")) {
			this.programStateCode = 3;
		} else {
			this.programStateCode = 0;
		}
	}

	public static RobotStatus disconnected() {
		return new RobotStatus(false, false, "DISCONNECTED", "FAULT", "STOPPED");
	}

	public boolean isInRemoteControl() {
		return inRemoteControl;
	}

	public boolean isProgramRunning() {
		return programRunning || "PLAYING".equalsIgnoreCase(programState);
	}

	public String getRobotMode() {
		return robotMode;
	}

	public String getSafetyStatus() {
		return safetyStatus;
	}

	public String getProgramState() {
		return programState;
	}

	/**
	 * ロボットアームの電源が入っているか (POWER_ON, IDLE, RUNNING 等)
	 */
	public boolean isPowerOn() {
		return powerOn;
	}

	/**
	 * ロボットが待機完了・動作可能状態か (IDLE または RUNNING)
	 */
	public boolean isReady() {
		return ready;
	}

	/**
	 * セーフティが正常状態か (NORMAL または REDUCED)
	 */
	public boolean isSafetyNormal() {
		return safetyNormal;
	}

	/**
	 * 保護停止が発生しているか (PROTECTIVE_STOP)
	 */
	public boolean isProtectiveStopped() {
		return protectiveStopped;
	}

	/**
	 * 非常停止が押下されているか (SYSTEM_EMERGENCY_STOP または ROBOT_EMERGENCY_STOP)
	 */
	public boolean isEmergencyStopped() {
		return emergencyStopped;
	}

	/**
	 * RobotMode を整数コードに変換して返す。
	 */
	public int getRobotModeCode() {
		return robotModeCode;
	}

	/**
	 * SafetyStatus を整数コードに変換して返す。
	 */
	public int getSafetyStatusCode() {
		return safetyStatusCode;
	}

	/**
	 * ProgramState を整数コードに変換して返す。
	 */
	public int getProgramStateCode() {
		return programStateCode;
	}

	@Override
	public String toString() {
		return "RobotStatus{" +
				"inRemote=" + inRemoteControl +
				", running=" + programRunning +
				", mode='" + robotMode + '\'' +
				", safety='" + safetyStatus + '\'' +
				", progState='" + programState + '\'' +
				'}';
	}
}
