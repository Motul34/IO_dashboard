package com.ad287483.IO_dashboard.impl.style;

import java.awt.Dimension;

public abstract class Style {
	private static final int HORIZONTAL_SPACING = 10;
	private static final int VERTICAL_SPACING = 10;
	private static final int LARGE_VERTICAL_SPACING = 20;
	private static final int XLARGE_VERTICAL_SPACING = 35;
	private static final int SMALL_HEADER_FONT_SIZE = 15;

	public int getHorizontalSpacing() {
		return HORIZONTAL_SPACING;
	}

	public int getVerticalSpacing() {
		return VERTICAL_SPACING;
	}

	public int getLargeVerticalSpacing() {
		return LARGE_VERTICAL_SPACING;
	}

	public int getExtraLargeVerticalSpacing() {
		return XLARGE_VERTICAL_SPACING;
	}

	public int getSmallHeaderFontSize() {
		return SMALL_HEADER_FONT_SIZE;
	}

	public abstract Dimension getInputfieldSize();
}
