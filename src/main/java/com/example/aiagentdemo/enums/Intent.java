package com.example.aiagentdemo.enums;

public enum Intent {
    // 项目级
    PROJECT_STRUCTURE,   // 查看项目结构、包列表、项目列表
    PROJECT_RELATION,    // 项目间依赖关系

    // 类级
    CLASS_STRUCTURE,     // 查看类的字段、方法、继承关系
    CLASS_HIERARCHY,     // 查看类的继承链、父类、接口

    // 文件级
    READ_FILE,           // 读取文件内容
    LIST_FILES,          // 列出目录下的文件

    // 代码操作
    CODE_MODIFY,         // 修改代码建议、重构
    CODE_WRITE,          // 生成新代码
    CODE_REVIEW,         // 代码审查、分析
    CODE_EXPLAIN,        // 解释代码逻辑
    CODE_SEARCH,         // 搜索/查找代码

    // 业务
    TEST_GENERATE,       // 生成单元测试
    DEPENDENCY_ANALYSIS, // 依赖分析
    IMPACT_ANALYSIS,   // 影响范围分析
    CALLER_QUERY,      // 查询调用者

    // 兜底
    GENERAL_CHAT         // 普通问答
}
