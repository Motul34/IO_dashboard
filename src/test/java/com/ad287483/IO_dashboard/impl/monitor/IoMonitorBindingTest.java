package com.ad287483.IO_dashboard.impl.monitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class IoMonitorBindingTest {

	@Test
	public void testCommandBinding() {
		IoCommandBinding b1 = new IoCommandBinding(CommandAction.PLAY, "input_bit_register_64", "");
		assertTrue(b1.isAssigned());
		assertEquals(CommandAction.PLAY, b1.getAction());
		assertEquals("input_bit_register_64", b1.getIoName());

		IoCommandBinding b2 = new IoCommandBinding(CommandAction.STOP, IoConstants.UNASSIGNED, "");
		assertFalse(b2.isAssigned());

		IoCommandBinding b2Str = new IoCommandBinding(CommandAction.STOP, "(未割り当て)", "");
		assertFalse(b2Str.isAssigned());

		IoCommandBinding b3 = new IoCommandBinding(CommandAction.PAUSE, "input_bit_register_10", "");
		assertTrue(b3.isAssigned());
		assertEquals("input_bit_register_10", b3.getIoName());
	}

	@Test
	public void testStatusBinding() {
		IoStatusBinding b1 = new IoStatusBinding(StatusOutputAction.IS_IN_REMOTE_CONTROL, "output_bit_register_64");
		assertTrue(b1.isAssigned());

		IoStatusBinding b2 = new IoStatusBinding(StatusOutputAction.IS_PROGRAM_RUNNING, IoConstants.UNASSIGNED);
		assertFalse(b2.isAssigned());

		IoStatusBinding b2Str = new IoStatusBinding(StatusOutputAction.IS_PROGRAM_RUNNING, "(未割り当て)");
		assertFalse(b2Str.isAssigned());
	}

	@Test
	public void testStatusCodeBinding() {
		IoStatusCodeBinding b1 = new IoStatusCodeBinding(StatusCodeOutputAction.ROBOT_MODE_CODE, "output_int_register_24");
		assertTrue(b1.isAssigned());

		IoStatusCodeBinding b2 = new IoStatusCodeBinding(StatusCodeOutputAction.SAFETY_STATUS_CODE, IoConstants.NOT_USED);
		assertFalse(b2.isAssigned());

		IoStatusCodeBinding b2Str = new IoStatusCodeBinding(StatusCodeOutputAction.SAFETY_STATUS_CODE, "(使用しない)");
		assertFalse(b2Str.isAssigned());
	}
}
