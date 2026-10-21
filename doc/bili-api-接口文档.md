# bili-api 接口文档

> 模块：`bili-api`（`dev.oaktech.biliapi`）
> 定位：BV 项目的 B 站数据访问层，封装 B 站 web / app / TV / live / biliplus 的 HTTP、WebSocket 接口。
> 技术栈：Kotlin + Ktor Client（OkHttp 引擎）+ kotlinx.serialization + Koin（注解式 DI）
> 本文档基于源码提取，覆盖 `http/`、`repositories/`、`websocket/` 三层。

---

## 1. 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│  app 层（tv / mobile / shared）                              │
│      只依赖 repositories，不直接调 http                      │
├─────────────────────────────────────────────────────────────┤
│  repositories/   业务语义封装（Koin @Single/@ComponentScan）  │
│      UgcRepository / PgcRepository / VideoPlayRepository ... │
├─────────────────────────────────────────────────────────────┤
│  http/           接口原样封装（object 单例）                  │
│      BiliHttpApi（主）/ BiliPassportHttpApi（登录）           │
│      BiliLiveHttpApi（直播）/ BiliPlusHttpApi（bilibili.tv）  │
│      BiliHttpProxyApi（用户自定义代理服务器）                  │
├─────────────────────────────────────────────────────────────┤
│  http/util/      签名与配置                                   │
│      ApiSign（WBI + app 签名）/ BiliAppConf / BiliWebConf    │
│      Buvid / BiliUserAgent（UA 伪装）/ Zlib                   │
├─────────────────────────────────────────────────────────────┤
│  websocket/      LiveDataWebSocket（直播弹幕 wss 长连接）      │
│  entity/         全部请求/响应数据类（kotlinx.serialization）  │
└─────────────────────────────────────────────────────────────┘
```

**调用入口**：`ChannelRepository`

```kotlin
// 默认直连（api.bilibili.com）
ChannelRepository.initDefaultChannel(accessKey, buvid)
// 用户自定义 HTTP 代理（BiliHttpProxyApi）
ChannelRepository.initProxyChannel(accessKey, buvid, proxyServer)
```

`BiliHttpApi` 内部持有 Ktor `HttpClient(OkHttp)`，默认域名 `endPoint = "api.bilibili.com"`，
相对路径（`/x/...`）走该域名，部分 app 端接口显式写 `https://app.bilibili.com/...`。

---

## 2. 通用约定

### 2.1 统一响应结构 `BiliResponse<T>`

```kotlin
data class BiliResponse<T>(
    val code: Int,      // 业务状态码
    val message: String,
    val ttl: Int? = null,
    val data: T? = null,
    val result: T? = null   // 少数接口（app 端）用 result 字段承载数据
) {
    fun getResponseData(): T  // code != 0 时抛异常
}
```

常见 `code` 值：

| code | 含义 |
|---|---|
| 0 | 成功 |
| -101 | 账号未登录 / SESSDATA 失效 |
| -403 | 权限不足（含风控拦截） |
| -404 | 数据不存在 |
| -62013 | 风控/异地登录验证（需验证码） |
| -500 | 服务端错误 |

### 2.2 认证方式（两条通道）

| 通道 | 载体 | 适用接口 |
|---|---|---|
| **web** | `Cookie: SESSDATA=xxx;` | `/x/web-interface/...`、`/pgc/view/web/...` 等 |
| **app** | 查询参数 `access_key=xxx`（+ `build`、`mobi_app`、app 签名） | `app.bilibili.com/...`、`/pgc/app/...` |

- 写操作（点赞/投币/收藏/关注）额外需要 `csrf`（web）或 `access_key`（app）。
- 收藏夹类接口强制 `checkToken`：`accessKey` 与 `sessData` 至少传一个，否则直接 `require` 失败。
- 请求头 UA 由 `BiliUserAgent` 插件统一伪装为 B 站官方 App（版本参数在 `BiliAppConf`）。

### 2.3 WBI 签名

- `BiliHttpApi.updateWbi()`：定期从 `/x/web-interface/nav` 刷新 `wbiImgKey`/`wbiSubKey`（mixin key 计算见 `ApiSign.getMixinKey`）。
- 所有路径含 `wbi` 的接口（`/x/space/wbi/arc/search`、`/x/v2/reply/wbi/main`、`/x/web-interface/wbi/...`）自动附加 `w_rid`/`wts` 签名参数（`ApiSign` 拦截器统一处理）。
- app 端接口另走 app 签名（`ApiSign` 中按请求类型分流）。

### 2.4 域名速查

| 域名 | 用途 |
|---|---|
| `api.bilibili.com` | 主 API（默认 endPoint，相对路径） |
| `app.bilibili.com` | App 专用接口（`/x/v2/...`、`/pgc/app/...`） |
| `s.search.bilibili.com` | 搜索联想 suggest |
| `www.bilibili.com` | PGC 页面 InitialState 抓取（Jsoup 解析） |
| 动态 `hostList`（来自 getDanmuInfo） | 直播弹幕 wss |

---

## 3. HTTP 接口清单

> 方法列：GET/POST。参数只列关键的，完整参数见源码。
> 返回类型省略 `BiliResponse<>` 前缀的只写内部 `T`。

### 3.1 视频（BiliHttpApi）

| 函数 | 方法 | 路径 | 关键参数 | 返回 |
|---|---|---|---|---|
| `getPopularVideoData` | GET | `/x/web-interface/popular` | pn, ps, SESSDATA | `PopularVideoData` |
| `getVideoInfo` | GET | `/x/web-interface/view` | aid / bvid | `VideoInfo` |
| `getVideoDetail` | GET | `/x/web-interface/view/detail` | aid / bvid | `VideoDetail` |
| `getVideoPlayUrl` | GET | `/x/player/playurl` | cid, qn, fnval, fourk, … | `PlayUrlData` |
| `getPgcVideoPlayUrl` | GET | `/pgc/player/web/playurl` | epid/cid, qn, fourk, … | `PlayUrlData` |
| `getPgcVideoPlayUrlV2` | GET | `/pgc/player/web/v2/playurl` | 同上 + supportMultiAudio, drmTechType | `PlayUrlV2Data` |
| `getDanmakuXml` | GET | `/x/v1/dm/list.so` | oid(=cid) | 弹幕 XML（流式解析为 `DanmakuResponse`） |
| `getRelatedVideos` | GET | `/x/web-interface/archive/related` | aid / bvid | `RelatedVideosResponse` |
| `getVideoMoreInfo` | GET | `/x/player/wbi/v2` | aid, cid, buvid3, SESSDATA（WBI） | `VideoMoreInfo` |
| `getWebVideoShot` | GET | `/x/player/videoshot` | aid/bvid, cid, index | `VideoShot` |
| `getAppVideoShot` | GET | `https://app.bilibili.com/x/v2/view/video/shot` | aid, cid | `VideoShot` |
| `download` | GET | 任意 URL | url | `ByteArray` |

### 3.2 PGC 番剧/影视（BiliHttpApi）

| 函数 | 方法 | 路径 | 关键参数 | 返回 |
|---|---|---|---|---|
| `getWebSeasonInfo` | GET | `/pgc/view/web/season` | season_id / ep_id | `WebSeasonData` |
| `getAppSeasonInfo` | GET | `/pgc/view/v2/app/season` | season_id / ep_id, mobi_app, build, access_key | `AppSeasonData` |
| `addSeasonFollow`(web) | POST | `/pgc/web/follow/add` | season_id, csrf | `SeasonFollowData` |
| `addSeasonFollow`(app) | POST | `/pgc/app/follow/add` | season_id, access_key | `SeasonFollowData` |
| `delSeasonFollow`(web/app) | POST | `/pgc/web/follow/del`、`/pgc/app/follow/del` | 同上 | `SeasonFollowData` |
| `getSeasonUserStatus` | GET | `/pgc/view/web/season/user/status` | season_id | `WebSeasonData.UserStatus` |
| `getPgcFeedV3` | GET | `/pgc/page/web/v3/feed` | name(分区), cursor | `PgcFeedV3Data` |
| `getPgcFeed` | GET | `/pgc/page/web/feed` | name, cursor | `PgcFeedData` |
| `getFollowingSeasons`(web) | GET | `/x/space/bangumi/follow/list` | type, follow_status, pn, ps, vmid | `FollowingSeasonWebData` |
| `getFollowingSeasons`(app) | GET | `/pgc/app/follow/v2/{type}` | status, pn, ps, build, access_key | `FollowingSeasonAppData` |
| `getTimeline`(web) | GET | `/pgc/web/timeline` | types, before, after | `List<Timeline>` |
| `getTimeline`(app) | GET | `/pgc/app/timeline` | filter_type, access_key | `TimelineAppData` |
| `seasonIndexAnime/Guochuang/Variety/Movie/Tv/DocumentaryResult` | GET | `/pgc/season/index/result` | st(索引类型) + order/年份/地区/完结/制作等筛选 | `IndexResultData` |
| `getPgcWebInitialStateData` | GET | `https://www.bilibili.com/{path}` | 抓 HTML 用 Jsoup 解析 `__INITIAL_STATE__` | `PgcWebInitialStateData` |

### 3.3 UGC 分区（BiliHttpApi）

| 函数 | 方法 | 路径 | 关键参数 | 返回 |
|---|---|---|---|---|
| `getRegionFeedRcmd` | GET | `/x/web-interface/region/feed/rcmd` | display_id, request_cnt, from_region, device, plat | `RegionFeedRcmd` |
| `getRegionDynamic` | GET | `https://app.bilibili.com/x/v2/region/dynamic` | rid, build, access_key | `RegionDynamic` |
| `getRegionDynamicList` | GET | `https://app.bilibili.com/x/v2/region/dynamic/list` | rid, ctime, access_key | `RegionDynamicListData` |
| `getRegionBanner` | GET | `/x/web-show/region/banner` | region_id | `RegionBanner` |
| `getLocs` | GET | `/x/web-show/res/locs` | ids(逗号分隔) | `RegionLocs` |

### 3.4 用户（BiliHttpApi）

| 函数 | 方法 | 路径 | 关键参数 | 返回 |
|---|---|---|---|---|
| `getUserInfo` | GET | `/x/space/acc/info` | mid | `UserInfoData` |
| `getUserCardInfo` | GET | `/x/web-interface/card` | mid, photo | `UserCardData` |
| `getUserSelfInfo` | GET | `/x/space/myinfo` | SESSDATA | `MyInfoData` |
| `getWebUserSpaceVideos` | GET | `/x/space/wbi/arc/search`（WBI） | mid, order, tid, keyword, pn, ps | `WebSpaceVideoData` |
| `getAppUserSpaceVideos` | GET | `https://app.bilibili.com/x/v2/space/archive/cursor` | mid, lastAvid, order, ts, access_key | `AppSpaceVideoData` |
| `getUserFollow` | GET | `/x/relation/followings` | vmid, order_type, ps, pn | `UserFollowData` |
| `modifyFollow` | POST | `/x/relation/modify` | vmid, action(1关注/2取关), csrf 或 access_key | `BiliResponseWithoutData` |
| `getRelations` | GET | `/x/space/wbi/acc/relation`（WBI） | mid | `RelationData` |
| `getRelationStat` | GET | `x/relation/stat` | vmid | `RelationStat`（关注/粉丝/获赞数） |
| `getUserEquippedGarb` | GET | `/x/garb/user/equip` | part(装扮部位) | `Equip` |

### 3.5 动态（BiliHttpApi）

| 函数 | 方法 | 路径 | 关键参数 | 返回 |
|---|---|---|---|---|
| `getDynamicList` | GET | `/x/polymer/web-dynamic/v1/feed/all` | timezone_offset, type, page, offset | `DynamicData` |
| `getDynamicDetail` | GET | `/x/polymer/web-dynamic/v1/detail` | id, timezone_offset | `DynamicDetailData` |

### 3.6 收藏夹（BiliHttpApi）

| 函数 | 方法 | 路径 | 关键参数 | 返回 |
|---|---|---|---|---|
| `getFavoriteFolderInfo` | GET | `/x/v3/fav/folder/info` | media_id | `FavoriteFolderInfo` |
| `getAllFavoriteFoldersInfo` | GET | `/x/v3/fav/folder/created/list-all` | up_mid, type, rid | `UserFavoriteFoldersData` |
| `getFavoriteList` | GET | `/x/v3/fav/resource/list` | media_id, tid, keyword, order, pn, ps | `FavoriteFolderInfoListData` |
| `getFavoriteIdList` | GET | `/x/v3/fav/resource/ids` | media_id | `FavoriteItemIdListResponse` |
| `setVideoToFavorite` | POST | `/x/v3/fav/resource/deal` | aid, add_media_ids / del_media_ids, csrf | — |
| `checkVideoFavoured` | GET | `/x/v2/fav/video/favoured` | aid | `Boolean` |

### 3.7 历史 / 稍后再看（BiliHttpApi）

| 函数 | 方法 | 路径 | 关键参数 | 返回 |
|---|---|---|---|---|
| `getHistories` | GET | `/x/web-interface/history/cursor` | max, view_at, ps | `HistoryData` |
| `getToView` | GET | `/x/v2/history/toview` | SESSDATA | `ToViewData` |
| `sendHeartbeat` | POST | `/x/click-interface/web/heartbeat` 或 `/x/v2/history/report` | aid/bvid/epid, played_time, dt, … | `String` |

### 3.8 互动：点赞 / 投币（BiliHttpApi）

| 函数 | 方法 | 路径 | 关键参数 | 返回 |
|---|---|---|---|---|
| `sendVideoLike` | POST | `/x/web-interface/archive/like` | aid/bvid, like(1/2), csrf | `Pair<Boolean, String>` |
| `checkVideoLiked` | GET | `/x/web-interface/archive/has/like` | aid/bvid | `Boolean` |
| `sendVideoCoin` | POST | `/x/web-interface/coin/add` | aid/bvid, multiply, csrf | `Pair<Boolean, String>` |
| `checkVideoSentCoin` | GET | `/x/web-interface/archive/coins` | aid/bvid | `Boolean` |

### 3.9 搜索（BiliHttpApi / BiliHttpProxyApi）

| 函数 | 方法 | 路径 | 关键参数 | 返回 |
|---|---|---|---|---|
| `getWebSearchSquare` | GET | `/x/web-interface/wbi/search/square`（WBI） | limit, platform | 热搜 |
| `getAppSearchSquare` | GET | `https://app.bilibili.com/x/v2/search/square` | limit, build | 热搜 |
| `getSearchTrendRank` | GET | `https://app.bilibili.com/x/v2/search/trending/ranking` | limit | 趋势榜 |
| `getKeywordSuggest` | GET | `https://s.search.bilibili.com/main/suggest` | term, main_ver, buvid | 联想词（纯文本） |
| `searchAll` | GET | `/x/web-interface/wbi/search/all/v2`（WBI） | keyword, page, tids, order, duration | `SearchResultData` |
| `searchType` | GET | `/x/web-interface/wbi/search/type`（WBI） | keyword, search_type, … | `SearchResultData` |

> `BiliHttpProxyApi` 是对 `getPgcVideoPlayUrl` / `searchType` 的代理版（可指向用户自定义服务器，其余路径规则同 BiliHttpApi）。

### 3.10 推荐流 / 导航（BiliHttpApi）

| 函数 | 方法 | 路径 | 关键参数 | 返回 |
|---|---|---|---|---|
| `getFeedRcmd` | GET | `/x/web-interface/wbi/index/top/feed/rcmd`（WBI） | fresh_type, ps, fresh_idx | `RcmdTopData` |
| `getFeedIndex` | GET | `https://app.bilibili.com/x/v2/feed/index` | idx, access_key | App 首页 feed |
| `getWebInterfaceNav` | GET | `/x/web-interface/nav` | SESSDATA | `NavResponseData`（登录态 + WBI key 来源） |
| `updateWbi` | — | 基于 nav 数据 | — | `Boolean` |

### 3.11 标签（BiliHttpApi）

| 函数 | 方法 | 路径 | 关键参数 | 返回 |
|---|---|---|---|---|
| `getVideoTags` | GET | `/x/tag/archive/tags` | aid / bvid | `List<Tag>` |
| `getTagDetail` | GET | `/x/tag/detail` | tag_id, pn, ps | `TagDetail` |
| `getTagTopVideos` | GET | `/x/web-interface/tag/top` | tid, pn, ps | `TagTopVideosResponse` |

### 3.12 登录（BiliPassportHttpApi）

| 函数 | 方法 | 路径 | 说明 |
|---|---|---|---|
| `getWebQRUrl` | GET | `/x/passport-login/web/qrcode/generate` | Web 扫码登录：取二维码 |
| `loginWithWebQR` | GET | `/x/passport-login/web/qrcode/poll` | 轮询扫码结果（返回登录态 + Cookie） |
| `getAppQRUrl` | POST | `/x/passport-tv-login/qrcode/auth_code` | TV/手机扫码登录：取 auth_code |
| `loginWithAppQR` | POST | `/x/passport-tv-login/qrcode/poll` | 轮询 TV 扫码结果（返回 access_key） |
| `getCaptcha` | GET | `/x/passport-login/captcha` | 滑块验证码 |
| `sendSms` | POST | `/x/passport-login/sms/send` | 发送短信验证码 |
| `loginWithSms` | POST | `/x/passport-login/login/sms` | 短信验证码登录 |

### 3.13 直播（BiliLiveHttpApi + LiveDataWebSocket）

| 函数 | 方法 | 路径 | 说明 |
|---|---|---|---|
| `getLiveDanmuInfo` | GET | `/xlive/web-room/v1/index/getDanmuInfo` | 房间弹幕信息（含 wss hostList） |
| `getLiveRoomPlayInfo` | GET | `/xlive/web-room/v1/index/getRoomPlayInfo` | 房间流地址 |
| `getLiveDanmuHistory` | GET | `/xlive/web-room/v1/dM/gethistory` | 历史弹幕 |

**弹幕长连接**：`LiveDataWebSocket` 取 `getDanmuInfo` 的 `hostList` 末项，
`wss://<host>:<wssPort>/sub` 建立连接，按 B 站弹幕协议帧（gzip 压缩 + 心跳 `2`）收发弹幕事件。

### 3.14 BiliPlus（BiliPlusHttpApi，bilibili.tv 端）

| 函数 | 路径 | 说明 |
|---|---|---|
| `view` | `https://api.biliplus.com/api/view`（独立域名/UA） | 视频信息（TV 端 Plus 协议） |
| `getSeasonIdByAvid` | 基于 `view` 派生 | 由 avid 反查 season_id |

---

## 4. Repository 层（应用实际调用的入口）

> 全部由 Koin `@Single` 提供（`BiliApiModule` 的 `@ComponentScan` 扫描 `dev.oaktech.biliapi.repositories`，`AppModule` includes 之）。
> Repository 负责参数组装、web/app 通道选择、错误降级；http 层负责协议细节。

### 4.1 AuthRepository
认证状态管理：SESSDATA / access_key / csrf / buvid 的保存与读取（供各接口与签名使用）。

### 4.2 ChannelRepository
- `initDefaultChannel(accessKey, buvid)`：初始化默认直连通道
- `initProxyChannel(accessKey, buvid, proxyServer)`：切换用户自定义代理通道（`BiliHttpProxyApi`）

### 4.3 VideoPlayRepository
| 方法 | 说明 |
|---|---|
| `getPlayData` | UGC 播放地址（qn/fnval/fourk/codec 组装） |
| `getPgcPlayData` | PGC 播放地址（含多音轨 v2） |
| `getSubtitle` | 字幕列表 |
| `sendHeartbeat` | 播放进度上报 |
| `getDanmakuMask` | 弹幕屏蔽配置 |
| `getVideoShot` | 视频截帧 |

### 4.4 VideoDetailRepository
`getVideoDetail` / `getPgcVideoDetail` / `getComments` / `getCommentReplies`

### 4.5 CommentRepository
`getComments(type, oid, mode, pagination)` / `getCommentReplies`（WBI 评论接口）

### 4.6 UgcRepository
| 方法 | 说明 |
|---|---|
| `getCarousel(ugcType)` | UGC 分区轮播 |
| `getRegionData(ugcType)` | 分区详情 |
| `getRegionMoreData(ugcType)` | 分区更多数据 |
| `getRegionFeedRcmd(ugcType, page)` | 分区推荐 feed（对应 3.3） |

### 4.7 PgcRepository
`getCarousel(pgcType)` / `getFeed(pgcType, cursor)` / `getPgcIndex(...)`（分区索引，对应 3.2 的 index/result）

### 4.8 SeasonRepository
`getFollowingSeasons` / `getTimeline`（关注番剧与放送时间表）

### 4.9 RecommendVideoRepository
`getPopularVideos`（热门）/ `getRecommendVideos`（首页推荐，web rcmd + app feed/index）

### 4.10 SearchRepository
`search` / `getSearchHotwords` / `getSearchSuggest` / `searchType`

### 4.11 UserRepository
`followUser` / `unfollowUser` / `checkIsFollowing` / `getFollowingUpCount` /
`addSeasonFollow` / `delSeasonFollow` / `getSpaceVideos` / `getDynamicVideos` /
`getDynamics` / `getDynamicDetail` / `getFollowedUsers`

### 4.12 FavoriteRepository
`checkVideoFavoured` / `addVideoToFavoriteFolder` / `delVideoFromFavoriteFolder` /
`updateVideoToFavoriteFolder` / `getAllFavoriteFolderMetadataList` / `getFavoriteFolderData`

### 4.13 HistoryRepository
`getHistories(max, viewAt, pageSize)`

### 4.14 ToViewRepository
`getToView()`（稍后再看列表）

### 4.15 LoginRepository
`requestWebQrLogin` / `checkWebQrLoginState` / `requestAppQrLogin` / `checkAppQrLoginState` /
`getCaptcha` / `requestSms` / `loginWithSms` / `generateLoginSessionId`

---

## 5. 工具类（util/）

| 类 | 用途 |
|---|---|
| `ApiSign` | WBI 签名（mixin key、`w_rid`/`wts`）+ app 端签名，Ktor 拦截器统一注入 |
| `BiliAppConf` | App 端常量：版本号、build、mobi_app、channel 等 |
| `BiliWebConf` | Web 端常量：UA 相关 |
| `Buvid` | buvid3/buvid4 设备标识获取 |
| `Zlib` | gzip/zlib 解压（弹幕、部分 app 响应） |
| `AvBvConverter` | av 号 ↔ bv 号互转 |
| `UrlUtil` | URL 工具 |
| `Extends` | 通用扩展 |

`grpc/utils/`：gRPC 相关辅助（当前主要用于内部工具）。

---

## 6. 使用示例

```kotlin
// 1. 应用启动时初始化通道（BVApp 内，Koin 已注入 AuthRepository）
ChannelRepository.initDefaultChannel(accessKey = "", buvid = buvid3)

// 2. 播放页取流
val playData = videoPlayRepository.getPlayData(
    bvid = "BV1xx411c7mD", cid = 123456,
    qn = 80, fnval = 4048, fourk = 0, codec = CodeType.CodeAv1
)

// 3. UGC 分区推荐
val feed = ugcRepository.getRegionFeedRcmd(UgcTypeV2.Home, UgcFeedPage(page = 1))

// 4. 评论
val comments = commentRepository.getComments(type = 1, oid = cid, mode = 3, paginationStr = ...)
```

---

## 7. 注意事项

1. **WBI 接口**：路径含 `wbi` 的接口依赖 `updateWbi()` 成功刷新 key；nav 接口本身是 WBI key 的来源，登录态过期会导致签名全部失效（表现为 `-403`）。
2. **web/app 双通道**：同一业务往往有两个入口（如 space 视频列表、关注番剧列表），web 用 SESSDATA、app 用 access_key，按登录方式选择；Repository 层已做封装，应用层不要混用。
3. **`BiliResponse.result` 与 `data`**：少数 app 端接口数据在 `result` 字段，`getResponseData()` 已处理。
4. **风控**：`-62013` 需要走 `BiliPassportHttpApi.getCaptcha` 滑块流程；频繁调用写接口可能触发 `-403`。
5. **合规**：本项目为本地 Demo，接持牌方须替换 `BiliAppConf` 中的 UA/版本参数（见项目 README 合规说明）。
6. **代理通道**：`initProxyChannel` 后仅 `getPgcVideoPlayUrl`/`searchType` 走代理，其余接口仍直连。
