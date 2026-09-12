package com.ad287483.IO_dashboard.impl.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RobotStatusTest {

	@Test
	public void testRobotStatusEvaluations() {
		// 1. リモートモード & 稼働中 & IDLE
		RobotStatus status1 = new RobotStatus(true, false, "IDLE", "NORMAL", "STOPPED");
		assertTrue(status1.isInRemoteControl());
		assertFalse(status1.isProgramRunning());
		assertTrue(status1.isPowerOn());
		assertTrue(status1.isReady());
		assertTrue(status1.isSafetyNormal());
		assertFalse(status1.isProtectiveStopped());
		assertFalse(status1.isEmergencyStopped());
		assertEquals(4, status1.getRobotModeCode());
		assertEquals(1, status1.getSafetyStatusCode());
		assertEquals(1, status1.getProgramStateCode());

		// 2. 保護停止中
		RobotStatus status2 = new RobotStatus(true, false, "RUNNING", "PROTECTIVE_STOP", "PAUSED");
		assertTrue(status2.isProtectiveStopped());
		assertFalse(status2.isSafetyNormal());
		assertTrue(status2.isPowerOn());
		assertEquals(7, status2.getRobotModeCode());
		assertEquals(3, status2.getSafetyStatusCode());
		assertEquals(3, status2.getProgramStateCode());

		// 3. 非常停止 & 電源OFF
		RobotStatus status3 = new RobotStatus(false, false, "POWER_OFF", "SYSTEM_EMERGENCY_STOP", "STOPPED");
		assertFalse(status3.isInRemoteControl());
		assertFalse(status3.isPowerOn());
		assertFalse(status3.isReady());
		assertTrue(status3.isEmergencyStopped());
		assertEquals(1, status3.getRobotModeCode());
		assertEquals(5, status3.getSafetyStatusCode());

		// 4. 切断状態 (disconnected)
		RobotStatus disc = RobotStatus.disconnected();
		assertFalse(disc.isInRemoteControl());
		assertFalse(disc.isPowerOn());
		assertEquals(0, disc.getRobotModeCode());
	}
}
