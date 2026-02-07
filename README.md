# HaE-Storage

`HaE-Storage` 是基于 HaE Network 的存储与大数据量场景优化版本，核心目标是解决“数据量一大就因全量内存加载而不可用”的问题。

---

## 本次主要改动

### 1) 存储架构：从全量内存改为 SQLite 外部持久化
- 将完整 `HttpRequestResponse` 从长期内存驻留改为落盘存储（SQLite）。
- 通过 `message_id` 关联消息元数据与完整报文，减少内存压力。

### 2) 列表查询：数据库级分页
- 消息列表改为 `COUNT + LIMIT/OFFSET` 的数据库分页。
- 避免启动或过滤时把全量数据加载到内存。

### 3) Message 过滤：SQL 化
- 新增 `message_match` 映射表（`message_id / rule_name / extracted_value`）。
- Message 过滤由数据库执行，不再进行内存全量扫描。

### 4) `*` 全部语义支持
- `table=* & value=*`：取消 Message 过滤。
- `table=具体规则 & value=*`：该规则下全部值。
- `table=* & value=具体值`：所有规则中匹配该值。

### 5) 可运维能力
- 新增 `Clear storage` 按钮，可手动清理本地历史存储。
- 关闭 Burp 时自动清理数据（SQLite、扩展持久化、内存缓存）。

### 6) 构建与兼容性
- 适配新版本 Gradle 构建链路。
- 已验证可打包为 Burp 可加载 Jar。

---

## 使用方式

### 打包
```bash
cd src/HaENet
gradle clean jar
```

### 产物
`src/HaENet/build/libs/HaE.jar`

### Burp 加载
`Extensions -> Installed -> Add -> 选择 HaE.jar`

---

## 致谢

感谢原作者与原项目：

- https://github.com/gh0stkey/HaE

本项目在原有能力基础上，重点增强了大数据量场景下的可用性与稳定性。

---

## 许可证

本仓库根目录采用 **MIT License**（见 `LICENSE` 文件）。

> 说明：本项目基于上游项目进行修改，使用时请同时关注上游项目及其保留文件中的原始许可证与声明要求。
