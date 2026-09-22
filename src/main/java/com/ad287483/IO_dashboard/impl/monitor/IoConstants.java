package com.ad287483.IO_dashboard.impl.monitor;

/**
 * IO ダッシュボード全体で共有される IO 名、レジスタ名、未割り当て定数。
 */
public final class IoConstants {

	private IoConstants() {}

	/** 未割り当てを表す文字列: (未割り当て) */
	public static final String UNASSIGNED = "(未割り当て)";

	/** 使用しないを表す文字列: (使用しない) */
	public static final String NOT_USED = "(使用しない)";

	/** 汎用ブール入力レジスタプレフィックス */
	public static final String INPUT_BIT_PREFIX = "input_bit_register_";

	/** 汎用ブール出力レジスタプレフィックス */
	public static final String OUTPUT_BIT_PREFIX = "output_bit_register_";

	/** 汎用整数出力レジスタプレフィックス */
	public static final String OUTPUT_INT_PREFIX = "output_int_register_";

	/** 汎用ブール入力レジスタ最大インデックス (0〜64) */
	public static final int MAX_INPUT_BIT_INDEX = 64;

	/** 汎用ブール出力レジスタ最大インデックス (0〜64) */
	public static final int MAX_OUTPUT_BIT_INDEX = 64;

	/** 汎用整数出力レジスタ最大インデックス (0〜47) */
	public static final int MAX_OUTPUT_INT_INDEX = 47;
}
