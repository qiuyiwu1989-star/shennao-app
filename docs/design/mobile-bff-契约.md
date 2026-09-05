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

## 2026-09-05 新开的（分支 agent/mobile-bff-phase2，未部署）

| 端点 | 用途 | 客户端 |
| --- | --- | --- |
| `GET credits` | `{ balance, month: { deep, quick, credits, since } \| null }`；深判断 = ≥2 个方法；用量从流水按分析净额算 | 我的 · 积分行 + 「这个月：深判断 x 次…」（404 时不显示，没 month 不画第二行） |
| `POST predictions/[id]` | `{verdict: borne_out/refuted/partial/too_early}` → `{ok,verdict,bridged}` 或 `{ok,deferred,nextDueAt}` | 今天 · 预测卡三按钮 |
| `GET transcript/[id]/speakers` | 未认的说话人各配样本句 + 候选名单 | 认人页 S14 |
| `POST transcript/[id]/speakers` | `{speakers:[{id,name}|{id,skip:true}]}` | 认人页 |
| `sessions[].captureClient` · `sessions[].source` | `source` = card / share / phone / other，从幂等键前缀派生（ble- / share-） | 记录 · 来源分段（没有 `source` 时分段行不显示） |
| `sessions[].scene` · `POST /api/recordings {scene}` | 白名单 meeting / one_on_one / interview / negotiation / lecture / memo；400 会把合法值列出来 | 录音 · 录前一行场合 |
| `transcript/[id].segments[]` | `{startMs,endMs,speaker,text}`，毫秒，封顶 2000 | 详情 · 原话 tab 逐句 + 依据跳转 |
| `POST insights/[id]/feedback` | `{verdict: up/down/hide}`，每人每条留最新一份；今天页过滤本人 hide 的。网页端 `POST /api/insights/[id]/feedback` 共用同一函数 | 判断卡展开态「对 / 不对 / 别再看」 |
| `GET card` · `POST card {address}` | 我名下的灵魂卡 `{cards:[{deviceNo,boundAt,granted,monthly}],monthly}`；两者都顺手补发欠的月份；绑定幂等，别人的卡 409 | 灵魂卡页 · 连上即绑、权益行 |

迁移两份：`20260905T0940_session_scene`、`20260905T0941_insight_feedback`。部署顺序见《phase2-部署手册》。

客户端对这些端点一律**404 即隐藏**：服务端没部署时不报错、不重试、不画点了没反应的按钮。

## 原型需要的，现在都有了

2026-09-05 之前这一节列着六项缺口。现在只剩一句话要记：

**「设备 → 权益」没有建表。** 台账复用 `iot_devices`（provider=soulcard，蓝牙地址做 device_no，
(provider, device_no) 全局唯一 = 一张卡一个主人），权益复用 `credit_ledger`
（reason=soulcard:<设备号>:<YYYY-MM>，**每月每卡一次、永久、多卡叠加**，谁当月持卡发给谁；上一任领过的月份不重发）。
再建一张表就是「归属」有了第二个答案。`SOULCARD_MONTHLY = 10 次深判断 × 3 积分 = 30`。

## 一条硬规矩

**提炼逻辑留在服务端，客户端不自己算。**
例子：`Meeting.analysisAbsentReason` 的字段注释写着——

> 理由由服务端给，客户端不自己拿时长去和阈值比——那条规矩（不到 5 分钟不自动分析）
> 只有服务端知道，抄一份到手机上，**下次改阈值必然只改一处**。
