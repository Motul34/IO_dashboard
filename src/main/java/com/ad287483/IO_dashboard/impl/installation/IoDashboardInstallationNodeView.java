package com.ad287483.IO_dashboard.impl.installation;

import com.ad287483.IO_dashboard.impl.client.RobotStatus;
import com.ad287483.IO_dashboard.impl.monitor.CommandAction;
import com.ad287483.IO_dashboard.impl.monitor.StatusCodeOutputAction;
import com.ad287483.IO_dashboard.impl.monitor.StatusOutputAction;
import com.ad287483.IO_dashboard.impl.style.Style;
import com.ur.urcap.api.contribution.installation.swing.SwingInstallationNodeView;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PolyScope 5 完全準拠の Swing UI ビュー。
 * ルートコンテナの制約を遵守し、Box レイアウトと setVisible() によるタブ切替を採用。
 */
public class IoDashboardInstallationNodeView implements SwingInstallationNodeView<IoDashboardInstallationNodeContribution> {

	private final Style style;

	// ヘッダー部
	private JCheckBox enableCheckBox;
	private JLabel dashboardStatusLabel;
	private JLabel primaryStatusLabel;

	// ナビゲーションボタン (タブ切替)
	private JButton tabBtnInput;
	private JButton tabBtnOutputBool;
	private JButton tabBtnOutputCode;
	private JButton tabBtnLog;

	// 各タブのコンテナパネル
	private JPanel panelInput;
	private JPanel panelOutputBool;
	private JPanel panelOutputCode;
	private JPanel panelLog;
	private int activeTabIndex = 0;

	// 入力制御マッピング用コンポーネント
	private final Map<CommandAction, JComboBox> inputIoComboBoxes = new HashMap<CommandAction, JComboBox>();
	private final Map<CommandAction, JLabel> inputStatusLabels = new HashMap<CommandAction, JLabel>();
	private final Map<CommandAction, JButton> testActionButtons = new HashMap<CommandAction, JButton>();
	private JTextField programPathField;

	// 出力状態マッピング用コンポーネント (ブール)
	private final Map<StatusOutputAction, JComboBox> outputIoComboBoxes = new HashMap<StatusOutputAction, JComboBox>();
	private final Map<StatusOutputAction, JLabel> outputStatusLabels = new HashMap<StatusOutputAction, JLabel>();

	// 出力状態マッピング用コンポーネント (整数コード)
	private final Map<StatusCodeOutputAction, JComboBox> codeIoComboBoxes = new HashMap<StatusCodeOutputAction, JComboBox>();
	private final Map<StatusCodeOutputAction, JLabel> codeStatusLabels = new HashMap<StatusCodeOutputAction, JLabel>();

	// ハンドシェイク出力用
	private JComboBox busyIoComboBox;
	private JComboBox doneIoComboBox;
	private JComboBox errorIoComboBox;
	private JComboBox errorCodeIoComboBox;

	// ログ表示部
	private JTextPane logTextPane;
	private JButton clearLogButton;

	// UI再描画抑制のための値キャッシュ (スライダーの引っかかり・イベント遅延を根絶)
	private final Map<CommandAction, Boolean> lastInputStatusCache = new HashMap<CommandAction, Boolean>();
	private final Map<StatusOutputAction, Boolean> lastOutputStatusCache = new HashMap<StatusOutputAction, Boolean>();
	private final Map<StatusCodeOutputAction, Integer> lastCodeStatusCache = new HashMap<StatusCodeOutputAction, Integer>();
	private Boolean lastDashboardConnected = null;
	private Boolean lastPrimaryConnected = null;
	private int lastPrimaryPort = -1;

	public IoDashboardInstallationNodeView(Style style) {
		this.style = style;
	}

	@Override
	public void buildUI(JPanel panel, final IoDashboardInstallationNodeContribution contribution) {
		// PolyScope 必須: ルート panel は BoxLayout.Y_AXIS のみ、setBorder は呼ばない
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

		// 1. ヘッダー (有効化トグル & 接続ステータス)
		panel.add(createHeaderBox(contribution));
		panel.add(Box.createRigidArea(new Dimension(0, 10)));

		// 2. ナビゲーションバー (タブ選択ボタン)
		panel.add(createNavBar());
		panel.add(Box.createRigidArea(new Dimension(0, 10)));

		// 3. 各タブコンテンツ
		panelInput = createInputControlPanel(contribution);
		panelOutputBool = createOutputBoolPanel(contribution);
		panelOutputCode = createOutputCodePanel(contribution);
		panelLog = createLogPanel(contribution);

		panel.add(panelInput);
		panel.add(panelOutputBool);
		panel.add(panelOutputCode);
		panel.add(panelLog);

		// 初期表示は「入力制御」タブ
		switchTab(0);
	}

	private Component createHeaderBox(final IoDashboardInstallationNodeContribution contribution) {
		Box box = Box.createHorizontalBox();
		box.setAlignmentX(Component.LEFT_ALIGNMENT);

		enableCheckBox = new JCheckBox("IO Dashboard 監視サービスを有効にする");
		enableCheckBox.setFont(new Font("SansSerif", Font.BOLD, 13));
		enableCheckBox.setFocusPainted(false);
		enableCheckBox.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				contribution.onEnableToggled(enableCheckBox.isSelected());
			}
		});
		box.add(enableCheckBox);

		box.add(Box.createRigidArea(new Dimension(20, 0)));
		dashboardStatusLabel = new JLabel("Dashboard (29999): 確認中");
		dashboardStatusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
		box.add(dashboardStatusLabel);

		box.add(Box.createRigidArea(new Dimension(15, 0)));
		primaryStatusLabel = new JLabel("Primary (30002): 確認中");
		primaryStatusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
		box.add(primaryStatusLabel);

		box.add(Box.createRigidArea(new Dimension(15, 0)));
		JButton reconnectBtn = new JButton("再接続テスト");
		reconnectBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
		reconnectBtn.setFocusPainted(false);
		reconnectBtn.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				contribution.testConnection();
			}
		});
		box.add(reconnectBtn);

		box.add(Box.createHorizontalGlue());
		return box;
	}

	private Component createNavBar() {
		Box navBox = Box.createHorizontalBox();
		navBox.setAlignmentX(Component.LEFT_ALIGNMENT);

		tabBtnInput = new JButton("① 入力制御 (PLC → UR)");
		tabBtnOutputBool = new JButton("② 出力状態 (UR → PLC: ビット)");
		tabBtnOutputCode = new JButton("③ 整数コード & ハンドシェイク");
		tabBtnLog = new JButton("④ 通信ログ");

		final JButton[] buttons = {tabBtnInput, tabBtnOutputBool, tabBtnOutputCode, tabBtnLog};
		for (int i = 0; i < buttons.length; i++) {
			final int index = i;
			buttons[i].setFont(new Font("SansSerif", Font.BOLD, 12));
			buttons[i].setFocusPainted(false);
			buttons[i].addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					switchTab(index);
				}
			});
			navBox.add(buttons[i]);
			if (i < buttons.length - 1) {
				navBox.add(Box.createRigidArea(new Dimension(6, 0)));
			}
		}

		navBox.add(Box.createHorizontalGlue());
		return navBox;
	}

	private void switchTab(int index) {
		this.activeTabIndex = index;
		if (panelInput != null) panelInput.setVisible(index == 0);
		if (panelOutputBool != null) panelOutputBool.setVisible(index == 1);
		if (panelOutputCode != null) panelOutputCode.setVisible(index == 2);
		if (panelLog != null) panelLog.setVisible(index == 3);

		// ボタンの強調表示
		JButton[] buttons = {tabBtnInput, tabBtnOutputBool, tabBtnOutputCode, tabBtnLog};
		for (int i = 0; i < buttons.length; i++) {
			if (buttons[i] != null) {
				buttons[i].setSelected(i == index);
				buttons[i].setFont(new Font("SansSerif", i == index ? Font.BOLD : Font.PLAIN, 12));
			}
		}
	}

	public boolean isLogTabActive() {
		return activeTabIndex == 3;
	}

	// ------------------ ① 入力制御タブ ------------------
	private JPanel createInputControlPanel(final IoDashboardInstallationNodeContribution contribution) {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel descLabel = new JLabel("PLCからのブール入力 (立ち上がりエッジ 0→1) で実行するコマンドを割り当てます。");
		descLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
		descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(descLabel);
		panel.add(Box.createRigidArea(new Dimension(0, 8)));

		for (final CommandAction action : CommandAction.values()) {
			Box row = Box.createHorizontalBox();
			row.setAlignmentX(Component.LEFT_ALIGNMENT);

			JLabel nameLabel = new JLabel(action.getDisplayName());
			nameLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
			nameLabel.setPreferredSize(new Dimension(230, 26));
			row.add(nameLabel);

			final JComboBox combo = new JComboBox();
			combo.setFont(new Font("SansSerif", Font.PLAIN, 11));
			combo.setPreferredSize(new Dimension(175, 26));
			combo.setMaximumSize(new Dimension(175, 26));
			combo.setMaximumRowCount(14);
			combo.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					if (e.getStateChange() == ItemEvent.SELECTED) {
						contribution.onCommandIoSelected(action, (String) combo.getSelectedItem());
					}
				}
			});
			inputIoComboBoxes.put(action, combo);
			row.add(combo);
			row.add(Box.createRigidArea(new Dimension(6, 0)));

			JButton numBtn = new JButton("番号入力");
			numBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
			numBtn.setPreferredSize(new Dimension(75, 26));
			numBtn.setFocusPainted(false);
			numBtn.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					promptRegisterInput(combo, "input_bit_register_", 0, 127);
				}
			});
			row.add(numBtn);
			row.add(Box.createRigidArea(new Dimension(10, 0)));

			JLabel statusLabel = new JLabel("0");
			statusLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
			statusLabel.setPreferredSize(new Dimension(50, 26));
			inputStatusLabels.put(action, statusLabel);
			row.add(statusLabel);
			row.add(Box.createRigidArea(new Dimension(10, 0)));

			JButton testBtn = new JButton("実行");
			testBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
			testBtn.setPreferredSize(new Dimension(65, 24));
			testBtn.setFocusPainted(false);
			testBtn.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					contribution.executeActionManually(action);
				}
			});
			testActionButtons.put(action, testBtn);
			row.add(testBtn);

			row.add(Box.createHorizontalGlue());
			panel.add(row);
			panel.add(Box.createRigidArea(new Dimension(0, 4)));

			// プログラムロード専用のパス入力行
			if (action == CommandAction.LOAD_PROGRAM) {
				Box pathRow = Box.createHorizontalBox();
				pathRow.setAlignmentX(Component.LEFT_ALIGNMENT);
				JLabel pathPrompt = new JLabel("  └ 対象パス: ");
				pathPrompt.setFont(new Font("SansSerif", Font.ITALIC, 11));
				pathPrompt.setPreferredSize(new Dimension(90, 24));
				pathRow.add(pathPrompt);

				programPathField = new JTextField("/programs/default.urp");
				programPathField.setFont(new Font("SansSerif", Font.PLAIN, 11));
				programPathField.setPreferredSize(new Dimension(280, 24));
				programPathField.setMaximumSize(new Dimension(280, 24));
				pathRow.add(programPathField);
				pathRow.add(Box.createRigidArea(new Dimension(6, 0)));

				JButton savePathBtn = new JButton("保存");
				savePathBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
				savePathBtn.setPreferredSize(new Dimension(60, 24));
				savePathBtn.setFocusPainted(false);
				savePathBtn.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						contribution.onProgramPathChanged(programPathField.getText());
					}
				});
				pathRow.add(savePathBtn);
				pathRow.add(Box.createHorizontalGlue());

				panel.add(pathRow);
				panel.add(Box.createRigidArea(new Dimension(0, 4)));
			}
		}

		return panel;
	}

	// ------------------ ② 出力状態 (ビット) タブ ------------------
	private JPanel createOutputBoolPanel(final IoDashboardInstallationNodeContribution contribution) {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel descLabel = new JLabel("ロボット状態をEthernet/IP出力ビット(汎用ブール出力レジスタ/DO)へ常時追従して出力します。");
		descLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
		descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(descLabel);
		panel.add(Box.createRigidArea(new Dimension(0, 8)));

		for (final StatusOutputAction action : StatusOutputAction.values()) {
			Box row = Box.createHorizontalBox();
			row.setAlignmentX(Component.LEFT_ALIGNMENT);

			JLabel nameLabel = new JLabel(action.getDisplayName());
			nameLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
			nameLabel.setPreferredSize(new Dimension(230, 26));
			row.add(nameLabel);

			final JComboBox combo = new JComboBox();
			combo.setFont(new Font("SansSerif", Font.PLAIN, 11));
			combo.setPreferredSize(new Dimension(175, 26));
			combo.setMaximumSize(new Dimension(175, 26));
			combo.setMaximumRowCount(14);
			combo.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					if (e.getStateChange() == ItemEvent.SELECTED) {
						contribution.onStatusIoSelected(action, (String) combo.getSelectedItem());
					}
				}
			});
			outputIoComboBoxes.put(action, combo);
			row.add(combo);
			row.add(Box.createRigidArea(new Dimension(6, 0)));

			JButton numBtn = new JButton("番号入力");
			numBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
			numBtn.setPreferredSize(new Dimension(75, 26));
			numBtn.setFocusPainted(false);
			numBtn.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					promptRegisterInput(combo, "output_bit_register_", 0, 127);
				}
			});
			row.add(numBtn);
			row.add(Box.createRigidArea(new Dimension(10, 0)));

			JLabel statusLabel = new JLabel("0 (OFF)");
			statusLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
			statusLabel.setPreferredSize(new Dimension(65, 26));
			outputStatusLabels.put(action, statusLabel);
			row.add(statusLabel);

			JLabel desc = new JLabel(action.getDescription());
			desc.setFont(new Font("SansSerif", Font.PLAIN, 10));
			desc.setForeground(Color.GRAY);
			row.add(desc);

			row.add(Box.createHorizontalGlue());
			panel.add(row);
			panel.add(Box.createRigidArea(new Dimension(0, 4)));
		}

		return panel;
	}

	// ------------------ ③ 整数コード & ハンドシェイク タブ ------------------
	private JPanel createOutputCodePanel(final IoDashboardInstallationNodeContribution contribution) {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel h1 = new JLabel("【多値状態の整数コード出力 (output_int_register)】");
		h1.setFont(new Font("SansSerif", Font.BOLD, 12));
		h1.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(h1);
		panel.add(Box.createRigidArea(new Dimension(0, 6)));

		for (final StatusCodeOutputAction action : StatusCodeOutputAction.values()) {
			Box row = Box.createHorizontalBox();
			row.setAlignmentX(Component.LEFT_ALIGNMENT);

			JLabel lbl = new JLabel(action.getDisplayName());
			lbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
			lbl.setPreferredSize(new Dimension(240, 26));
			row.add(lbl);

			final JComboBox combo = new JComboBox();
			combo.setFont(new Font("SansSerif", Font.PLAIN, 11));
			combo.setPreferredSize(new Dimension(175, 26));
			combo.setMaximumSize(new Dimension(175, 26));
			combo.setMaximumRowCount(14);
			combo.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					if (e.getStateChange() == ItemEvent.SELECTED) {
						contribution.onStatusCodeIoSelected(action, (String) combo.getSelectedItem());
					}
				}
			});
			codeIoComboBoxes.put(action, combo);
			row.add(combo);
			row.add(Box.createRigidArea(new Dimension(6, 0)));

			JButton numBtn = new JButton("番号入力");
			numBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
			numBtn.setPreferredSize(new Dimension(75, 26));
			numBtn.setFocusPainted(false);
			numBtn.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					promptRegisterInput(combo, "output_int_register_", 0, 47);
				}
			});
			row.add(numBtn);
			row.add(Box.createRigidArea(new Dimension(10, 0)));

			JLabel valLbl = new JLabel("0");
			valLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
			valLbl.setPreferredSize(new Dimension(40, 26));
			codeStatusLabels.put(action, valLbl);
			row.add(valLbl);

			JLabel desc = new JLabel(action.getDescription());
			desc.setFont(new Font("SansSerif", Font.PLAIN, 10));
			desc.setForeground(Color.GRAY);
			row.add(desc);

			row.add(Box.createHorizontalGlue());
			panel.add(row);
			panel.add(Box.createRigidArea(new Dimension(0, 4)));
		}

		panel.add(Box.createRigidArea(new Dimension(0, 10)));
		JLabel h2 = new JLabel("【コマンド実行ハンドシェイク信号 (Busy / Done / Error)】");
		h2.setFont(new Font("SansSerif", Font.BOLD, 12));
		h2.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(h2);
		panel.add(Box.createRigidArea(new Dimension(0, 6)));

		// Busy
		// Busy
		busyIoComboBox = createHandshakeCombo(panel, "Command Busy (処理中):", false, contribution);
		// Done
		doneIoComboBox = createHandshakeCombo(panel, "Command Done (正常完了):", false, contribution);
		// Error
		errorIoComboBox = createHandshakeCombo(panel, "Command Error (実行エラー):", false, contribution);
		// ErrorCode
		errorCodeIoComboBox = createHandshakeCombo(panel, "Last Error Code 出力先:", true, contribution);

		return panel;
	}

	private JComboBox createHandshakeCombo(JPanel parent, String title, final boolean isInteger, final IoDashboardInstallationNodeContribution contribution) {
		Box row = Box.createHorizontalBox();
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel lbl = new JLabel(title);
		lbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
		lbl.setPreferredSize(new Dimension(240, 26));
		row.add(lbl);

		final JComboBox combo = new JComboBox();
		combo.setFont(new Font("SansSerif", Font.PLAIN, 11));
		combo.setPreferredSize(new Dimension(175, 26));
		combo.setMaximumSize(new Dimension(175, 26));
		combo.setMaximumRowCount(14);
		combo.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				if (e.getStateChange() == ItemEvent.SELECTED) {
					contribution.onHandshakeChanged();
				}
			}
		});
		row.add(combo);
		row.add(Box.createRigidArea(new Dimension(6, 0)));

		JButton numBtn = new JButton("番号入力");
		numBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
		numBtn.setPreferredSize(new Dimension(75, 26));
		numBtn.setFocusPainted(false);
		numBtn.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				String prefix = isInteger ? "output_int_register_" : "output_bit_register_";
				int max = isInteger ? 47 : 127;
				promptRegisterInput(combo, prefix, 0, max);
			}
		});
		row.add(numBtn);
		row.add(Box.createHorizontalGlue());

		parent.add(row);
		parent.add(Box.createRigidArea(new Dimension(0, 4)));
		return combo;
	}

	// ------------------ ④ 通信ログ タブ ------------------
	private JPanel createLogPanel(final IoDashboardInstallationNodeContribution contribution) {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		Box top = Box.createHorizontalBox();
		top.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel title = new JLabel("Dashboard Server 通信ログ (直近50件)");
		title.setFont(new Font("SansSerif", Font.BOLD, 12));
		top.add(title);
		top.add(Box.createHorizontalGlue());

		clearLogButton = new JButton("ログクリア");
		clearLogButton.setFont(new Font("SansSerif", Font.PLAIN, 11));
		clearLogButton.setFocusPainted(false);
		clearLogButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				contribution.clearLogs();
			}
		});
		top.add(clearLogButton);
		panel.add(top);
		panel.add(Box.createRigidArea(new Dimension(0, 6)));

		logTextPane = new JTextPane();
		logTextPane.setEditable(false);
		logTextPane.setFont(new Font("Monospaced", Font.PLAIN, 11));
		logTextPane.setPreferredSize(new Dimension(650, 300));
		logTextPane.setMaximumSize(new Dimension(750, 320));
		logTextPane.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(logTextPane);

		return panel;
	}

	// ------------------ UI 更新メソッド群 ------------------

	public void setEnabledCheckbox(boolean enabled) {
		if (enableCheckBox != null) {
			enableCheckBox.setSelected(enabled);
		}
	}

	public void setConnectionStatus(boolean dashboardConnected, boolean primaryConnected, int primaryPort) {
		if (lastDashboardConnected == null || lastDashboardConnected.booleanValue() != dashboardConnected) {
			lastDashboardConnected = dashboardConnected;
			if (dashboardStatusLabel != null) {
				if (dashboardConnected) {
					dashboardStatusLabel.setText("Dashboard (29999): 接続中");
					dashboardStatusLabel.setForeground(new Color(0, 150, 0));
				} else {
					dashboardStatusLabel.setText("Dashboard: 切断");
					dashboardStatusLabel.setForeground(Color.RED);
				}
			}
		}
		if (lastPrimaryConnected == null || lastPrimaryConnected.booleanValue() != primaryConnected || lastPrimaryPort != primaryPort) {
			lastPrimaryConnected = primaryConnected;
			lastPrimaryPort = primaryPort;
			if (primaryStatusLabel != null) {
				if (primaryConnected) {
					primaryStatusLabel.setText("Primary (" + primaryPort + "): 接続中");
					primaryStatusLabel.setForeground(new Color(0, 150, 0));
				} else {
					primaryStatusLabel.setText("Primary: 切断");
					primaryStatusLabel.setForeground(Color.RED);
				}
			}
		}
	}

	public void populateInputIoOptions(List<String> inputOptions) {
		for (JComboBox combo : inputIoComboBoxes.values()) {
			updateComboItems(combo, inputOptions);
		}
	}

	public void populateOutputBoolOptions(List<String> outputOptions) {
		for (JComboBox combo : outputIoComboBoxes.values()) {
			updateComboItems(combo, outputOptions);
		}
		updateComboItems(busyIoComboBox, outputOptions);
		updateComboItems(doneIoComboBox, outputOptions);
		updateComboItems(errorIoComboBox, outputOptions);
	}

	public void populateOutputIntOptions(List<String> intOptions) {
		for (JComboBox combo : codeIoComboBoxes.values()) {
			updateComboItems(combo, intOptions);
		}
		updateComboItems(errorCodeIoComboBox, intOptions);
	}

	private void updateComboItems(JComboBox combo, List<String> items) {
		if (combo == null) return;
		Object selected = combo.getSelectedItem();
		combo.removeAllItems();
		for (String item : items) {
			combo.addItem(item);
		}
		if (selected != null && items.contains(selected)) {
			combo.setSelectedItem(selected);
		}
	}

	public void selectCommandIo(CommandAction action, String ioName) {
		JComboBox combo = inputIoComboBoxes.get(action);
		if (combo != null && ioName != null) {
			combo.setSelectedItem(ioName);
		}
	}

	public void selectStatusIo(StatusOutputAction action, String ioName) {
		JComboBox combo = outputIoComboBoxes.get(action);
		if (combo != null && ioName != null) {
			combo.setSelectedItem(ioName);
		}
	}

	public void selectStatusCodeIo(StatusCodeOutputAction action, String ioName) {
		JComboBox combo = codeIoComboBoxes.get(action);
		if (combo != null && ioName != null) {
			combo.setSelectedItem(ioName);
		}
	}

	public void setProgramPath(String path) {
		if (programPathField != null && path != null) {
			programPathField.setText(path);
		}
	}

	public String getProgramPath() {
		return programPathField != null ? programPathField.getText() : "";
	}

	private void promptRegisterInput(JComboBox combo, String prefix, int min, int max) {
		String input = JOptionPane.showInputDialog(
				null,
				"レジスタ番号を入力してください (" + min + " 〜 " + max + "):",
				"レジスタ直接指定",
				JOptionPane.QUESTION_MESSAGE
		);
		if (input != null && !input.trim().isEmpty()) {
			try {
				int num = Integer.parseInt(input.trim());
				if (num >= min && num <= max) {
					String targetName = prefix + num;
					boolean found = false;
					for (int i = 0; i < combo.getItemCount(); i++) {
						if (targetName.equalsIgnoreCase(String.valueOf(combo.getItemAt(i)))) {
							combo.setSelectedIndex(i);
							found = true;
							break;
						}
					}
					if (!found) {
						combo.addItem(targetName);
						combo.setSelectedItem(targetName);
					}
				} else {
					JOptionPane.showMessageDialog(null, "番号は " + min + " 〜 " + max + " の範囲で入力してください。", "範囲外エラー", JOptionPane.WARNING_MESSAGE);
				}
			} catch (NumberFormatException ex) {
				JOptionPane.showMessageDialog(null, "半角数字を入力してください。", "入力エラー", JOptionPane.ERROR_MESSAGE);
			}
		}
	}

	public void selectHandshakeIos(String busy, String done, String error, String errCode) {
		if (busyIoComboBox != null && busy != null) busyIoComboBox.setSelectedItem(busy);
		if (doneIoComboBox != null && done != null) doneIoComboBox.setSelectedItem(done);
		if (errorIoComboBox != null && error != null) errorIoComboBox.setSelectedItem(error);
		if (errorCodeIoComboBox != null && errCode != null) errorCodeIoComboBox.setSelectedItem(errCode);
	}

	public String getBusyIo() { return busyIoComboBox != null ? (String) busyIoComboBox.getSelectedItem() : ""; }
	public String getDoneIo() { return doneIoComboBox != null ? (String) doneIoComboBox.getSelectedItem() : ""; }
	public String getErrorIo() { return errorIoComboBox != null ? (String) errorIoComboBox.getSelectedItem() : ""; }
	public String getErrorCodeIo() { return errorCodeIoComboBox != null ? (String) errorCodeIoComboBox.getSelectedItem() : ""; }

	public void updateInputStatus(CommandAction action, boolean val) {
		Boolean prev = lastInputStatusCache.get(action);
		if (prev != null && prev.booleanValue() == val) {
			return;
		}
		lastInputStatusCache.put(action, val);
		JLabel lbl = inputStatusLabels.get(action);
		if (lbl != null) {
			lbl.setText(val ? "1 (ON)" : "0 (OFF)");
			lbl.setForeground(val ? new Color(0, 150, 0) : Color.DARK_GRAY);
		}
	}

	public void updateOutputStatus(StatusOutputAction action, boolean val) {
		Boolean prev = lastOutputStatusCache.get(action);
		if (prev != null && prev.booleanValue() == val) {
			return;
		}
		lastOutputStatusCache.put(action, val);
		JLabel lbl = outputStatusLabels.get(action);
		if (lbl != null) {
			lbl.setText(val ? "1 (ON)" : "0 (OFF)");
			lbl.setForeground(val ? new Color(0, 150, 0) : Color.DARK_GRAY);
		}
	}

	public void updateCodeStatus(StatusCodeOutputAction action, int val) {
		Integer prev = lastCodeStatusCache.get(action);
		if (prev != null && prev.intValue() == val) {
			return;
		}
		lastCodeStatusCache.put(action, val);
		JLabel lbl = codeStatusLabels.get(action);
		if (lbl != null) {
			lbl.setText(String.valueOf(val));
		}
	}

	public void updateLogs(List<String> logs) {
		if (logTextPane == null) return;
		StringBuilder sb = new StringBuilder();
		for (String l : logs) {
			sb.append(l).append("\n");
		}
		logTextPane.setText(sb.toString());
		logTextPane.setCaretPosition(logTextPane.getDocument().getLength());
	}
}
