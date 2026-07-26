# 任务 1 报告：VVD 切线数据解析

**状态：** DONE

## 实现内容

- 在 `VvdParser.java` 中新增 `VvdTangent` 内部类（x,y,z,w 四个 float 字段）
- 在 `ParsedVvd` 类中追加 `List<VvdTangent> tangents` 字段
- 实现 `parseTangents(byte[] data, ParsedVvd vvd)` 静态方法
  - 从 VVD header 的 `tangentDataStart` 读取切线数据
  - 每个切线为 16 字节（Vector4D: x,y,z,w）
  - 边界检查：tangentStart <= 0 或数据超出文件范围时返回空列表
  - 空安全：vvd 或 header 为 null 时返回空列表

## 测试结果

- `parseTangents()` - 通过。构造 2 个顶点 + 2 个切线的 VVD，验证切线值正确
- `parseTangents_noTangentData()` - 通过。tangentDataStart=0 时返回空列表

## 修改文件

- `src/main/java/transferstation/transferstation_whimsicalideas/client/model/VvdParser.java`
- `src/test/java/transferstation/transferstation_whimsicalideas/client/model/VvdParserTest.java`

## 自审

- ✅ 只做加法 — 没有修改任何现有方法
- ✅ VvdTangent 是 VvdParser 内部类，与项目模式一致
- ✅ 边界情况（null、tangentStart=0、数据截断）都已处理
