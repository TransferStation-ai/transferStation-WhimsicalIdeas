# 任务 A1 简报：修复 SeqDesc 字段偏移

## 需求

在 `MdlDataTypes.java` 的 `SeqDesc` 类中的 `label` 字段之后、`activity` 字段之前，添加缺失的 `szactivitynameindex` 字段（`int` 类型）。

在 `MdlParser.java` 的 `parseSequences()` 方法中，版本感知地读取 SeqDesc 字段：
- 当 `seqdescSize >= 220`（v49+）：读取顺序为 `baseptr` → `sznameindex` → `szactivitynameindex` → `flags`（跳过） → `activity` → `actweight`
- 当 `seqdescSize < 220`（v48）：读取顺序为 `baseptr` → `sznameindex` → `activity` → `actweight`

## 精确修改

### MdlDataTypes.java

在 `SeqDesc` 类（约 241-285 行）中，`public int sznameindex;` 和 `public String label;` 之后、`public int activity;` 之前插入：

```java
public int szactivitynameindex;
```

### MdlParser.java

在 `parseSequences()` 方法（约 796-855 行），将当前：

```java
seq.baseptr = buf.getInt();
seq.sznameindex = buf.getInt();
if (seq.sznameindex > 0) {
    int absNameOff = entryOff + seq.sznameindex;
    seq.label = readNullTerminatedString(buf, absNameOff, bufferLimit);
} else {
    seq.label = "";
}
seq.activity = buf.getInt();
seq.actweight = buf.getInt();
```

改为：

```java
seq.baseptr = buf.getInt();
seq.sznameindex = buf.getInt();
if (seq.sznameindex > 0) {
    int absNameOff = entryOff + seq.sznameindex;
    seq.label = readNullTerminatedString(buf, absNameOff, bufferLimit);
} else {
    seq.label = "";
}
if (seqdescSize >= SEQDESC_SIZE_V49) {
    seq.szactivitynameindex = buf.getInt();
    int activityFlags = buf.getInt();
    seq.activity = buf.getInt();
    seq.actweight = buf.getInt();
} else {
    seq.szactivitynameindex = 0;
    seq.activity = buf.getInt();
    seq.actweight = buf.getInt();
}
```

**不修改其他代码。** `events`、`numevents`、`eventindex` 及之后的所有字段保持原有读取方式不变。

## 验证

此修改不改变任何功能——只是修复了存在的字段偏移问题。验证方式：

1. 确保 `MdlDataTypes.java` 和 `MdlParser.java` 编译无错误
2. 确保项目中所有引用 `SeqDesc.activity` 和 `SeqDesc.actweight` 的地方仍然有效
3. 运行：在 IDE 中打开项目确认无编译错误后 commit

## 报告契约

实现者需将完整报告写入 `docs/superpowers/plans/task-A1-report.md`，包含：
- 所做的修改
- 编译结果
- 自审发现的任何问题
