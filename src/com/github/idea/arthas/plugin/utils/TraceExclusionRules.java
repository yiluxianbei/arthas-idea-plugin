package com.github.idea.arthas.plugin.utils;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * trace -E 排除规则。
 *
 * <p>包规则：{@code com.foo} 只匹配当前包；{@code com.foo.*} 匹配当前包及所有子包。</p>
 * <p>类规则：全限定类名精确匹配，内部类兼容 {@code Outer$Inner} 与 {@code Outer.Inner}。</p>
 * <p>方法规则：{@code 全限定类名#方法名} 精确匹配，同名重载全部排除。</p>
 *
 * @author arthas idea plugin
 */
public final class TraceExclusionRules {

    public static final TraceExclusionRules EMPTY = new TraceExclusionRules(
            Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), 0);

    private final Set<PackageRule> packageRules;
    private final Set<String> classRules;
    private final Set<String> methodRules;
    private final int invalidRuleCount;

    private TraceExclusionRules(Set<PackageRule> packageRules,
                                Set<String> classRules,
                                Set<String> methodRules,
                                int invalidRuleCount) {
        this.packageRules = packageRules;
        this.classRules = classRules;
        this.methodRules = methodRules;
        this.invalidRuleCount = invalidRuleCount;
    }

    /**
     * 解析三个输入框，每行一条规则；空行忽略。
     */
    public static TraceExclusionRules parse(String packagesText, String classesText, String methodsText) {
        Set<PackageRule> packageRules = new LinkedHashSet<>();
        Set<String> classRules = new LinkedHashSet<>();
        Set<String> methodRules = new LinkedHashSet<>();
        int[] invalidRuleCount = new int[]{0};

        parsePackages(packagesText, packageRules, invalidRuleCount);
        parseClasses(classesText, classRules, invalidRuleCount);
        parseMethods(methodsText, methodRules, invalidRuleCount);

        return new TraceExclusionRules(packageRules, classRules, methodRules, invalidRuleCount[0]);
    }

    /**
     * 判断方法是否命中排除规则。
     */
    public boolean isExcluded(PsiMethod method) {
        if (method == null) {
            return false;
        }

        String packageName = getPackageName(method);
        for (PackageRule packageRule : packageRules) {
            if (packageRule.matches(packageName)) {
                return true;
            }
        }

        String className = normalizeClassName(OgnlPsUtils.getCommonOrInnerOrAnonymousClassName(method));
        if (className.isEmpty()) {
            return false;
        }
        if (classRules.contains(className)) {
            return true;
        }

        String methodName = OgnlPsUtils.getMethodName(method);
        return methodRules.contains(className + "#" + methodName);
    }

    public int getInvalidRuleCount() {
        return invalidRuleCount;
    }

    public boolean isEmpty() {
        return packageRules.isEmpty() && classRules.isEmpty() && methodRules.isEmpty();
    }

    private static void parsePackages(String text, Set<PackageRule> rules, int[] invalidRuleCount) {
        for (String line : splitLines(text)) {
            String rule = line.trim();
            if (rule.isEmpty()) {
                continue;
            }

            boolean recursive = rule.endsWith(".*");
            if (rule.indexOf('*') >= 0) {
                if (!recursive) {
                    invalidRuleCount[0]++;
                    continue;
                }
                String base = rule.substring(0, rule.length() - 2).trim();
                if (base.isEmpty()
                        || base.indexOf('*') >= 0
                        || base.endsWith(".")
                        || containsWhitespace(base)) {
                    invalidRuleCount[0]++;
                    continue;
                }
                rules.add(new PackageRule(base, true));
                continue;
            }

            if (rule.endsWith(".") || containsWhitespace(rule)) {
                invalidRuleCount[0]++;
                continue;
            }
            rules.add(new PackageRule(rule, false));
        }
    }

    private static void parseClasses(String text, Set<String> rules, int[] invalidRuleCount) {
        for (String line : splitLines(text)) {
            String rule = line.trim();
            if (rule.isEmpty()) {
                continue;
            }
            if (rule.indexOf('*') >= 0 || rule.endsWith(".") || containsWhitespace(rule)) {
                invalidRuleCount[0]++;
                continue;
            }
            String normalized = normalizeClassName(rule);
            if (normalized.isEmpty()) {
                invalidRuleCount[0]++;
                continue;
            }
            rules.add(normalized);
        }
    }

    private static void parseMethods(String text, Set<String> rules, int[] invalidRuleCount) {
        for (String line : splitLines(text)) {
            String rule = line.trim();
            if (rule.isEmpty()) {
                continue;
            }

            int separator = rule.indexOf('#');
            if (separator <= 0
                    || separator == rule.length() - 1
                    || rule.indexOf('#', separator + 1) >= 0
                    || rule.indexOf('*') >= 0) {
                invalidRuleCount[0]++;
                continue;
            }

            String className = rule.substring(0, separator).trim();
            String methodName = rule.substring(separator + 1).trim();
            if (className.isEmpty()
                    || methodName.isEmpty()
                    || className.endsWith(".")
                    || containsWhitespace(className)
                    || containsWhitespace(methodName)) {
                invalidRuleCount[0]++;
                continue;
            }

            String normalizedClassName = normalizeClassName(className);
            if (normalizedClassName.isEmpty()) {
                invalidRuleCount[0]++;
                continue;
            }
            rules.add(normalizedClassName + "#" + methodName);
        }
    }

    private static String[] splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        return text.split("\\R", -1);
    }

    private static boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeClassName(String className) {
        if (className == null) {
            return "";
        }
        return className.trim().replace('$', '.');
    }

    private static String getPackageName(PsiMethod method) {
        PsiFile containingFile = method.getContainingFile();
        if (containingFile instanceof PsiJavaFile) {
            return ((PsiJavaFile) containingFile).getPackageName();
        }
        return "";
    }

    private static final class PackageRule {
        private final String packageName;
        private final boolean recursive;

        private PackageRule(String packageName, boolean recursive) {
            this.packageName = packageName;
            this.recursive = recursive;
        }

        private boolean matches(String candidate) {
            if (candidate == null) {
                return false;
            }
            if (recursive) {
                return candidate.equals(packageName) || candidate.startsWith(packageName + ".");
            }
            return candidate.equals(packageName);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PackageRule)) {
                return false;
            }
            PackageRule that = (PackageRule) o;
            return recursive == that.recursive && packageName.equals(that.packageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packageName, recursive);
        }
    }
}