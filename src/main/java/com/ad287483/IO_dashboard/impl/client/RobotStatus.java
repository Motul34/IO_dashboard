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

	public RobotStatus(boolean inRemoteControl, boolean programRunning, String robotMode, String safetyStatus, String programState) {
		this.inRemoteControl = inRemoteControl;
		this.programRunning = programRunning;
		this.robotMode = robotMode != null ? robotMode.trim() : "UNKNOWN";
		this.safetyStatus = safetyStatus != null ? safetyStatus.trim() : "UNKNOWN";
		this.programState = programState != null ? programState.trim() : "UNKNOWN";
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
		return "POWER_ON".equalsIgnoreCase(robotMode)
				|| "IDLE".equalsIgnoreCase(robotMode)
				|| "RUNNING".equalsIgnoreCase(robotMode);
	}

	/**
	 * ロボットが待機完了・動作可能状態か (IDLE または RUNNING)
	 */
	public boolean isReady() {
		return "IDLE".equalsIgnoreCase(robotMode) || "RUNNING".equalsIgnoreCase(robotMode);
	}

	/**
	 * セーフティが正常状態か (NORMAL または REDUCED)
	 */
	public boolean isSafetyNormal() {
		return "NORMAL".equalsIgnoreCase(safetyStatus) || "REDUCED".equalsIgnoreCase(safetyStatus);
	}

	/**
	 * 保護停止が発生しているか (PROTECTIVE_STOP)
	 */
	public boolean isProtectiveStopped() {
		return safetyStatus.toUpperCase().contains("PROTECTIVE_STOP");
	}

	/**
	 * 非常停止が押下されているか (SYSTEM_EMERGENCY_STOP または ROBOT_EMERGENCY_STOP)
	 */
	public boolean isEmergencyStopped() {
		return safetyStatus.toUpperCase().contains("EMERGENCY_STOP");
	}

	/**
	 * RobotMode を整数コードに変換して返す。
	 */
	public int getRobotModeCode() {
		String m = robotMode.toUpperCase();
		if (m.contains("DISCONNECTED") || m.contains("NO_CONTROLLER")) return 0;
		if (m.contains("POWER_OFF")) return 1;
		if (m.contains("BOOTING")) return 2;
		if (m.contains("POWER_ON")) return 3;
		if (m.contains("IDLE")) return 4;
		if (m.contains("BACKDRIVE")) return 5;
		if (m.contains("CONFIRM_SAFETY")) return 6;
		if (m.contains("RUNNING")) return 7;
		return -1;
	}

	/**
	 * SafetyStatus を整数コードに変換して返す。
	 */
	public int getSafetyStatusCode() {
		String s = safetyStatus.toUpperCase();
		if (s.contains("NORMAL")) return 1;
		if (s.contains("REDUCED")) return 2;
		if (s.contains("PROTECTIVE_STOP")) return 3;
		if (s.contains("SAFEGUARD_STOP")) return 4;
		if (s.contains("EMERGENCY_STOP")) return 5;
		if (s.contains("VIOLATION") || s.contains("FAULT")) return 6;
		return 0;
	}

	/**
	 * ProgramState を整数コードに変換して返す。
	 */
	public int getProgramStateCode() {
		String p = programState.toUpperCase();
		if (p.contains("STOPPED")) return 1;
		if (p.contains("PLAYING")) return 2;
		if (p.contains("PAUSED")) return 3;
		return 0;
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
