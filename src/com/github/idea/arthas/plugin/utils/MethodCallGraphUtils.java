package com.github.idea.arthas.plugin.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.SyntheticElement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 静态解析方法调用链，为 trace -E 生成匹配目标。
 *
 * @author arthas idea plugin
 */
public final class MethodCallGraphUtils {

    private static final Logger LOG = Logger.getInstance(MethodCallGraphUtils.class);

    /**
     * 日志方法名。
     */
    private static final Set<String> LOGGING_METHOD_NAMES = new HashSet<>(Arrays.asList(
            "trace", "debug", "info", "warn", "error", "fatal", "log"));

    /**
     * 常见日志框架的类名前缀。
     */
    private static final String[] LOGGER_CLASS_PREFIXES = new String[]{
            "org.slf4j.",
            "org.apache.logging.",
            "java.util.logging.",
            "org.apache.commons.logging.",
            "ch.qos.logback.",
            "org.apache.log4j."
    };

    /**
     * 项目内日志包装类的常见类名后缀。
     */
    private static final String[] LOGGING_CLASS_SUFFIXES = new String[]{
            "Log", "Logger", "Logs", "LogUtils", "LoggerUtils",
            "LogHelper", "LoggerHelper", "Logging", "LogService", "LoggerService",
            "LogFactory", "LoggerFactory", "LogHandler", "LoggerHandler",
            "LogWrapper", "LoggerWrapper", "LogAspect", "LogAdvice"
    };

    private MethodCallGraphUtils() {
    }

    /**
     * 按层收集从 root 向下 depth 层内的项目源码方法。
     * 第 0 层固定为 root，深度包含当前方法。
     */
    public static List<Set<PsiMethod>> collectLayers(PsiMethod root, int depth) {
        return collectLayers(root, depth, TraceExclusionRules.EMPTY);
    }

    /**
     * 按层收集从 root 向下 depth 层内的项目源码方法，并应用排除规则。
     * 第 0 层固定为 root，根方法始终保留。
     */
    public static List<Set<PsiMethod>> collectLayers(PsiMethod root, int depth, TraceExclusionRules exclusions) {
        List<Set<PsiMethod>> layers = new ArrayList<>();
        if (root == null || depth <= 0) {
            return layers;
        }
        TraceExclusionRules rules = exclusions == null ? TraceExclusionRules.EMPTY : exclusions;
        Set<PsiMethod> firstLayer = new LinkedHashSet<>();
        firstLayer.add(root);
        layers.add(firstLayer);

        Set<String> visited = new HashSet<>();
        visited.add(signature(root));

        Set<PsiMethod> current = firstLayer;
        for (int level = 1; level < depth; level++) {
            Set<PsiMethod> next = new LinkedHashSet<>();
            for (PsiMethod method : current) {
                collectCallees(method, next, visited, rules);
            }
            if (next.isEmpty()) {
                break;
            }
            layers.add(next);
            current = next;
        }
        return layers;
    }

    private static void collectCallees(PsiMethod method, Set<PsiMethod> next, Set<String> visited,
                                      TraceExclusionRules exclusions) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return;
        }
        try {
            body.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    addCallee(expression.resolveMethod(), next, visited, exclusions);
                    super.visitMethodCallExpression(expression);
                }

                @Override
                public void visitMethodReferenceExpression(PsiMethodReferenceExpression expression) {
                    PsiElement resolved = expression.resolve();
                    if (resolved instanceof PsiMethod) {
                        addCallee((PsiMethod) resolved, next, visited, exclusions);
                    }
                    super.visitMethodReferenceExpression(expression);
                }
            });
        } catch (IndexNotReadyException e) {
            LOG.info("[arthas] collect method call graph failed, index not ready", e);
        }
    }

    private static void addCallee(PsiMethod method, Set<PsiMethod> next, Set<String> visited,
                                  TraceExclusionRules exclusions) {
        if (method == null || method.isConstructor()) {
            return;
        }
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null
                || containingClass instanceof PsiAnonymousClass
                || containingClass.isAnnotationType()) {
            return;
        }
        if (exclusions.isExcluded(method)) {
            return;
        }
        if (!isProjectSourceMethod(method)) {
            return;
        }
        if (isSyntheticOrCompilerGenerated(method)) {
            return;
        }

        // equals 一律不 trace，包括接口/抽象方法中的 equals。
        if ("equals".equals(method.getName())) {
            return;
        }

        // 日志判断放在无方法体分支之前：项目内自定义 Logger 接口的 log/info 等
        // 也是接口方法，否则会被当成普通接口叶子加入 trace -E。
        if (isLoggingMethod(method)) {
            return;
        }

        if (method.getBody() == null) {
            // 接口/抽象方法没有方法体，无法继续向下展开；
            // 但仍然作为叶子匹配目标加入，Arthas -E 会通过子类搜索匹配到实现类。
            if (!containingClass.isInterface() && !method.hasModifierProperty(PsiModifier.ABSTRACT)) {
                return;
            }
        } else if (isEmptyMethod(method)
                || isTrivialGetterOrSetter(method)
                || isSimpleObjectMethod(method)) {
            return;
        }

        if (!visited.add(signature(method))) {
            return;
        }
        next.add(method);
    }

    /**
     * 是否为当前工程源码中的方法。
     */
    public static boolean isProjectSourceMethod(PsiMethod method) {
        PsiFile containingFile = method.getContainingFile();
        if (!(containingFile instanceof PsiJavaFile)) {
            return false;
        }
        VirtualFile virtualFile = containingFile.getVirtualFile();
        if (virtualFile == null) {
            return false;
        }
        return ProjectFileIndex.getInstance(method.getProject()).isInSource(virtualFile);
    }

    /**
     * 是否为编译器/框架生成的 synthetic 方法。
     */
    private static boolean isSyntheticOrCompilerGenerated(PsiMethod method) {
        if (method instanceof SyntheticElement) {
            return true;
        }
        if (!method.isPhysical()) {
            return true;
        }
        if (method.getNavigationElement() != method) {
            return true;
        }
        String name = method.getName();
        return name.startsWith("lambda$")
                || name.startsWith("access$")
                || "$deserializeLambda$".equals(name)
                || "$values".equals(name)
                || "$closeResource".equals(name)
                || name.startsWith("$default");
    }

    /**
     * 是否为空方法：没有语句，或只有一条 `return;`。
     */
    private static boolean isEmptyMethod(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return false;
        }
        PsiStatement[] statements = body.getStatements();
        if (statements.length == 0) {
            return true;
        }
        if (statements.length == 1 && statements[0] instanceof PsiReturnStatement) {
            return ((PsiReturnStatement) statements[0]).getReturnValue() == null;
        }
        return false;
    }

    /**
     * 是否为简单的 getter/setter（方法体只有一条 return 字段或赋值字段语句）。
     */
    private static boolean isTrivialGetterOrSetter(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return false;
        }
        PsiStatement[] statements = body.getStatements();
        if (statements.length != 1) {
            return false;
        }
        String name = method.getName();
        if (hasAccessorPrefix(name, "get") || hasAccessorPrefix(name, "is")) {
            if (method.getParameterList().getParametersCount() != 0) {
                return false;
            }
            if (!(statements[0] instanceof PsiReturnStatement)) {
                return false;
            }
            return isFieldReference(((PsiReturnStatement) statements[0]).getReturnValue());
        }
        if (hasAccessorPrefix(name, "set")) {
            if (method.getParameterList().getParametersCount() != 1) {
                return false;
            }
            if (!(statements[0] instanceof PsiExpressionStatement)) {
                return false;
            }
            PsiExpression expression = ((PsiExpressionStatement) statements[0]).getExpression();
            if (!(expression instanceof PsiAssignmentExpression)) {
                return false;
            }
            return isFieldReference(((PsiAssignmentExpression) expression).getLExpression());
        }
        return false;
    }

    private static boolean hasAccessorPrefix(String methodName, String prefix) {
        return methodName.length() > prefix.length()
                && methodName.startsWith(prefix)
                && Character.isUpperCase(methodName.charAt(prefix.length()));
    }

    private static boolean isFieldReference(PsiExpression expression) {
        if (!(expression instanceof PsiReferenceExpression)) {
            return false;
        }
        PsiElement resolved = ((PsiReferenceExpression) expression).resolve();
        return resolved instanceof PsiField;
    }

    /**
     * toString/hashCode 只在方法体很简单时过滤；equals 在调用方单独判断。
     */
    private static boolean isSimpleObjectMethod(PsiMethod method) {
        String name = method.getName();
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return false;
        }
        int parametersCount = method.getParameterList().getParametersCount();
        if (("toString".equals(name) || "hashCode".equals(name)) && parametersCount == 0) {
            PsiStatement[] statements = body.getStatements();
            return statements.length == 1
                    && statements[0] instanceof PsiReturnStatement
                    && ((PsiReturnStatement) statements[0]).getReturnValue() != null;
        }
        return false;
    }

    /**
     * 是否为项目内的日志方法。日志框架本身在依赖库中，通常已经被“非项目源码”过滤，
     * 这里主要处理项目内的日志包装类。
     */
    private static boolean isLoggingMethod(PsiMethod method) {
        String name = method.getName().toLowerCase(Locale.ENGLISH);
        if (!LOGGING_METHOD_NAMES.contains(name)) {
            return false;
        }
        PsiClass containingClass = method.getContainingClass();
        if (containingClass != null && isLoggingClassName(containingClass)) {
            return true;
        }
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return false;
        }
        final boolean[] hasLoggerCall = new boolean[]{false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                PsiMethod callee = expression.resolveMethod();
                if (callee != null && isLoggerClass(callee.getContainingClass())) {
                    hasLoggerCall[0] = true;
                }
                super.visitMethodCallExpression(expression);
            }

            @Override
            public void visitMethodReferenceExpression(PsiMethodReferenceExpression expression) {
                PsiElement resolved = expression.resolve();
                if (resolved instanceof PsiMethod
                        && isLoggerClass(((PsiMethod) resolved).getContainingClass())) {
                    hasLoggerCall[0] = true;
                }
                super.visitMethodReferenceExpression(expression);
            }
        });
        return hasLoggerCall[0];
    }

    private static boolean isLoggingClassName(PsiClass psiClass) {
        String name = psiClass.getName();
        if (name == null) {
            return false;
        }
        for (String suffix : LOGGING_CLASS_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLoggerClass(PsiClass psiClass) {
        if (psiClass == null) {
            return false;
        }
        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName == null) {
            return false;
        }
        for (String prefix : LOGGER_CLASS_PREFIXES) {
            if (qualifiedName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用于遍历去重的完整方法签名：类名#方法名(参数类型)。
     */
    public static String signature(PsiMethod method) {
        StringBuilder builder = new StringBuilder(className(method));
        builder.append('#').append(OgnlPsUtils.getMethodName(method)).append('(');
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(parameters[i].getType().getCanonicalText());
        }
        return builder.append(')').toString();
    }

    /**
     * 用于命令输出的去重 key：类名#方法名（trace 只按方法名匹配，重载共享一个 key）。
     */
    public static String methodKey(PsiMethod method) {
        return className(method) + "#" + OgnlPsUtils.getMethodName(method);
    }

    private static String className(PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            return "";
        }
        String name = OgnlPsUtils.getCommonOrInnerOrAnonymousClassName(containingClass);
        if (name == null || name.isEmpty()) {
            name = containingClass.getName();
        }
        return name == null ? "" : name;
    }
}