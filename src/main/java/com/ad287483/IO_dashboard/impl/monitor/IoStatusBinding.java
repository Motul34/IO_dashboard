package com.ad287483.IO_dashboard.impl.monitor;

import java.io.Serializable;

/**
 * ブール状態出力項目と出力先IOの割付モデル。
 */
public class IoStatusBinding implements Serializable {
	private static final long serialVersionUID = 1L;

	private final StatusOutputAction action;
	private String ioName;

	public IoStatusBinding(StatusOutputAction action, String ioName) {
		this.action = action;
		this.ioName = ioName;
	}

	public StatusOutputAction getAction() {
		return action;
	}

	public String getIoName() {
		return ioName;
	}

	public void setIoName(String ioName) {
		this.ioName = ioName;
	}

	public boolean isAssigned() {
		return ioName != null && !ioName.trim().isEmpty() && !IoConstants.UNASSIGNED.equals(ioName);
	}
}
