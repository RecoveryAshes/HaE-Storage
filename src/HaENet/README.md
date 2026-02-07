<div align="center">
<img src="/resource/image/logo.png" style="width: 20%" />
<h3>HaE Network</h3>
<h5>第一作者： <a href="https://github.com/gh0stkey">EvilChen</a><br>第二作者： <a href="https://github.com/0chencc">0chencc</a>（米斯特安全团队）<br>第三作者： <a href="https://github.com/vaycore">vaycore</a>（独立安全研究员）</h5>
</div>

## 项目介绍

通过运用**多引擎**的自定义正则表达式，HaE Network 能够准确匹配并处理 HTTP 请求与响应报文（包含 WebSocket），对匹配成功的内容进行有效标记和信息抽取，从而提升网络安全（数据安全）场景下的**漏洞和数据分析效率**。

> 随着现代 Web 应用采用前后端分离模式，日常漏洞挖掘中捕获的 HTTP 流量显著增加。若想全面评估一个应用，会花费大量时间在无用报文上。HaE Network 的目标就是帮助你聚焦高价值报文，降低筛选成本。

## 注意事项

1. HaE Network 3.0 起采用 `Montoya API` 开发，需使用 BurpSuite `>= 2023.12.1`。
2. 自定义规则中，需提取的内容必须由括号 `()` 包裹。例如匹配 `rememberMe=delete`，应写作 `(rememberMe=delete)`。

## 近期更新（2026-02）

1. **SQLite 外部持久化**：完整 `HttpRequestResponse` 已改为落盘存储，降低内存占用。
2. **数据库级分页**：消息列表改为 `COUNT + LIMIT/OFFSET` 分页查询，不再全量元数据驻留内存。
3. **Message 过滤 SQL 化**：新增 `message_match` 映射表，Message 过滤由数据库执行。
4. **支持 `*` 全部语义**：
   - `table=* & value=*`：等同取消 Message 过滤
   - `table=具体规则 & value=*`：该规则下全部值
   - `table=* & value=具体值`：所有规则里匹配该值
5. **新增 Clear storage 按钮**：可一键清理存储历史。
6. **关闭 Burp 自动清理数据**：在 Burp 关闭时自动清理 SQLite、扩展持久化数据与内存缓存。

## 使用方法

插件装载：`Extender -> Extensions -> Add -> Select File -> Next`

### 本地打包

```bash
cd src/HaENet
gradle clean jar
```

产物路径：`src/HaENet/build/libs/HaE.jar`

初次装载 `HaE Network` 会从 Jar 包中加载离线规则库。若规则更新，可点击 `Reinit` 重新初始化。内置规则库地址：

`https://github.com/gh0stkey/HaE/blob/main/src/HaENet/src/main/resources/rules/Rules.yml`

配置文件（`Config.yml`）与规则文件（`Rules.yml`）默认目录：

1. Linux / Mac：`~/.config/HaE/`
2. Windows：`%USERPROFILE%/.config/HaE/`

也可放在 `HaE Network Jar` 同级目录下的 `/.config/HaE/` 中，方便离线携带。

### 规则释义

HaE Network 当前规则包含 8 个字段：

| 字段      | 含义 |
|-----------|------|
| Name      | 规则名称，简要概括当前规则用途。 |
| F-Regex   | 主正则表达式。需提取匹配内容应由 `(`、`)` 包裹。 |
| S-Regex   | 二次正则。可对 F-Regex 命中结果再匹配提取，不需要可留空。 |
| Format    | 格式化输出。NFA 模式下可使用 `{0}`、`{1}`、`{2}` 等分组输出，默认 `{0}`。 |
| Scope     | 规则作用域，可作用于请求/响应的行、头、体，或完整报文。 |
| Engine    | 正则引擎。DFA：快、特性少；NFA：慢一些、特性更丰富。 |
| Color     | 命中高亮颜色。支持颜色升级算法。 |
| Sensitive | 大小写敏感性。`True` 严格区分大小写，`False` 不区分。 |

## 优势特点

1. **功能**：通过高亮、注释和提取，帮助使用者聚焦高价值报文。
2. **界面**：界面清晰、交互简洁，降低配置复杂度。
3. **查询**：高亮、注释与提取信息集中在数据面板，一键检索。
4. **算法**：内置高亮颜色升级算法，自动提升重复颜色优先级。
5. **管理**：兼容 BurpSuite 项目化管理。
6. **实战**：官方规则与字段能力来源于实战场景总结。

| 界面名称 | 界面展示 |
|---------|---------|
| Rules（规则管理） | <img src="images/rules.png" style="width: 80%" /> |
| Config（配置管理） | <img src="images/config.png" style="width: 80%" /> |
| Databoard（数据集合） | <img src="images/databoard.png" style="width: 80%" /> |
| MarkInfo（数据展示） | <img src="images/markinfo.png" style="width: 80%" /> |
