package com.example.aiagentdemo.service;

import com.example.aiagentdemo.config.CodeIndexConfig;
import com.example.aiagentdemo.data.CallRelation;
import com.example.aiagentdemo.data.CodeSegment;
import com.example.aiagentdemo.data.FilePath;
import com.example.aiagentdemo.data.ParseResult;
import com.example.aiagentdemo.instances.IDocumentParser;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "agent.domain.name", havingValue = "code", matchIfMissing = true)
public class JavaCodeParser implements IDocumentParser {
    private JavaParser javaParser;

    @Autowired
    private CodeIndexConfig indexConfig;

    private final Map<String, String> packagePrefixToProject = new ConcurrentHashMap<>();

    private static final Map<String, String> KNOWN_THIRD_PARTY = Map.ofEntries(
            Map.entry("java", "jdk"),
            Map.entry("javax", "jdk"),
            Map.entry("jakarta", "jdk"),
            Map.entry("org.springframework", "spring"),
            Map.entry("org.apache", "apache"),
            Map.entry("com.alibaba", "alibaba"),
            Map.entry("com.fasterxml", "fasterxml"),
            Map.entry("com.google", "google"),
            Map.entry("io.milvus", "milvus"),
            Map.entry("dev.langchain4j", "langchain4j"),
            Map.entry("lombok", "lombok"),
            Map.entry("org.slf4j", "slf4j")
    );

    @PostConstruct
    public void init() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        javaParser = new JavaParser(config);
        buildPackageProjectMapping();
    }

    // ================================================================
    // 项目名推断初始化
    // ================================================================

    private void buildPackageProjectMapping() {
        String subProjectsPath = indexConfig.getSubProjectsPath();
        if (subProjectsPath != null && !subProjectsPath.isEmpty()) {
            java.nio.file.Path dir = java.nio.file.Paths.get(subProjectsPath);
            if (java.nio.file.Files.exists(dir)) {
                try {
                    java.nio.file.Files.list(dir)
                            .filter(java.nio.file.Files::isDirectory)
                            .forEach(p -> {
                                String projectName = p.getFileName().toString();
                                registerProjectPrefix(projectName, projectName);
                                registerProjectPrefix(projectName, "com.bf." + projectName);
                                registerProjectPrefix(projectName, "com.example." + projectName);
                            });
                } catch (Exception e) {
                    log.error("扫描子项目失败", e);
                }
            }
        }

        List<Map<String, String>> subProjects = indexConfig.getSubProjectPaths();
        if (subProjects != null) {
            for (Map<String, String> sub : subProjects) {
                String name = sub.get("pname");
                if (name != null) {
                    registerProjectPrefix(name, name);
                    registerProjectPrefix(name, "com.bf." + name);
                    registerProjectPrefix(name, "com.example." + name);
                }
            }
        }

        log.info("已注册 {} 个包前缀 → 项目名 映射", packagePrefixToProject.size());
    }

    private void registerProjectPrefix(String projectName, String prefix) {
        if (prefix != null && !prefix.isEmpty()) {
            packagePrefixToProject.put(prefix, projectName);
        }
    }

    // ================================================================
    // 解析入口
    // ================================================================

    @Override
    public String getDomain() {
        return "code";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return List.of(".java");
    }

    @Override
    public ParseResult parse(FilePath filePath, String content) {
        // 直接复用 parseWithRelations（返回 ParseResult）
        return parseWithRelations(filePath, content);
    }

    /**
     * 解析 Java 文件，提取多个代码片段（兼容原有调用方）
     */
    public List<CodeSegment> parseSegments(FilePath filePath, String content) {
        List<CodeSegment> segments = new ArrayList<>();
        try {
            CompilationUnit cu = javaParser.parse(content).getResult()
                    .orElseThrow(() -> new RuntimeException("解析失败"));

            String packageName = cu.getPackageDeclaration()
                    .map(NodeWithName::getNameAsString)
                    .orElse("");

            List<String> imports = cu.getImports().stream()
                    .map(ImportDeclaration::getNameAsString)
                    .toList();

            for (TypeDeclaration<?> type : cu.getTypes()) {
                CodeSegment classSegment = buildClassSegment(filePath, packageName, imports, type);
                segments.add(classSegment);

                if (type instanceof ClassOrInterfaceDeclaration classDecl) {
                    for (MethodDeclaration method : classDecl.getMethods()) {
                        if (method.isPublic() || method.isProtected()) {
                            CodeSegment methodSegment = buildMethodSegment(
                                    filePath, packageName, classDecl, method);
                            if (methodSegment != null) {
                                segments.add(methodSegment);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析文件失败: {}", filePath.getFullPath(), e);
        }
        return segments;
    }

    /**
     * 解析 Java 文件，提取代码片段 + 方法调用关系
     */
    public ParseResult parseWithRelations(FilePath filePath, String content) {
        ParseResult result = new ParseResult();
        result.setSegments(new ArrayList<>());
        result.setCallRelations(new ArrayList<>());

        try {
            CompilationUnit cu = javaParser.parse(content).getResult()
                    .orElseThrow(() -> new RuntimeException("解析失败"));

            String packageName = cu.getPackageDeclaration()
                    .map(NodeWithName::getNameAsString)
                    .orElse("");

            ImportContext importContext = buildImportContext(cu, packageName, filePath.getProjectName());

            List<String> imports = cu.getImports().stream()
                    .map(ImportDeclaration::getNameAsString)
                    .toList();

            for (TypeDeclaration<?> type : cu.getTypes()) {
                CodeSegment classSegment = buildClassSegment(filePath, packageName, imports, type);
                result.getSegments().add(classSegment);

                if (type instanceof ClassOrInterfaceDeclaration classDecl) {
                    // 继承关系
                    result.getCallRelations().addAll(
                            extractInheritanceRelations(filePath, packageName, classDecl, importContext)
                    );
                    // 字段类型关系
                    result.getCallRelations().addAll(
                            extractFieldRelations(filePath, packageName, classDecl, importContext)
                    );

                    for (MethodDeclaration method : classDecl.getMethods()) {
                        if (method.isPublic() || method.isProtected()) {
                            if (!isSimpleGetterSetter(method)) {
                                CodeSegment methodSegment = buildMethodSegment(
                                        filePath, packageName, classDecl, method);
                                result.getSegments().add(methodSegment);
                            }

                            List<CallRelation> relations = extractCallRelations(
                                    filePath, packageName, classDecl, method, importContext
                            );
                            result.getCallRelations().addAll(relations);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析文件失败: {}", filePath.getFullPath(), e);
        }
        return result;
    }

    // ================================================================
    // ★ 你原来的实现：buildClassSegment
    // ================================================================

    private CodeSegment buildClassSegment(FilePath filePath, String packageName,
                                          List<String> imports, TypeDeclaration<?> type) {
        String className = type.getNameAsString();
        String typeKind = getTypeKind(type);

        String javadoc = type.getJavadoc()
                .map(javadocComment -> javadocComment.toComment().getContent())
                .orElse("");

        String fields = extractFields(type);
        String methodSignatures = extractMethodSignatures(type);
        String skeleton = buildClassSkeleton(type, packageName, imports, fields, methodSignatures);

        String searchableText = String.format("""
            [项目: %s]
            [包: %s]
            [类名: %s]
            [类型: %s]
            [文档: %s]
            [导入: %s]
            
            %s
            """,
                filePath.getProjectName(),
                packageName,
                className,
                typeKind,
                javadoc,
                String.join(", ", imports),
                skeleton
        );

        return CodeSegment.builder()
                .content(searchableText)
                .rawCode(skeleton)
                .projectName(filePath.getProjectName())
                .filePath(filePath.getRelativePath())
                .packageName(packageName)
                .className(className)
                .typeKind(typeKind)
                .segmentType("class")
                .imports(imports)
                .javadoc(javadoc)
                .build();
    }

    // ================================================================
    // ★ 你原来的实现：buildMethodSegment
    // ================================================================

    private CodeSegment buildMethodSegment(FilePath filePath, String packageName,
                                           ClassOrInterfaceDeclaration classDecl,
                                           MethodDeclaration method) {
        String methodName = method.getNameAsString();
        String signature = method.getSignature().asString();

        String javadoc = method.getJavadoc()
                .map(item -> item.toComment().getContent())
                .orElse("");

        String params = method.getParameters().stream()
                .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                .collect(Collectors.joining(", "));

        String returnType = method.getTypeAsString();

        String modifiers = method.getModifiers().stream()
                .map(m -> m.getKeyword().asString())
                .collect(Collectors.joining(" "));

        String methodSignatureOnly = String.format("%s %s %s(%s);",
                modifiers, returnType, methodName, params);

        String searchableText = String.format("""
            [项目: %s]
            [类: %s.%s]
            [方法: %s]
            [签名: %s]
            [返回类型: %s]
            [参数: %s]
            [修饰符: %s]
            [文档: %s]
            """,
                filePath.getProjectName(),
                packageName,
                classDecl.getNameAsString(),
                methodName,
                methodSignatureOnly,
                returnType,
                params,
                modifiers,
                javadoc
        );

        if (searchableText.length() > 15000) {
            log.warn("代码片段过长: {} 字符，来自 {}.{}",
                    searchableText.length(), classDecl.getNameAsString(),
                    methodName != null ? methodName : "类");
            searchableText = searchableText.substring(0, 15000);
        }

        return CodeSegment.builder()
                .content(searchableText)
                .rawCode(method.getDeclarationAsString(false, false, true) + ";")
                .projectName(filePath.getProjectName())
                .filePath(filePath.getRelativePath())
                .packageName(packageName)
                .className(classDecl.getNameAsString())
                .methodName(methodName)
                .methodSignature(signature)
                .segmentType("method")
                .javadoc(javadoc)
                .build();
    }

    // ================================================================
    // 原有的辅助方法（保留）
    // ================================================================

    private boolean isSimpleGetterSetter(MethodDeclaration method) {
        String name = method.getNameAsString();
        return (name.startsWith("get") && method.getParameters().isEmpty()) ||
                (name.startsWith("set") && method.getParameters().size() == 1) ||
                name.equals("toString") ||
                name.equals("hashCode") ||
                name.equals("equals");
    }

    private String getTypeKind(TypeDeclaration<?> type) {
        if (type.isClassOrInterfaceDeclaration()) {
            return type.asClassOrInterfaceDeclaration().isInterface() ? "interface" : "class";
        }
        if (type.isEnumDeclaration()) return "enum";
        if (type.isAnnotationDeclaration()) return "annotation";
        return "unknown";
    }

    private String extractFields(TypeDeclaration<?> type) {
        StringBuilder sb = new StringBuilder();
        if (type instanceof ClassOrInterfaceDeclaration classDecl) {
            classDecl.getFields().forEach(field -> {
                sb.append("    ").append(field.toString()).append("\n");
            });
        }
        return sb.toString();
    }

    private String extractMethodSignatures(TypeDeclaration<?> type) {
        StringBuilder sb = new StringBuilder();
        if (type instanceof ClassOrInterfaceDeclaration classDecl) {
            for (MethodDeclaration method : classDecl.getMethods()) {
                String signature = method.getDeclarationAsString(false, false, true);
                sb.append("    ").append(signature).append(";\n");
            }
        }
        return sb.toString();
    }

    private String buildClassSkeleton(TypeDeclaration<?> type, String packageName,
                                      List<String> imports, String fields, String methodSignatures) {
        String className = type.getNameAsString();
        StringBuilder sb = new StringBuilder();

        sb.append("package ").append(packageName).append(";\n\n");

        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        type.getJavadoc().ifPresent(doc -> {
            sb.append(doc.toComment().getContent()).append("\n");
        });

        if (type.isClassOrInterfaceDeclaration()) {
            ClassOrInterfaceDeclaration classDecl = type.asClassOrInterfaceDeclaration();
            sb.append("public ");
            if (classDecl.isInterface()) {
                sb.append("interface ");
            } else {
                sb.append("class ");
            }
            sb.append(className);

            classDecl.getExtendedTypes().forEach(ext -> {
                sb.append(" extends ").append(ext.getNameAsString());
            });

            if (!classDecl.getImplementedTypes().isEmpty()) {
                sb.append(" implements ");
                classDecl.getImplementedTypes().forEach(impl -> {
                    sb.append(impl.getNameAsString()).append(", ");
                });
                sb.setLength(sb.length() - 2);
            }

            sb.append(" {\n");

            if (!fields.isEmpty()) {
                sb.append("\n    // ========== 字段 ==========\n");
                sb.append(fields);
            }

            if (!methodSignatures.isEmpty()) {
                sb.append("\n    // ========== 方法 ==========\n");
                sb.append(methodSignatures);
            }

            sb.append("}");
        }

        return sb.toString();
    }

    // ================================================================
    // import 上下文 + 推断逻辑（新加）
    // ================================================================

    private static class ImportContext {
        Map<String, String> simpleToFqn = new HashMap<>();
        List<String> wildcardPackages = new ArrayList<>();
        String currentPackage;
        String currentProject;

        String resolve(String simpleName) {
            if (simpleName == null || simpleName.isEmpty()) return null;
            if (simpleName.contains(".")) return simpleName;
            return simpleToFqn.get(simpleName);
        }
    }

    private ImportContext buildImportContext(CompilationUnit cu, String packageName, String projectName) {
        ImportContext ctx = new ImportContext();
        ctx.currentPackage = packageName;
        ctx.currentProject = projectName;

        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (imp.isAsterisk()) {
                ctx.wildcardPackages.add(name);
            } else {
                String simpleName = name.substring(name.lastIndexOf('.') + 1);
                ctx.simpleToFqn.put(simpleName, name);
            }
        }
        return ctx;
    }

    private static class CalleeInfo {
        String fqn;
        String packageName;
        String project;
    }

    private CalleeInfo resolveCallee(String calleeClassName, ImportContext ctx) {
        CalleeInfo info = new CalleeInfo();
        if (calleeClassName == null || calleeClassName.isEmpty()
                || "unknown".equals(calleeClassName) || "this".equals(calleeClassName)) {
            info.fqn = null;
            info.packageName = ctx.currentPackage;
            info.project = ctx.currentProject;
            return info;
        }

        String cleanName = calleeClassName.replaceAll("<.*>", "").replace("[]", "").trim();

        if (cleanName.contains(".")) {
            String[] parts = cleanName.split("\\.");
            if (parts.length > 1 && !parts[parts.length - 1].isEmpty()
                    && Character.isUpperCase(parts[parts.length - 1].charAt(0))) {
                info.fqn = cleanName;
                info.packageName = cleanName.substring(0, cleanName.lastIndexOf('.'));
                info.project = inferProjectFromPackage(info.packageName);
                return info;
            }
        }

        String fqn = ctx.resolve(cleanName);
        if (fqn != null) {
            info.fqn = fqn;
            info.packageName = fqn.substring(0, fqn.lastIndexOf('.'));
            info.project = inferProjectFromPackage(info.packageName);
            return info;
        }

        for (String wildcardPkg : ctx.wildcardPackages) {
            info.fqn = wildcardPkg + "." + cleanName;
            info.packageName = wildcardPkg;
            info.project = inferProjectFromPackage(wildcardPkg);
            return info;
        }

        info.fqn = ctx.currentPackage.isEmpty() ? cleanName : ctx.currentPackage + "." + cleanName;
        info.packageName = ctx.currentPackage;
        info.project = ctx.currentProject;
        return info;
    }

    private String inferProjectFromPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "unknown";

        if (packageName.startsWith("java.") || packageName.startsWith("javax.")
                || packageName.startsWith("jakarta.")) {
            return "jdk";
        }

        String bestMatch = null;
        int bestLen = 0;

        for (Map.Entry<String, String> entry : KNOWN_THIRD_PARTY.entrySet()) {
            if (packageName.startsWith(entry.getKey()) && entry.getKey().length() > bestLen) {
                bestMatch = entry.getValue();
                bestLen = entry.getKey().length();
            }
        }
        if (bestMatch != null) return bestMatch;

        for (Map.Entry<String, String> entry : packagePrefixToProject.entrySet()) {
            if (packageName.startsWith(entry.getKey()) && entry.getKey().length() > bestLen) {
                bestMatch = entry.getValue();
                bestLen = entry.getKey().length();
            }
        }
        if (bestMatch != null) return bestMatch;

        String[] parts = packageName.split("\\.");
        if (parts.length >= 3) {
            return parts[0] + "." + parts[1] + "." + parts[2];
        } else if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return packageName;
    }

    // ================================================================
    // 调用关系提取（重写）
    // ================================================================

    private List<CallRelation> extractCallRelations(
            FilePath filePath,
            String packageName,
            ClassOrInterfaceDeclaration classDecl,
            MethodDeclaration method,
            ImportContext ctx) {

        List<CallRelation> relations = new ArrayList<>();
        String className = classDecl.getNameAsString();
        String methodName = method.getNameAsString();
        String projectName = filePath.getProjectName();

        // 1. 方法调用
        method.findAll(MethodCallExpr.class).forEach(call -> {
            String calleeMethodName = call.getNameAsString();
            String scopeStr = call.getScope().map(Expression::toString).orElse(null);
            String calleeClassName = scopeStr;

            if ("this".equals(scopeStr)) {
                calleeClassName = className;
            } else if (scopeStr != null && !scopeStr.contains(".")
                    && !scopeStr.isEmpty() && Character.isLowerCase(scopeStr.charAt(0))) {
                String fieldType = findFieldType(classDecl, scopeStr);
                if (fieldType != null) {
                    calleeClassName = fieldType;
                }
            }

            CalleeInfo calleeInfo = resolveCallee(calleeClassName, ctx);

            relations.add(CallRelation.builder()
                    .callerProject(projectName)
                    .callerPackage(packageName)
                    .callerClass(className)
                    .callerMethod(methodName)
                    .callerLine(call.getBegin().map(pos -> pos.line).orElse(-1))
                    .calleeProject(calleeInfo.project)
                    .calleePackage(calleeInfo.packageName)
                    .calleeClass(calleeClassName != null ? calleeClassName : "unknown")
                    .calleeMethod(calleeMethodName)
                    .callType("method_call")
                    .filePath(filePath.getRelativePath())
                    .build());
        });

        // 2. 构造函数调用
        method.findAll(ObjectCreationExpr.class).forEach(creation -> {
            String calleeClassName = creation.getType().getNameAsString();
            CalleeInfo calleeInfo = resolveCallee(calleeClassName, ctx);

            relations.add(CallRelation.builder()
                    .callerProject(projectName)
                    .callerPackage(packageName)
                    .callerClass(className)
                    .callerMethod(methodName)
                    .callerLine(creation.getBegin().map(pos -> pos.line).orElse(-1))
                    .calleeProject(calleeInfo.project)
                    .calleePackage(calleeInfo.packageName)
                    .calleeClass(calleeClassName)
                    .calleeMethod("new_" + calleeClassName)
                    .callType("constructor_call")
                    .filePath(filePath.getRelativePath())
                    .build());
        });

        // 3. super 调用
        method.findAll(SuperExpr.class).forEach(superExpr -> {
            relations.add(CallRelation.builder()
                    .callerProject(projectName)
                    .callerPackage(packageName)
                    .callerClass(className)
                    .callerMethod(methodName)
                    .callerLine(superExpr.getBegin().map(pos -> pos.line).orElse(-1))
                    .calleeProject("unknown")
                    .calleePackage("")
                    .calleeClass("super")
                    .calleeMethod("super_call")
                    .callType("super_call")
                    .filePath(filePath.getRelativePath())
                    .build());
        });

        // 4. 字段访问（静态字段）
        method.findAll(FieldAccessExpr.class).forEach(fieldAccess -> {
            Expression scope = fieldAccess.getScope();
            String scopeStr = scope.toString();
            if (scopeStr != null && !scopeStr.isEmpty()
                    && !scopeStr.equals("this")
                    && Character.isUpperCase(scopeStr.charAt(0))) {
                String calleeFieldName = fieldAccess.getNameAsString();
                CalleeInfo calleeInfo = resolveCallee(scopeStr, ctx);

                relations.add(CallRelation.builder()
                        .callerProject(projectName)
                        .callerPackage(packageName)
                        .callerClass(className)
                        .callerMethod(methodName)
                        .callerLine(fieldAccess.getBegin().map(pos -> pos.line).orElse(-1))
                        .calleeProject(calleeInfo.project)
                        .calleePackage(calleeInfo.packageName)
                        .calleeClass(scopeStr)
                        .calleeMethod(calleeFieldName)
                        .callType("field_access")
                        .filePath(filePath.getRelativePath())
                        .build());
            }
        });

        return relations;
    }

    // ================================================================
    // 继承关系
    // ================================================================

    private List<CallRelation> extractInheritanceRelations(
            FilePath filePath, String packageName,
            ClassOrInterfaceDeclaration classDecl, ImportContext ctx) {

        List<CallRelation> relations = new ArrayList<>();
        String className = classDecl.getNameAsString();
        String projectName = filePath.getProjectName();

        for (ClassOrInterfaceType ext : classDecl.getExtendedTypes()) {
            String parentName = ext.getNameAsString();
            CalleeInfo info = resolveCallee(parentName, ctx);
            relations.add(CallRelation.builder()
                    .callerProject(projectName)
                    .callerPackage(packageName)
                    .callerClass(className)
                    .callerMethod("<class>")
                    .callerLine(classDecl.getBegin().map(p -> p.line).orElse(-1))
                    .calleeProject(info.project)
                    .calleePackage(info.packageName)
                    .calleeClass(parentName)
                    .calleeMethod("extends")
                    .callType("extends")
                    .filePath(filePath.getRelativePath())
                    .build());
        }

        for (ClassOrInterfaceType impl : classDecl.getImplementedTypes()) {
            String interfaceName = impl.getNameAsString();
            CalleeInfo info = resolveCallee(interfaceName, ctx);
            relations.add(CallRelation.builder()
                    .callerProject(projectName)
                    .callerPackage(packageName)
                    .callerClass(className)
                    .callerMethod("<class>")
                    .callerLine(classDecl.getBegin().map(p -> p.line).orElse(-1))
                    .calleeProject(info.project)
                    .calleePackage(info.packageName)
                    .calleeClass(interfaceName)
                    .calleeMethod("implements")
                    .callType("implements")
                    .filePath(filePath.getRelativePath())
                    .build());
        }

        return relations;
    }

    // ================================================================
    // 字段类型关系
    // ================================================================

    private List<CallRelation> extractFieldRelations(
            FilePath filePath, String packageName,
            ClassOrInterfaceDeclaration classDecl, ImportContext ctx) {

        List<CallRelation> relations = new ArrayList<>();
        String className = classDecl.getNameAsString();
        String projectName = filePath.getProjectName();

        for (FieldDeclaration field : classDecl.getFields()) {
            String fieldType = field.getElementType().asString();
            String fieldName = field.getVariable(0).getNameAsString();

            if (isPrimitiveOrCommon(fieldType)) continue;

            CalleeInfo info = resolveCallee(fieldType, ctx);
            relations.add(CallRelation.builder()
                    .callerProject(projectName)
                    .callerPackage(packageName)
                    .callerClass(className)
                    .callerMethod("<field>")
                    .callerLine(field.getBegin().map(p -> p.line).orElse(-1))
                    .calleeProject(info.project)
                    .calleePackage(info.packageName)
                    .calleeClass(fieldType)
                    .calleeMethod("field:" + fieldName)
                    .callType("field_type")
                    .filePath(filePath.getRelativePath())
                    .build());
        }

        return relations;
    }

    private boolean isPrimitiveOrCommon(String type) {
        Set<String> skip = Set.of(
                "int", "long", "short", "byte", "char", "boolean", "float", "double",
                "Integer", "Long", "Short", "Byte", "Character", "Boolean", "Float", "Double",
                "String", "Object", "List", "Map", "Set", "Collection",
                "ArrayList", "HashMap", "HashSet", "Optional"
        );
        return skip.contains(type);
    }

    private String findFieldType(ClassOrInterfaceDeclaration classDecl, String fieldName) {
        for (FieldDeclaration field : classDecl.getFields()) {
            for (var variable : field.getVariables()) {
                if (variable.getNameAsString().equals(fieldName)) {
                    return field.getElementType().asString();
                }
            }
        }
        return null;
    }
}