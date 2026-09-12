package com.ad287483.IO_dashboard.impl.monitor;

import java.io.Serializable;

/**
 * 入力IOとDashboardコマンドの割付モデル。
 */
public class IoCommandBinding implements Serializable {
	private static final long serialVersionUID = 1L;

	private final CommandAction action;
	private String ioName;
	private String parameter; // プログラムファイル名など

	public IoCommandBinding(CommandAction action, String ioName, String parameter) {
		this.action = action;
		this.ioName = ioName;
		this.parameter = parameter;
	}

	public CommandAction getAction() {
		return action;
	}

	public String getIoName() {
		return ioName;
	}

	public void setIoName(String ioName) {
		this.ioName = ioName;
	}

	public String getParameter() {
		return parameter;
	}

	public void setParameter(String parameter) {
		this.parameter = parameter;
	}

	public boolean isAssigned() {
		return ioName != null && !ioName.trim().isEmpty() && !"(未割り当て)".equals(ioName);
	}
}
