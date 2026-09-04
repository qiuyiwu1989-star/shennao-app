# mobile BFF 契约

> 真源在 `qiuyiwu1989-star/shennao` 的 `apps/web/src/app/api/mobile/*`。
> 本文摘录于 **2026-09-04**，只列端点与用途，字段以那边为准。

## 为什么手机端有自己的一个面

网页端有 60 个页面、111 个接口用 cookie 鉴权，只有录音链路那 12 个支持原生 Bearer。
`today/route.ts` 的注释写下了三选一的理由，原文照抄：

> · 给每个接口加 Bearer —— 是把网页的形状硬套给手机，每加一个面改一次服务端；
> · 手机直连 PostgREST —— 不用改服务端，但「什么算到期」「怎么排序」这类提炼逻辑要在
>   客户端重写一遍，迟早和网页两套判据；
> · **一个为手机设计的面** —— 提炼在服务端做完，客户端只管画。
>
> 第三条还有一个不明显的好处：**改提炼规则不用发版**。APK 外发没有商店的自动更新，
> 用户可能长期停在旧版本——把判断留在服务端，旧客户端也能跟着变。

以及这一句，它是本 App「读只到浅层」那条边界的出处：

> 它不是「网页的移动版」…… **记忆库、概念活页、图谱一概不在这儿——那些在浏览器里看更合适。**

## 现有 12 个端点

| 端点 | 用途 | 对应原型屏 |
| --- | --- | --- |
| `GET today` | `counts{overdue,total,awaitingSpeaker}` + `commitments[]` + `insights[]` + `predictions[]` | S7 |
| `GET sessions` | `SessionCard[]`：链路四站 + 卡住原因 | S5 |
| `GET transcript/[id]` | `Meeting`：summary · speakers · atoms[] · commitments[] · analysis{methods,routingReason} | S8 S9 S10 |
| `POST transcript/[id]/analyze` | 手动触发分析 | S29 |
| `POST transcript/[id]/share` | 出「能发出去的一页」 | S11 |
| `GET people/[id]` | `Person`：kept/broken/open/keptRate + judgments[] + openCommitments[] | S12 |
| `GET search` | `Hit[]{kind,text,who,transcriptId}` | S28 |
| `POST ask` | **SSE 流式**，六种事件：mode / tool_call / token / insufficient / done / error | S19 S20 S21 |
| `POST commitments/[id]` | 落账（兑现 / 没兑现 / 改期） | S7 S10 |
| `POST web-ticket` → `web-open` | 带登录态开网页版 | S30 |
| `POST crash` | 崩溃回传 | — |

## 原型需要、但 mobile 面还没开的 6 个

能力在 web API 里**都已经有了**，缺的是适配层，不是新功能：

| 要什么 | web 侧已有 | 用在哪 |
| --- | --- | --- |
| 预测落账 | `predictions/[id]/verify` | S15 |
| 认人 | `speaker-candidates` · `infer-speakers` | S14 |
| 判断反馈（👍👎 / 删除） | 待确认 | S8 S20（埋点②） |
| 额度与权益 | `credits` | S16 S17 S29 |
| 更多采集入口 | `upload` · `source-file` | S22 |
| 设备与权益归属 | **不存在** | S2 S23 S31 |

**最后一行是唯一的真缺口。** 现在 `credits` 绑的是 org，而「899 买断 + 权益随卡走」
要求有一层「设备 → 权益」的归属关系。S23/S31 两屏全压在它上面。

## 一条硬规矩

**提炼逻辑留在服务端，客户端不自己算。**
例子：`Meeting.analysisAbsentReason` 的字段注释写着——

> 理由由服务端给，客户端不自己拿时长去和阈值比——那条规矩（不到 5 分钟不自动分析）
> 只有服务端知道，抄一份到手机上，**下次改阈值必然只改一处**。
