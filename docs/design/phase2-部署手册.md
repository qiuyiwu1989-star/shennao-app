# Phase 2 部署手册（服务端分支 `agent/mobile-bff-phase2`）

> 写给你照着做的。我不部署、不跑迁移（CLAUDE.md 红线）。每一步都有「怎么知道成了」。
> 分支在主仓库 `shennao`，工作树 `/tmp/deepbrain-mobile-bff`；基于 `agent/ble-and-mac-client`。

## 0. 前提：把根分支推上去

`agent/ble-and-mac-client` 领先 main 562 个提交、从未推过远端。整套 `api/mobile/*` 只在这条本地分支上。

```bash
cd "/Users/qiu/Documents/冷静/lengjing" && git push -u origin agent/ble-and-mac-client
```

成了的标志：GitHub 上能看到这条分支。

## 1. 合线上，过护栏

`deploy-122.sh` 有祖先护栏：线上跑的 commit 必须是本地 HEAD 的祖先，否则拒发（它救过一次 106 个提交被抹掉）。

```bash
cd /tmp/deepbrain-mobile-bff && git fetch origin && git status -sb
```

护栏报错就按提示 `git merge <线上 sha>`，跑完门禁（typecheck + vitest）再往下。**永不 force。**

## 2. 先跑迁移，再发代码

两份都是**只加不改**：一列可空、一张新表。老代码碰到新列不受影响，新代码碰到没有的列会退化（scene 不传就不写）。
所以顺序是「先库后码」，中间隔多久都安全；反过来则有一小段「代码要写 scene、库没这列」的窗口。

```bash
# 自托管 Postgres 走本机隧道（RUNBOOK：5433 仅本机/隧道）。ON_ERROR_STOP=1 必须有——
# bootstrap.sh 曾用 =0 把建表错误吞了五个月。
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f infra/supabase/migrations/20260905T0940_session_scene.sql
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f infra/supabase/migrations/20260905T0941_insight_feedback.sql
```

怎么知道成了：

```bash
psql "$DATABASE_URL" -c "\d+ public.recording_sessions" | grep -E "scene|scene_known"
psql "$DATABASE_URL" -c "\d public.insight_feedback"
```

前者要看到 `scene` 列和 `recording_sessions_scene_known` 约束；后者要看到表、`insight_feedback_one_per_user` 唯一约束。
用 `postgres` 角色改既有表会报 owner 错——那是这套自托管 Supabase 的已知坑，用 `supabase_admin`。

## 3. 发代码

```bash
cd /tmp/deepbrain-mobile-bff && pgrep -f "next/dist/bin/next build" || bash scripts/deploy-122.sh
```

（有别的构建在跑就等它结束；`scripts/deploy.sh` 是旧站的，对 122 不适用。）

## 4. 冒烟（拿一个手机端的 Bearer token）

```bash
H='Authorization: Bearer <token>'; O='x-deepbrain-org-id: <orgId>'; B=https://<122 域名>
curl -s $B/api/mobile/credits -H "$H" -H "$O"                       # {"balance":N}
curl -s $B/api/mobile/card -H "$H" -H "$O"                          # {"cards":[...],"grant":300}
curl -s $B/api/mobile/sessions -H "$H" -H "$O" | head -c 400        # 每条带 "source":"card|phone|share|other"
curl -s -X POST $B/api/recordings -H "$H" -H "$O" -H 'Content-Type: application/json' \
  -d '{"clientRequestId":"smoke-scene","captureClient":"android","scene":"party"}'   # 400，错误里列出六个合法值
```

最后一条**必须是 400 且把合法值列出来**：那说明白名单和迁移的 check 都在线上了。

然后装 3.5.0 的 APK：记录页出现「全部 / 灵魂卡 / 手机 / 分享来的」一行；我的页积分行有数字；
连上灵魂卡后权益行从固定那句变成「这张卡发过 300 积分」。

## 5. 出了问题

- 代码：`deploy-122.sh` 自带回滚（30 秒起不来回上一版）。
- 迁移：**不回滚**。两份都是加法，留着对老代码无害；真要撤，`drop table insight_feedback` 和
  `alter table recording_sessions drop column scene` 各一句，但先确认没人写进去过。

## 6. 发完记一笔

CLAUDE.md：迁移登记与用户可见必须同时发生。发完把 `docs/PLAN.md` Phase 2 那节的「未部署」改掉，日期写上。
