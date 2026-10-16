package com.ad287483.IO_dashboard.impl.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * IO Dashboard URCap 全体の多言語（日本語 / 英語）リソース管理クラス。
 * PolyScope の日本語ロケール ("jp" または "ja") を判定し、それ以外の言語では英語 (ルートバンドル) にフォールバックする。
 */
public final class I18n {

	private static final String BUNDLE_BASE_NAME = "com/ad287483/IO_dashboard/impl/i18n/messages";
	private static final UTF8Control UTF8_CONTROL = new UTF8Control();

	private static volatile Locale currentLocale = normalizeLocale(Locale.getDefault());
	private static volatile ResourceBundle currentBundle = loadBundle(currentLocale);

	private I18n() {
	}

	/**
	 * PolyScope 等から取得した Locale を設定する。
	 * "ja" または PolyScope 固有の "jp" の場合は Locale.JAPANESE に正規化し、
	 * それ以外のすべての言語（英語等）は Locale.ROOT (英語デフォルトバンドル) に正規化する。
	 */
	public static synchronized void setLocale(Locale locale) {
		Locale normalized = normalizeLocale(locale);
		currentLocale = normalized;
		currentBundle = loadBundle(normalized);
	}

	public static Locale getLocale() {
		return currentLocale;
	}

	public static boolean isJapanese() {
		return Locale.JAPANESE.equals(currentLocale);
	}

	private static Locale normalizeLocale(Locale locale) {
		if (locale == null) {
			return Locale.ROOT;
		}
		String lang = locale.getLanguage();
		if ("ja".equalsIgnoreCase(lang) || "jp".equalsIgnoreCase(lang)) {
			return Locale.JAPANESE;
		}
		return Locale.ROOT;
	}

	private static ResourceBundle loadBundle(Locale normalizedLocale) {
		return ResourceBundle.getBundle(
				BUNDLE_BASE_NAME,
				normalizedLocale,
				I18n.class.getClassLoader(),
				UTF8_CONTROL
		);
	}

	/**
	 * 指定したキーの翻訳文字列を取得する。
	 */
	public static String t(String key) {
		if (key == null) {
			return "";
		}
		try {
			return currentBundle.getString(key);
		} catch (Exception e) {
			return "!" + key;
		}
	}

	/**
	 * 指定したキーの翻訳文字列に引数を埋め込んで取得する。
	 */
	public static String t(String key, Object... args) {
		String pattern = t(key);
		if (args == null || args.length == 0 || pattern.startsWith("!")) {
			return pattern;
		}
		try {
			return MessageFormat.format(pattern, args);
		} catch (Exception e) {
			return pattern;
		}
	}
}
