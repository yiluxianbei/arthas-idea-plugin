package com.github.idea.arthas.plugin.ui;

import com.github.idea.arthas.plugin.common.command.CommandContext;
import com.github.idea.arthas.plugin.common.enums.ShellScriptCommandEnum;
import com.github.idea.arthas.plugin.constants.ArthasCommandConstants;
import com.github.idea.arthas.plugin.setting.AppSettingsState;
import com.github.idea.arthas.plugin.utils.ActionLinkUtils;
import com.github.idea.arthas.plugin.utils.ClipboardUtils;
import com.github.idea.arthas.plugin.utils.MethodCallGraphUtils;
import com.github.idea.arthas.plugin.utils.NotifyUtils;
import com.github.idea.arthas.plugin.utils.OgnlPsUtils;
import com.github.idea.arthas.plugin.utils.StringUtils;
import com.github.idea.arthas.plugin.utils.TraceExclusionRules;
import com.github.idea.arthas.plugin.utils.TracePatternUtils;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.components.ActionLink;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 多层级 trace 命令生成弹窗。
 *
 * @author arthas idea plugin
 */
public class ArthasTraceDepthDialog extends JDialog {

    private static final Logger LOG = Logger.getInstance(ArthasTraceDepthDialog.class);

    private JPanel contentPane;
    private JSpinner depthSpinner;
    private JLabel statsLabel;
    private JTextArea excludePackagesTextArea;
    private JTextArea excludeClassesTextArea;
    private JTextArea excludeMethodsTextArea;
    private JTextArea commandTextArea;
    private JButton copyButton;
    private JButton closeButton;
    private ActionLink traceHelp;
    private Timer exclusionRefreshTimer;

    private final Project project;
    private final PsiMethod method;
    private final Editor editor;

    public ArthasTraceDepthDialog(Project project, PsiMethod method, Editor editor) {
        this.project = project;
        this.method = method;
        this.editor = editor;

        setContentPane(contentPane);
        setModal(true);
        getRootPane().setDefaultButton(copyButton);
        depthSpinner.setModel(new SpinnerNumberModel(2, 1, 10, 1));
        loadExclusionSettings();
        initEvent();
        refreshCommand();
    }

    private void initEvent() {
        depthSpinner.addChangeListener(e -> {
            updateExclusionFieldsEnabled();
            refreshCommand();
        });
        copyButton.addActionListener(e -> onCopy());
        closeButton.addActionListener(e -> dispose());

        DocumentListener exclusionListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleExclusionRefresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                scheduleExclusionRefresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleExclusionRefresh();
            }
        };
        excludePackagesTextArea.getDocument().addDocumentListener(exclusionListener);
        excludeClassesTextArea.getDocument().addDocumentListener(exclusionListener);
        excludeMethodsTextArea.getDocument().addDocumentListener(exclusionListener);

        exclusionRefreshTimer = new Timer(300, e -> refreshCommand());
        exclusionRefreshTimer.setRepeats(false);
        updateExclusionFieldsEnabled();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dispose();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                if (exclusionRefreshTimer != null) {
                    exclusionRefreshTimer.stop();
                }
                saveExclusionSettings();
            }
        });
        contentPane.registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
    }

    private void refreshCommand() {
        int depth = (Integer) depthSpinner.getValue();
        CommandBuildResult result;
        try {
            result = ReadAction.compute(() -> buildCommand(depth));
        } catch (Exception e) {
            LOG.warn("[arthas] build multi-level trace command failed", e);
            commandTextArea.setText("");
            statsLabel.setText("解析调用链失败: " + e.getMessage());
            return;
        }

        commandTextArea.setText(result.command);
        commandTextArea.setCaretPosition(0);

        StringBuilder stats = new StringBuilder();
        stats.append("层级 ").append(result.depth)
                .append("，类 ").append(result.classCount)
                .append(" 个，方法 ").append(result.methodCount)
                .append(" 个");
        if (result.depth <= 1) {
            stats.append("<br/><font color='#666666'>深度 1 使用原始 Trace 命令，排除规则不生效</font>");
        }
        if (result.invalidRuleCount > 0) {
            stats.append("<br/><font color='#cc6600'>无效排除规则 ")
                    .append(result.invalidRuleCount)
                    .append(" 条，请检查输入</font>");
        }
        if (result.classCount > 50 || result.command.length() > 2000) {
            stats.append("<br/><font color='#cc6600'>命令较长，建议减小层级或手动编辑预览</font>");
        }
        statsLabel.setText("<html>" + stats + "</html>");
    }

    private CommandBuildResult buildCommand(int depth) {
        if (depth <= 1) {
            CommandContext commandContext = new CommandContext(project, method);
            String command = ShellScriptCommandEnum.TRACE.getArthasCommand(commandContext);
            return new CommandBuildResult(command, 1, 1, 1, 0);
        }

        TraceExclusionRules exclusions = TraceExclusionRules.parse(
                excludePackagesTextArea.getText(),
                excludeClassesTextArea.getText(),
                excludeMethodsTextArea.getText());
        List<Set<PsiMethod>> layers = MethodCallGraphUtils.collectLayers(method, depth, exclusions);
        if (layers.isEmpty()) {
            return new CommandBuildResult("", 0, 0, 0, exclusions.getInvalidRuleCount());
        }

        Set<String> classNames = new LinkedHashSet<>();
        Set<String> methodNames = new LinkedHashSet<>();
        for (Set<PsiMethod> layer : layers) {
            for (PsiMethod psiMethod : layer) {
                String className = OgnlPsUtils.getCommonOrInnerOrAnonymousClassName(psiMethod);
                String methodName = OgnlPsUtils.getMethodName(psiMethod);
                if (StringUtils.isNotBlank(className)) {
                    classNames.add(className);
                    // 类名无法解析时不能只把方法名加入命令，否则会跨类误匹配。
                    if (StringUtils.isNotBlank(methodName)) {
                        methodNames.add(methodName);
                    }
                }
            }
        }
        if (classNames.isEmpty()) {
            String className = OgnlPsUtils.getCommonOrInnerOrAnonymousClassName(method);
            if (StringUtils.isNotBlank(className)) {
                classNames.add(className);
            }
        }
        if (methodNames.isEmpty()) {
            methodNames.add(OgnlPsUtils.getMethodName(method));
        }

        AppSettingsState settings = AppSettingsState.getInstance(project);
        List<String> parts = new ArrayList<>();
        parts.add("trace -E");
        parts.add(TracePatternUtils.joinEscaped(classNames));
        parts.add(TracePatternUtils.joinEscaped(methodNames));
        if (settings.printConditionExpress) {
            parts.add("-v");
        }
        parts.add("-n");
        String invokeCount = StringUtils.isBlank(settings.invokeCount)
                ? ArthasCommandConstants.INVOKE_COUNT : settings.invokeCount;
        parts.add(invokeCount);
        if (!settings.traceSkipJdk) {
            parts.add(ArthasCommandConstants.DEFAULT_SKIP_JDK_FALSE);
        }
        if (settings.conditionExpressDisplay) {
            parts.add(ArthasCommandConstants.DEFAULT_CONDITION_EXPRESS);
        }
        String command = String.join(" ", parts);
        return new CommandBuildResult(command, layers.size(), classNames.size(), methodNames.size(),
                exclusions.getInvalidRuleCount());
    }

    private void loadExclusionSettings() {
        AppSettingsState settings = AppSettingsState.getInstance(project);
        excludePackagesTextArea.setText(nullToEmpty(settings.traceExcludePackages));
        excludeClassesTextArea.setText(nullToEmpty(settings.traceExcludeClasses));
        excludeMethodsTextArea.setText(nullToEmpty(settings.traceExcludeMethods));
    }

    private void saveExclusionSettings() {
        AppSettingsState settings = AppSettingsState.getInstance(project);
        settings.traceExcludePackages = excludePackagesTextArea.getText();
        settings.traceExcludeClasses = excludeClassesTextArea.getText();
        settings.traceExcludeMethods = excludeMethodsTextArea.getText();
    }

    private void scheduleExclusionRefresh() {
        if (exclusionRefreshTimer != null) {
            exclusionRefreshTimer.restart();
        }
    }

    private void updateExclusionFieldsEnabled() {
        boolean enabled = ((Integer) depthSpinner.getValue()) > 1;
        excludePackagesTextArea.setEnabled(enabled);
        excludeClassesTextArea.setEnabled(enabled);
        excludeMethodsTextArea.setEnabled(enabled);
        String tooltip = enabled
                ? "每行一条；包规则 com.foo 仅当前包，com.foo.* 含子包"
                : "深度 1 使用原始 Trace 命令，排除规则不生效";
        excludePackagesTextArea.setToolTipText(tooltip);
        excludeClassesTextArea.setToolTipText(tooltip);
        excludeMethodsTextArea.setToolTipText(tooltip);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void onCopy() {
        String command = commandTextArea.getText();
        if (StringUtils.isBlank(command)) {
            return;
        }
        command = command.trim();
        ClipboardUtils.setClipboardString(command);
        dispose();
        NotifyUtils.notifyMessageOpenTerminal(project, NotifyUtils.COMMAND_COPIED, command, editor);
    }

    public void open() {
        setTitle("arthas trace 多层级");
        pack();
        setLocationRelativeTo(WindowManager.getInstance().getFrame(project));
        setVisible(true);
    }

    /**
     * 自定义 UI 系统自动调用。
     */
    private void createUIComponents() {
        traceHelp = ActionLinkUtils.newActionLink("https://arthas.aliyun.com/doc/trace.html");
    }

    private static final class CommandBuildResult {
        private final String command;
        private final int depth;
        private final int classCount;
        private final int methodCount;
        private final int invalidRuleCount;

        private CommandBuildResult(String command, int depth, int classCount, int methodCount,
                                   int invalidRuleCount) {
            this.command = command;
            this.depth = depth;
            this.classCount = classCount;
            this.methodCount = methodCount;
            this.invalidRuleCount = invalidRuleCount;
        }
    }
}