package com.ad287483.IO_dashboard.impl.monitor;

import java.io.Serializable;

/**
 * 整数コード出力項目と出力先レジスタの割付モデル。
 */
public class IoStatusCodeBinding implements Serializable {
	private static final long serialVersionUID = 1L;

	private final StatusCodeOutputAction action;
	private String ioName;

	public IoStatusCodeBinding(StatusCodeOutputAction action, String ioName) {
		this.action = action;
		this.ioName = ioName;
	}

	public StatusCodeOutputAction getAction() {
		return action;
	}

	public String getIoName() {
		return ioName;
	}

	public void setIoName(String ioName) {
		this.ioName = ioName;
	}

	public boolean isAssigned() {
		return ioName != null && !ioName.trim().isEmpty() && !IoConstants.NOT_USED.equals(ioName);
	}
}
